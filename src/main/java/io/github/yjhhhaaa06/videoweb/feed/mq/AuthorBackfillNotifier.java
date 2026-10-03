package io.github.yjhhhaaa06.videoweb.feed.mq;

import io.github.yjhhhaaa06.videoweb.feed.model.message.AuthorBackfillMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 降级补推投递封装（承接 TV {@code follow.service.AuthorBackfillNotifier}）：作者由大V降为普通
 * （{@code AutoBigVStateService} 的 {@code DOWNGRADED} edge）⇒ 投递 {@link AuthorBackfillMessage}，
 * 令消费者把该作者最近 K 条内容补写进现任粉丝收件箱。
 *
 * <p><b>为什么需要补推</b>：大V期间其内容**不进粉丝收件箱**（由"大V发件箱"读时拉）；
 * 降级后若只等"下次关注 / 取关触发的重建"，存量内容会出现可见性断档——补推把这段断档消除，
 * 且**不依赖**该粉丝的下一次关注 / 取关。
 *
 * <p><b>投递点</b>：{@code feed/event/FollowChangedFeedListener} 在 {@code AFTER_COMMIT} 阶段、
 * 且**仅当** {@code transition == DOWNGRADED} 时调用。edge 天然唯一 ⇒ **无需去抖**。
 *
 * <p><b>红线</b>：任何情况下都不抛异常（委托 {@link FeedPublisher}）。
 */
@Slf4j
@Component
public class AuthorBackfillNotifier {

    private final FeedPublisher publisher;

    public AuthorBackfillNotifier(FeedPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 投递"作者降级需补推"事件（只带 authorId）。
     *
     * <p>幂等性说明：接收侧补推是 {@code INSERT IGNORE} 追增（同 contentId 重复行被唯一键静默忽略）
     * ⇒ 重复投递无副作用。
     *
     * @param authorId 降级的作者（= 关注 / 取关的**被关注者**，不是操作者）
     */
    public void publishAuthorBackfill(long authorId) {
        publisher.publish(FeedTopology.EXCHANGE_PUSH, FeedTopology.RK_PUSH_BACKFILL,
                new AuthorBackfillMessage(authorId), "backfill authorId=" + authorId);
    }
}
