package io.github.yjhhhaaa06.videoweb.content.event;

import io.github.yjhhhaaa06.videoweb.content.cache.ContentCache;
import io.github.yjhhhaaa06.videoweb.like.event.LikeChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 点赞变更 ⇒ **失效内容缓存**（S6-B2b 从 {@code like.event.LikeChangedListener} 拆出）。
 *
 * <h2>为什么要有这样一个"只为一行失效"的监听器</h2>
 * {@code content.like_count} 是冗余计数，内容缓存里也存了一份。点赞成功后必须让内容 key 失效，
 * 否则缓存里的旧点赞数会一直服务到 TTL 到期——**对外表现为"点赞了但数字不变"**。
 *
 * <p>这行失效原先写在点赞域的监听器里（点赞域直接 import 本域的 {@code ContentCache}）。
 * 拆出来的意义不是"少写一行"，而是**依赖方向**：
 * 现在由内容域订阅 {@code LikeChangedEvent}、失效**自己的**缓存，
 * 点赞域不必知道内容缓存的存在。详见 {@code like.event.LikeChangedListener} 的类注释。
 *
 * <h2>时序</h2>
 * 事件在点赞事务内发布，本方法**只在提交后**被调用；事务回滚 ⇒ 完全不调用。
 * 与 {@code LikeChangedListener}、{@code LikeChangedCommentListener} 是同一次提交后的**并列**回调，
 * 三者互不依赖，顺序无所谓。
 *
 * <p>⚠️ <b>用 {@code event.targetId()}（内容 id），不要错写成 {@code userId}</b>——
 * 这是原实现就标了警告的一处易错点，拆分时原样保留。
 */
@Slf4j
@Component
public class LikeChangedContentListener {

    private final ContentCache contentCache;

    public LikeChangedContentListener(ContentCache contentCache) {
        this.contentCache = contentCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLikeChanged(LikeChangedEvent event) {
        if (event.target() != LikeChangedEvent.Target.CONTENT) {
            return;
        }
        log.debug("点赞事务已提交，失效内容缓存: contentId={}, liked={}", event.targetId(), event.liked());
        // 失效内容 key，读自愈回填 DB 最新 like_count
        contentCache.notifyLikeCountChanged(event.targetId());
    }
}
