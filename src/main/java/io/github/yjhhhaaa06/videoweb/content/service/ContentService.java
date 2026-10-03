package io.github.yjhhhaaa06.videoweb.content.service;

import io.github.yjhhhaaa06.videoweb.common.exception.ConflictException;
import io.github.yjhhhaaa06.videoweb.common.exception.ForbiddenException;
import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.content.cache.ContentCache;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentMediaDao;
import io.github.yjhhhaaa06.videoweb.content.event.ContentCacheChangedEvent;
import io.github.yjhhhaaa06.videoweb.content.event.ContentMilestoneEvent;
import io.github.yjhhhaaa06.videoweb.content.event.ContentPublishedEvent;
import io.github.yjhhhaaa06.videoweb.content.model.ContentType;
import io.github.yjhhhaaa06.videoweb.content.model.cache.ContentCacheDTO;
import io.github.yjhhhaaa06.videoweb.content.model.entity.Content;
import io.github.yjhhhaaa06.videoweb.content.model.entity.ContentMedia;
import io.github.yjhhhaaa06.videoweb.content.model.vo.AdminContentVO;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentDetailVO;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.comment.dao.CommentDao;
import io.github.yjhhhaaa06.videoweb.like.dao.ContentLikeDao;
import io.github.yjhhhaaa06.videoweb.like.service.LikeService;
import io.github.yjhhhaaa06.videoweb.upload.model.UploadType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
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
 *   <tr><td>{@code POST /api/upload/video}</td><td>{@link #addVideo}</td><td>{@code @Transactional}（S7）</td></tr>
 *   <tr><td>{@code POST /api/upload/post}</td><td>{@link #addPost}</td><td>{@code @Transactional}（S7）</td></tr>
 *   <tr><td>{@code POST /api/upload/replace}</td><td>{@link #replaceMedia}</td><td>{@code @Transactional}（S7）</td></tr>
 *   <tr><td>{@code GET /api/admin/content/list}</td><td>{@link #listContentForAdmin}</td>
 *       <td>无（纯读，S8 去事务）</td></tr>
 *   <tr><td>{@code POST /api/admin/content/hide}</td><td>{@link #hideContent}</td><td>{@code @Transactional}（S8）</td></tr>
 *   <tr><td>{@code POST /api/admin/content/unhide}</td><td>{@link #unhideContent}</td><td>{@code @Transactional}（S8）</td></tr>
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
    private final CommentDao commentDao;
    private final ContentLikeDao contentLikeDao;
    private final ContentCache contentCache;
    private final LikeService likeService;
    private final ContentStatusFiller contentStatusFiller;
    private final ApplicationEventPublisher events;

    public ContentService(ContentDao contentDao,
                          ContentMediaDao contentMediaDao,
                          CommentDao commentDao,
                          ContentLikeDao contentLikeDao,
                          ContentCache contentCache,
                          LikeService likeService,
                          ContentStatusFiller contentStatusFiller,
                          ApplicationEventPublisher events) {
        this.contentDao = contentDao;
        this.contentMediaDao = contentMediaDao;
        this.commentDao = commentDao;
        this.contentLikeDao = contentLikeDao;
        this.contentCache = contentCache;
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

    /**
     * 按 id 批量装载内容 VO（feed 的 {@code renderPage} 用）——**跨域只走 Service 契约**。
     *
     * <h2>为什么要开这个方法（S6-B2a 同款）</h2>
     * feed 域需要"该页 id → ContentVO 列表"，但按 ArchUnit 规则 2，feed **不得触碰
     * {@code content.cache.ContentCache}**。故把这条窄查询提升为 {@code ContentService} 的公开方法，
     * feed 依赖本 Service 而非内容域的缓存实现。
     *
     * <p>语义与 {@code recall} 的装载段逐字一致：一趟批量读（含 miss 装载），按入参 {@code contentIds}
     * 的**原序**输出，取不到的条目（已删 / 媒体损坏）**跳过**——于是返回条数可能少于入参。
     *
     * @param contentIds 该页内容 id（可为空 → 空列表）
     */
    public List<ContentVO> loadContentVOs(List<Long> contentIds) {
        List<ContentVO> result = new ArrayList<>();
        if (contentIds == null || contentIds.isEmpty()) {
            return result;
        }
        Map<Long, ContentCacheDTO> byId = contentCache.getContentsBatch(contentIds);
        for (Long contentId : contentIds) {
            ContentCacheDTO dto = byId.get(contentId);
            if (dto == null) {
                continue;   // 已删 / 媒体损坏 ⇒ 跳过（total 不变——与 search/profile 同款语义）
            }
            result.add(contentCache.toContentVO(dto));
        }
        return result;
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
    // 评论可读性门禁（GET /comment/show 的前置判断）
    // ========================================================================

    /**
     * 内容是否**允许读取评论**：内容存在（未软删、媒体未损坏）**且**评论区开启。
     *
     * <h2>★ S6-B2d：为什么这里只剩"门禁"，评论数据本身搬走了</h2>
     * 迁移自 TV 的 {@code ContentService.getCommentsForContent}，但**只有门禁部分留在这里**。
     * 原来整个方法在本类，理由是"能不能看评论取决于**内容**的状态（是否存在 / 是否隐藏 /
     * 评论区是否开启），而那些状态在内容缓存里"——这个理由**对门禁成立，对数据不成立**：
     * 评论树、评论 VO、点赞态填充全是 comment 域自己的事。
     *
     * <p>把整段留在这里的代价是实打实的：{@code content} 域被迫 import
     * {@code comment.cache.CommentCache}、{@code comment.dao.CommentDao}、
     * {@code comment.service.CommentService}、{@code comment.model.vo.CommentVO}——
     * 于是 {@code content⇄comment} 形成了一个**双向环**，两个域谁也不能独立看懂。
     *
     * <p>现在拆成一条**窄查询**：内容域只回答"能不能看"（本方法），
     * 评论域用这个答案 + 自己的缓存与 VO 组装响应
     * （{@code CommentService.getCommentsForContent}）。依赖方向变成**单向** {@code comment → content}。
     *
     * <p>⚠️ 注意这与"硬搬到 CommentService 会迫使它依赖 ContentCache"并不矛盾——
     * 本方法把那次缓存读封装在**内容域内部**，评论域看到的是一个 {@code boolean}，
     * 不是 {@code ContentCache}。这正是端口的用法。
     *
     * <h2>口径（TV 原样，不可改）</h2>
     * <pre>
     * dto == null              → 内容不存在 / 已软删 / 媒体损坏 ⇒ false
     * !dto.isCommentEnabled()  → 评论区已关 ⇒ false（评论数据保留，重开即恢复）
     * </pre>
     * 调用方对 false 的处理是**返回空**而不是 404/409——这是有意的：读评论不该因为内容不可见
     * 而报错，否则前端要把"内容被删"与"评论为空"当两种情况处理。**判空与报错的区别留在调用方**。
     *
     * @return {@code true} 表示可以读取该内容的评论
     */
    public boolean isCommentReadable(long contentId) {
        ContentCacheDTO dto = contentCache.getContent(contentId);
        return dto != null && dto.isCommentEnabled();
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

    // ========================================================================
    // 作者删单条媒体（G-1：✅ 保持单事务）
    // ========================================================================

    /**
     * 作者删除自己作品的单条**图片**（{@code POST /content/mediaDelete?contentId=&type=&sort=}）。
     *
     * <p>两条**不得"顺手优化"**的规则（TV 原注释）：
     * <ol>
     *   <li><b>只允许 {@code type == 2}（图片）</b>：视频与封面是**结构性资源**，只可替换不可删
     *       （删了视频，内容就成了一条构建不出来的记录）。校验在**事务外**（TV 亦在事务外，
     *       {@code ParamException("仅支持删除图片")} → 400），保留"参数错不占事务"的性质。</li>
     *   <li><b>删完必须重排 {@code sort}</b>：前端用 {@code index+1} 定位第 N 张图，
     *       {@code sort} 出现空洞会让"第 3 张"指错。重排与删除同一事务（重排失败会留空洞）。</li>
     * </ol>
     *
     * <p>提交后 {@code REFRESH} 内容 key —— 详情的 {@code imageUrls} 与封面可能变了。
     *
     * @return 被删媒体的 url（**调用方据此清理物理文件**——S7 已补回：
     *         {@code ContentController.mediaDelete} 提交后调 {@code deleteFileByUrl}）
     */
    @Transactional
    public String deleteMedia(long contentId, long userId, int type, int sort) {
        if (type != 2) {
            throw new ParamException("仅支持删除图片");
        }
        ContentMedia media = findOwnedMedia(contentId, userId, type, sort);
        String oldUrl = media.getUrl();
        contentMediaDao.deleteMediaByContentIdAndTypeSort(contentId, type, sort);
        contentMediaDao.compactImageSort(contentId, sort);
        events.publishEvent(ContentCacheChangedEvent.refresh(contentId));
        return oldUrl;
    }

    // ========================================================================
    // 作者删作品（G-1：✅ 保持单事务 —— 本切片最宽的级联）
    // ========================================================================

    /**
     * 作者删除自己的作品（{@code POST /content/delete?contentId=}，软删除、不可恢复）。
     *
     * <h2>★ 决策表 G-1：✅ 保持单事务</h2>
     * 一个事务里做四件事，**任一失败必须整体回滚**：
     * <pre>
     * ① findOwnedContent            — 存在 + 所有权（404 / 403）
     * ② 读全部媒体 url              — 返回值要交给调用方清理物理文件（本切片裁剪了清理）
     * ③ contentDao.softDeleteContent — 内容软删
     * ④ commentDao.softDeleteByContentId — 级联软删**全部**评论（含主楼与楼内回复，对已单删幂等）
     * ⑤ contentLikeDao.deleteByContentId — 物理删点赞记录
     * ⑥ contentMediaDao.deleteByContentId — 物理删媒体记录
     * </pre>
     * ③④⑤⑥ 不同进同退会留下**不可自愈的不一致**（内容已删而点赞记录/媒体行仍在，或反之）
     * ——没有任何补偿机制能修复它。故撤销事务是错的。
     *
     * <h2>★ 三个提交后副作用（一个自有事件 + 两个订阅方）</h2>
     * <ol>
     *   <li>{@code ContentCacheChangedEvent.remove} —— DEL 内容 key + 从**全部**索引 key 剔除
     *       （只 DEL 内容 key 会让它留在推荐索引里，被反复探测到一个永远 null 的 id）；</li>
     *   <li>评论两键组的失效**由评论域自己订阅上面这个事件**完成
     *       （{@code comment.event.ContentRemovedCommentListener}）——S6-B2d 起本域不再发布
     *       评论域的事件；</li>
     *   <li>点赞计数 key 的失效**由点赞域自己订阅同一个事件**完成
     *       （{@code like.event.ContentRemovedLikeListener}；TV 的 T4 结论：成员 key 是用户维度，
     *       内容被删时无法廉价反查逐个 SREM）。</li>
     * </ol>
     * 即"**内容删了**"这一个业务事实，三方各自失效自己的缓存，本域**不必知道**谁有缓存。
     *
     * <h2>★ S7 补回：物理文件清理已兑现（A7 闭合）</h2>
     * TV 在这里返回 url 列表，由 Controller 逐个 {@code fileUploadService.deleteFileByUrl(url)}
     * （"尽力而为，DB 已提交"）。S5 因 upload 域未迁移**有意裁剪**（只留返回值 + 补回位置），
     * S7 接入 {@code FileUploadService} 后在 {@code ContentController.delete} 的提交后段补回。
     * 至此"删作品后磁盘文件同步消失"成立（双端可观察：DB 行 + 文件系统）。
     *
     * @return 该内容全部媒体的 url（供调用方清理物理文件——S7 已补回：
     *         {@code ContentController.delete} 提交后逐个 {@code deleteFileByUrl}）
     */
    @Transactional
    public List<String> deleteContent(long contentId, long userId) {
        findOwnedContent(contentId, userId);
        List<String> mediaUrls = new ArrayList<>();
        Map<Integer, List<ContentMedia>> mediaByType =
                groupMediaByType(contentMediaDao.findMediaByContentId(contentId));
        for (List<ContentMedia> group : mediaByType.values()) {
            for (ContentMedia media : group) {
                mediaUrls.add(media.getUrl());
            }
        }
        contentDao.softDeleteContent(contentId);
        commentDao.softDeleteByContentId(contentId);
        contentLikeDao.deleteByContentId(contentId);
        contentMediaDao.deleteByContentId(contentId);

        events.publishEvent(ContentCacheChangedEvent.remove(contentId));
        // T2 里程碑（B12）：作者删除 —— 提交后由 ContentMilestoneListener 记 "删除内容成功"。
        // ⚠️ 不复用 ContentCacheChangedEvent.remove：管理端 hide 也会发 REMOVE，
        //    复用会让"下架"被误记成"作者删除"（行为改动，坚决不要）。
        events.publishEvent(new ContentMilestoneEvent(contentId, ContentMilestoneEvent.Action.DELETED));
        return mediaUrls;
    }

    // ========================================================================
    // S7：upload 发布写路径（决策表 §四·S7：三处 ✅ 保持单事务）
    // ========================================================================

    /**
     * 发布视频（{@code POST /api/upload/video}）：建内容行 + 插视频/封面两条媒体行。
     *
     * <h2>★ 决策表 §四·S7：✅ 保持单事务</h2>
     * 「建内容 + 插媒体」必须同进同退——否则会留下"有内容行但无媒体行"的记录，
     * 而 {@code ContentCache} 装载时"无媒体 ⇒ 媒体损坏 ⇒ 视为不可见"，等于一条永远打不开的内容。
     * TV 用 {@code transactionTemplate.execute} 把 {@code doAddContent + addMedia×2} 包在一个事务里，
     * 新实现照此用 {@code @Transactional}。
     *
     * <h2>提交后副作用（模式 A）</h2>
     * 旧实现在事务后调 {@code contentCache.addContent(videoId)}。本实现改为发
     * {@code ContentCacheChangedEvent.refresh(videoId)}（AFTER_COMMIT）。
     * **语义已核对等价**：新 {@code ContentCache.refreshContent} = 装载 → 写 key → 进索引，
     * 与旧 {@code addContent} 逐字相同（两者在新实现里其实是同一段代码）。
     *
     * <h2>提交后副作用（S9 起共两个事件）</h2>
     * <ol>
     *   <li><b>feed 投递</b>：TV 在此处直调 {@code feedPushNotifier.publishContentPublished(videoId, userId)}
     *       （提交后、失败只降级）。**S9 已兑现**（原 {@code TODO(feed 切片)} / 台账 A3 闭合）：
     *       本类只发 {@code ContentPublishedEvent}，由 {@code feed.event.ContentPublishedFeedListener}
     *       订阅后投 MQ —— 于是 **content 不依赖 feed**（消掉 TV 的 {@code content ⇄ feed} 环）。</li>
     *   <li><b>内容缓存刷新</b>：发 {@code ContentCacheChangedEvent.refresh(contentId)}（AFTER_COMMIT）。</li>
     * </ol>
     *
     * <h2>⚠️ 一处有意留后</h2>
     * <b>文件落盘与 DB 无法原子</b>：由 Controller 在失败时删除已落盘文件补偿（见 {@code UploadController}）。
     *
     * @param videoUrl 已落盘视频的**应用内相对 URL**
     * @param coverUrl 已落盘封面的应用内相对 URL
     * @return 新内容 id
     */
    @Transactional
    public long addVideo(long userId, String title, String description, int categoryId,
                         String videoUrl, String coverUrl) {
        long contentId = doAddContent(userId, ContentType.VIDEO, title, description, categoryId);
        contentMediaDao.addMedia(contentId, videoUrl, UploadType.VIDEO.getMediaType(), 1);
        contentMediaDao.addMedia(contentId, coverUrl, UploadType.COVER.getMediaType(), 1);
        // S9 补回（原 TODO(feed 切片)）：声明"内容已发布"，由 feed 域订阅后投递写扩散消息。
        // ★ 与 TV 的差异：TV 直调 feedPushNotifier（content→feed 编译期依赖），此处改发事件
        //   （content 只声明自己发生了什么）——消掉 content⇄feed 环（《决策留痕表》C-9）。
        events.publishEvent(new ContentPublishedEvent(contentId, userId));
        events.publishEvent(ContentCacheChangedEvent.refresh(contentId));
        // T2 里程碑（B12）：提交后由 ContentMilestoneListener 记 "添加视频成功" ——
        // 事务内只**声明事实**，避免"提交失败却留下成功日志"（见 ContentMilestoneEvent 类注释）。
        events.publishEvent(new ContentMilestoneEvent(contentId, ContentMilestoneEvent.Action.VIDEO_ADDED));
        return contentId;
    }

    /**
     * 发布图文（{@code POST /api/upload/post}）：建内容行 + 可选封面 + 0~n 张图片。
     *
     * <p>决策与副作用口径同 {@link #addVideo}（✅ 保持单事务 / REFRESH 事件 / feed TODO）。
     * 与视频的唯一结构差异：封面**可选**（{@code coverUrl == null} 不插行），
     * 图片的 {@code sort} 从 1 递增（TV 原样）。
     *
     * @param coverUrl  封面相对 URL；{@code null} 表示无封面
     * @param imageUrls 正文图片相对 URL 列表（可为空；顺序即 {@code sort} 1..n）
     */
    @Transactional
    public long addPost(long userId, String title, String description, int categoryId,
                        String coverUrl, List<String> imageUrls) {
        long contentId = doAddContent(userId, ContentType.POST, title, description, categoryId);
        if (coverUrl != null) {
            contentMediaDao.addMedia(contentId, coverUrl, UploadType.COVER.getMediaType(), 1);
        }
        int sort = 1;
        for (String imageUrl : imageUrls) {
            contentMediaDao.addMedia(contentId, imageUrl, UploadType.IMAGE.getMediaType(), sort++);
        }
        // S9 补回（原 TODO(feed 切片)）：口径同 addVideo（《遗留台账》A3 → 已闭合）。
        events.publishEvent(new ContentPublishedEvent(contentId, userId));
        events.publishEvent(ContentCacheChangedEvent.refresh(contentId));
        // T2 里程碑（B12）：口径同 addVideo（AFTER_COMMIT 才落 "添加动态成功"）。
        events.publishEvent(new ContentMilestoneEvent(contentId, ContentMilestoneEvent.Action.POST_ADDED));
        return contentId;
    }

    /**
     * 建内容行（TV {@code doAddContent} 的等价内联）。插入列只有 TV 那五列，主键由
     * {@code useGeneratedKeys} 回填（TV 的手工取键骨架已删）。
     */
    private long doAddContent(long userId, ContentType type, String title, String description, int categoryId) {
        Content content = new Content(null, userId, type.getTypeNumber(), title, description, categoryId);
        contentDao.addContent(content);
        return content.getId();
    }

    /**
     * 作者换源（{@code POST /api/upload/replace}）：替换自作品某条媒体的文件。
     *
     * <h2>★ 决策表 §四·S7：✅ 保持单事务</h2>
     * 事务内三件事：① 归属 + 媒体定位（404 / 403）→ ② 更新 {@code content_media.url}
     * （同时把 {@code file_exists} 置 1 并记录校验时间）→ ③ 回写内容级 {@code content.file_exists}
     * 聚合。三者同进同退（TV 原样）。
     *
     * <h2>提交后：两条清理/刷新，顺序与归属</h2>
     * <ol>
     *   <li>{@code REFRESH} 内容缓存（媒体 url 变了，详情的 videoUrl/coverUrl/imageUrls 要重载）；</li>
     *   <li><b>旧文件删除由 Controller 做</b>（{@code deleteFileByUrl(oldUrl)}）——本方法把旧 url
     *       **返回给调用方**，与 TV 一致。删除发生在事务提交之后（Controller 拿到的返回值时
     *       事务已提交），故不会出现"DB 未提交却删了旧文件"。</li>
     * </ol>
     *
     * @return 被替换掉的**旧** url（供调用方清理物理文件）
     */
    @Transactional
    public String replaceMedia(long contentId, long userId, int type, int sort, String newUrl) {
        ContentMedia media = findOwnedMedia(contentId, userId, type, sort);
        String oldUrl = media.getUrl();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        contentMediaDao.updateMediaUrl(media.getMediaId(), newUrl, true, now);
        contentDao.updateFileExists(contentId, true, now);
        events.publishEvent(ContentCacheChangedEvent.refresh(contentId));
        return oldUrl;
    }

    /** 所有权校验 + 定位媒体行，供删媒体/换源复用（404「媒体资源不存在」）。 */
    private ContentMedia findOwnedMedia(long contentId, long userId, int type, int sort) {
        findOwnedContent(contentId, userId);
        ContentMedia media = contentMediaDao.findMediaByContentTypeSort(contentId, type, sort);
        if (media == null) {
            throw new NotFoundException("媒体资源不存在");
        }
        return media;
    }

    /** 扁平行按 {@code type} 分组（行已由 SQL 按 {@code type,sort} 排序 ⇒ 组内保序）。 */
    private static Map<Integer, List<ContentMedia>> groupMediaByType(List<ContentMedia> rows) {
        Map<Integer, List<ContentMedia>> byType = new HashMap<>();
        for (ContentMedia media : rows) {
            byType.computeIfAbsent(media.getType(), key -> new ArrayList<>()).add(media);
        }
        return byType;
    }

    // ========================================================================
    // S8：admin 内容运维（决策表 §四·S8）
    // ========================================================================

    /**
     * 管理端内容清单（{@code GET /api/admin/content/list}）：含正常与已下架，
     * **不含已删除(1)** 的内容（TV 的 {@code WHERE is_deleted IN (0,2)}）。
     *
     * <h2>★ 决策表 §四·S8：⚠️ 有意改进——去掉事务</h2>
     * TV 用 {@code transactionTemplate.execute} 包了一条纯 SELECT，其事务只承担"取连接"的作用
     * （旧模型每次 DAO 调用都要一个 conn）。单条读无原子性语义，去掉后**对外行为逐字不变**，
     * 与 G-2（search）/ G-5（profile）同款处置。
     *
     * <p>权限（{@code role == 1}）由 {@code JwtAuthFilter} 在 {@code /api/admin/*} 统一校验，
     * 本方法不再重复判权（TV 原样）。
     */
    public List<AdminContentVO> listContentForAdmin() {
        return contentDao.findContentForAdmin();
    }

    /**
     * 管理员下架内容（{@code POST /api/admin/content/hide}）：{@code is_deleted} 0→2。
     *
     * <h2>★ 决策表 §四·S8：✅ 保持单事务</h2>
     * 一个事务里两件事：① 前置校验（404 / 409，见 {@link #checkHideable}）
     * ② {@code updateContentDeletedState(2)}。校验与写必须同进同退——否则并发下
     * "校验通过但状态已被别人改掉"会静默写入错误状态。
     *
     * <h2>★ 提交后：三条失效由**既有订阅方**承接（A10 闭合，零新代码）</h2>
     * TV 下架后依次调三行：{@code contentCache.removeContent} +
     * {@code commentCache.invalidateComments} + {@code likeService.deleteContentLike}。
     * S6-B2 起这三件事已改由「内容域发事件、评论域与点赞域各自订阅」承担：
     * <pre>
     *   events.publishEvent(remove(contentId))        // 本行
     *     ├─ ContentCacheChangedListener      → removeContent（DEL 内容 key + 从**全部**索引剔除）
     *     ├─ ContentRemovedCommentListener    → invalidateComments（评论两键组整组失效）
     *     └─ ContentRemovedLikeListener       → deleteContentLike（点赞计数 key 失效）
     * </pre>
     * 三者都在本事务**提交后**执行（AFTER_COMMIT）；事务回滚 ⇒ 一条都不执行。
     * 这既与 TV 的三行**逐条等价**，又让内容域不必知道评论域/点赞域有缓存。
     *
     * <p>下架只改 {@code is_deleted}，**不动**评论/点赞/媒体记录与物理文件——"隐藏≠删除"，
     * 恢复后数据完好（旧 pytest {@code test_hide_content.py} 对此有逐字断言）。
     */
    @Transactional
    public void hideContent(long contentId) {
        checkHideable(contentId);
        contentDao.updateContentDeletedState(contentId, CONTENT_STATE_HIDDEN);
        events.publishEvent(ContentCacheChangedEvent.remove(contentId));
    }

    /**
     * 管理员恢复内容（{@code POST /api/admin/content/unhide}）：{@code is_deleted} 2→0。
     *
     * <p>决策同 {@link #hideContent}（✅ 保持单事务）。
     *
     * <h2>提交后：只发 {@code REFRESH}（与 TV 逐字对齐）</h2>
     * TV 恢复后只调 {@code contentCache.refreshContent(contentId)}——**不碰**评论/点赞缓存
     * （它们在**下架时**已整组失效，恢复时按需自愈回填）。故这里只发
     * {@code refresh}：重载内容 key + 刷新索引，前台立即重新可见。
     */
    @Transactional
    public void unhideContent(long contentId) {
        checkUnhideable(contentId);
        contentDao.updateContentDeletedState(contentId, CONTENT_STATE_NORMAL);
        events.publishEvent(ContentCacheChangedEvent.refresh(contentId));
    }

    /** {@code content.is_deleted}：正常。 */
    private static final int CONTENT_STATE_NORMAL = 0;
    /** {@code content.is_deleted}：作者删除。 */
    private static final int CONTENT_STATE_DELETED = 1;
    /** {@code content.is_deleted}：管理员下架。 */
    private static final int CONTENT_STATE_HIDDEN = 2;

    /**
     * 下架前置校验：存在且未被删除、未处于下架态。
     *
     * <p>⚠️ 与 TV 的**唯一差异**：TV 的 {@code getContentStatus} 用 {@code -1} 作"不存在"哨兵，
     * 这里让 DAO 返回 {@code Integer}（无行 ⇒ {@code null}）并判 {@code null} ⇒ 404。
     * 三条分支的**异常类型与文案逐字保留**（决定 404 / 409 / 409）。
     */
    private void checkHideable(long contentId) {
        Integer state = contentDao.getContentStatus(contentId);
        if (state == null) {
            throw new NotFoundException("内容不存在");
        }
        if (state == CONTENT_STATE_DELETED) {
            throw new ConflictException("内容已删除，无法下架");
        }
        if (state == CONTENT_STATE_HIDDEN) {
            throw new ConflictException("内容已下架");
        }
    }

    /** 恢复前置校验：存在且未被删除、当前**处于**下架态（否则 409「内容未下架」）。 */
    private void checkUnhideable(long contentId) {
        Integer state = contentDao.getContentStatus(contentId);
        if (state == null) {
            throw new NotFoundException("内容不存在");
        }
        if (state == CONTENT_STATE_DELETED) {
            throw new ConflictException("内容已删除，无法恢复");
        }
        if (state == CONTENT_STATE_NORMAL) {
            throw new ConflictException("内容未下架");
        }
    }
}
