package io.github.yjhhhaaa06.videoweb.comment.event;

import io.github.yjhhhaaa06.videoweb.comment.cache.CommentCache;
import io.github.yjhhhaaa06.videoweb.like.event.LikeChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 点赞变更 ⇒ **失效评论树缓存**（S6-B2b 从 {@code like.event.LikeChangedListener} 拆出）。
 *
 * <h2>为什么要有这样一个"只为一行失效"的监听器</h2>
 * {@code comment.like_count} 是冗余计数，评论树缓存（roots LIST + replies HASH 两键组）里也存了一份。
 * 点赞成功后必须让**该评论所属主楼**的 replies field 失效，否则缓存里的旧点赞数会一直服务到
 * TTL 到期——对外表现为"评论点赞了但数字不变"。
 *
 * <p>它与内容侧的 {@code content.event.LikeChangedContentListener} 是同构的拆分，理由见
 * {@code like.event.LikeChangedListener} 的类注释：**由评论域订阅事件、失效自己的缓存**，
 * 而不是让点赞域伸手进来。
 *
 * <h2>为什么是"定向失效主楼的 replies field"而不是删评论 key</h2>
 * 评论缓存的键结构是"roots LIST（该内容的主楼 id 序）+ replies HASH（每个主楼的回复树）"。
 * 一条评论被点赞，只影响它所属主楼那一格；定位主楼由上溯循环完成
 * （{@code CommentCache.locateCommentRef}），故这里是**定向**失效而非整内容清空。
 * 这与 S5 的原实现逐字一致——拆分是**搬运**，不是改写。
 *
 * <h2>时序</h2>
 * 事件在点赞事务内发布，本方法**只在提交后**被调用；事务回滚 ⇒ 完全不调用。
 */
@Slf4j
@Component
public class LikeChangedCommentListener {

    private final CommentCache commentCache;

    public LikeChangedCommentListener(CommentCache commentCache) {
        this.commentCache = commentCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLikeChanged(LikeChangedEvent event) {
        if (event.target() != LikeChangedEvent.Target.COMMENT) {
            return;
        }
        log.debug("点赞事务已提交，失效评论树缓存: commentId={}, liked={}", event.targetId(), event.liked());
        // 定位评论所属主楼并定向失效其 replies field（懒载刷新 like_count）
        commentCache.notifyCommentLikeChanged(event.targetId());
    }
}
