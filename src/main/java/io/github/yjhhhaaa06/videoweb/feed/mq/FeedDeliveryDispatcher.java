package io.github.yjhhhaaa06.videoweb.feed.mq;

import io.github.yjhhhaaa06.videoweb.common.config.FeedDeliveryProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MQ **异步投递线程池**（第三批 T5 / 账 B8，承接 TV {@code mq.MqDeliveryDispatcher}）：
 * 把「序列化 + publish」从 Web 请求线程挪到**专用后台线程**——发布 / 关注 / 取关接口的 RT
 * 不再受 MQ 慢 / 不可达影响。
 *
 * <h2>为什么新项目仍需它（决策 C-7 的"必要性消失"只说对了一半）</h2>
 * C-7 裁掉本类的理由是"**发布不启用 publisher-confirm ⇒ 写 socket 即返回，天然不阻塞**"。
 * 那只在 **broker 可达**时成立：broker 不可达时 {@code RabbitTemplate.send} 会先等一次
 * {@code spring.rabbitmq.connection-timeout}（dev 2s）才抛 {@code AmqpException}
 * ——而这正是 {@link FeedPublisher} 类注释自己登记的代价。**每次内容发布 / 关注 / 取关
 * 命中该路径，就是一次 2s 的 Web 线程阻塞**。本类把这笔等待整体挪到后台。
 *
 * <h2>形态：单 worker + 有界队列（JDK 自带构件、零新依赖）</h2>
 * <ul>
 *   <li><b>单 worker 是设计而非省略</b>：单 worker 保留**同一提交源内的提交序**——写扩散与重建
 *       分别从监听线程 / 去抖线程提交（提交源与时刻本就不同），本池只能保证"同一来源的先后不被
 *       打乱"，不宣称跨来源的全局序。真实投递瓶颈在 broker 连接而非 CPU，多线程只在锁上排队。</li>
 *   <li><b>有界队列 = 背压</b>（本类存在的核心理由）：无界队列会让 MQ 慢时任务在内存无界堆积
 *       （每条持 payload）。队列满 ⇒ {@link #submit} 返回 {@code false} 并记 WARNING
 *       （**降级丢弃，不影响业务**——丢失面由"下次重建 / 纯拉自愈"兜底，与既有口径一致）。
 *       ⚠️ **不得用 {@code CallerRunsPolicy}**——那会把等待拉回 Web 线程，RT 解耦白做。</li>
 *   <li><b>关停有界 drain</b>：{@link #destroy()} 先停收新任务、再在上界内等 worker 投完队列存量；
 *       超时 {@code shutdownNow()} 丢弃剩余并记 WARNING。上界存在的原因：不得挂住应用关停。</li>
 * </ul>
 *
 * <h2>与 TV 的三处差异（都由 Spring 化带来）</h2>
 * <ol>
 *   <li><b>reqId 串联改用 MDC</b>（TV 用自研 {@code LogContext.wrap}）：提交时
 *       {@link MDC#getCopyOfContextMap()} 捕获、worker 内恢复、执行完还原 worker 原上下文
 *       ——否则异步段的日志丢 {@code req=}（{@code RequestIdFilter} 类注释的"已知边界"）。</li>
 *   <li><b>任务体根捕获在本类</b>（TV 放在 Notifier 任务体）：本类的调用方 {@link FeedDelivery}
 *       → {@link FeedPublisher#publish} 全链契约"绝不抛"，此处兜底只为"意外运行时异常不得静默"
 *       （{@code Error} 仍按 JVM 语义冒泡、不在此吞——吞掉 OOM 这类错误比让池换线程更坏）。</li>
 *   <li><b>关停钩子用 {@code @PreDestroy}</b>（TV 实现自研 {@code Disposable.destroy()}）。</li>
 * </ol>
 *
 * <h2>残余（登记）</h2>
 * ① 队列满 / 关停期未投完 ⇒ **丢弃**（降级口径既有）；② 内存队列 ⇒ 单实例语义，
 * 多实例部署需重评；③ 本类**不承诺吞吐提升**（吞吐面 = 多 channel，属另一议题）。
 */
@Slf4j
@Component
public class FeedDeliveryDispatcher {

    /** 投递 worker 线程名（对齐 {@code mq-*} 命名族；单线程无需序号）。 */
    static final String WORKER_THREAD_NAME = "feed-delivery";

    private final ThreadPoolExecutor executor;
    private final long drainTimeoutMillis;

    /**
     * 关停标志：令并发 {@link #submit} 走"已关停"分支。用 {@link AtomicBoolean} 而非
     * {@code volatile boolean} 是因为 {@link #destroy()} 的"检查—置位"必须是原子的
     * （Spring 与测试可能各触发一次销毁，且不排除并发）。
     */
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    /**
     * Spring 注入构造：队列容量与 drain 上界取自 {@code video.feed.delivery.*}。
     */
    @Autowired
    public FeedDeliveryDispatcher(FeedDeliveryProperties props) {
        this(props.queueCapacity(), props.drainTimeout().toMillis());
    }

    /**
     * 受控构造（单测 / 特殊部署）：显式指定队列容量与关停 drain 上界。
     *
     * @throws IllegalArgumentException 容量 / drain 上界非正——非正分别导致投递永远无法入队、
     *                                  关停等待无上界，宁可构造即拒（对齐 TV 先例）
     */
    public FeedDeliveryDispatcher(int queueCapacity, long drainTimeoutMillis) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("video.feed.delivery.queue-capacity 必须为正数: " + queueCapacity);
        }
        if (drainTimeoutMillis <= 0) {
            throw new IllegalArgumentException("video.feed.delivery.drain-timeout 必须为正数: " + drainTimeoutMillis);
        }
        this.drainTimeoutMillis = drainTimeoutMillis;
        // 拒绝策略沿用默认 AbortPolicy：拒绝时抛 RejectedExecutionException，由 submit() 捕获后
        // 记 WARNING（唯一日志点）并返回 false——不用"静默吞"型 handler，提交方必须能得知被拒
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                newWorkerFactory());
    }

    /**
     * 提交一条投递任务（**非阻塞、绝不抛**，提交方是 Web 请求线程）。
     *
     * <p>语义：入队成功 = 本方法职责结束，真实投递由 worker 异步完成（发布线程不再等连接超时）；
     * 队列满 / 已关停 ⇒ 任务被丢弃（记 WARNING 后返回 {@code false}），失败不影响业务。
     *
     * @param task 投递任务体（调用方 {@link FeedDelivery}，其链上契约"绝不抛"）
     * @param desc 一句任务描述（进丢弃日志，便于定位丢的是哪条：如 {@code push contentId=42}）
     * @return {@code true} = 已入队；{@code false} = 队列满 / 已关停被丢弃
     */
    public boolean submit(Runnable task, String desc) {
        if (task == null) {
            // 契约"绝不抛"：把 null 也纳入降级出口，而不是用 requireNonNull 抛 NPE 穿透业务线程
            log.warn("投递任务为空，已丢弃（降级，不影响业务）: {}", desc);
            return false;
        }
        if (shuttingDown.get()) {
            log.warn("投递线程池已关停，任务未提交（降级，不影响业务）: {}", desc);
            return false;
        }
        // 提交线程捕获 MDC（reqId），worker 内恢复——异步段仍与请求链同 req= 串起来
        Map<String, String> requestContext = MDC.getCopyOfContextMap();
        try {
            executor.execute(() -> runWithContext(task, requestContext, desc));
            return true;
        } catch (RejectedExecutionException e) {
            // 队列满 / 已关停是两条不同的降级面：分开设辞，便于运维一眼定位
            if (executor.isShutdown() || shuttingDown.get()) {
                log.warn("投递线程池已关停，任务未提交（降级，不影响业务）: {}", desc);
            } else {
                log.warn("投递队列已满，任务被丢弃（降级，不影响业务）: {}", desc);
            }
            return false;
        } catch (RuntimeException e) {
            // 兜底：其余意外——任何情况下不得穿透到业务线程
            log.warn("投递任务提交失败（已丢弃，不影响业务）: {}", desc, e);
            return false;
        }
    }

    /** 关停：停收新任务 → 上界内 drain 队列存量 → 超时丢弃剩余；每步不外抛，不挂住应用关停。
     *  幂等（对顺序与并发调用均成立）。 */
    @PreDestroy
    public void destroy() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        boolean drained = false;
        try {
            drained = executor.awaitTermination(drainTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!drained) {
            List<Runnable> remaining = executor.shutdownNow();
            log.warn("投递队列在 {}ms drain 上界内未投完，剩余 {} 条未投递即退出（关停期丢失，由收件箱重建兜底）",
                    drainTimeoutMillis, remaining.size());
        }
        log.info("MQ 投递线程池已关闭（drain 上界 {}ms，{}）",
                drainTimeoutMillis, drained ? "队列存量已处理完" : "队列未投完即退出");
    }

    // ==================== 内部实现 ====================

    /**
     * worker 侧执行：恢复提交线程的 MDC → 执行任务（根捕获）→ 还原 worker 原上下文。
     *
     * <p>{@code finally} 里**还原提交前的 worker 上下文**：若 worker 原本有 MDC 则
     * {@code setContextMap} 复原，原本为空才 {@code clear()}——本池是**专用单线程**，
     * 不会抹掉别人的键；但把"还原"写成 clear 在将来共享线程池时会变成坑，故显式区分两态
     * （与 {@code RequestIdFilter}「只清自己写的键」同纪律）。
     */
    private static void runWithContext(Runnable task, Map<String, String> requestContext, String desc) {
        Map<String, String> workerPrevious = MDC.getCopyOfContextMap();
        if (requestContext != null) {
            MDC.setContextMap(requestContext);
        }
        try {
            task.run();
        } catch (RuntimeException e) {
            // 兜底：链上契约"绝不抛"，走到这里即编程错误。只兜 RuntimeException——
            // Error（如 OOM）按 JVM 语义冒泡，吞掉它比让池换线程更坏（类注释同款说明）。
            log.error("MQ 投递任务执行异常（已兜底，不影响业务）: {}", desc, e);
        } finally {
            if (workerPrevious == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(workerPrevious);
            }
        }
    }

    private static ThreadFactory newWorkerFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, WORKER_THREAD_NAME);
            // 守护线程：即使 drain 失常也不阻塞 JVM 退出（对齐 InboxRebuildDebouncer）
            thread.setDaemon(true);
            return thread;
        };
    }
}
