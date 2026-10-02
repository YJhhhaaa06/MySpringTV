package io.github.yjhhhaaa06.videoweb.comment.service;

import io.github.yjhhhaaa06.videoweb.comment.dao.CommentDao;
import io.github.yjhhhaaa06.videoweb.comment.model.dto.AddCommentRequest;
import io.github.yjhhhaaa06.videoweb.comment.model.entity.Comment;
import io.github.yjhhhaaa06.videoweb.common.exception.ConflictException;
import io.github.yjhhhaaa06.videoweb.common.exception.ForbiddenException;
import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评论业务（S2：**写路径**）。
 *
 * <p>迁移自 TV {@code com.itheima.comment.service.CommentService}。
 * 骨架（{@code transactionTemplate.execute} / {@code conn} 穿透 / {@code catch (SQLException)} 包装）已删除，
 * 业务语义（校验顺序、异常类型、楼中楼归一化、软删除规则、计数口径）逐条保留——
 * 对照与反模式警告见《事务边界决策表》§二·C（CM-1 / CM-2）。
 *
 * <h2>本切片只交付写路径（CM-3：读路径划归 S5）</h2>
 * 未搬 TV 的 {@code getRepliesForRoot} 与 VO 树转换（{@code convertToCommentVOList}）。
 * 理由：读路径经 {@code ContentService.getCommentsForContent} 拖入 {@code CommentCache}（8 处事务）
 * + {@code ContentCache} + {@code LikeService}，那是 S3/S5 的领域。
 * 端点 {@code GET /comment/show}、{@code GET /comment/replies} 随之不在本切片。
 *
 * <h2>三处与 TV 的差异（均已表态，勿当缺陷"修正"）</h2>
 * <ol>
 *   <li><b>评论区开关改读 DB</b>（TV 读 {@code ContentCache}）：消除旧实现的缓存陈旧窗口，见 CM-1 要点 3。</li>
 *   <li><b>提交后缓存失效被裁剪</b>：当前不存在任何读缓存，"失效"是空操作。
 *       ⚠️ 补回位置见 CM-3 末段——S5 接缓存时必须回来把副作用改为
 *       {@code @TransactionalEventListener(AFTER_COMMIT)}。**在那之前"评论后计数不即时更新"属预期。**</li>
 *   <li><b>不再手写 {@code ServerException} 包装与 SEVERE 堆栈日志</b>：由 {@code GlobalExceptionHandler}
 *       单一出口承担（{@code DataAccessException} → 记堆栈 500），业务代码里不再有 try/catch。</li>
 * </ol>
 *
 * <h2>⚠️ 事务注解为什么标在"公开入口"而不是私有方法</h2>
 * {@code doDeleteComment} 只被本类调用。若把 {@code @Transactional} 标在它上面，
 * 调用走的是 {@code this.xxx} ⇒ **不走代理 ⇒ 事务静默失效**（Spring 自调用陷阱，且不报错）。
 * 故注解标在 {@link #deleteCommentByUser} / {@link #deleteCommentByAdmin} 两个公开入口上，
 * 私有方法只写业务、运行在调用方的事务里。
 */
@Service
public class CommentService {

    private final CommentDao commentDao;
    private final ContentDao contentDao;

    public CommentService(CommentDao commentDao, ContentDao contentDao) {
        this.commentDao = commentDao;
        this.contentDao = contentDao;
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

        // ⚠️ TV 在此之后有 3 行缓存失效（contentCache.notifyCommentCountChanged /
        //    commentCache.invalidateRoots|invalidateReplyUnder）。S2 裁剪——当前无读缓存，
        //    失效是空操作。补回位置见《事务边界决策表》CM-3 末段。
        //
        // 顺带：TV 在事务内还回查了一遍 findCommentById(commentId)，其**唯一用途**是给上面
        // 那几行失效做 newComment != null 判据（回查结果本身未被使用，且刚插入必然非 null）。
        // 失效既已裁剪，该回查无人消费 ⇒ 不搬（省一次带两个 JOIN 的查询）。
        // 补回位置：S5 恢复失效时需一并恢复（或直接以"插入成功"为判据）。
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

        // ⚠️ TV 在此之后有缓存失效（notifyCommentCountChanged + invalidateRoots|invalidateReplyUnder），
        //    S2 裁剪，理由与补回位置同 addComment。TV 为此在事务内构造了 DeletedComment 载体
        //    （contentId/commentId/deletedCount/isMain/rootId）并 return 出事务；失效既已裁剪，
        //    该载体无消费者 ⇒ 不搬，方法返回 void。补回位置：S5 恢复失效时需重新引入该载体
        //    （或改为在 AFTER_COMMIT 事件里携带同样 5 个字段）。
    }
}
