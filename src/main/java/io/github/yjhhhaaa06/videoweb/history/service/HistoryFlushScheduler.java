package io.github.yjhhhaaa06.videoweb.history.service;

import io.github.yjhhhaaa06.videoweb.common.config.HistoryBufferProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 播放记录缓冲的**兜底轮询**（`T51-2`）：每 {@code video.history.buffer.poll-interval} 跑一次
 * {@link HistoryFlushService#pollOnce()}（剪枝 + 落"够闲"的用户）。
 *
 * <p>口径唯一事实源 = {@code architecture/domain/history/history.md} §12.3。
 *
 * <h2>★ 它只兜"没正常上报退出"的用户</h2>
 * 正常退出的用户走 {@code /history/flush}（同步、延迟 0），**不经过这里**。
 * 所以轮询周期只决定"崩溃 / 断网会话的兜底延迟"，不是降频参数。
 *
 * <h2>为什么抄 {@code FeedDeliveryBuffer} 的形态</h2>
 * 单个**守护** {@link ScheduledExecutorService}（零新依赖）、{@code @PreDestroy} 关停、
 * 任务体**吞掉一切运行时异常** —— {@code scheduleWithFixedDelay} 一旦让异常逃出任务体就**永久停摆**，
 * 而停摆的表现是"崩溃用户的进度再也不落库"，且没有任何报错。
 *
 * <p>★ 与 `FeedDeliveryBuffer` 的关键差异：本缓冲在 **Redis**（不是内存）⇒ 应用重启后
 * 轮询线程起来就能接着处理，**不丢**。
 *
 * <h2>并发</h2>
 * {@code running} 互斥：上一轮还没跑完（DB 慢）时不再叠加下一轮。轮询是"尽力而为"，
 * 错过一轮下一轮会补上（脏成员一直留在 ZSET 里）。
 */
@Slf4j
@Component
public class HistoryFlushScheduler {

    /** 线程名（对齐 {@code feed-delivery-buffer} 的命名族）。 */
    static final String THREAD_NAME = "history-buffer-flush";

    private final HistoryFlushService flushService;
    private final HistoryBufferProperties props;
    private final ScheduledExecutorService scheduler;

    /** 上一轮未结束时跳过本轮（不排队、不叠加）。 */
    private final AtomicBoolean running = new AtomicBoolean();

    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    @Autowired
    public HistoryFlushScheduler(HistoryFlushService flushService, HistoryBufferProperties props) {
        this.flushService = flushService;
        this.props = props;
        this.scheduler = newScheduler();
    }

    /**
     * 启动定时轮询。**绝不抛**（启动期异常会阻断应用启动）：
     * 失败只记 WARN —— 后果是"崩溃会话的进度不再自动落库"（只剩退出信号那条路），必须显式告警。
     */
    @PostConstruct
    void start() {
        long interval = props.pollInterval().toMillis();
        try {
            scheduler.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.warn("播放记录兜底轮询启动失败 ⇒ 崩溃/断网会话的进度不再自动落库（只剩 /history/flush 路径）", e);
        }
    }

    /** 关停：停调度、等当前轮次被中断（未确认的缓冲留在 Redis，下次启动继续）。 */
    @PreDestroy
    void stop() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        log.debug("播放记录兜底轮询已关闭");
    }

    /**
     * 一轮（包级可见：单测可直接驱动，不依赖真实计时）。
     *
     * <p>⚠️ **任何异常都必须吞掉**：逃出任务体会让 {@code scheduleWithFixedDelay} 永久停摆。
     */
    void tick() {
        if (shuttingDown.get() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            flushService.pollOnce();
        } catch (RuntimeException e) {
            log.warn("播放记录兜底轮询异常（忽略，等下一轮）", e);
        } finally {
            running.set(false);
        }
    }

    private static ScheduledExecutorService newScheduler() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            // 守护线程：即使关停流程异常也不阻塞 JVM 退出（对齐 FeedDeliveryBuffer）
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }
}
