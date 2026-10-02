package io.github.yjhhhaaa06.videoweb.like.event;

import io.github.yjhhhaaa06.videoweb.comment.cache.CommentCache;
import io.github.yjhhhaaa06.videoweb.content.cache.ContentCache;
import io.github.yjhhhaaa06.videoweb.like.cache.LikeCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 点赞缓存更新器 —— 在**事务提交后**把变更同步进 Redis。
 *
 * <h2>★ {@code AFTER_COMMIT} 是本切片的核心机制（决策表 L-6）</h2>
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 的语义：
 * 事件在事务内发布，但监听器**只在事务成功提交后**才被调用；事务回滚 ⇒ **完全不调用**。
 * 于是"回滚后缓存被污染"这个旧实现靠注释纪律规避的隐患，被变成框架层面不可能发生的事。
 *
 * <h3>配套测试（关键，勿删）</h3>
 * {@code LikeCacheTests.事务回滚后缓存未被污染}：让事务内的计数更新抛异常 ⇒ 回滚 ⇒
 * 断言 Redis 里那个 key **根本不存在**。这是该机制的存在证明——
 * 若有人把缓存更新改回"写在事务方法体末尾"或"事务内更新"，此用例立刻失败。
 * 只会写"正常点赞后缓存被更新"的测试**不足以**证明时序正确（那种测试在两种实现下都会过）。
 *
 * <h3>两个实现要点</h3>
 * ① <b>不用 {@code @Async}</b>：TV 的缓存更新就在提交线程同步执行，语义一致；
 *   异步会引入"提交后缓存短暂未更新"的新窗口，且让测试需要等待，属无谓复杂化。
 * ② <b>{@code fallbackExecution} 保持默认 false</b>：本项目的点赞写操作**一律**在
 *    {@code @Transactional} 方法内发布事件，没有"无事务时也要更新缓存"的场景；
 *    若将来出现，必须显式打开并在此说明理由（而不是默默打开）。
 *
 * <h2>⚠️ 一处曾裁剪、已由 S5 补回</h2>
 * TV 在这里还会失效**内容详情缓存** / **评论树缓存**：
 * {@code contentCache.notifyLikeCountChanged(contentId)}、
 * {@code commentCache.notifyCommentLikeChanged(commentId)}。
 * S3 时这两个 Cache 属 S5、尚不存在 ⇒ 失效动作无从执行（"不为尚不存在的缓存预埋失效逻辑"）。
 * <b>S5 已按这里的补回位置补上</b>（见下方 switch 的两个分支）——它们是
 * {@code content.like_count} / {@code comment.like_count} 变化的**唯一失效点**：
 * 少了它们，缓存里的旧点赞数会一直服务到 TTL 到期（对外表现为"点赞后数字不变"）。
 */
@Slf4j
@Component
public class LikeChangedListener {

    private final LikeCache likeCache;
    private final ContentCache contentCache;
    private final CommentCache commentCache;

    public LikeChangedListener(LikeCache likeCache, ContentCache contentCache, CommentCache commentCache) {
        this.likeCache = likeCache;
        this.contentCache = contentCache;
        this.commentCache = commentCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLikeChanged(LikeChangedEvent event) {
        log.debug("点赞事务已提交，更新缓存: user={}, target={}, id={}, liked={}",
                event.userId(), event.target(), event.targetId(), event.liked());

        switch (event.target()) {
            case CONTENT -> {
                if (event.liked()) {
                    likeCache.likeContent(event.userId(), event.targetId());
                } else {
                    likeCache.unlikeContent(event.userId(), event.targetId());
                }
                // S5 补回（L-6）：失效内容 key，读自愈回填 DB 最新 like_count
                // ⚠️ 用 contentId（= event.targetId()）——别错写成 userId
                contentCache.notifyLikeCountChanged(event.targetId());
            }
            case COMMENT -> {
                if (event.liked()) {
                    likeCache.likeComment(event.userId(), event.targetId());
                } else {
                    likeCache.unlikeComment(event.userId(), event.targetId());
                }
                // S5 补回（L-6）：定位评论所属主楼并定向失效其 replies field（懒载刷新 like_count）
                commentCache.notifyCommentLikeChanged(event.targetId());
            }
        }
    }
}
