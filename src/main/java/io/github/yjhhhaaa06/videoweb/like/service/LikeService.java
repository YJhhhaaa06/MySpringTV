package io.github.yjhhhaaa06.videoweb.like.service;

import io.github.yjhhhaaa06.videoweb.comment.dao.CommentDao;
import io.github.yjhhhaaa06.videoweb.common.exception.ConflictException;
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
 * <h2>已知并接受的旧实现尖锐处（不修）</h2>
 * 两处 {@code updateLikeCount} 都**无防负守卫**，而底层列类型不对称：
 * {@code content.like_count} 是 {@code int unsigned} ⇒ 计数为 0 时再 −1 会下溢报错 → 500；
 * {@code comment.like_count} 是**有符号** int ⇒ 同场景静默变 −1。
 * 触发前提是数据不自洽（有记录但计数为 0），正常路径走不到。详见决策表 L-1/L-2。
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
     * 点赞内容。
     *
     * <p><b>决策表 L-1：✅ 保持单事务。</b>"点赞记录"与 {@code content.like_count} 必须同进同退，
     * 否则计数漂移。校验顺序（存在性 → 是否已赞）逐条保留，决定客户端拿到 404 还是 409。
     *
     * <p>并发仍靠唯一键 {@code content_like.uk_user_content} 兜底：撞键 ⇒ {@code DuplicateKeyException}
     * ⇒ 全局出口 409（同 C-1 / U-2 的映射点）。
     */
    @Transactional
    public void likeContent(long userId, long contentId) {
        if (!contentDao.isContentExist(contentId)) {
            throw new NotFoundException("内容不存在");
        }
        if (contentLikeDao.isLiked(userId, contentId)) {
            throw new ConflictException("不可重复点赞");
        }
        contentLikeDao.addLike(userId, contentId);
        contentDao.updateLikeCount(contentId, 1);
        events.publishEvent(LikeChangedEvent.content(userId, contentId, true));
    }

    /**
     * 取消内容点赞。
     *
     * <p>文案 "未点赞，无法取消" 与评论侧的 "未点赞，不可取消" 在 TV 里**本来就不一致**，
     * 此处**原样保留**——`msg` 不在冻结契约内，但"顺手统一"是一次无声的契约改动。
     */
    @Transactional
    public void removeLikeContent(long userId, long contentId) {
        if (!contentDao.isContentExist(contentId)) {
            throw new NotFoundException("内容不存在");
        }
        if (!contentLikeDao.isLiked(userId, contentId)) {
            throw new ConflictException("未点赞，无法取消");
        }
        contentLikeDao.deleteLike(userId, contentId);
        contentDao.updateLikeCount(contentId, -1);
        events.publishEvent(LikeChangedEvent.content(userId, contentId, false));
    }

    // ========================================================================
    // 评论点赞 / 取消（L-3 / L-4）
    // ========================================================================

    /**
     * 点赞评论。
     *
     * <p>与 {@link #likeContent} 同构，只换对象。★ 存在性判定走 {@code CommentDao.isCommentExist}，
     * 其 SQL 带 {@code AND is_deleted = 0} ⇒ **给已软删的评论点赞被拒（404）**，
     * 与 S2 的评论写路径同口径，已固化测试。
     */
    @Transactional
    public void likeComment(long userId, long commentId) {
        if (!commentDao.isCommentExist(commentId)) {
            throw new NotFoundException("评论不存在");
        }
        if (commentLikeDao.isLiked(userId, commentId)) {
            throw new ConflictException("不可重复点赞");
        }
        commentLikeDao.addLike(userId, commentId);
        commentDao.updateLikeCount(commentId, 1);
        events.publishEvent(LikeChangedEvent.comment(userId, commentId, true));
    }

    /** 取消评论点赞（同构；文案保留 TV 原文 "未点赞，不可取消"）。 */
    @Transactional
    public void removeLikeComment(long userId, long commentId) {
        if (!commentDao.isCommentExist(commentId)) {
            throw new NotFoundException("评论不存在");
        }
        if (!commentLikeDao.isLiked(userId, commentId)) {
            throw new ConflictException("未点赞，不可取消");
        }
        commentLikeDao.removeLike(userId, commentId);
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
}
