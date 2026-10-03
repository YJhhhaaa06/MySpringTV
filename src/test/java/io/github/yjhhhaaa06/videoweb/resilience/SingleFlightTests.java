package io.github.yjhhhaaa06.videoweb.resilience;

import io.github.yjhhhaaa06.videoweb.common.cache.SingleFlight;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单飞组件本身（第三批 T3 / 账 B1）——纯逻辑单测，不碰 Spring / Redis。
 *
 * <p>接线是否真的生效由 {@link SingleFlightCacheTests} 在真实读路径上验证；本类钉的是
 * TV 的三个配套约束：并发只执行一次、失败/成功都清理条目、异常语义不被包装丢失。
 */
@Tag("resilience")
class SingleFlightTests {

    private final SingleFlight singleFlight = new SingleFlight();

    @Test
    @DisplayName("★同一 key 并发只执行一次 loader，其余线程共享结果")
    void 同key并发只执行一次loader() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return singleFlight.get("k", () -> {
                        loads.incrementAndGet();
                        Thread.sleep(250); // 拉宽窗口，让所有线程堆到同一次在飞任务上
                        return "v";
                    });
                }));
            }
            gate.countDown();
            for (Future<String> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo("v");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(loads.get()).as("并发 miss 只回源一次").isEqualTo(1);
        assertThat(singleFlight.inFlightCount()).as("完成后条目必须清理（防内存泄漏）").isZero();
    }

    @Test
    @DisplayName("★loader 失败 ⇒ 条目被移除（不缓存失败结果），下次重新执行")
    void loader失败后条目移除() {
        AtomicInteger loads = new AtomicInteger();

        assertThatThrownBy(() -> singleFlight.get("k", () -> {
            loads.incrementAndGet();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(singleFlight.inFlightCount()).as("失败也必须清理（约束 ①）").isZero();

        String value = singleFlight.get("k", () -> {
            loads.incrementAndGet();
            return "ok";
        });
        assertThat(value).isEqualTo("ok");
        assertThat(loads.get()).as("失败后下次是全新执行，不共享失败").isEqualTo(2);
    }

    @Test
    @DisplayName("DB 异常经单飞原样重抛（不被包装成别的类型）")
    void DB异常原样重抛() {
        DataAccessException dbFailure = new DataAccessException("模拟 DB 故障") {
        };
        assertThatThrownBy(() -> singleFlight.get("k", () -> {
            throw dbFailure;
        })).isSameAs(dbFailure);
    }

    @Test
    @DisplayName("不同 key 各自独立执行（不互相阻塞/串结果）")
    void 不同key各自执行() {
        assertThat(singleFlight.get("a", () -> "A")).isEqualTo("A");
        assertThat(singleFlight.get("b", () -> "B")).isEqualTo("B");
        assertThat(singleFlight.inFlightCount()).isZero();
    }
}
