package io.github.yjhhhaaa06.videoweb.feed.mq;

import io.github.yjhhhaaa06.videoweb.feed.model.message.InboxRebuildMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 收件箱重建投递封装（承接 TV {@code follow.service.InboxRebuildNotifier}）：关注 / 取关 →
 * 投递 rebuild 消息，令**自己的**收件箱失效并重建。
 *
 * <p><b>语义</b>：关注（新博主的既有内容要进来）与取关（该博主的内容要出去）都会让本人收件箱的
 * "关注者内容快照"失效 ⇒ 投一条 {@link InboxRebuildMessage}，由 {@code feed.rebuild.queue} 的消费者
 * 执行**窗口重建**（单事务整窗替换 {@code feed_inbox} + 写 {@code feed_inbox_sync}，随后失效读缓存）。
 *
 * <p><b>红线</b>：任何情况下都不抛异常（委托 {@link FeedPublisher}；投递失败只降级——该次重建缺失，
 * 由下次关注 / 取关或读侧"未同步 ⇒ 回退纯拉"兜底），关注 / 取关接口的响应与语义一概不变。
 *
 * <h2>去抖（承接 TV {@code InboxRebuildDebouncer}）</h2>
 * 本方法**不直接投递**，而是先登记到 {@link InboxRebuildDebouncer}（per-user 尾沿合并，窗口 =
 * {@code video.feed.rebuild-debounce}）；窗口到期后由**去抖调度线程**投递。
 * <b>顺带的收益</b>：去抖窗口 &gt; 0 时，投递天然发生在后台线程而非 Web 线程——TV 为此专门建的
 * 异步投递线程池（B8 裁剪）在重建这条路径上被去抖器替代。
 *
 * <p><b>投递点</b>：{@code feed/event/FollowChangedFeedListener} 在 {@code AFTER_COMMIT} 阶段调用。
 */
@Slf4j
@Component
public class InboxRebuildNotifier {

    private final FeedPublisher publisher;
    private final InboxRebuildDebouncer debouncer;

    public InboxRebuildNotifier(FeedPublisher publisher, InboxRebuildDebouncer debouncer) {
        this.publisher = publisher;
        this.debouncer = debouncer;
    }

    /**
     * 投递"收件箱需要重建"事件（只带 userId）。
     *
     * @param userId 收件箱归属者（= 关注 / 取关的**发起方**，不是被关注的博主）
     */
    public void publishInboxRebuild(long userId) {
        debouncer.schedule(userId, () -> publisher.publish(
                FeedTopology.EXCHANGE_REBUILD, FeedTopology.RK_REBUILD_INBOX,
                new InboxRebuildMessage(userId), "rebuild userId=" + userId));
    }
}
