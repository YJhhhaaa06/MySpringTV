package io.github.yjhhhaaa06.videoweb.feed.mq;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.cache.JsonCodec;
import io.github.yjhhhaaa06.videoweb.feed.model.message.InboxRebuildMessage;
import io.github.yjhhhaaa06.videoweb.feed.service.FeedRebuildService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 收件箱重建消费者（承接 TV {@code feed.service.FeedRebuildConsumer}）：接收 {@code feed.rebuild.queue}
 * 上的重建指令，驱动 {@code FeedRebuildService.rebuildInbox(long)} 执行**窗口重建**
 * （单事务整窗替换 {@code feed_inbox} 窗口 + 写 {@code feed_inbox_sync}，随后只失效读缓存）。
 *
 * <h2>失败出口（与 TV 裁决一致）</h2>
 * 本类**只对"载荷不可用"抛异常**（空载荷 / 非法 JSON）——交容器先本地有限重试、仍失败才转死信。
 * 重建过程自身的 Redis / DB 失败**仍在 {@code FeedRebuildService} 内降级吞掉**（ACK）——
 * 其缺口已有读态闸门（未同步 ⇒ 回退纯拉）与下次关注 / 取关重建兜底，故不会走到死信出口。
 */
@Slf4j
@Component
public class FeedRebuildConsumer {

    private final JsonCodec codec;
    private final FeedRebuildService rebuildService;

    public FeedRebuildConsumer(JsonCodec codec, FeedRebuildService rebuildService) {
        this.codec = codec;
        this.rebuildService = rebuildService;
    }

    /** 消费回调（由容器保证确认 / 重试 / 耗尽转死信）。 */
    @RabbitListener(queues = FeedTopology.QUEUE_REBUILD)
    public void handle(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        if (!FeedTopology.RK_REBUILD_INBOX.equals(routingKey)) {
            log.debug("收件箱重建收到非收件箱重建消息，跳过: routingKey={}", routingKey);
            return;
        }
        byte[] body = message.getBody();
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("收件箱重建消息载荷为空");
        }
        InboxRebuildMessage payload;
        try {
            payload = codec.fromJson(new String(body, StandardCharsets.UTF_8), InboxRebuildMessage.class);
        } catch (CacheUnavailableException e) {
            throw new IllegalArgumentException("收件箱重建消息非法 JSON: " + e.getMessage(), e);
        }
        if (payload == null) {
            throw new IllegalArgumentException("收件箱重建消息载荷为空");
        }
        rebuildService.rebuildInbox(payload.userId());
    }
}
