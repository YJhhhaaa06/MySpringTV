package io.github.yjhhhaaa06.videoweb.feed.mq;

import io.github.yjhhhaaa06.videoweb.common.config.FeedProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DLQ 消息 TTL 的**取值契约**（《遗留台账》**B13 → D31**）。
 *
 * <h2>为什么单独立一个类</h2>
 * 它测的不是"配置绑没绑上"（那归 {@code config/FeedPropertiesBindingTests}），
 * 而是"**绑上了之后，落到队列声明参数上的那个值对不对**"——
 * {@code video.feed.dlq-ttl} 的唯一消费点就是 {@code FeedRabbitConfig.feedTopology}。
 *
 * <h2>★ 这条用例存在的理由：溢出是**静默**的</h2>
 * 原实现 {@code .ttl((int) props.dlqTtl().toMillis())}：{@code x-message-ttl} 是 32 位整型，
 * 配到 {@code > Integer.MAX_VALUE} 毫秒（约 **24.86 天**）时会**静默溢出成负 TTL**
 * ⇒ 队列声明参数非法，症状是 broker {@code 406 PRECONDITION_FAILED} 或消息立即过期，
 * 而**报错点离"配置写错"这个病因很远**（这正是 B13 登记的痛点）。
 * 改为 {@link Math#toIntExact(long)} 后，超界在 bean 创建期即抛
 * {@link ArithmeticException} ⇒ **启动即拒**。
 *
 * <h2>为什么不需要容器</h2>
 * {@code Declarables} 是**纯声明对象**（broker 声明由 {@code RabbitAdmin} 在连上时做），
 * 故本类直接断言 {@code Queue.getArguments()}，秒级、无 I/O。
 */
class FeedDlqTtlTests {

    /** 与 {@link FeedPropertiesBindingTests} 同一套构造参数，只把 dlqTtl 换掉。 */
    private static final Duration TTL_1M = Duration.ofMinutes(1);

    @Test
    @DisplayName("★合法 TTL 真的落到队列参数 x-message-ttl（默认 7d = 604800000）")
    void 合法TTL落到队列声明参数() {
        Queue dlq = dlqOf(propsWith(Duration.ofDays(7)));
        assertThat(dlq.getArguments())
                .as("x-message-ttl 必须是毫秒整型（RabbitMQ 队列声明参数）")
                .containsEntry("x-message-ttl", 604_800_000);
    }

    @Test
    @DisplayName("★超范围 TTL 快速失败：> Integer.MAX_VALUE 毫秒（约 24.86 天）抛异常，不静默溢出为负")
    void 超范围TTL快速失败() {
        // 反向验证：把 Math.toIntExact 换回 (int) 强转，本条立刻变红——
        // 且红的是"断言拿到负 TTL / 没抛异常"，而不是"找不到队列"。
        assertThatThrownBy(() -> new FeedRabbitConfig().feedTopology(propsWith(Duration.ofDays(25))))
                .as("24.86 天以上必须炸在声明处，而不是变成负 TTL 交给 broker 报 406")
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("边界：Integer.MAX_VALUE 毫秒（约 24.86 天）仍合法，紧邻的 +1 毫秒即非法")
    void 边界值仍合法且紧邻越界即拒() {
        assertThat(dlqOf(propsWith(Duration.ofMillis(Integer.MAX_VALUE))).getArguments())
                .as("上界本身合法（含）")
                .containsEntry("x-message-ttl", Integer.MAX_VALUE);

        assertThatThrownBy(() -> new FeedRabbitConfig()
                .feedTopology(propsWith(Duration.ofMillis(Integer.MAX_VALUE + 1L))))
                .as("上界 +1 毫秒即溢出 ⇒ 必须拒绝，而不是静默回绕")
                .isInstanceOf(ArithmeticException.class);
    }

    // ==================== 辅助 ====================

    private static FeedProperties propsWith(Duration dlqTtl) {
        return new FeedProperties(TTL_1M, 20, 200, 300, 20, TTL_1M, 200, 50, 200,
                Duration.ofSeconds(1), dlqTtl);
    }

    private static Queue dlqOf(FeedProperties props) {
        List<Queue> queues = new FeedRabbitConfig().feedTopology(props).getDeclarablesByType(Queue.class);
        return queues.stream()
                .filter(q -> FeedTopology.QUEUE_DLQ.equals(q.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("拓扑里找不到队列 " + FeedTopology.QUEUE_DLQ));
    }
}
