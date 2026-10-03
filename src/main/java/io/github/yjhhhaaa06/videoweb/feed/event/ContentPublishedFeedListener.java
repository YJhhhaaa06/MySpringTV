package io.github.yjhhhaaa06.videoweb.feed.event;

import io.github.yjhhhaaa06.videoweb.content.event.ContentPublishedEvent;
import io.github.yjhhhaaa06.videoweb.feed.mq.FeedPushNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 「内容已发布」→ 写扩散投递（S9）：订阅 content 域的 {@link ContentPublishedEvent}，
 * 在**提交后**投递 {@code feed.push.content} 消息，令消费者写各粉丝收件箱。
 *
 * <h2>为什么这个监听器在 feed 域、而事件在 content 域</h2>
 * 按 {@code A-3 / A-4} 范式：**每个域只声明"自己发生了什么"**（content 发"内容已发布"），
 * **需要响应的人自己订阅**（feed 想要写扩散 ⇒ 在 {@code feed.event} 订阅）。
 * 这样 content **不依赖 feed**（消掉 TV 的 {@code content ⇄ feed} 环），且"谁依赖谁"在包结构上可见。
 *
 * <p>落在 {@code feed.event} 包是硬约束：ArchUnit 规则 3 要求跨域事件只能由自己的 {@code event} 包消费。
 *
 * <h2>为什么用 {@code AFTER_COMMIT}</h2>
 * 投递必须发生在**内容行提交之后**——否则事务回滚会留下一条指向不存在内容的收件箱消息。
 * {@code fallbackExecution} 保持默认 {@code false}：{@code addVideo}/{@code addPost} 一律在
 * {@code @Transactional} 内发布事件，没有"无事务也要投递"的场景。
 *
 * <p>投递失败只降级（{@link FeedPushNotifier} 契约"绝不抛"），不影响发布接口响应。
 */
@Slf4j
@Component
public class ContentPublishedFeedListener {

    private final FeedPushNotifier feedPushNotifier;

    public ContentPublishedFeedListener(FeedPushNotifier feedPushNotifier) {
        this.feedPushNotifier = feedPushNotifier;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentPublished(ContentPublishedEvent event) {
        feedPushNotifier.publishContentPublished(event.contentId(), event.authorId());
    }
}
