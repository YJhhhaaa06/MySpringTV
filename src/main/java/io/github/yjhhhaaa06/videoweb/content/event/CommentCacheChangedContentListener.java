package io.github.yjhhhaaa06.videoweb.content.event;

import io.github.yjhhhaaa06.videoweb.comment.event.CommentCacheChangedEvent;
import io.github.yjhhhaaa06.videoweb.content.cache.ContentCountCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 评论增删 ⇒ **失效内容评论数**（T43 改造；原 S6-B2d 的"失效整份内容缓存"已废弃）。
 *
 * <h2>为什么还由内容域订阅评论域的事件</h2>
 * {@code content.comment_count} 是内容表自己的列，评论域在同事务里 ± 它
 * （{@code CommentService.addComment} / 删评论 → {@code contentDao.updateCommentCount}）。
 * 内容域要做的只剩一件事：让 {@code content:commentCount:{id}} 别再服务旧值。
 *
 * <h2>★ T43：从这里删掉的是"失效 content:{id}"</h2>
 * 旧实现收到任何 op 都调 {@code contentCache.invalidateContent(contentId)}——因为
 * {@code commentCount} 当时**内联在内容 JSON 里**。代价是：发一条评论就整份内容缓存陪葬
 * （标题、作者名、媒体 URL 全要重新 JOIN + 装配）。T43 把计数搬进独立 key 后，
 * 这里只 DEL 那个**标量**，内容载荷原样保留。
 *
 * <p>★ 为什么是 <b>DEL 而不是 INCR</b>：事件只说明"哪条内容变了"，不带可靠 delta ——
 * 删主楼会**级联软删**它的回复，真实变化量由 DB 决定。DEL + 读自愈（回源
 * {@code content.comment_count} 单列）是幂等的；增量会漂移。
 *
 * <h2>时序</h2>
 * 事件在评论写事务**内**发布，本方法**只在提交后**被调用；事务回滚 ⇒ 完全不调用。
 * 与内容域自己的 {@code ContentCacheChangedListener} 是并列的 AFTER_COMMIT 回调，互不依赖。
 *
 * <p>⚠️ 三种 op（主楼增删 / 回复增删）**都会改变 {@code content.comment_count}**
 * （楼中楼同样计入）⇒ 不需要按 op 分支，收到即失效。
 */
@Slf4j
@Component
public class CommentCacheChangedContentListener {

    private final ContentCountCache contentCountCache;

    public CommentCacheChangedContentListener(ContentCountCache contentCountCache) {
        this.contentCountCache = contentCountCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommentCacheChanged(CommentCacheChangedEvent event) {
        log.debug("评论事务已提交，失效内容评论数缓存: contentId={}, op={}", event.contentId(), event.op());
        contentCountCache.invalidateCommentCount(event.contentId());
    }
}
