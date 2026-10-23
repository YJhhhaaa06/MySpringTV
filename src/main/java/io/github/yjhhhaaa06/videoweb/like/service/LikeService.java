package io.github.yjhhhaaa06.videoweb.like.service;

import io.github.yjhhhaaa06.videoweb.comment.dao.CommentDao;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.like.cache.LikeCache;
import io.github.yjhhhaaa06.videoweb.like.dao.CommentLikeDao;
import io.github.yjhhhaaa06.videoweb.like.dao.ContentLikeDao;
import io.github.yjhhhaaa06.videoweb.like.event.LikeChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 点赞业务。
 *
 * <p>迁移自 TV {@code com.itheima.like.service.LikeService}。
 * 骨架（{@code transactionTemplate.execute} / {@code conn} 穿透 / {@code catch (SQLException)} 包装）已删除，
 * 业务语义（校验顺序、异常类型、异常文案、事务边界）逐条保留——
 * 对照与反模式警告见《事务边界决策表》§二·D（L-1 ~ L-6）。
 *
 * <h2>三类方法，三种职责</h2>
 * <ol>
 *   <li><b>写（4 个）</b>：{@code @Transactional}，校验 + 记录 ±1 + 计数 ±1，末尾**发布事件**，
 *       由 {@code LikeChangedListener} 在 {@code AFTER_COMMIT} 更新缓存（L-6）。</li>
 *   <li><b>读（8 个中的单条/计数）</b>：纯委托 {@link LikeCache}（缓存三态读 + 回源 + 降级）。</li>
 *   <li><b>批量读（2 个）</b>：同上，供下游列表页复用（本切片无端点，S5 的
 *       {@code /comment/replies} 与内容列表会用）。</li>
 * </ol>
 *
 * <h2>★ 写路径为什么必须发事件、而不是直接调缓存</h2>
 * 直接调用会把"必须在提交后"变成一条**注释约定**（TV 的老问题：写错位置就是回滚了但缓存已改）。
 * 发事件 + {@code AFTER_COMMIT} 把时序交给框架，见 {@link LikeChangedEvent} 的说明。
 *
 * <h2>计数防负守卫（T34）</h2>
 * 两处 {@code updateLikeCount} 现已加防负守卫，但**形式因列类型而异**：
 * {@code content.like_count} 是 {@code int unsigned} ⇒ 守卫必须 {@code CAST(... AS SIGNED) + delta >= 0}
 * （否则 {@code 0 + (-1)} 在 unsigned 列上先触发 1690 下溢，守卫自身也炸）；
 * {@code comment.like_count} 是**有符号** int ⇒ 原生 {@code col + delta >= 0} 即可。
 * 语义 = block：拦住 ⇒ 影响 0 行、不抛错、不回滚 ⇒ **取消赞照常成功**（计数停在 0，不再 500 / −1）。
 * 触发前提是数据不自洽（有记录但计数为 0），正常路径走不到。
 */
@Service
public class LikeService {

    private final ContentDao contentDao;
    private final CommentDao commentDao;
    private final ContentLikeDao contentLikeDao;
    private final CommentLikeDao commentLikeDao;
    private final LikeCache likeCache;
    private final ApplicationEventPublisher events;

    public LikeService(ContentDao contentDao,
                       CommentDao commentDao,
                       ContentLikeDao contentLikeDao,
                       CommentLikeDao commentLikeDao,
                       LikeCache likeCache,
                       ApplicationEventPublisher events) {
        this.contentDao = contentDao;
        this.commentDao = commentDao;
        this.contentLikeDao = contentLikeDao;
        this.commentLikeDao = commentLikeDao;
        this.likeCache = likeCache;
        this.events = events;
    }

    // ========================================================================
    // 内容点赞 / 取消（L-1 / L-2）
    // ========================================================================

    /**
     * 点赞内容（`T59` 起**幂等设置**：重复 / 并发点赞 = 200，不重复计数）。
     *
     * <p><b>决策表 L-1：✅ 保持单事务。</b>"点赞记录"与 {@code content.like_count} 必须同进同退，
     * 否则计数漂移。存在性仍先判（不存在 → 404）。
     *
     * <p>★ 幂等靠 {@code INSERT IGNORE} + 受影响行数：唯一键 {@code uk_user_content} 撞键返回 0 行
     * ⇒ 视为"已赞"直接返回，**不 +1、不发事件**（事件监听器会 INCR 缓存，多发一次就多算一次）。
     */
    @Transactional
    public void likeContent(long userId, long contentId) {
        if (!contentDao.isContentExist(contentId)) {
            throw new NotFoundException(ErrorCode.CONTENT_NOT_FOUND, "内容不存在");
        }
        if (contentLikeDao.addLike(userId, contentId) == 0) {
            return;
        }
        contentDao.updateLikeCount(contentId, 1);
        events.publishEvent(LikeChangedEvent.content(userId, contentId, true));
    }

