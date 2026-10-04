package io.github.yjhhhaaa06.videoweb.feed.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * feed 消息投递的**统一门面**（第三批 T5）：三类投递（写扩散 {@code feed.push.content} /
 * 降级补推 {@code feed.push.backfill} / 收件箱重建 {@code feed.rebuild.inbox}）**统一从这里出去**。
 *
 * <p>存在的理由（rule of three）：三个 Notifier 都需要同一套"异步 + 失败兜底"的组合，
 * 各自拼装必然漂移；本类是该组合的**唯一落点**，也是"投递管道长什么样"的唯一可读处。
 *
 * <h2>管道</h2>
 * <pre>
 *   Web 线程 / 去抖线程
 *     └─ {@link #deliver} ──────────────► 立即返回（不等 broker）
 *          └─ {@link FeedDeliveryDispatcher#submit} 单 worker + 有界队列（账 B8）
 *               └─ worker 线程 ─► {@link FeedPublisher#publish}（"绝不抛"，失败只降级）
 * </pre>
 *
 * <p><b>红线</b>：{@link #deliver} **任何情况下都不抛异常**（提交被拒也只记日志）。
 * 投递失败不影响发起它的业务（发布作品 / 关注 / 取关）的响应与语义。
 *
 * <p><b>投递点</b>：由订阅方 {@code feed/event/*FeedListener} 在 {@code AFTER_COMMIT} 阶段调用
 * ——"提交后副作用"由框架保证。
 */
@Slf4j
@Component
public class FeedDelivery {

    private final FeedDeliveryDispatcher dispatcher;
    private final FeedPublisher publisher;

    public FeedDelivery(FeedDeliveryDispatcher dispatcher, FeedPublisher publisher) {
        this.dispatcher = dispatcher;
        this.publisher = publisher;
    }

    /**
     * 投递一条 feed 消息（**非阻塞、绝不抛**）。
     *
     * @param exchange   目标交换机（{@link FeedTopology} 常量）
     * @param routingKey 路由键（{@link FeedTopology} 常量）
     * @param payload    载荷对象（JSON 序列化；record 即可）
     * @param desc       一句业务标识（进降级日志，便于定位丢的是哪条）
     */
    public void deliver(String exchange, String routingKey, Object payload, String desc) {
        dispatcher.submit(() -> publisher.publish(exchange, routingKey, payload, desc), desc);
    }
}
