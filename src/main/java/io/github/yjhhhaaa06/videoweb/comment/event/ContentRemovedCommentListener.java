package io.github.yjhhhaaa06.videoweb.comment.event;

import io.github.yjhhhaaa06.videoweb.comment.cache.CommentCache;
import io.github.yjhhhaaa06.videoweb.content.event.ContentCacheChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 内容被删/下架 ⇒ **整组失效评论两键组**（S6-B2d）。
 *
 * <h2>为什么评论域要订阅内容域的事件</h2>
 * 内容被软删时，它名下的评论会被**级联软删**（{@code ContentService.deleteContent} 内的
 * {@code commentDao.softDeleteByContentId}）。此刻评论两键组（roots LIST / replies HASH / count）
 * 全部作废，必须整组剔除——留着会被读路径当成"这些评论还在"。
 *
 * <p>S5 的写法是**内容域去发评论域的事件**（{@code CommentCacheChangedEvent.comments(contentId)}）。
 * 现在反过来：内容域只声明"这条内容没了"（{@code ContentCacheChangedEvent.REMOVE}），
 * 评论域订阅它并失效**自己的**缓存。
 *
 * <h2>⚠️ 这里**不能**改写成"在 AFTER_COMMIT 回调里转发事件"</h2>
 * {@code ContentCacheChangedListener} 的类注释记了这条纪律：AFTER_COMMIT 回调执行时事务已经结束，
 * 此时再 {@code publishEvent} 不会被其它 {@code AFTER_COMMIT} 监听器收到
 * （{@code fallbackExecution} 默认 false）⇒ **转发会被静默丢弃**。
 * 本类没有转发——它直接失效缓存。这正是"订阅同一事件"相比"转发"的优势。
 *
 * <h2>与本域 {@code CommentCacheChangedListener} 的关系</h2>
 * 二者职责不重叠：那个处理**评论自己**的增删（roots / 某主楼的 replies field），
 * 本类处理**内容消失**导致的整组作废。都标 {@code AFTER_COMMIT}，是并列回调。
 *
 * <h2>时序</h2>
 * 事件在内容删除事务**内**发布，本方法**只在提交后**被调用；事务回滚 ⇒ 完全不调用
 * （内容没删成，评论也不该被软删，缓存自然不该动）。
 */
@Slf4j
@Component
public class ContentRemovedCommentListener {

    private final CommentCache commentCache;

    public ContentRemovedCommentListener(CommentCache commentCache) {
        this.commentCache = commentCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentCacheChanged(ContentCacheChangedEvent event) {
        if (event.op() != ContentCacheChangedEvent.Op.REMOVE) {
            return;
        }
        log.debug("内容删除事务已提交，整组失效评论缓存: contentId={}", event.contentId());
        commentCache.invalidateComments(event.contentId());
    }
}
