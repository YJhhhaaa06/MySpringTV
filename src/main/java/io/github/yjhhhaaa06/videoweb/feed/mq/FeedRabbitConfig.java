package io.github.yjhhhaaa06.videoweb.feed.mq;

import io.github.yjhhhaaa06.videoweb.common.config.FeedProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明（承接 TV {@code mq.MqTopologyDeclarer}）——把 3 交换机 + 3 队列 + 3 绑定
 * 变成 {@link Declarables} bean，由 Boot 自动装配的 {@code RabbitAdmin} 在**连接建立时幂等声明**。
 *
 * <h2>为什么交 {@code Declarables} + {@code RabbitAdmin}（而不是手写 {@code channel.exchangeDeclare}）</h2>
 * TV 的 {@code MqConnectionManager} 每次建连后手工调 {@code MqTopologyDeclarer.declare(channel)}。
 * Spring AMQP 的原生等价物就是"声明式 bean + RabbitAdmin"：RabbitAdmin 实现 {@code ConnectionListener}，
 * 在**每条新连接**上重新声明全部 {@code Exchange}/{@code Queue}/{@code Binding}（幂等，
 * 同名同参重复声明在 broker 侧是 no-op）——恰好是 TV"每次建连声明一次"的框架版，且少了连接管理代码。
 *
 * <h2>逐条对齐 TV 声明</h2>
 * <table>
 *   <caption>TV {@code MqTopologyDeclarer} → 本类</caption>
 *   <tr><th>TV 调用</th><th>本类 bean</th></tr>
 *   <tr><td>{@code exchangeDeclare(PUSH, TOPIC, true)}</td><td>{@link TopicExchange} durable</td></tr>
 *   <tr><td>{@code exchangeDeclare(REBUILD, TOPIC, true)}</td><td>{@link TopicExchange} durable</td></tr>
 *   <tr><td>{@code exchangeDeclare(DLX, DIRECT, true)}</td><td>{@link DirectExchange} durable</td></tr>
 *   <tr><td>{@code queueDeclare(PUSH, durable, 非排他, 非自动删, deadLetterArgs())}</td>
 *       <td>{@link QueueBuilder#deadLetterExchange} + {@link QueueBuilder#deadLetterRoutingKey}</td></tr>
 *   <tr><td>{@code queueDeclare(REBUILD, …, deadLetterArgs())}</td><td>同上</td></tr>
 *   <tr><td>{@code queueDeclare(DLQ, …, dlqArgs(ttl))}（自身无 DLX 防环）</td>
 *       <td>{@link QueueBuilder#ttl(int)}，不设 dead-letter 参数</td></tr>
 *   <tr><td>三条 {@code queueBind}</td><td>三条 {@link Binding}</td></tr>
 * </table>
 *
 * <h2>⚠️ DLQ 的 TTL 是**队列声明参数**</h2>
 * broker 拒绝同名不同参的重复声明（{@code 406 PRECONDITION_FAILED}）——调整
 * {@code video.feed.dlq-ttl} 后必须**先删除既有 {@code feed.dlq} 队列**（TV 原注记，逐字保留）。
 */
@Configuration
public class FeedRabbitConfig {

    /**
     * 全部交换机 / 队列 / 绑定。{@code RabbitAdmin} 会在每条新连接上幂等声明本 bean 的全部元素。
     *
     * @param props feed 配置（当前只取 {@code dlq-ttl}——DLQ 的消息保留时长）
     */
    @Bean
    public Declarables feedTopology(FeedProperties props) {
        TopicExchange pushExchange = new TopicExchange(FeedTopology.EXCHANGE_PUSH, true, false);
        TopicExchange rebuildExchange = new TopicExchange(FeedTopology.EXCHANGE_REBUILD, true, false);
        DirectExchange dlx = new DirectExchange(FeedTopology.EXCHANGE_DLX, true, false);

        Queue pushQueue = QueueBuilder.durable(FeedTopology.QUEUE_PUSH)
                .deadLetterExchange(FeedTopology.EXCHANGE_DLX)
                .deadLetterRoutingKey(FeedTopology.RK_DLQ)
                .build();
        Queue rebuildQueue = QueueBuilder.durable(FeedTopology.QUEUE_REBUILD)
                .deadLetterExchange(FeedTopology.EXCHANGE_DLX)
                .deadLetterRoutingKey(FeedTopology.RK_DLQ)
                .build();
        // DLQ 自身不设死信参数（防环）；设消息 TTL（证据保留窗口有界，不无限堆积）。
        // ⚠️ TTL 是队列声明参数，改值须先删旧队列（见类注释）。
        Queue dlq = QueueBuilder.durable(FeedTopology.QUEUE_DLQ)
                .ttl((int) props.dlqTtl().toMillis())
                .build();

        return new Declarables(
                pushExchange, rebuildExchange, dlx,
                pushQueue, rebuildQueue, dlq,
                BindingBuilder.bind(pushQueue).to(pushExchange).with(FeedTopology.BIND_PUSH_ALL),
                BindingBuilder.bind(rebuildQueue).to(rebuildExchange).with(FeedTopology.BIND_REBUILD_ALL),
                BindingBuilder.bind(dlq).to(dlx).with(FeedTopology.RK_DLQ));
    }
}
