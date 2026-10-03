package io.github.yjhhhaaa06.videoweb.feed.mq;

import io.github.yjhhhaaa06.videoweb.common.config.FeedProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 收件箱重建请求**去抖器**（承接 TV {@code follow.service.InboxRebuildDebouncer}）：
 * 同一用户在**一个窗口**（{@code video.feed.rebuild-debounce}，默认 1s）内的多次关注 / 取关
 * **只投一条重建消息**。
 *
 * <h2>为什么需要（去抖与既有锁的分工）</h2>
 * {@code FeedRebuildService} 的 {@code SET NX EX} 锁只做**并发去重优化、不聚合请求**；
 * 而投递是单 worker 串行、消费是单 channel / prefetch=1 串行 ⇒ N 条重建消息基本**不重叠**、
 * 逐条到达 ⇒ 那把锁**几乎永远拿得到** ⇒ **N 次连续关注 ≈ N 次真实整窗重算**。
 * 本类把"事件即投递"改为"事件入桶、桶静默满一个窗口后投一条"，是治此问题的唯一动作，
 * **不改变重建语义**（重建仍是整窗重算 + "存在即已同步"）。
 *
 * <h2>语义（尾沿 / trailing）</h2>
 * 每次 {@link #schedule} 重置该用户的窗口计时，**自最后一次事件起再等满窗口**才触发 ⇒
 * 合并窗口内 N 次事件为 1 条。
 *
 * <h2>载体与依赖</h2>
 * {@code ConcurrentHashMap<userId, Pending>}（{@code compute} 保证"取消旧计时 + 排新计时"原子）
 * + 单个**守护** {@link ScheduledExecutorService}（JDK 自带，**零新依赖**）。
 *
 * <h2>与 TV 的两处**有意差异**（都由 Spring 化带来，语义不变）</h2>
 * <ol>
 *   <li><b>窗口取配置</b>：TV 在无参构造里读静态 {@code AppConfig}；本实现由 Spring 注入
 *       {@link FeedProperties}。</li>
 *   <li><b>关停钩子</b>：TV 实现自研 {@code Disposable.destroy()}；本实现用 {@code @PreDestroy}
 *       （Spring 标准生命周期）。</li>
 *   <li><b>req= 串联（TV 的 {@code LogContext.wrap}）不搬</b>——本项目的日志串联走 MDC/框架，
 *       不在此处承担（《迁移参照系》§三：{@code LogContext} 属"平行机制"已收敛）。</li>
 * </ol>
 *
 * <h2>残余（登记，TV 原样）</h2>
 * ① 可见性上界 = 窗口 + 投递 + 消费；② 内存去抖 = **单实例语义**（多实例部署需重评）；
 * ③ 持续高频事件流（同一用户间隔 &lt; 窗口且不停）下尾沿被无限推迟——属**不正常行为、责任归限流专项**，
 * 故**刻意不加 maxDelay 上界**，且本类结构（map + 定时任务）**不随事件次数增长**。
 */
@Slf4j
@Component
public class InboxRebuildDebouncer {

    /** 调度线程名（对齐 TV 的 {@code mq-delivery-debounce}）。 */
    private static final String THREAD_NAME = "mq-delivery-debounce";

    /** 待触发项（代次 + 窗口内合并次数 + 到期要执行的任务 + 排期句柄）。 */
    private record Pending(long token, int merged, Runnable task, ScheduledFuture<?> future) {
    }

    /** 代次序号：区分"同一用户的新旧待触发项"，供 {@link #fire} 做**条件删除**（避免误删并发排入的新条目）。 */
    private final AtomicLong generation = new AtomicLong();

    /** 待触发项（userId → Pending）；{@code compute} 之下"取消旧计时 + 排新计时 + 替换条目"整体原子。 */
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();

    /** 调度器；**窗口为 0（显式关闭去抖）时为 null**，此时不建线程池。 */
    private final ScheduledExecutorService scheduler;

    private final long windowMillis;

    /** 关停标志：令并发 {@link #schedule} 立即走"直接执行 / 丢弃"分支，不再排期。 */
    private volatile boolean shuttingDown;

    /**
     * Spring 注入构造：窗口取自 {@code video.feed.rebuild-debounce}。
     *
     * <p>⚠️ 必须显式 {@code @Autowired}：本类另有"受控构造"（{@code long}），
     * 两个构造器且未标注时 Spring 找不到默认构造器 ⇒ **启动即失败**（实测：
     * {@code NoSuchMethodException: InboxRebuildDebouncer.<init>()}）。
     *
     * @throws IllegalArgumentException 窗口为负——负数无意义（0 有显式语义 = 关闭去抖），
     *                                  宁可构造即拒（对齐 TV {@code MqDeliveryDispatcher} 先例）；
     *                                  由 {@link FeedProperties} 在绑定期已拒，此处是第二道守卫
     */
    @Autowired
    public InboxRebuildDebouncer(FeedProperties props) {
        this(props.rebuildDebounce().toMillis());
    }

    /**
     * 受控构造（单测 / 特殊部署）：显式指定窗口毫秒。
     *
     * @throws IllegalArgumentException 窗口为负
     */
    public InboxRebuildDebouncer(long windowMillis) {
        if (windowMillis < 0) {
            throw new IllegalArgumentException("video.feed.rebuild-debounce 不得为负数: " + windowMillis);
        }
        this.windowMillis = windowMillis;
        this.scheduler = (windowMillis > 0L) ? newScheduler() : null;
    }

    /**
     * 登记一次"该用户需要重建收件箱"的请求（**非阻塞、绝不抛**，调用方是 Web 请求线程）。
     *
     * <p>同一 userId 在窗口内的多次调用只会在窗口末尾执行**最后一次**登记的 {@code task}；
     * 不同 userId 各自独立计时。窗口为 0（显式关闭去抖）或已关停 ⇒ **立即执行**（退回"事件即投递"
     * 的既有行为；调用方传入的 task 本身已是非阻塞动作）。
     *
     * @param userId 收件箱归属者（= 关注 / 取关的发起方）
     * @param task   到期要执行的动作（调用方给出的"投递重建消息"任务体，须自带根捕获）
     */
    public void schedule(long userId, Runnable task) {
        Objects.requireNonNull(task, "task");
        if (shuttingDown || windowMillis == 0L) {
            runTask(userId, task);
            return;
        }
        try {
            pending.compute(userId, (key, current) -> {
                int merged = (current == null) ? 1 : current.merged() + 1;
                if (current != null) {
                    // 旧触发尚未开始时取消之；已开始执行的由"重建幂等 + 代次条件删除"兜底
                    current.future().cancel(false);
                }
                long token = generation.incrementAndGet();
                ScheduledFuture<?> future = scheduler.schedule(
                        () -> fire(key, merged, task, token), windowMillis, TimeUnit.MILLISECONDS);
                return new Pending(token, merged, task, future);
            });
        } catch (RejectedExecutionException e) {
            // 关停竞态（destroy 已 shutdownNow）：降级丢弃，绝不向业务线程抛
            log.warn("重建请求去抖器已关停，本次重建请求被丢弃（降级，不影响关注/取关）: userId={}", userId);
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 窗口到期触发：**先条件删除**（只有"我这一代"仍挂在 map 上才移除自己，避免误删并发排入的新条目；
     * 误删的后果也只是多投一次——重建整窗替换 ⇒ 幂等）→ 记合并结论行（仅 &gt; 1 次）→ 执行任务。
     */
    private void fire(long userId, int merged, Runnable task, long token) {
        pending.computeIfPresent(userId, (key, current) -> (current.token() == token) ? null : current);
        if (merged > 1) {
            // 合并结论行刻意不带 req=（聚合了多个请求的登记，语义上不属任何单个 req）
            log.info("收件箱重建请求已去抖合并, userId={}, merged={}, windowMillis={}", userId, merged, windowMillis);
        }
        runTask(userId, task);
    }

    /** 执行任务体（**最后兜底**：任务体异常穿透只记 error，绝不外抛——本链不得影响业务线程）。 */
    private void runTask(long userId, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("收件箱重建去抖任务执行异常（已兜底，不影响关注/取关）, userId={}", userId, e);
        }
    }

    /**
     * 关停：停收新请求 → **flush 待触发项**（立即执行；本步有界）→ {@code shutdownNow} 取消未到期的排期。
     * 每步不外抛，不挂住应用关停。
     */
    @PreDestroy
    public void destroy() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        List<Map.Entry<Long, Runnable>> drained = new ArrayList<>();
        for (Long userId : new ArrayList<>(pending.keySet())) {
            Pending removed = pending.remove(userId);
            if (removed != null) {
                drained.add(Map.entry(userId, removed.task()));
            }
        }
        if (!drained.isEmpty()) {
            // 汇总一条（不按条刷）；单条投递成败仍由 FeedPublisher 的既有降级口径持有
            log.info("重建请求去抖器关停，立即触发待合并的重建请求 {} 条", drained.size());
            for (Map.Entry<Long, Runnable> entry : drained) {
                runTask(entry.getKey(), entry.getValue());
            }
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        log.debug("重建请求去抖器已关闭（windowMillis={}）", windowMillis);
    }

    private static ScheduledExecutorService newScheduler() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            // 守护线程：即使关停流程异常也不阻塞 JVM 退出
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }
}
