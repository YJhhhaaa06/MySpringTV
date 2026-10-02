package io.github.yjhhhaaa06.videoweb.like.event;

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
 *   若将来出现，必须显式打开并在此说明理由（而不是默默打开）。
 *
 * <h2>★ S6-B2b：本监听器已"瘦身"——只剩点赞域自己的缓存</h2>
 * 原先它还直接驱动 {@code content.cache.ContentCache} 与 {@code comment.cache.CommentCache}：
 *
 * <pre>
 *   like.event.LikeChangedListener ──► content.cache.ContentCache    ❌ 跨域碰别人的缓存
 *                                  └─► comment.cache.CommentCache    ❌
 * </pre>
 *
 * 那让**点赞域**被迫知道"内容缓存有个 notifyLikeCountChanged、评论缓存有个
 * notifyCommentLikeChanged"——别人的缓存实现成了点赞域的编译期依赖，
 * 也让 {@code content⇄like}、{@code comment⇄like} 两个环闭得更死。
 *
 * <p>现在改为**各方订阅同一个事件、各自失效自己的缓存**：
 * <ul>
 *   <li>{@code content.event.LikeChangedContentListener} —— 失效内容 key（{@code content.like_count} 变了）</li>
 *   <li>{@code comment.event.LikeChangedCommentListener} —— 失效评论树 field（{@code comment.like_count} 变了）</li>
 * </ul>
 * 依赖方向由"like → 别人的缓存"变成"content/comment → like 的事件"，
 * 而 {@code content→like}、{@code comment→like} 本就存在（它们要查点赞态），**没有新增环**。
 *
 * <p>⚠️ <b>时序不变性</b>：三个监听器都标 {@code AFTER_COMMIT}，在**同一次**提交后被同步调用。
 * 发起点赞的那个域不再决定别人的失效顺序——这是有意的：它们本就互不依赖。
 */
@Slf4j
@Component
public class LikeChangedListener {

    private final LikeCache likeCache;

    public LikeChangedListener(LikeCache likeCache) {
        this.likeCache = likeCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLikeChanged(LikeChangedEvent event) {
        log.debug("点赞事务已提交，更新点赞缓存: user={}, target={}, id={}, liked={}",
                event.userId(), event.target(), event.targetId(), event.liked());

        switch (event.target()) {
            case CONTENT -> {
                if (event.liked()) {
                    likeCache.likeContent(event.userId(), event.targetId());
                } else {
                    likeCache.unlikeContent(event.userId(), event.targetId());
                }
            }
            case COMMENT -> {
                if (event.liked()) {
                    likeCache.likeComment(event.userId(), event.targetId());
                } else {
                    likeCache.unlikeComment(event.userId(), event.targetId());
                }
            }
        }
    }
}
