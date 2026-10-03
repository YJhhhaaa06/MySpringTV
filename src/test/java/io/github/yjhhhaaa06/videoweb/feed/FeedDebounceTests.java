package io.github.yjhhhaaa06.videoweb.feed;

import io.github.yjhhhaaa06.videoweb.feed.mq.InboxRebuildDebouncer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 重建请求**去抖器**的单元测试（S9）——纯 JDK 组件（{@link InboxRebuildDebouncer}），无需 Spring 上下文。
 *
 * <h2>为什么必须测它</h2>
 * 去抖是本切片的**语义机制**（用户点名）：没有它，"N 次连续关注"≈"N 次真实整窗重算"
 * （锁只做并发去重、不聚合请求）。这条测试是"合并真的发生"的存在证明——
 * 若有人把 {@code schedule} 改成"每次直接执行"，本类立刻失败。
 */
class FeedDebounceTests {

    @Test
    @DisplayName("★窗口内多次登记 ⇒ 只执行最后一次（尾沿 / trailing 合并）")
    void 窗口内合并为一次() throws InterruptedException {
        InboxRebuildDebouncer debouncer = new InboxRebuildDebouncer(150L);
        AtomicInteger executions = new AtomicInteger();
        List<Long> executedUsers = new CopyOnWriteArrayList<>();
        CountDownLatch fired = new CountDownLatch(1);

        // 同一用户连点 5 次（间隔远小于窗口）
        for (int i = 0; i < 5; i++) {
            debouncer.schedule(42L, () -> {
                executions.incrementAndGet();
                executedUsers.add(42L);
                fired.countDown();
            });
        }
        assertThat(fired.await(3, TimeUnit.SECONDS)).as("窗口到期后必须触发一次").isTrue();
        Thread.sleep(300);   // 再等一会，确认没有第 2 次
        assertThat(executions.get()).as("5 次登记必须合并为 1 次执行").isEqualTo(1);
        assertThat(executedUsers).containsExactly(42L);
        debouncer.destroy();
    }

    @Test
    @DisplayName("不同用户各自独立计时（互不合并）")
    void 不同用户各自计时() throws InterruptedException {
        InboxRebuildDebouncer debouncer = new InboxRebuildDebouncer(120L);
        CountDownLatch fired = new CountDownLatch(2);
        debouncer.schedule(1L, fired::countDown);
        debouncer.schedule(2L, fired::countDown);
        assertThat(fired.await(3, TimeUnit.SECONDS)).as("两个用户各触发一次").isTrue();
        debouncer.destroy();
    }

    @Test
    @DisplayName("窗口为 0 ⇒ 显式关闭去抖：每次登记立即执行（退回'事件即投递'）")
    void 窗口为零即关闭去抖() {
        InboxRebuildDebouncer debouncer = new InboxRebuildDebouncer(0L);
        AtomicInteger executions = new AtomicInteger();
        debouncer.schedule(7L, executions::incrementAndGet);
        debouncer.schedule(7L, executions::incrementAndGet);
        assertThat(executions.get()).as("0 = 关闭去抖 ⇒ 不合并").isEqualTo(2);
        debouncer.destroy();
    }

    @Test
    @DisplayName("任务体异常穿透被兜底：绝不外抛（本链不得影响业务线程）")
    void 任务体异常被兜底() throws InterruptedException {
        InboxRebuildDebouncer debouncer = new InboxRebuildDebouncer(50L);
        CountDownLatch fired = new CountDownLatch(1);
        debouncer.schedule(9L, () -> {
            fired.countDown();
            throw new IllegalStateException("任务体异常");
        });
        assertThat(fired.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(150);
        debouncer.destroy();
    }

    @Test
    @DisplayName("负窗口 ⇒ 构造即拒（0 有显式语义，负数无意义）")
    void 负窗口构造即拒() {
        assertThatThrownBy(() -> new InboxRebuildDebouncer(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
