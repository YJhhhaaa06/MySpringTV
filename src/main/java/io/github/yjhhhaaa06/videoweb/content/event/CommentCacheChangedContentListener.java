package io.github.yjhhhaaa06.videoweb.content.event;

import io.github.yjhhhaaa06.videoweb.comment.event.CommentCacheChangedEvent;
import io.github.yjhhhaaa06.videoweb.content.cache.ContentCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 评论增删 ⇒ **失效内容 key**（S6-B2d）。
 *
 * <h2>为什么内容域要订阅评论域的事件</h2>
 * {@code content.comment_count} 是冗余计数，内容缓存里存了一份。评论一增一删，
 * 这个数就变了，内容 key 必须失效——否则列表页会一直显示旧评论数，直到 TTL 到期。
 *
 * <p>S5 的写法是**评论域去发内容域的事件**
 * （{@code CommentService} 里 {@code events.publishEvent(ContentCacheChangedEvent.invalidate(...))}）
 * ——于是评论域被迫 import 内容域的事件类型，{@code content⇄comment} 的编译期环更死一层。
 *
 * <p>现在反过来：**评论域只声明自己发生了什么**（{@code CommentCacheChangedEvent}），
 * 内容域订阅它并失效**自己的** key。
 *
 * <h2>为什么"任何 op 都要失效内容 key"</h2>
 * {@code CommentCacheChangedEvent} 的三种 op 分别对应"增/删主楼"与"增/删回复"——
 * 而**两者都会改变 {@code content.comment_count}**（楼中楼回复同样计入，见
 * {@code CommentService.addComment} 无条件调 {@code contentDao.updateCommentCount(contentId, 1)}）。
 * 故这里不需要按 op 分支，收到即失效。
 *
 * <h2>时序</h2>
 * 事件在评论写事务**内**发布，本方法**只在提交后**被调用；事务回滚 ⇒ 完全不调用。
 * 与内容域自己的 {@code ContentCacheChangedListener} 是并列的 AFTER_COMMIT 回调，互不依赖。
 */
@Slf4j
@Component
public class CommentCacheChangedContentListener {

    private final ContentCache contentCache;

    public CommentCacheChangedContentListener(ContentCache contentCache) {
        this.contentCache = contentCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommentCacheChanged(CommentCacheChangedEvent event) {
        log.debug("评论事务已提交，失效内容缓存: contentId={}, op={}", event.contentId(), event.op());
        // 纯 DEL + 空标记；读自愈会回源 DB 回填最新的 comment_count
        contentCache.invalidateContent(event.contentId());
    }
}
