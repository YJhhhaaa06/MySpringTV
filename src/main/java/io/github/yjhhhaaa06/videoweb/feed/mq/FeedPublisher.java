package io.github.yjhhhaaa06.videoweb.feed.mq;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.cache.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * MQ 发布载体的薄封装（承接 TV {@code mq.MqPublisher} 的**对外契约**，骨架交 Spring AMQP）。
 *
 * <h2>★ 契约：**绝不抛异常**（与 TV {@code MqPublisher} / Notifier 同族）</h2>
 * 三类投递（写扩散 / 重建 / 降级补推）失败一律**只降级**——投递失败不得影响发起它的业务
 * （发布作品 / 关注 / 取关）的响应与语义。失败面：
 * <ul>
 *   <li><b>序列化失败</b>（{@link CacheUnavailableException}，来自 {@link JsonCodec}）：
 *       该链唯一捕获点 → WARNING <b>持栈</b> → 返回 {@link DeliveryOutcome#UNSENDABLE}；</li>
 *   <li><b>broker 不可达</b>（{@link AmqpException}）：连接级故障 → WARNING 结论行（不持栈——
 *       {@code RabbitTemplate} 内部已记一次异常；一条故障只留一条诊断）→ 返回
 *       {@link DeliveryOutcome#UNAVAILABLE}；</li>
 *   <li>其余运行时异常 → WARNING 兜底 → 也按 {@link DeliveryOutcome#UNAVAILABLE} 处理
 *       （状态不明 ⇒ 交补偿缓冲按"至少一次"重放，靠消费侧幂等对冲）。</li>
 * </ul>
 *
 * <p><b>为什么返回值是三态而不是 boolean（第三批 T5 / 账 B9）</b>：补偿缓冲必须区分
 * "重放有意义"与"重放必然同样失败"——二态会把不可序列化的载荷也塞进缓冲，永久占位。
 * 三态语义见 {@link DeliveryOutcome}。</p>
 *
 * <h2>与 TV {@code MqPublisher} 的两处**有意差异**（《决策留痕表》C-7，保真度档 A）</h2>
 * <ol>
 *   <li><b>不等 publisher-confirm</b>：TV 单 channel + confirmLock + {@code waitForConfirms(5s)}，
 *       并为之建了异步投递线程池（{@code MqDeliveryDispatcher}）把等待挪离 Web 线程（治 N8）。
 *       本实现**不启用 confirm** ⇒ {@code send} 写完 socket 即返回。⚠️ **但"天然不阻塞"只在 broker
 *       可达时成立**：broker 不可达时会等一次连接超时（{@code spring.rabbitmq.connection-timeout}，
 *       dev 2s）⇒ **该代价已由第三批 T5（账 B8）用 {@code FeedDeliveryDispatcher} 收掉**
 *       （publish 现在由后台单 worker 执行，Web 线程不再等这笔超时）。</li>
 *   <li><b>无应用层补偿缓冲</b>：TV 的 {@code MqDeliveryBuffer} 在"连接确定不可用"时把消息暂存
 *       内存、恢复后重放。本实现由第三批 T5（账 B9）以 {@link FeedDeliveryBuffer} 补回——
 *       判据即本方法的三态返回值；消费侧幂等（{@code INSERT IGNORE} / 整窗替换）使"至少一次"
 *       重放零副作用。该机制 TV 自己标注为"内存态、重启即丢"，本仓同口径（残余如实登记）。</li>
 * </ol>
 */
@Slf4j
@Component
public class FeedPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final JsonCodec codec;

    public FeedPublisher(RabbitTemplate rabbitTemplate, JsonCodec codec) {
        this.rabbitTemplate = rabbitTemplate;
        this.codec = codec;
    }

    /**
     * 发布一条 JSON 消息（**绝不抛**）。
     *
     * @param exchange   目标交换机（{@link FeedTopology} 常量）
     * @param routingKey 路由键（{@link FeedTopology} 常量）
     * @param payload    载荷对象（JSON 序列化；record 即可）
     * @param desc       一句业务标识（进降级日志，便于定位丢的是哪条，如 {@code push contentId=42}）
     * @return {@link DeliveryOutcome#SENT} = 已交给客户端发送；{@code UNAVAILABLE} = broker 不可达
     *         （可入补偿缓冲重放）；{@code UNSENDABLE} = 载荷不可序列化（重放无意义，已降级记录）
     */
    public DeliveryOutcome publish(String exchange, String routingKey, Object payload, String desc) {
        byte[] body;
        try {
            body = codec.toJson(payload).getBytes(StandardCharsets.UTF_8);
        } catch (CacheUnavailableException e) {
            // 该链唯一捕获点 → 持栈（§3.1 附加纪律 2）
            log.warn("MQ 投递跳过（载荷序列化失败，不影响业务）: {}", desc, e);
            return DeliveryOutcome.UNSENDABLE;
        }
        try {
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setContentEncoding(StandardCharsets.UTF_8.name());
            rabbitTemplate.send(exchange, routingKey, new Message(body, props));
            return DeliveryOutcome.SENT;
        } catch (AmqpException e) {
            // 连接级故障：结论行不持栈（一条故障只留一条诊断）；业务照常返回
            log.warn("MQ 投递失败（broker 不可达，已降级，不影响业务）: {}", desc);
            return DeliveryOutcome.UNAVAILABLE;
        } catch (RuntimeException e) {
            log.warn("MQ 投递异常（已兜底，不影响业务）: {}", desc, e);
            return DeliveryOutcome.UNAVAILABLE;
        }
    }
}
