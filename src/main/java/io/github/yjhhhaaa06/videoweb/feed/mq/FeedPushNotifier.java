package io.github.yjhhhaaa06.videoweb.feed.mq;

import io.github.yjhhhaaa06.videoweb.feed.model.message.FeedPushMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 写扩散投递封装（承接 TV {@code feed.service.FeedPushNotifier}）：内容发布 → 投递 push 消息，
 * 交由消费者写各粉丝收件箱。
 *
 * <p><b>红线</b>：本类**任何情况下都不抛异常**（委托 {@link FeedPublisher}，其"绝不抛"契约见类注释）
 * ——投递失败只降级（消息缺失至下次重建），不影响发布接口的响应与语义。
 *
 * <p><b>投递点</b>：由订阅方 {@code feed/event/ContentPublishedFeedListener} 在
 * {@code ContentPublishedEvent} 的 {@code AFTER_COMMIT} 阶段调用——"提交后副作用"由框架保证，
 * 不再是 TV 的"写在事务 lambda 之后 + 注释纪律"。
 */
@Slf4j
@Component
public class FeedPushNotifier {

    private final FeedPublisher publisher;

    public FeedPushNotifier(FeedPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 投递"内容已发布"事件（内容 id + 作者 id）。
     *
     * <p>幂等性说明：MQ 不保证只投一次（客户端 automatic recovery 可能重发）；
     * 接收侧收件箱写入用 {@code INSERT IGNORE}，天然幂等，故重复投递无副作用。
     *
     * @param contentId 新内容 id（收件箱成员 / ZSet score）
     * @param authorId  作者 id（接收侧据此取粉丝列表）
     */
    public void publishContentPublished(long contentId, long authorId) {
        publisher.publish(FeedTopology.EXCHANGE_PUSH, FeedTopology.RK_PUSH_CONTENT,
                new FeedPushMessage(contentId, authorId), "push contentId=" + contentId);
    }
}
