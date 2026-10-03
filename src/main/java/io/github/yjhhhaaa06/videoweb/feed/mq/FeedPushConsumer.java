package io.github.yjhhhaaa06.videoweb.feed.mq;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.cache.JsonCodec;
import io.github.yjhhhaaa06.videoweb.feed.model.message.AuthorBackfillMessage;
import io.github.yjhhhaaa06.videoweb.feed.model.message.FeedPushMessage;
import io.github.yjhhhaaa06.videoweb.feed.service.FeedInboxWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 写扩散消费者（承接 TV {@code feed.service.FeedPushConsumer}）：接收 {@code feed.push.queue} 上的消息，
 * 按路由键分发——{@code feed.push.content}（内容发布事件）写各粉丝收件箱；
 * {@code feed.push.backfill}（降级补推任务）把作者最近 K 条内容补写进其现任粉丝收件箱。
 *
 * <h2>挂载方式：{@code @RabbitListener}（取代 TV 的 {@code Initializable} + 手工 {@code basicConsume}）</h2>
 * TV 由 {@code MqConsumerContainer} 在"连接可用"时手工起消费者，并处理"IoC 初始化顺序不定"；
 * Spring AMQP 的监听容器是 {@code SmartLifecycle}，随上下文启动、断线自动恢复、消费者自动重建
 * ⇒ 那套顺序处理与重连代码整体消失（《迁移参照系》§三：{@code mq/} 整体替换）。
 *
 * <h2>失败出口：交给容器（不吞异常）</h2>
 * 解析失败（空载荷 / 非法 JSON）与写扩散链的 DB 类失败一律**抛出**，由 Spring AMQP 的
 * {@code listener.simple.retry.*}（同线程退避、次数有界）先本地重试，**仍失败才**记 error 后
 * 转死信（{@code default-requeue-rejected=false} + 队列 DLX 参数）——与 TV
 * {@code MqConsumerContainer} 的口径一致。本类**不重复记栈**。
 *
 * <h2>通配绑定前向兼容</h2>
 * {@code feed.push.queue} 以 {@code feed.push.#} 通配绑定 ⇒ 未登记的路由键只记 debug 后跳过，
 * 不做误解析（也不投死信，避免未知类型在 DLQ 堆积）。
 */
@Slf4j
@Component
public class FeedPushConsumer {

    private final JsonCodec codec;
    private final FeedInboxWriter inboxWriter;

    public FeedPushConsumer(JsonCodec codec, FeedInboxWriter inboxWriter) {
        this.codec = codec;
        this.inboxWriter = inboxWriter;
    }

    /** 消费回调（由容器保证手动确认 / 失败重试 / 耗尽转死信）。 */
    @RabbitListener(queues = FeedTopology.QUEUE_PUSH)
    public void handle(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        if (FeedTopology.RK_PUSH_CONTENT.equals(routingKey)) {
            FeedPushMessage payload = decode(message.getBody(), FeedPushMessage.class, "写扩散消息载荷为空");
            inboxWriter.fanout(payload.contentId(), payload.authorId());
            return;
        }
        if (FeedTopology.RK_PUSH_BACKFILL.equals(routingKey)) {
            AuthorBackfillMessage payload = decode(message.getBody(), AuthorBackfillMessage.class, "降级补推消息载荷为空");
            inboxWriter.backfillAuthor(payload.authorId());
            return;
        }
        log.debug("写扩散收到非内容发布消息，跳过: routingKey={}", routingKey);
    }

    /**
     * 解码载荷：空载荷 / 非法 JSON ⇒ 抛 {@link IllegalArgumentException}（交容器重试→耗尽转死信，
     * 保留证据、不静默丢弃）。**不把 {@code CacheUnavailableException} 直接外抛**——那是"缓存不可用"
     * 的语义，此处是"消息不可用"，必须换类型以免误导诊断。
     */
    private <T> T decode(byte[] body, Class<T> type, String emptyPayloadMessage) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException(emptyPayloadMessage);
        }
        T payload;
        try {
            payload = codec.fromJson(new String(body, StandardCharsets.UTF_8), type);
        } catch (CacheUnavailableException e) {
            throw new IllegalArgumentException("消息载荷非法 JSON: " + e.getMessage(), e);
        }
        if (payload == null) {
            throw new IllegalArgumentException(emptyPayloadMessage);
        }
        return payload;
    }
}
