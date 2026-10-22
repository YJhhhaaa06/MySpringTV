package io.github.yjhhhaaa06.videoweb.history.cache;

import io.github.yjhhhaaa06.videoweb.common.config.HistoryBufferProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 播放记录**上报缓冲的语义层**（`R-19` / `T51-1`）：负责"此刻"与"业务含义"，
 * 命令细节全部委托 {@link HistoryBufferRedisOps}。
 *
 * <p>口径唯一事实源 = {@code history-上报缓冲.md}。
 *
 * <h2>它在整条链路里的位置</h2>
 * <pre>
 * /report  ──record()──> Redis 缓冲          （本类）
 * /flush   ──snapshot() + 落库 + acknowledge()──>
 * 轮询     ──idleUserIds() 取"够闲"的用户，再走同一段
 * /position ──cachedProgress() 读缓冲优先
 * </pre>
 * ★ 本类**不做落库**（T51-1 的边界：不接 Service / DAO）。落库与失败分流在 T51-2。
 *
 * <h2>★ 时钟为什么要能注入</h2>
 * "够不够闲"完全由时间判定；用 {@code System.currentTimeMillis()} 直取会让相关用例只能靠
 * {@code Thread.sleep} —— 那既慢又抖（{@code 60s} 级阈值根本没法测）。故持有 {@link Clock}：
 * 生产用 {@link Clock#systemUTC()}，用例传固定 / 可推进的时钟。
 * ⚠️ **同一个 ts 必须同时用于 hash 值与 dirty score**（写入是一条 Lua，只传一个 ts）——
 * 用两个时钟会让"刚上报"被判成"已闲置"（history-上报缓冲.md} §2）。
 */
@Slf4j
@Component
public class HistoryReportBuffer {

    private final HistoryBufferRedisOps ops;
    private final HistoryBufferProperties props;
    private final Clock clock;

    /** Spring 注入构造：时钟取系统时。 */
    @Autowired
    public HistoryReportBuffer(HistoryBufferRedisOps ops, HistoryBufferProperties props) {
        this(ops, props, Clock.systemUTC());
    }

    /** 受控构造（用例驱动"够闲"判定，不依赖真实计时）。 */
    HistoryReportBuffer(HistoryBufferRedisOps ops, HistoryBufferProperties props, Clock clock) {
        this.ops = ops;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 启动自检：把"缓冲依赖 {@code maxmemory-policy=noeviction}"这条**部署前提**变成可观测的 WARN。
     *
     * <p>★ 为什么必须有它：缓冲与缓存**共用同一 Redis**，而淘汰策略会**静默**杀掉缓冲 ——
     * 缓存丢了能重建，缓冲丢了是数据丢失，且外部只看得到"进度偶尔回退"，无法归因。
     * 代码管不住运维配置，但可以让它不再隐形。
     *
     * <p>⚠️ **绝不阻断启动**：命令被托管 Redis 禁用、连接失败都是合法情形（{@link
     * HistoryBufferRedisOps#maxmemoryPolicy()} 已兜底成 empty）。
     */
    @PostConstruct
    void warnIfBufferMayBeEvicted() {
        Optional<String> policy = ops.maxmemoryPolicy();
        if (policy.isEmpty()) {
            log.debug("播放记录缓冲自检：读不到 maxmemory-policy（托管 Redis 常禁用 CONFIG）⇒ 跳过检查；"
                    + "前提仍是该实例不得淘汰缓冲键");
            return;
        }
        if ("noeviction".equalsIgnoreCase(policy.get())) {
            log.info("播放记录缓冲自检通过：maxmemory-policy=noeviction（缓冲不会被淘汰）");
            return;
        }
        log.warn("★ 播放记录缓冲自检未通过：maxmemory-policy={} ⇒ Redis 内存压力下 history:report:* / "
                        + "history:dirty 可能被淘汰，表现为**进度静默回退**（缓存可重建、缓冲不可）。"
                        + "请把该实例设成 noeviction，或把缓冲迁到独立实例",
                policy.get());
    }

    /**
     * `/report` 的前置探测：缓冲后端此刻是否可用。
     *
     * <p>★ 必须**先判它、再做内容校验**：Redis 熔断时 `loadContentVOs` 会降级查库，
     * 先校验就等于给数据库白白加一次压力，与"故障期别碰 DB"直接冲突（history-上报缓冲.md} §5）。
     */
    public boolean isBackingStoreAvailable() {
        return ops.isCacheAvailable();
    }

    /**
     * 建议客户端等待的秒数（`Retry-After`）。
     *
     * <p>取轮询周期：它正好是"服务端自己会多久重试一次"的节奏，客户端按同一节奏退避即可；
     * 恢复后下一次 200 就会把它拉回正常频率。
     */
    public long retryAfterSeconds() {
        return Math.max(1L, props.pollInterval().toSeconds());
    }

    /**
     * 记录一条上报（`/report` 校验 + clamp 之后调用）。
     *
     * <p>★ 只写 Redis，**不落库**；落库由 `/flush`（退出信号）或空闲轮询兜底完成。
     */
    public void record(long userId, long contentId, int progress) {
        recordAt(userId, contentId, progress, clock.millis());
    }

    /** 带显式接收时刻的写入（用例驱动"够闲"判定；生产路径走 {@link #record}）。 */
    void recordAt(long userId, long contentId, int progress, long receiveTs) {
        ops.write(userId, contentId, progress, receiveTs, props.bufferTtl());
    }

    /**
     * 取一个用户的落库快照（**原始 field → 原始值**，条件删要用它比对）。
     *
     * <p>★ 返回的是"全部 field"而不是"解析成功的那些"：跳过任何 field 就等于它永远删不掉
     * （{@code HLEN > 0} ⇒ 脏成员摘不掉）。解析与丢弃判断在落库层。
     *
     * @return 空 map = 无脏数据（`/flush` 直接成功返回；轮询侧顺带清僵尸成员）
     */
    public Map<String, String> snapshot(long userId) {
        return ops.readRaw(userId);
    }

    /**
     * 落库成功后确认（条件删）。
     *
     * @return 实际删掉的 field 数
     */
    public long acknowledge(long userId, Map<String, String> written) {
        return ops.removeIfUnchanged(userId, written);
    }

    /**
     * 本轮轮询应处理的用户：最后一次上报早于 {@code now - idleThreshold}。
     *
     * <p>★ 这就是"兜底"的全部判定——**正常退出的用户已经走 `/flush` 落库，不在这里**。
     */
    public List<Long> idleUserIds() {
        long cutoff = clock.millis() - props.idleThreshold().toMillis();
        return ops.idleUserIds(cutoff, props.idleBatchSize());
    }

    /**
     * `/position` 用：读缓冲（比库里的新）。未命中 / 值坏 ⇒ {@link OptionalInt#empty()}，
     * 由调用方回落点查 DB。
     *
     * <p>⚠️ Redis 不可用时本方法抛 {@code CacheUnavailableException}（不吞）——
     * "降级查 DB"是读路径的语义决定，属于调用方（T51-3），不是缓冲层的职责。
     */
    public OptionalInt cachedProgress(long userId, long contentId) {
        Optional<HistoryBufferRedisOps.Entry> entry = ops.readOne(userId, contentId);
        return entry.isPresent() ? OptionalInt.of(entry.get().progress()) : OptionalInt.empty();
    }
}
