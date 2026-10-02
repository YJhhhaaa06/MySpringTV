package io.github.yjhhhaaa06.videoweb.content.service;

import io.github.yjhhhaaa06.videoweb.comment.cache.CommentCache;
import io.github.yjhhhaaa06.videoweb.comment.event.CommentCacheChangedEvent;
import io.github.yjhhhaaa06.videoweb.comment.model.cache.CommentCacheDTO;
import io.github.yjhhhaaa06.videoweb.comment.model.vo.CommentVO;
import io.github.yjhhhaaa06.videoweb.comment.service.CommentService;
import io.github.yjhhhaaa06.videoweb.common.exception.ForbiddenException;
import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.content.cache.ContentCache;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentMediaDao;
import io.github.yjhhhaaa06.videoweb.content.event.ContentCacheChangedEvent;
import io.github.yjhhhaaa06.videoweb.content.model.cache.ContentCacheDTO;
import io.github.yjhhhaaa06.videoweb.content.model.entity.ContentMedia;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentDetailVO;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.like.service.LikeService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容业务。
 *
 * <p>迁移自 TV {@code com.itheima.content.service.ContentService}（515 行 / 11 处事务）。
 * 骨架（{@code transactionTemplate.execute} / {@code conn} 穿透 / {@code catch (SQLException)} 包装）
 * 已删除；业务语义（校验顺序、异常类型与文案、事务边界、提交后副作用时机）逐条保留——
 * 对照与表态见《事务边界决策表》§二·G（G-1 / G-2 / G-3 / G-7）。
 *
 * <h2>本切片交付的端点与实现</h2>
 * <table>
 *   <caption>端点 → 方法</caption>
 *   <tr><th>端点</th><th>方法</th><th>事务</th></tr>
 *   <tr><td>{@code GET /search/keywordSearch}</td><td>{@link #search}</td><td>无（纯读，G-2）</td></tr>
 *   <tr><td>{@code GET /search/IdSearch}</td><td>{@link #getContentDetailVO}</td><td>无（纯读）</td></tr>
 *   <tr><td>{@code GET /comment/show}</td><td>{@link #getCommentsForContent} ×2 重载</td><td>无（纯读）</td></tr>
 *   <tr><td>{@code POST /content/commentEnabled}</td><td>{@link #setCommentEnabled}</td><td>{@code @Transactional}</td></tr>
 *   <tr><td>{@code POST /content/update}</td><td>{@link #updateContentInfo}</td><td>{@code @Transactional}</td></tr>
 *   <tr><td>{@code POST /content/delete} / {@code /mediaDelete}</td><td>{@link #deleteContent} / {@link #deleteMedia}</td>
 *       <td>{@code @Transactional}</td></tr>
 * </table>
 *
 * <h2>提交后副作用统一走事件（G-3）</h2>
 * 本类**不直接调 ContentCache**——缓存变更一律
 * {@code events.publishEvent(ContentCacheChangedEvent.…)}，由
 * {@code ContentCacheChangedListener} 在 {@code AFTER_COMMIT} 执行。
 * 于是"缓存更新必须在提交后"从**注释纪律**变成**框架保证**
 * （TV 的写法是把 {@code contentCache.xxx()} 写在 {@code transactionTemplate.execute(...)} 之后，
 * 靠行序 + 注释维系；踩坑清单 §5 记录过"回滚了但副作用已发出"）。
 */
@Service
public class ContentService {

    private final ContentDao contentDao;
    private final ContentMediaDao contentMediaDao;
    private final ContentCache contentCache;
    private final CommentCache commentCache;
    private final CommentService commentService;
    private final LikeService likeService;
    private final ContentStatusFiller contentStatusFiller;
    private final ApplicationEventPublisher events;

    public ContentService(ContentDao contentDao,
                          ContentMediaDao contentMediaDao,
                          ContentCache contentCache,
                          CommentCache commentCache,
                          CommentService commentService,
                          LikeService likeService,
                          ContentStatusFiller contentStatusFiller,
                          ApplicationEventPublisher events) {
        this.contentDao = contentDao;
        this.contentMediaDao = contentMediaDao;
        this.contentCache = contentCache;
        this.commentCache = commentCache;
        this.commentService = commentService;
        this.likeService = likeService;
        this.contentStatusFiller = contentStatusFiller;
        this.events = events;
    }

    // ========================================================================
    // 搜索（G-2：⚠️ 有意改进 —— 去掉事务包装）
    // ========================================================================

    /**
     * 关键词搜索。
     *
     * <p><b>决策表 G-2：⚠️ 有意改进——去掉事务。</b>TV 的事务回调**只**承载两条 DB 查询
     * （命中总数 + 页内 id），查完即提交归还连接，再在事务外做缓存批量读（TV 的 T12 已改成这样）。
     * 两条纯读无原子性需求，TV 的事务只是"取连接的手段"；去掉后**对外行为完全一致**
     * （事务本来就在缓存读之前结束了），只是少了 BEGIN/COMMIT 两次往返。
     *
     * <p><b>顺序契约</b>（T15 tie-breaker，见 {@code ContentDao.keywordSearchInBrief}）：
     * 结果顺序 = {@code create_time DESC, id DESC}，来自 DAO 的 SQL；**字段值来自缓存**。
     * 故即使有人改了缓存加载顺序，页间顺序也不会漂移。
     *
     * <p><b>跳过 null</b>：某 id 在缓存/DB 都取不到（已删 / 媒体损坏）时**跳过该条**，不从列表里补位
     * ——于是返回条数可能少于 pageSize，而 {@code total} 仍是 SQL 的命中总数。TV 原样，
     * 且旧 pytest 对"total == 单页条数"只在**测试数据集可控**的用例里断言。
     *
     * @param keyword 关键词（**已 trim** 由调用方保证；DAO 的分支判据就是它的长度）
     * @param userId  当前用户 id，{@code null} = 匿名（不填个性化字段）
     */
    public PageResult<ContentVO> search(String keyword, Long userId, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        int total = contentDao.countKeywordSearch(keyword);
        List<Long> contentIds = contentDao.keywordSearchInBrief(keyword, offset, pageSize);

        // 事务外：该页批量读（一趟 pipeline 拉多条三态，miss 走批量装载），按原序跳过 null
        List<ContentVO> result = new ArrayList<>();
        Map<Long, ContentCacheDTO> byId = contentCache.getContentsBatch(contentIds);
        for (Long contentId : contentIds) {
            ContentCacheDTO dto = byId.get(contentId);
            if (dto == null) {
                continue;
            }
            result.add(contentCache.toContentVO(dto));
        }
        if (userId != null && !result.isEmpty()) {
            contentStatusFiller.fillLikeAndFollowBatch(result, userId);
        }
        return new PageResult<>(result, total, page, pageSize);
    }

    // ========================================================================
    // 内容详情
    // ========================================================================

    /**
     * 内容详情（含媒体列表 + 已登录时的 isLiked / isFollowed）。
     *
     * @return {@code null} = 内容不存在 / 已软删 / 媒体损坏（Controller 据此 404）
     */
    public ContentDetailVO getContentDetailVO(long contentId, Long userId) {
        ContentCacheDTO dto = contentCache.getContent(contentId);
        if (dto == null) {
            return null;
        }
        ContentDetailVO vo = contentCache.toDetailVO(dto);
        if (userId != null) {
            contentStatusFiller.fillContentLikeStatus(vo, contentId, userId);
            contentStatusFiller.fillFollowStatus(vo, userId);
        }
        return vo;
    }

    // ========================================================================
    // 评论查询（GET /comment/show —— **注意实现在本类，不在 CommentService**）
    // ========================================================================

    /**
     * 评论列表**缺省全量**路径（不传分页参数）。
     *
     * <h2>★ 为什么这个端点实现在 {@code ContentService}</h2>
     * TV 的 {@code CommentController.showComment} 调的是
     * {@code ContentService.getCommentsForContent}，**不是** {@code CommentService}——
     * 因为"能不能看评论"取决于**内容**的状态（是否存在 / 是否隐藏 / 评论区是否开启），
     * 而那些状态在内容缓存里。这是 S2 盘点时发现的"端点在别人 Service 里"的陷阱，
     * 也是 CM-3 把读路径划归 S5 的直接理由。**迁移时保持同一实现位置**：
     * 硬搬到 CommentService 会迫使它依赖 ContentCache，反而更糟。
     *
     * <h2>前置门禁（TV 原样，顺序不可改）</h2>
     * <pre>
     * dto == null              → 内容不存在/已软删/媒体损坏 ⇒ 空数组
     * !dto.isCommentEnabled()  → 评论区已关 ⇒ 空数组（评论数据保留，重开即恢复）
     * </pre>
     * 两者都是"返回空"而不是 404/409——**这是有意的**：读评论不该因为内容不可见而报错，
     * 否则前端要把"内容被删"与"评论为空"当两种情况处理。
     *
     * @return 全量评论树数组（主楼 + 每条的 children 全量）；无评论 ⇒ 空数组（不是 null）
     */
    public List<CommentVO> getCommentsForContent(long contentId, Long userId) {
        ContentCacheDTO dto = contentCache.getContent(contentId);
        if (dto == null || !dto.isCommentEnabled()) {
            return new ArrayList<>();
        }
        List<CommentCacheDTO> commentTree = commentCache.getFullTree(contentId);
        if (commentTree == null || commentTree.isEmpty()) {
            return new ArrayList<>();
        }
        return toCommentVOList(commentTree, userId);
    }

    /**
     * 评论列表**分页**路径（传了 {@code page} 或 {@code pageSize} 任一）。
     *
     * <p>{@code total} = **主楼条数**（不是评论总数）——信封语义与 {@code /follow} 一致；
     * 每页主楼只带前 K=2 条楼中楼 + {@code replyCount}，展开走 {@code /comment/replies}（T10-B）。
     *
     * <p>越界页返回空 list 但 {@code total} 仍为真值（前端据此判末页）。
     */
    public PageResult<CommentVO> getCommentsForContent(long contentId, Long userId, int page, int pageSize) {
        ContentCacheDTO dto = contentCache.getContent(contentId);
        if (dto == null || !dto.isCommentEnabled()) {
            return new PageResult<>(new ArrayList<>(), 0, page, pageSize);
        }
        CommentCache.PageWindow window = commentCache.getRootPage(contentId, page, pageSize);
        List<CommentCacheDTO> pageRoots = window.roots();
        return new PageResult<>(toCommentVOList(pageRoots, userId), window.rootTotal(), page, pageSize);
    }

    /** 评论树 → VO 树（带点赞态）：点赞态只对**本次要返回的这棵树**批量查询。 */
    private List<CommentVO> toCommentVOList(List<CommentCacheDTO> tree, Long userId) {
        Map<Long, Boolean> likedMap = new HashMap<>();
        if (userId != null && tree != null && !tree.isEmpty()) {
            List<Long> commentIds = commentCache.collectCommentIds(tree);
            likedMap = likeService.batchIsCommentLiked(userId, commentIds);
            if (likedMap == null) {
                likedMap = new HashMap<>();
            }
        }
        return commentService.convertToCommentVOList(tree, likedMap);
    }

    // ========================================================================
    // 作者开关评论区（G-1：✅ 保持单事务）
    // ========================================================================

    /**
     * 作者本人开关自己作品的评论区（S2 的 CM-3 把它显式划入本切片）。
     *
     * <p>校验顺序逐条保留（决定 404 / 403 / 200）：内容存在（**未软删**）→ 所有权 → 写。
     * 关闭后 {@code /comment/add} 拒绝（409）、{@code /comment/show} 返回空；评论数据保留，
     * 重新开启即恢复。
     *
     * <p>提交后失效内容 key（{@code Op.INVALIDATE}），读自愈回填 {@code comment_enabled}
     * ——这正是 S2 落地结果里记的那句："S5 搬 {@code /content/commentEnabled} 后即可闭环"。
     */
    @Transactional
    public void setCommentEnabled(long contentId, long userId, boolean enabled) {
        ContentCacheDTO dto = contentDao.findContent(contentId);
        if (dto == null) {
            throw new NotFoundException("内容不存在");
        }
        if (dto.getAuthorId() != userId) {
            throw new ForbiddenException("只能操作自己的作品");
        }
        contentDao.updateCommentEnabled(contentId, enabled);
        events.publishEvent(ContentCacheChangedEvent.invalidate(contentId));
    }

    // ========================================================================
    // 作者编辑作品（G-1：✅ 保持单事务）
    // ========================================================================

    /**
     * 作者本人编辑作品标题与简介（A3）。
     *
     * <p>三条**参数校验在事务外**（TV 亦在事务外）：标题非空、标题 ≤50（对齐前端 maxlength）、
     * 简介 ≤5000。校验先于归属校验——顺序保留决定"非作者 + 标题为空"时报 400 还是 403（TV 报 400）。
     *
     * <p>全文索引（ngram）由 MySQL 自动维护，无额外语句（TV 原注释）。
     *
     * <p>提交后**重载**内容 key（{@code Op.REFRESH}）并刷新索引——文案变了，光失效会让首次读者
     * 回源（可接受）但索引里只有 id 不含文案，故这里用 REFRESH 更贴近 TV 的 {@code refreshContent}。
     */
    @Transactional
    public void updateContentInfo(long contentId, long userId, String title, String description) {
        String t = title == null ? "" : title.trim();
        String d = description == null ? "" : description.trim();
        if (t.isEmpty()) {
            throw new ParamException("标题不能为空");
        }
        if (t.length() > 50) {
            throw new ParamException("标题不超过50字");
        }
        if (d.length() > 5000) {
            throw new ParamException("简介不超过5000字");
        }
        findOwnedContent(contentId, userId);
        contentDao.updateContentInfo(contentId, t, d);
        events.publishEvent(ContentCacheChangedEvent.refresh(contentId));
    }

    /**
     * 所有权校验（内容存在 + 作者本人），供编辑 / 删媒体 / 删作品复用。
     *
     * <p>文案逐字保留：{@code "内容不存在"}（404）/ {@code "只能操作自己的作品"}（403）。
     */
    protected ContentCacheDTO findOwnedContent(long contentId, long userId) {
        ContentCacheDTO dto = contentDao.findContent(contentId);
        if (dto == null) {
            throw new NotFoundException("内容不存在");
        }
        if (dto.getAuthorId() != userId) {
            throw new ForbiddenException("只能操作自己的作品");
        }
        return dto;
    }
}
