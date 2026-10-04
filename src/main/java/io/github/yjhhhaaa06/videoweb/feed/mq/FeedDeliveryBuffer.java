package io.github.yjhhhaaa06.videoweb.feed.mq;

import io.github.yjhhhaaa06.videoweb.common.config.FeedCompensateProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MQ **内存补偿缓冲**（第三批 T5 / 账 B9，承接 TV {@code mq.MqDeliveryBuffer}）：broker 不可用时
 * **已确定投不出去**的消息暂存有界内存队列，**恢复后重放**——三类消息（写扩散 / 降级补推 /
 * 收件箱重建）统一经过本类。
 *
 * <h2>为什么能安全重放</h2>
 * 三类消息的消费侧**本就幂等**（重建 = 整窗替换 + {@code SET NX EX} 去重锁；写扩散 / 补推 =
 * {@code INSERT IGNORE}）⇒ 重放是"至少一次"语义、零副作用。**这是本方案成立的基石**，
 * 因此不需要为它新增任何东西（TV 原话）。
 *
 * <h2>入缓冲判据 = {@link FeedPublisher#publish} 返回 {@link DeliveryOutcome#UNAVAILABLE}</h2>
 * 三态里 {@code SENT} 不入、{@code UNSENDABLE}（载荷不可序列化）**也不入**——后者重放必然同样
 * 失败，入缓冲只会永久占位（见 {@link DeliveryOutcome} 类注释）。
 * ⚠️ 这意味着"发送中途连接断开"（状态不明）也会被重放 ⇒ **依赖消费侧幂等**，与 TV 同款依赖、
 * 已登记（TV 只缓冲"连接确定不可用"，本仓放宽到"不可达 / 状态不明"，靠幂等对冲）。
 *
 * <h2>不可达窗口 = 零 I/O 暂存（本类必须先于"每次投递都等连接超时"）</h2>
 * 若每次投递都真的去撞一次 {@code spring.rabbitmq.connection-timeout}（dev 2s），
 * 一次 MQ 故障会让后台单 worker 每 2s 才吞下一条消息 ⇒ 有界队列很快被灌满 ⇒ 补偿反而失效。
 * 故本类自持一个极小的**不可达窗口**状态：一次 {@code UNAVAILABLE} 后进入"不可达"，
 * 窗口内新消息**直接暂存、不触达 broker**；窗口期满后放行一次真实投递作为探针。
 * （这与 TV 的 {@code MqConnectionManager.ensureConnected()} 同构——TV 用它做同样的"不空转"，
 * 本仓不搬它的连接管理，只保留这层判定。）
 *
 * <h2>重放触发 = 定时探测（主通道）</h2>
 * 缓冲非空时，探测线程每 {@code video.feed.compensate.probe-interval}（默认 30s）尝试重放；
 * 探测本身零 I/O（缓冲为空直接返回）。恢复后可补偿的时延上界 = 探测周期 + 投递 + 消费。
 * <br>⚠️ **与 TV 的差异（登记）**：TV 还有一条"连接回调 onConnected 立即重放"的加速通道，
 * 本仓**不搬**——Spring AMQP 的连接自动恢复不暴露等价的业务可见钩子。本仓的探针**会真的尝试投递**
 * （而非 TV 那样只读本地状态），因此"启动时 broker 不可达"这一 TV 的已知窄路径在本仓**能自驱恢复**。
 *
 * <h2>失败与容量口径</h2>
 * 缓冲**有界**（{@code video.feed.compensate.buffer-capacity}，默认 10000）——满则丢弃新消息，
 * **首次记一条 WARNING 后节流**（不按条刷），队列腾出后自动恢复告警能力。
 * 重放中若连接再次不可用 ⇒ **停止本轮并保留剩余**（下轮探测继续）；重放失败的消息放回队尾。
 *
 * <h2>残余（登记，与 TV 同口径）</h2>
 * ① 内存缓冲 ⇒ **应用重启 / 崩溃即丢**（跨重启不可补）；② 关停时剩余消息**丢弃**（汇总一条 INFO），
 * 不阻塞关停；③ 缓冲满 ⇒ 丢弃 + 节流 WARNING；④ 重放 = 至少一次 ⇒ **依赖消费侧幂等**（已具备）；
 * ⑤ 内存态 ⇒ **单实例语义**，多实例部署需重评。
 *
 * <p><b>契约</b>：本类**绝不抛异常**，供后台投递线程调用；不改变任何既有降级语义。
 */
@Slf4j
@Component
public class FeedDeliveryBuffer {

    /** 探测线程名（对齐 {@code mq-*} 命名族）。 */
    static final String PROBE_THREAD_NAME = "feed-delivery-buffer";

    /** 待重放消息（载荷保持对象形态，重放时重新序列化——消息均为不可变 record，成本可忽略）。 */
    private record Pending(String exchange, String routingKey, Object payload, String desc) {
    }

    private final FeedPublisher publisher;

    /** 有界待重放队列（容量 = {@code video.feed.compensate.buffer-capacity}）。 */
    private final ArrayBlockingQueue<Pending> pending;

    private final ScheduledExecutorService scheduler;

    /** 恢复探测周期：同时是"不可达窗口"的长度（见类注释）。 */
    private final long probeIntervalMillis;

    /** 重放互斥：探测线程与投递线程可能并发触发。 */
    private final AtomicBoolean flushing = new AtomicBoolean();

    /** 溢出告警节流：首次满记一条 WARNING，之后静默；队列腾出后重置（不按条刷日志）。 */
    private final AtomicBoolean overflowWarned = new AtomicBoolean();

    /** 关停标志：之后不再缓冲、不再重放（关停期丢失 = 残余②口径）。 */
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    /** 不可达窗口状态：{@code down} 时窗口内新消息零 I/O 暂存。 */
    private volatile boolean down;

    private volatile long downSinceMillis;

    /** Spring 注入构造：容量与探测周期取自 {@code video.feed.compensate.*}。 */
    @Autowired
    public FeedDeliveryBuffer(FeedPublisher publisher, FeedCompensateProperties props) {
        this(publisher, props.bufferCapacity(), props.probeInterval().toMillis());
    }

    /**
     * 受控构造（单测 / 特殊部署）：显式指定缓冲容量与探测周期。
     *
     * @throws IllegalArgumentException 容量 / 周期非正——非正分别让缓冲永远无法入队、探测无意义，
     *                                  宁可构造即拒（对齐 TV {@code MqDeliveryBuffer} 先例）
     */
    public FeedDeliveryBuffer(FeedPublisher publisher, int capacity, long probeIntervalMillis) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("video.feed.compensate.buffer-capacity 必须为正数: " + capacity);
        }
        if (probeIntervalMillis <= 0) {
            throw new IllegalArgumentException("video.feed.compensate.probe-interval 必须为正数: " + probeIntervalMillis);
        }
        this.publisher = publisher;
        this.probeIntervalMillis = probeIntervalMillis;
        this.pending = new ArrayBlockingQueue<>(capacity);
        this.scheduler = newScheduler();
    }

    /**
     * 投递一条消息（**绝不抛**）：broker 不可达 ⇒ 暂存待补偿；可达 ⇒ 就地投递。
     *
     * @return {@code true} = 本次已交给客户端发送；{@code false} = 已暂存 / 载荷不可序列化 / 关停（已降级）
     */
    public boolean publish(String exchange, String routingKey, Object payload, String desc) {
        if (shuttingDown.get()) {
            return false;   // 关停期不再缓冲（缓冲也无从重放）
        }
        Pending message = new Pending(exchange, routingKey, payload, desc);
        if (!allowAttempt()) {
            // 不可达窗口内：零 I/O 暂存，不撞连接超时（否则后台 worker 会每 2s 才吞一条）
            buffer(message, "MQ 不可达窗口内");
            return false;
        }
        DeliveryOutcome outcome = publisher.publish(exchange, routingKey, payload, desc);
        return switch (outcome) {
            case SENT -> {
                markUp();
                yield true;
            }
            case UNAVAILABLE -> {
                markDown();
                buffer(message, "broker 不可达");
                yield false;
            }
            // 载荷不可序列化（编程错误），重放必然同样失败 ⇒ 不入缓冲（publisher 已记）
            case UNSENDABLE -> false;
        };
    }

    /** 当前待重放条数（诊断 / 单测断言用）。 */
    int pendingCount() {
        return pending.size();
    }

    /** 是否处于"不可达窗口"（诊断 / 单测断言用）。 */
    boolean isDown() {
        return down;
    }

    // ==================== 生命周期 ====================

    /**
     * 启动定时探测（主通道）。**绝不抛**（启动期异常会阻断应用启动）：失败只记 WARNING，
     * 降级为"只靠下一次业务投递触发重放"。
     */
    @PostConstruct
    void start() {
        try {
            scheduler.scheduleWithFixedDelay(this::probe,
                    probeIntervalMillis, probeIntervalMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.warn("MQ 投递补偿：定时探测启动失败（降级，仅靠下一次业务投递触发重放）", e);
        }
    }

    /** 关停：停收 → 丢弃剩余（汇总一条 INFO）→ 关探测线程；每步不外抛，不挂住应用关停。 */
    @PreDestroy
    public void destroy() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        int remaining = pending.size();
        pending.clear();
        if (remaining > 0) {
            log.info("MQ 投递补偿缓冲关停：丢弃未重放的 {} 条（内存态 + 关停期丢失 = 已登记残余，由收件箱重建兜底）",
                    remaining);
        }
        scheduler.shutdownNow();
        log.debug("MQ 投递补偿缓冲已关闭");
    }

    // ==================== 内部实现 ====================

    /**
     * 定时探测（主通道）：缓冲非空才动作（为空时零 I/O）。**任何异常都吞掉**——任务体抛出会让
     * {@code scheduleWithFixedDelay} 永久停摆。包级可见：单测可直接驱动，不依赖真实计时。
     */
    void probe() {
        try {
            if (shuttingDown.get() || pending.isEmpty()) {
                return;
            }
            flush("定时探测");
        } catch (RuntimeException e) {
            log.warn("MQ 投递补偿探测异常（忽略，等下次探测）", e);
        }
    }

    /**
     * 重放（互斥）：逐条投出；连接再次不可用 ⇒ **停止并保留剩余**（下轮探测继续）；
     * 载荷不可序列化 ⇒ 就地放弃（重放无意义）。投出 &gt; 0 时记一条 INFO 结论行（常态零输出）。
     */
    private void flush(String trigger) {
        if (shuttingDown.get() || !flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            int delivered = 0;
            while (!pending.isEmpty() && !shuttingDown.get()) {
                Pending message = pending.poll();
                if (message == null) {
                    break;
                }
                DeliveryOutcome outcome = publisher.publish(
                        message.exchange(), message.routingKey(), message.payload(), message.desc());
                if (outcome == DeliveryOutcome.SENT) {
                    markUp();
                    delivered++;
                    continue;
                }
                if (outcome == DeliveryOutcome.UNAVAILABLE) {
                    // 仍未恢复：放回队尾（顺序微调无妨——消费侧幂等）并结束本轮，避免坏状态下空转
                    markDown();
                    pending.offer(message);
                    break;
                }
                log.warn("MQ 投递补偿：放弃重放（载荷不可序列化，重放必然同样失败）: {}", message.desc());
            }
            if (delivered > 0) {
                overflowWarned.set(false);   // 队列已腾出 ⇒ 恢复溢出告警能力
                log.info("MQ 恢复，补偿重放完成: 本次投出 {} 条, trigger={}, pending={}",
                        delivered, trigger, pending.size());
            }
        } catch (RuntimeException e) {
            // 兜底：本类契约"绝不抛"（调用方可能是调度线程 / 关停钩子）
            log.warn("MQ 投递补偿重放异常（已兜底，剩余待下次探测）", e);
        } finally {
            flushing.set(false);
        }
    }

    /** 暂存一条（满则丢弃 + **节流** WARNING）。 */
    private void buffer(Pending message, String reason) {
        if (pending.offer(message)) {
            log.debug("MQ 投递暂存待补偿（{}）: desc={}, pending={}",
                    reason, message.desc(), pending.size());
            return;
        }
        if (overflowWarned.compareAndSet(false, true)) {
            // 首次溢出记一条（之后静默，队列腾出后自动恢复告警）——不按条刷日志
            log.warn("MQ 投递补偿缓冲已满，新投递被丢弃（降级，不影响业务；本条之后同类丢弃不再逐条告警）: "
                    + "desc={}, capacity={}", message.desc(), pending.size());
        }
    }

    /**
     * 此刻能否真的去撞 broker：正常时恒真；进入"不可达"后**只在窗口期满时放行一次**
     * （既是探针，也保证"一次故障期间每个周期最多一次连接超时"）。
     */
    private boolean allowAttempt() {
        if (!down) {
            return true;
        }
        return System.currentTimeMillis() - downSinceMillis >= probeIntervalMillis;
    }

    private void markDown() {
        down = true;
        downSinceMillis = System.currentTimeMillis();
    }

    private void markUp() {
        down = false;
    }

    private static ScheduledExecutorService newScheduler() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, PROBE_THREAD_NAME);
            // 守护线程：即使关停流程异常也不阻塞 JVM 退出（对齐 InboxRebuildDebouncer）
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }
}
