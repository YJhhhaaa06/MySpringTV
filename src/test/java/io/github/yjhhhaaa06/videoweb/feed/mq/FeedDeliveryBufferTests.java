package io.github.yjhhhaaa06.videoweb.feed.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MQ 内存补偿缓冲（第三批 T5 / 账 B9）的故障注入测试——用 mock 的 {@link FeedPublisher} 注入
 * "broker 不可达 / 恢复"，无需 Spring 上下文、也**不真停容器**
 * （测试 broker 是 {@code Containers} 的进程级单例，停机波及其它测试类——与
 * {@code RedisCircuitBreakerTests} 同一处置）。
 *
 * <h2>这条账的代价（为什么不是纯优化）</h2>
 * 《遗留台账》B9：MQ 不可用窗口内投递的消息**永久丢失**——写扩散丢了，粉丝的时间线要等到
 * 自己下一次关注 / 取关才重建才看得见；重建丢了，收件箱就一直停在旧窗口。本类证明：
 * 窗口内消息**被暂存**，broker 恢复后**被重放**。
 *
 * <h2>★ 两条判据，各对应一个必须成立的机制</h2>
 * <ul>
 *   <li><b>暂存</b>：{@code UNAVAILABLE} ⇒ 入有界队列（且**后续消息零 I/O 暂存**，
 *       不去撞连接超时——否则后台单 worker 每 2s 才吞一条，队列先被灌满）；</li>
 *   <li><b>重放</b>：探测（{@code probe()}，生产里由定时器驱动）把队列里的消息重新投出去。</li>
 * </ul>
 *
 * <h2>★ 反向验证（注入 → 观察 → 还原，均已实测）</h2>
 * <ul>
 *   <li>RV-B9-a：删掉 {@code publish} 里 {@code UNAVAILABLE} 分支的 {@code buffer(...)} 调用
 *       ⇒ {@link #不可用时暂存_恢复后重放()} 变红（pending 恒为 0）；</li>
 *   <li>RV-B9-b：把 {@code probe()} 改成直接 return（不调用 {@code flush}）
 *       ⇒ 同上一条用例的"恢复后重放"段变红（pending 恒为 2）；</li>
 *   <li>RV-B9-c：删掉 {@code allowAttempt()} 判定（不可达窗口内仍去撞 broker）
 *       ⇒ {@link #不可达窗口内后续消息零IO暂存()} 变红。</li>
 * </ul>
 */
@Tag("resilience")
class FeedDeliveryBufferTests {

    private static final String EX = FeedTopology.EXCHANGE_PUSH;
    private static final String RK = FeedTopology.RK_PUSH_CONTENT;

    /** 不可达窗口取"很大"⇒ 一次失败后窗口内不再触达 broker（用于钉"零 I/O 暂存"）。 */
    private static final long LONG_WINDOW = 60_000L;

    @Test
    @DisplayName("★不可用时暂存 → 恢复后探测重放（本账的核心判据）")
    void 不可用时暂存_恢复后重放() {
        FeedPublisher publisher = mock(FeedPublisher.class);
        when(publisher.publish(anyString(), anyString(), any(), anyString()))
                .thenReturn(DeliveryOutcome.UNAVAILABLE);
        FeedDeliveryBuffer buffer = new FeedDeliveryBuffer(publisher, 16, LONG_WINDOW);

        assertThat(buffer.publish(EX, RK, 1L, "d1")).as("不可用时不算投出").isFalse();
        assertThat(buffer.pendingCount()).isEqualTo(1);
        assertThat(buffer.isDown()).isTrue();

        // 窗口内第二条：零 I/O 暂存（不触达 publisher）
        assertThat(buffer.publish(EX, RK, 2L, "d2")).isFalse();
        assertThat(buffer.pendingCount()).isEqualTo(2);
        verify(publisher, times(1)).publish(anyString(), anyString(), any(), anyString());

        // broker 恢复 → 一次探测把积压全部重放
        when(publisher.publish(anyString(), anyString(), any(), anyString()))
                .thenReturn(DeliveryOutcome.SENT);
        buffer.probe();

        assertThat(buffer.pendingCount()).as("恢复后必须清空积压").isZero();
        assertThat(buffer.isDown()).isFalse();
        // 调用次数 = 首次投递 1 次（失败）+ 探测重放 2 次（两条各一次）
        verify(publisher, times(3)).publish(anyString(), anyString(), any(), anyString());
        buffer.destroy();
    }

    @Test
    @DisplayName("★重放中 broker 仍未恢复 ⇒ 保留剩余、不空转（下轮探测继续）")
    void 仍未恢复则保留剩余() {
        FeedPublisher publisher = mock(FeedPublisher.class);
        when(publisher.publish(anyString(), anyString(), any(), anyString()))
                .thenReturn(DeliveryOutcome.UNAVAILABLE);
        FeedDeliveryBuffer buffer = new FeedDeliveryBuffer(publisher, 16, LONG_WINDOW);

        buffer.publish(EX, RK, 1L, "d1");
        buffer.publish(EX, RK, 2L, "d2");
        int before = buffer.pendingCount();

        buffer.probe();

        assertThat(buffer.pendingCount()).as("仍不可达 ⇒ 一条都不能丢").isEqualTo(before);
        buffer.destroy();
    }

    @Test
    @DisplayName("★不可达窗口内后续消息零 I/O 暂存（不撞连接超时）")
    void 不可达窗口内后续消息零IO暂存() {
        FeedPublisher publisher = mock(FeedPublisher.class);
        when(publisher.publish(anyString(), anyString(), any(), anyString()))
                .thenReturn(DeliveryOutcome.UNAVAILABLE);
        FeedDeliveryBuffer buffer = new FeedDeliveryBuffer(publisher, 32, LONG_WINDOW);

        for (int i = 0; i < 10; i++) {
            buffer.publish(EX, RK, (long) i, "d" + i);
        }

        assertThat(buffer.pendingCount()).isEqualTo(10);
        verify(publisher, times(1))
                .publish(anyString(), anyString(), any(), anyString());   // 只第一次真的撞了 broker
        buffer.destroy();
    }

    @Test
    @DisplayName("UNAVAILABLE 与 UNSENDABLE 必须分开：载荷不可序列化**不入缓冲**（重放无意义）")
    void 载荷不可序列化不入缓冲() {
        FeedPublisher publisher = mock(FeedPublisher.class);
        when(publisher.publish(anyString(), anyString(), any(), anyString()))
                .thenReturn(DeliveryOutcome.UNSENDABLE);
        FeedDeliveryBuffer buffer = new FeedDeliveryBuffer(publisher, 16, LONG_WINDOW);

        assertThat(buffer.publish(EX, RK, "bad", "d1")).isFalse();
        assertThat(buffer.pendingCount())
                .as("重放必然同样失败 ⇒ 不得占住缓冲位（否则是内存泄漏 + 探测噪声）").isZero();
        assertThat(buffer.isDown()).as("序列化失败不是'broker 不可达'，不得置窗口").isFalse();
        buffer.destroy();
    }

    @Test
    @DisplayName("缓冲满 ⇒ 丢弃新消息 + 节流告警（不抛，不覆盖已有积压）")
    void 缓冲满即丢弃() {
        FeedPublisher publisher = mock(FeedPublisher.class);
        when(publisher.publish(anyString(), anyString(), any(), anyString()))
                .thenReturn(DeliveryOutcome.UNAVAILABLE);
        FeedDeliveryBuffer buffer = new FeedDeliveryBuffer(publisher, 2, LONG_WINDOW);

        buffer.publish(EX, RK, 1L, "d1");
        buffer.publish(EX, RK, 2L, "d2");
        buffer.publish(EX, RK, 3L, "d3");   // 溢出
        buffer.publish(EX, RK, 4L, "d4");   // 仍溢出（此后不再逐条告警）

        assertThat(buffer.pendingCount()).as("容量 2 ⇒ 只保留最早两条，不抛").isEqualTo(2);
        buffer.destroy();
    }

    @Test
    @DisplayName("关停后不再缓冲、不再重放；重复关停幂等")
    void 关停后不再受理() {
        FeedPublisher publisher = mock(FeedPublisher.class);
        when(publisher.publish(anyString(), anyString(), any(), anyString()))
                .thenReturn(DeliveryOutcome.UNAVAILABLE);
        FeedDeliveryBuffer buffer = new FeedDeliveryBuffer(publisher, 16, LONG_WINDOW);
        buffer.publish(EX, RK, 1L, "d1");

        buffer.destroy();
        assertThat(buffer.publish(EX, RK, 2L, "d2")).as("关停期不再缓冲").isFalse();
        assertThat(buffer.pendingCount()).as("关停清空剩余（已登记残余②）").isZero();
        buffer.destroy();   // 幂等，不抛
    }

    @Test
    @DisplayName("非正容量 / 非正探测周期 ⇒ 构造即拒")
    void 非正参数构造即拒() {
        FeedPublisher publisher = mock(FeedPublisher.class);
        assertThatThrownBy(() -> new FeedDeliveryBuffer(publisher, 0, LONG_WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeedDeliveryBuffer(publisher, 16, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
