package io.github.yjhhhaaa06.videoweb.feed.mq;

import io.github.yjhhhaaa06.videoweb.common.log.RequestIdFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MQ 异步投递线程池（第三批 T5 / 账 B8）的故障注入测试——纯 JDK 组件，无需 Spring 上下文。
 *
 * <h2>这条账的代价（为什么不是纯优化）</h2>
 * 发布不等 publisher-confirm，broker **可达**时 {@code send} 写完 socket 即返回；但 broker
 * **不可达**时会先等一次连接超时（{@code spring.rabbitmq.connection-timeout}，dev 2s）才抛
 * ——这笔等待原先就在发布 / 关注 / 取关的 **Web 线程**上。本类证明：投递被挪到后台后，
 * {@link FeedDeliveryDispatcher#submit} 在投递完成前就返回。
 *
 * <h2>真正"注入"的是什么</h2>
 * 不用真停容器（测试 broker 是 {@code Containers} 的进程级单例，停机波及其它测试类——
 * 与 {@code RedisCircuitBreakerTests} 同一处置）。注入手段 = **把投递体做成"会长时间阻塞"的任务**，
 * 于是"submit 是否阻塞"变成可判定的：同步投递会让提交线程卡在 latch 上（本类变红），
 * 异步投递则立即返回。
 *
 * <h2>★ 反向验证（两处缺陷各对应用例）</h2>
 * <ul>
 *   <li>把 {@code submit} 改成"直接 {@code task.run()}"（或给线程池配 {@code CallerRunsPolicy}）
 *       ⇒ {@link #submit非阻塞()} 变红（提交线程被投递体卡住）；</li>
 *   <li>去掉 {@code submit} 里的 MDC 捕获 / {@code runWithContext} 里的恢复
 *       ⇒ {@link #提交线程的MDC在worker内可读()} 变红。</li>
 * </ul>
 */
@Tag("resilience")
class FeedDeliveryDispatcherTests {

    @Test
    @DisplayName("★submit 非阻塞：worker 仍在执行时 submit 已返回（发布线程不等投递）")
    void submit非阻塞() throws InterruptedException {
        FeedDeliveryDispatcher dispatcher = new FeedDeliveryDispatcher(4, 1000);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread submitter = new Thread(() -> dispatcher.submit(() -> {
            started.countDown();
            awaitQuietly(release);
        }, "blocking"));
        submitter.start();

        assertThat(started.await(2, TimeUnit.SECONDS)).as("worker 必须已开始执行任务").isTrue();
        submitter.join(1000);
        assertThat(submitter.isAlive())
                .as("★submit 必须在投递完成前返回（若同步执行，提交线程会卡在 latch 上）")
                .isFalse();

        release.countDown();
        dispatcher.destroy();
    }

    @Test
    @DisplayName("★提交线程的 reqId（MDC）在 worker 内可读——异步段仍与请求链同 req= 串联")
    void 提交线程的MDC在worker内可读() throws InterruptedException {
        FeedDeliveryDispatcher dispatcher = new FeedDeliveryDispatcher(4, 1000);
        AtomicReference<String> seenInWorker = new AtomicReference<>();
        AtomicReference<String> seenInSubmitterAfter = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        MDC.put(RequestIdFilter.MDC_KEY, "abc12345");
        try {
            dispatcher.submit(() -> {
                seenInWorker.set(MDC.get(RequestIdFilter.MDC_KEY));
                done.countDown();
            }, "mdc");
            seenInSubmitterAfter.set(MDC.get(RequestIdFilter.MDC_KEY));
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(seenInWorker.get()).as("worker 必须恢复提交线程的 MDC 快照").isEqualTo("abc12345");
        assertThat(seenInSubmitterAfter.get()).as("提交线程自身上下文不受影响").isEqualTo("abc12345");
        dispatcher.destroy();
    }

    @Test
    @DisplayName("单 worker 保序：执行序 == 提交序（写扩散 / 重建消息的全局顺序不因异步化而乱）")
    void 单worker保序() throws InterruptedException {
        int tasks = 20;
        FeedDeliveryDispatcher dispatcher = new FeedDeliveryDispatcher(tasks, 1000);
        List<Integer> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(tasks);

        for (int i = 0; i < tasks; i++) {
            int seq = i;
            dispatcher.submit(() -> {
                order.add(seq);
                done.countDown();
            }, "t" + seq);
        }

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactlyElementsOf(IntStream.range(0, tasks).boxed().toList());
        dispatcher.destroy();
    }

    @Test
    @DisplayName("★队列满 ⇒ 丢弃并返回 false（降级，不向业务线程抛）")
    void 队列满即降级丢弃() throws InterruptedException {
        FeedDeliveryDispatcher dispatcher = new FeedDeliveryDispatcher(1, 500);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        assertThat(dispatcher.submit(() -> {
            started.countDown();
            awaitQuietly(release);
        }, "占住 worker")).isTrue();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(dispatcher.submit(() -> { }, "队列位")).as("容量 1 ⇒ 这一个能入队").isTrue();
        assertThat(dispatcher.submit(() -> { }, "溢出"))
                .as("队列满 ⇒ 返回 false（AbortPolicy 被捕获，不外抛）").isFalse();

        release.countDown();
        dispatcher.destroy();
    }

    @Test
    @DisplayName("关停后 submit ⇒ 返回 false（不抛，不挂住关停）")
    void 关停后不再受理() {
        FeedDeliveryDispatcher dispatcher = new FeedDeliveryDispatcher(4, 200);
        dispatcher.destroy();
        assertThat(dispatcher.submit(() -> { }, "after-shutdown")).isFalse();
        // 幂等：二次 destroy 不抛
        dispatcher.destroy();
    }

    @Test
    @DisplayName("任务体异常不穿透、也不打死池线程（链上契约'绝不抛'的兜底）")
    void 任务体异常被兜底() throws InterruptedException {
        FeedDeliveryDispatcher dispatcher = new FeedDeliveryDispatcher(4, 1000);
        CountDownLatch boom = new CountDownLatch(1);
        CountDownLatch after = new CountDownLatch(1);

        assertThat(dispatcher.submit(() -> {
            boom.countDown();
            throw new IllegalStateException("投递体异常");
        }, "boom")).isTrue();
        assertThat(boom.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(dispatcher.submit(after::countDown, "after-boom")).isTrue();
        assertThat(after.await(2, TimeUnit.SECONDS)).as("池线程未被异常打死").isTrue();
        dispatcher.destroy();
    }

    @Test
    @DisplayName("关停 drain：上界内把队列存量投完（不立即丢）")
    void 关停drain队列存量() throws InterruptedException {
        FeedDeliveryDispatcher dispatcher = new FeedDeliveryDispatcher(8, 2000);
        AtomicInteger executed = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            dispatcher.submit(executed::incrementAndGet, "t" + i);
        }
        dispatcher.destroy();
        assertThat(executed.get()).as("drain 上界内必须把 5 条都投完").isEqualTo(5);
    }

    @Test
    @DisplayName("空任务同样走降级出口（返回 false，不抛 NPE——'绝不抛'的字面成立）")
    void 空任务不抛() {
        FeedDeliveryDispatcher dispatcher = new FeedDeliveryDispatcher(4, 1000);
        assertThat(dispatcher.submit(null, "null-task")).isFalse();
        dispatcher.destroy();
    }

    @Test
    @DisplayName("非正容量 / 非正 drain 上界 ⇒ 构造即拒（宁可启动即拒）")
    void 非正参数构造即拒() {
        assertThatThrownBy(() -> new FeedDeliveryDispatcher(0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeedDeliveryDispatcher(4, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