    /**
     * 取消内容点赞（`T59` 起**幂等取消**：未点赞 / 重复取消 = 204，不产生负计数）。
     *
     * <p>★ 按 {@code deleteLike} 的受影响行数决定是否 −1 与发事件：0 行 = 本就没赞，直接返回。
     * 若无条件 −1，重复取消会把别人的赞从计数里扣掉（计数列是全局的）。
     */
    @Transactional
    public void cancelContentLike(long userId, long contentId) {
        if (!contentDao.isContentExist(contentId)) {
            throw new NotFoundException(ErrorCode.CONTENT_NOT_FOUND, "内容不存在");
        }
        if (contentLikeDao.deleteLike(userId, contentId) == 0) {
            return;
        }
        contentDao.updateLikeCount(contentId, -1);
        events.publishEvent(LikeChangedEvent.content(userId, contentId, false));
    }

    // ========================================================================
    // 评论点赞 / 取消（L-3 / L-4）
    // ========================================================================

    /**
     * 点赞评论（`T59` 起**幂等设置**：重复 / 并发点赞 = 200，不重复计数）。
     *
     * <p>与 {@link #likeContent} 同构，只换对象。★ 存在性判定走 {@code CommentDao.isCommentExist}，
     * 其 SQL 带 {@code AND is_deleted = 0} ⇒ **给已软删的评论点赞被拒（404）**，
     * 与 S2 的评论写路径同口径，已固化测试。
     */
    @Transactional
    public void likeComment(long userId, long commentId) {
        if (!commentDao.isCommentExist(commentId)) {
            throw new NotFoundException(ErrorCode.COMMENT_NOT_FOUND, "评论不存在");
        }
        if (commentLikeDao.addLike(userId, commentId) == 0) {
            return;
        }
        commentDao.updateLikeCount(commentId, 1);
        events.publishEvent(LikeChangedEvent.comment(userId, commentId, true));
    }

    /** 取消评论点赞（`T59` 起**幂等取消**：未点赞 / 重复取消 = 204，不产生负计数）。 */
    @Transactional
    public void cancelCommentLike(long userId, long commentId) {
        if (!commentDao.isCommentExist(commentId)) {
            throw new NotFoundException(ErrorCode.COMMENT_NOT_FOUND, "评论不存在");
        }
        if (commentLikeDao.removeLike(userId, commentId) == 0) {
            return;
        }
        commentDao.updateLikeCount(commentId, -1);
        events.publishEvent(LikeChangedEvent.comment(userId, commentId, false));
    }

    // ========================================================================
    // 读：单条 / 计数（纯委托 LikeCache —— 缓存三态读 + 回源 + Redis 失败降级）
    // ========================================================================

    public boolean isContentLiked(long userId, long contentId) {
        return likeCache.isContentLiked(userId, contentId);
    }

    public int getContentLikeCount(long contentId) {
        return likeCache.getContentLikeCount(contentId);
    }

    /**
     * 内容点赞数的**回填转发**（T44-2）：详情计数聚合读（content 域）经 {@code InteractionService}
     * 调到这里，值来自 {@code content.like_count} 列快照。content 不得直连 like（T13/F-16 拆掉的边），
     * 经互动域转发是既有形态（读 {@code isContentLiked} 同路）。
     */
    public void backfillContentLikeCount(long contentId, int count) {
        likeCache.backfillContentCount(contentId, count);
    }

    public boolean isCommentLiked(long userId, long commentId) {
        return likeCache.isCommentLiked(userId, commentId);
    }

    public int getCommentLikeCount(long commentId) {
        return likeCache.getCommentLikeCount(commentId);
    }

    // ========================================================================
    // 读：批量（供下游列表页复用；本切片无端点，S5 的 /comment/replies 等会用）
    // ========================================================================

    /**
     * 批量查询用户对多个内容的点赞状态，返回**完整**映射（无缺失，DB 兜底补齐）。
     *
     * <p>TV 注释：缓存内部 pipeline 扫描 + DB 批量兜底 + 回填；Redis 异常降级 DB。
     */
    public Map<Long, Boolean> batchIsContentLiked(long userId, List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Map.of();
        }
        return likeCache.batchIsContentLiked(userId, contentIds);
    }

    /**
     * 批量查询用户对多个评论的点赞状态（逻辑同内容批量）。
     *
     * <p>S5 的评论读路径（{@code /comment/replies}、{@code /comment/show}）需要它来给每条评论
     * 标注 {@code isLiked}——这也是 S2 把读路径划归 S5 时记录的那项依赖。
     */
    public Map<Long, Boolean> batchIsCommentLiked(long userId, List<Long> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return Map.of();
        }
        return likeCache.batchIsCommentLiked(userId, commentIds);
    }

    // ========================================================================
    // 级联失效：内容被删/下架（S5 补入；S3 曾声明"本切片无调用方 → S5"）
    // ========================================================================

    /**
     * 失效内容被删/下架后的点赞缓存（计数 key）。
     *
     * <p>调用方是 {@code ContentCacheChangedListener} 的 {@code REMOVE} 分支
     * （**提交后**执行）——TV 的调用点同样在 {@code ContentService} 的事务之后。
     * 为什么只失效计数、成员怎么办：见 {@link LikeCache#invalidateContentLikeCount}。
     */
    public void deleteContentLike(long contentId) {
        likeCache.invalidateContentLikeCount(contentId);
    }
}
