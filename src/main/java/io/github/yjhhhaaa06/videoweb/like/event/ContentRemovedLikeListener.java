package io.github.yjhhhaaa06.videoweb.like.event;

import io.github.yjhhhaaa06.videoweb.content.event.ContentCacheChangedEvent;
import io.github.yjhhhaaa06.videoweb.like.service.LikeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 内容被删/下架 ⇒ **失效该内容的点赞计数缓存**（S6-B2c 的承接方）。
 *
 * <h2>为什么点赞域要订阅内容域的事件</h2>
 * {@code content:likeCount:{contentId}} 这个 key 属于**点赞域的缓存**，但它失效的**触发条件**
 * 是内容域的一次业务事实（内容被软删/下架）。谁该负责这行失效？
 *
 * <p>S5 的写法是内容域直接调 {@code LikeService.deleteContentLike(contentId)}——于是
 * {@code ContentCacheChangedListener} 依赖了点赞域。拆出来之后，**由点赞域订阅事件、
 * 失效自己的 key**，内容域不必知道"点赞还有个计数缓存"。
 *
 * <h2>为什么只失效计数、成员 key 怎么办（TV 的 T4 结论，原样保留）</h2>
 * 点赞的成员 key 是**用户维度**的（{@code user:likeSet:{userId}}），无法廉价反查
 * "谁点过这条内容"来逐个 {@code SREM}。残留的成员指向已删内容，而内容 id 不复用
 * ⇒ **永不外显**，故无需清理。这与原实现逐字一致——本次只是搬家，不是改语义。
 *
 * <h2>时序</h2>
 * 事件在内容删除事务**内**发布，本方法**只在提交后**被调用；事务回滚 ⇒ 完全不调用
 * （内容没删成，点赞缓存自然不该动）。
 *
 * <p>与 {@code content.event.ContentCacheChangedListener} 是同一次提交后的**并列**回调，
 * 二者互不依赖，顺序无所谓。
 */
@Slf4j
@Component
public class ContentRemovedLikeListener {

    private final LikeService likeService;

    public ContentRemovedLikeListener(LikeService likeService) {
        this.likeService = likeService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentCacheChanged(ContentCacheChangedEvent event) {
        if (event.op() != ContentCacheChangedEvent.Op.REMOVE) {
            return;
        }
        log.debug("内容删除事务已提交，失效点赞计数缓存: contentId={}", event.contentId());
        likeService.deleteContentLike(event.contentId());
    }
}
