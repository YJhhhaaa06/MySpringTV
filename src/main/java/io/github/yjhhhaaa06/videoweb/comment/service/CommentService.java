package io.github.yjhhhaaa06.videoweb.comment.service;

import io.github.yjhhhaaa06.videoweb.comment.cache.CommentCache;
import io.github.yjhhhaaa06.videoweb.comment.dao.CommentDao;
import io.github.yjhhhaaa06.videoweb.comment.event.CommentCacheChangedEvent;
import io.github.yjhhhaaa06.videoweb.comment.model.cache.CommentCacheDTO;
import io.github.yjhhhaaa06.videoweb.comment.model.dto.AddCommentRequest;
import io.github.yjhhhaaa06.videoweb.comment.model.entity.Comment;
import io.github.yjhhhaaa06.videoweb.comment.model.vo.CommentVO;
import io.github.yjhhhaaa06.videoweb.common.exception.ConflictException;
import io.github.yjhhhaaa06.videoweb.common.exception.ForbiddenException;
import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.event.ContentCacheChangedEvent;
import io.github.yjhhhaaa06.videoweb.like.service.LikeService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 评论业务。
 *
 * <p>迁移自 TV {@code com.itheima.comment.service.CommentService}。
 * 骨架（{@code transactionTemplate.execute} / {@code conn} 穿透 / {@code catch (SQLException)} 包装）已删除，
 * 业务语义（校验顺序、异常类型、楼中楼归一化、软删除规则、计数口径）逐条保留——
 * 对照与反模式警告见《事务边界决策表》§二·C（CM-1 / CM-2）。
 *
 * <h2>两段历史</h2>
 * <ul>
 *   <li><b>S2</b>：写路径（{@code addComment} / {@code doDeleteComment}）。</li>
 *   <li><b>S5</b>（本切片）：读路径（{@link #getRepliesForRoot} + VO 树转换
 *       {@link #convertToCommentVOList}），并把 S2 裁剪掉的**提交后缓存失效补回**（CM-3 的承诺）。</li>
 * </ul>
 *
 * <h2>★ 提交后副作用：从"裁剪"到"补回"（G-3）</h2>
 * S2 因为"当时不存在任何读缓存"而把 TV 的 3 行失效整段裁掉（CM-3 有详细论证与补回位置）。
 * S5 接入两个 Cache 后**必须补回**，且改为 {@code @TransactionalEventListener(AFTER_COMMIT)}：
 * <ol>
 *   <li>{@code ContentCacheChangedEvent.invalidate(contentId)} —— 评论数变了 ⇒ 失效内容 key
 *       （读自愈回填 DB 最新的 {@code comment_count}）；</li>
 *   <li>{@code CommentCacheChangedEvent.roots/replyUnder} —— 增删主楼失效 roots+count、
 *       增删回复定向 HDEL 该主楼的 replies field。</li>
 * </ol>
 * ★ 顺序纪律（TV 的 T34/U-22 结论）：**必须在提交后**。事务内先失效会留出窗口，
 * 并发读者可按旧计数回填缓存，表现为"计数短暂陈旧"。
 *
 * <h2>S5 顺带找回的一处实现细节</h2>
 * TV 在 {@code addComment} 事务内回查了一次 {@code findCommentById(commentId)}，其唯一用途是给
 * 失效逻辑做 {@code newComment != null} 判据。S2 因失效被裁剪而不搬它。S5 恢复失效时，
 * 依旧**不恢复那次回查**——刚插入的行必然存在，"插入成功"本身就是判据（省一次带两个 JOIN 的查询）。
 * TV 为此构造的 {@code DeletedComment} 载体同理不再需要（局部变量已足够）。
 *
 * <h2>⚠️ 事务注解为什么标在"公开入口"而不是私有方法</h2>
 * {@code doDeleteComment} 只被本类调用。若把 {@code @Transactional} 标在它上面，
 * 调用走的是 {@code this.xxx} ⇒ **不走代理 ⇒ 事务静默失效**（Spring 自调用陷阱，且不报错）。
 * 故注解标在 {@link #deleteCommentByUser} / {@link #deleteCommentByAdmin} 两个公开入口上，
 * 私有方法只写业务、运行在调用方的事务里。
 */
@Service
public class CommentService {

    /**
     * T10-B：每主楼首屏只带前 K 条楼中楼。
     * ⚠️ 与 {@code CommentCache.PREVIEW_REPLIES_PER_ROOT} **必须同值**（TV 原注释要求两处同步）。
     */
    public static final int REPLY_PREVIEW_K = 2;

    /** 展开接口的 LIMIT 上界：仅防极端 {@code page*pageSize} 溢出（MySQL LIMIT 是 int）。 */
    private static final long MAX_REPLY_WINDOW = 50_000L;

    private final CommentDao commentDao;
    private final ContentDao contentDao;
    private final CommentCache commentCache;
    private final LikeService likeService;
    private final ApplicationEventPublisher events;

    public CommentService(CommentDao commentDao,
                          ContentDao contentDao,
                          CommentCache commentCache,
                          LikeService likeService,
                          ApplicationEventPublisher events) {
        this.commentDao = commentDao;
        this.contentDao = contentDao;
        this.commentCache = commentCache;
        this.likeService = likeService;
        this.events = events;
    }

    // ========================================================================
    // CM-1：addComment —— 插入 + 楼中楼归一化 + 跨表计数联动（单事务）
    // ========================================================================

    /**
     * 发表评论（主楼或楼中楼回复）。
     *
     * <p><b>决策表 CM-1：✅ 保持单事务。</b>"插入评论"与"两处计数 +1"必须原子，
     * 否则会出现"评论在但计数没加"的静默脏数据。
     *
     * <p>校验顺序与 TV 逐条一致（顺序决定客户端拿到 404 还是 409，是契约的一部分）：
     * <ol>
     *   <li>评论区开关：{@code comment_enabled = 0} → 409「评论区已关闭」</li>
     *   <li>内容存在性（**未软删**）→ 404「被评论的内容不存在」</li>
     *   <li>被回复评论存在性（**未软删**）→ 409</li>
     *   <li>被回复评论归属同一内容 → 否则 409</li>
     * </ol>
     *
     * <p><b>楼中楼归一化</b>（TV 原注释即规格）：{@code parentId} 一律指向**主楼**。
     * 被回复的若本身是楼内回复，则上溯挂到其主楼，并把 {@code replyToUserId} 记为
     * **被回复那条回复的作者**（供「回复 @xxx」展示）。
     *
     * @param userId  当前登录用户 id（由 {@code @CurrentUserId} 注入）
     * @param request 已通过 {@code @Valid} 的请求（contentId 正数、message 非空且 ≤1000 字）
     */
    @Transactional
    public void addComment(long userId, AddCommentRequest request) {
        long contentId = request.contentId();
        Long parentId = request.parentId();

        // ① 评论区开关门禁。内容不存在/已软删 → null ⇒ 跳过门禁，
        //    把 404 判定交给下一步的 isContentExist（与 TV 门禁的 dto==null 分支同构）。
        Boolean commentEnabled = contentDao.findCommentEnabledById(contentId);
        if (commentEnabled != null && !commentEnabled) {
            throw new ConflictException("评论区已关闭");
        }

        // ② 内容存在性（带 is_deleted = 0）
        if (!contentDao.isContentExist(contentId)) {
            throw new NotFoundException("被评论的内容不存在");
        }

        // ③④ 楼中楼归一化：把 parentId 折叠为"主楼 id"，并解析被 @ 者
        Long effectiveParentId = parentId;
        Long replyToUserId = null;
        if (effectiveParentId != null && effectiveParentId != 0) {
            if (!commentDao.isCommentExist(effectiveParentId)) {
                throw new ConflictException("被回复评论不存在或已删除");
            }
            Comment parent = commentDao.findById(effectiveParentId);
            if (parent.getContentId() != contentId) {
                throw new ConflictException("被回复评论不属于该视频或动态");
            }
            if (parent.getParentId() != null && parent.getParentId() != 0) {
                // 被回复的本身是楼内回复 ⇒ 上溯挂主楼，@ 目标是该回复的作者
                effectiveParentId = parent.getParentId();
                replyToUserId = parent.getUserId();
            }
        }

        Comment comment = new Comment();
        comment.setContentId(contentId);
        comment.setUserId(userId);
        comment.setContent(request.message());
        comment.setParentId(effectiveParentId);
        comment.setReplyToUserId(replyToUserId);
        commentDao.insert(comment);   // useGeneratedKeys 回填 commentId

        // 新增为回复 ⇒ 主楼 reply_count +1（effectiveParentId 已归一为主楼 id）
        if (effectiveParentId != null && effectiveParentId != 0) {
            commentDao.updateReplyCount(effectiveParentId, 1);
        }
        contentDao.updateCommentCount(contentId, 1);

        // ★ 提交后副作用（G-3，S5 补回 CM-3 的裁剪）：
        //   ① 评论数变了 ⇒ 失效内容 key（读自愈回填 DB 最新的 comment_count）；
        //   ② 增主楼失效 roots+count（读懒建窗口）；增回复定向 HDEL 所在主楼的 replies field。
        //   TV 的 3 行"写在事务 lambda 之后"的失效，从此是框架保证的 AFTER_COMMIT。
        events.publishEvent(ContentCacheChangedEvent.invalidate(contentId));
        if (effectiveParentId == null || effectiveParentId == 0) {
            events.publishEvent(CommentCacheChangedEvent.roots(contentId));
        } else {
            events.publishEvent(CommentCacheChangedEvent.replyUnder(contentId, effectiveParentId));
        }

        // TV 在事务内还回查了一遍 findCommentById(commentId)，其唯一用途是给失效做
        // newComment != null 判据。S5 恢复失效时**仍不恢复该回查**——刚插入的行必然存在，
        // "插入成功"本身就是判据（CM-1 的补回说明已预告这个结论）。
    }

    // ========================================================================
    // S5：读路径（CM-3 划归本切片）
    // ========================================================================

    /**
     * 按主楼展开全部回复（{@code GET /comment/replies}）。
     *
     * <p>{@code total} = 主楼 {@code reply_count}（与分页信封里 children 的前 K 口径一致，
     * 见 T10-B）；{@code list} = 直接回复 + 二级间接回复（{@code comment_id} 升序），
     * 页间不重不漏由 keyset 构造保证。点赞态只对该页回复批量查询。
     *
     * <p><b>决策表 G-3：⚠️ 有意改进——去掉事务。</b>TV 用两个 {@code transactionTemplate.execute}
     * 各包一条**纯读**（{@code findMainById} / {@code getRepliesInTreeByRoot}），
     * 无原子性需求，事务只是"取连接的手段"（同 U-3/U-4、C-2/C-3、L-5、F-3、G-2）。
     */
    public PageResult<CommentVO> getRepliesForRoot(long rootId, Long userId, int page, int pageSize) {
        if (page < 1 || pageSize < 1) {
            return new PageResult<>(new ArrayList<>(), 0, page, pageSize);
        }
        CommentCacheDTO root = commentDao.findMainById(rootId);
        if (root == null) {
            throw new NotFoundException("评论不存在或已删除");
        }
        int total = root.getReplyCount();
        List<CommentCacheDTO> rows = commentDao.getRepliesInTreeByRoot(
                root.getContentId(), rootId, 0L, toLimit((long) page * pageSize));
        List<CommentCacheDTO> pageList = slicePage(rows, page, pageSize);

        Map<Long, Boolean> likedMap = new HashMap<>();
        if (userId != null && !pageList.isEmpty()) {
            List<Long> replyIds = new ArrayList<>(pageList.size());
            for (CommentCacheDTO reply : pageList) {
                replyIds.add(reply.getCommentId());
            }
            likedMap = likeService.batchIsCommentLiked(userId, replyIds);
            if (likedMap == null) {
                likedMap = new HashMap<>();
            }
        }
        return new PageResult<>(convertToCommentVOList(pageList, likedMap), total, page, pageSize);
    }

    /** LIMIT 上界（防极端 {@code page*pageSize} 溢出；单主楼回复量内页间可达）。 */
    private static int toLimit(long window) {
        return (int) Math.min(Math.max(window, 1L), MAX_REPLY_WINDOW);
    }

    /** 从头取回的窗口行中切片该页（越界页空列表；信封 total 已是真实值）。 */
    private static List<CommentCacheDTO> slicePage(List<CommentCacheDTO> rows, int page, int pageSize) {
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<>();
        }
        long from = (long) (page - 1) * pageSize;
        if (from >= rows.size()) {
            return new ArrayList<>();
        }
        int to = (int) Math.min(from + pageSize, rows.size());
        return new ArrayList<>(rows.subList((int) from, to));
    }

    // ========================================================================
    // VO 树转换（承接 TV convertToCommentVOList / convertToCommentVO）
    // ========================================================================

    /** 评论树 → VO 树（带 {@code isLiked}）。 */
    public List<CommentVO> convertToCommentVOList(List<CommentCacheDTO> cacheList,
                                                  Map<Long, Boolean> likedMap) {
        List<CommentVO> result = new ArrayList<>();
        if (cacheList == null) {
            return result;
        }
        for (CommentCacheDTO node : cacheList) {
            result.add(convertToCommentVO(node, likedMap));
        }
        return result;
    }

    /**
     * 单节点转换（递归 children）。
     *
     * <p>与 TV 的一处差异（等价且更稳）：TV 用 7 参构造器填 7 个字段，再**分两处** setter 补
     * {@code isLiked} / {@code replyToUserId} / {@code replyToUsername} / {@code replyCount}，
     * 以及递归 children——漏掉任何一处都会**静默丢字段**。本实现逐字段显式拷贝，
     * 字段集一目了然（与 {@code ContentCache.copyCommon} 同一手法）。
     */
    private CommentVO convertToCommentVO(CommentCacheDTO node, Map<Long, Boolean> likedMap) {
        CommentVO vo = new CommentVO(
                node.getUsername(), node.getCommentId(), node.getContentId(),
                node.getUserId(), node.getContent(), node.getParentId(), node.getLikeCount());
        vo.setReplyToUserId(node.getReplyToUserId());
        vo.setReplyToUsername(node.getReplyToUsername());
        vo.setReplyCount(node.getReplyCount());
        Boolean liked = likedMap.get(node.getCommentId());
        vo.setIsLiked(liked != null && liked);
        if (node.getChildren() != null) {
            List<CommentVO> children = new ArrayList<>(node.getChildren().size());
            for (CommentCacheDTO child : node.getChildren()) {
                children.add(convertToCommentVO(child, likedMap));
            }
            vo.setChildren(new ArrayList<>(children));
        }
        return vo;
    }

    // ========================================================================
    // CM-2：doDeleteComment —— 软删除 + 计数回减（单事务）
    // ========================================================================

    /** 用户自删：**仅能删除自己的**评论。 */
    @Transactional
    public void deleteCommentByUser(long commentId, long userId) {
        doDeleteComment(commentId, userId, false);
    }

    /**
     * 管理员删除：可删任意评论。
     *
     * <p>⚠️ 本切片**无端点暴露**它（admin 模块不在本批）。仍要交付并测试：它是
     * {@code doDeleteComment} 的 {@code isAdmin} 分支**唯一入口**，砍掉即砍掉该分支语义，
     * 且 admin 切片补回时会引入未测路径。见决策表 CM-2。
     */
    @Transactional
    public void deleteCommentByAdmin(long commentId) {
        doDeleteComment(commentId, 0L, true);
    }

    /**
     * 删除评论（软删除，不可恢复）。
     *
     * <p><b>决策表 CM-2：✅ 保持单事务。</b>三条**不得"顺手优化"**的规则：
     * <ol>
     *   <li><b>必须先计数再软删</b>——{@code countFloorReplies} 过滤 {@code is_deleted = 0}，
     *       若先软删会把楼内回复也置 1、这里数到 0，导致 {@code comment_count} **少扣**。</li>
     *   <li><b>删主楼不扣 {@code reply_count}</b>（{@code !isMain} 是有意的：主楼整栋已删，其
     *       {@code reply_count} 无意义）。</li>
     *   <li><b>楼中楼删除规则</b>：主楼整栋软删，回复只删自己。</li>
     * </ol>
     *
     * <p>计数口径（与递增对称，TV 原注释）：删回复时 {@code comment_count} 扣 1；
     * 删主楼时扣 {@code 1 + 剩余未删回复数}——**避免对已单删的回复二次扣减**。
     *
     * @param operatorUserId 非 admin 时的操作者 id，用于归属校验
     * @param isAdmin        管理员可删任意评论
     */
    private void doDeleteComment(long commentId, long operatorUserId, boolean isAdmin) {
        if (!commentDao.isCommentExist(commentId)) {
            throw new NotFoundException("评论不存在");   // 已软删亦视为不存在
        }
        Comment comment = commentDao.findById(commentId);
        if (!isAdmin && comment.getUserId() != operatorUserId) {
            throw new ForbiddenException("只能删除自己的评论");
        }

        boolean isMain = comment.getParentId() == null || comment.getParentId() == 0;
        // 删主楼 = 自身；删回复 = 沿 parent 链上溯定位所属主楼（供 reply_count 回减）
        Long rootId = isMain ? commentId : commentDao.getRootIdByCommentId(commentId);

        int deletedCount;
        if (isMain) {
            // ★ 顺序不可改：先计数（口径 is_deleted=0）再软删。反了就会数到 0、计数少扣。
            deletedCount = 1 + commentDao.countFloorReplies(commentId);
            commentDao.softDeleteFloor(commentId);
        } else {
            commentDao.softDeleteOne(commentId);
            deletedCount = 1;
        }

        contentDao.updateCommentCount(comment.getContentId(), -deletedCount);

        // 删回复才扣主楼 reply_count；删主楼整栋不扣（主楼已删，无意义）。
        // updateReplyCount 自带 reply_count + ? >= 0 防负守卫，故不会出现 -1。
        if (!isMain && rootId != null) {
            commentDao.updateReplyCount(rootId, -1);
        }

        // ★ 提交后副作用（G-3，S5 补回）：失效内容 key（comment_count 变了）+ 评论树缓存。
        //   TV 为此在事务内构造了 DeletedComment 载体 return 出事务；本实现直接发事件
        //   （局部变量已含全部所需字段，无需载体）。
        long contentId = comment.getContentId();
        events.publishEvent(ContentCacheChangedEvent.invalidate(contentId));
        if (isMain) {
            // 删主楼整栋 ⇒ roots+count 失效；那栋的 replies field 也一并清掉
            events.publishEvent(CommentCacheChangedEvent.roots(contentId));
            events.publishEvent(CommentCacheChangedEvent.replyUnder(contentId, commentId));
        } else {
            // 删回复 ⇒ 定向清所属主楼的 replies field（懒载刷新）
            events.publishEvent(CommentCacheChangedEvent.replyUnder(contentId, rootId));
        }
    }
}
