package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 播放记录**上报缓冲**配置（`R-19` / `T51-1`）。
 *
 * <p>口径唯一事实源 = {@code architecture/domain/history/history.md} §十二。本类只承载"可调的时间与批量"，
 * 语义（何时算闲、何时落库、失败怎么办）在 {@code HistoryReportBuffer} 与 {@code HistoryFlushService}。
 *
 * <p>★ 为什么单独一段 {@code video.history.buffer.*} 而不是塞进 {@code video.cache.*}：
 * 这些参数**不是缓存参数**（TTL 只是兜底不作废机制、闲置阈值是业务语义），混进缓存段会让
 * "改缓存 TTL"波及到"多久算用户退出了"这类业务判断。
 *
 * <p>★ <b>唯一的不变式（构造期校验）</b>：{@code bufferTtl > idleThreshold + pollInterval + maxRetention}。
 * 一条上报"变闲"后最坏还要再等一个轮询周期才被捡起，之后才进入重试窗口 —— 三项缺一不可。
 * 否则缓冲可能在"还没轮到轮询"或"还在重试窗口内"就自己过期 —— 那是**静默丢数据**，
 * 且外部只看得到"进度偶尔回退"，无法归因。故宁可启动即拒（对齐 {@code CoinCacheProperties} 的构造期校验）。
 *
 * @param idleThreshold 距最后一次上报多久算"用户已离开"（兜底落库的判定窗口，默认 60s）
 * @param pollInterval  轮询周期（默认 15s；**只影响兜底延迟**，不影响正常退出的可见性）
 * @param bufferTtl     单用户缓冲的存活时长（默认 30m；纯兜底，不应早于 {@code idleThreshold + maxRetention}）
 * @param maxRetention  一条缓冲的最大滞留时长（超过即丢弃 + WARN，防"永远写不进去"的数据永久占内存）
 * @param flushBatchSize 一次落库的**行数**上限（按行不是按用户）
 * @param idleBatchSize  一轮轮询最多取多少个"够闲"的用户
 */
@ConfigurationProperties(prefix = "video.history.buffer")
public record HistoryBufferProperties(Duration idleThreshold,
                                      Duration pollInterval,
                                      Duration bufferTtl,
                                      Duration maxRetention,
                                      int flushBatchSize,
                                      int idleBatchSize) {

    public HistoryBufferProperties {
        requirePositive("video.history.buffer.idle-threshold", idleThreshold);
        requirePositive("video.history.buffer.poll-interval", pollInterval);
        requirePositive("video.history.buffer.buffer-ttl", bufferTtl);
        requirePositive("video.history.buffer.max-retention", maxRetention);
        if (flushBatchSize <= 0) {
            throw new IllegalArgumentException("video.history.buffer.flush-batch-size 必须为正数: " + flushBatchSize);
        }
        if (idleBatchSize <= 0) {
            throw new IllegalArgumentException("video.history.buffer.idle-batch-size 必须为正数: " + idleBatchSize);
        }
        // ★ 必须把 pollInterval 也算进来：一条上报"变闲"后，最坏要再等**一个轮询周期**才被捡起
        // （轮询是周期性的，不是精确在 idleThreshold 时刻触发），之后还有 maxRetention 的重试窗口。
        // 漏掉 pollInterval ⇒ 可以配出 "ttl=71m / idle=60s / poll=6h" 这种"能启动但缓冲先过期"的组合。
        Duration earliestRemoval = idleThreshold.plus(pollInterval).plus(maxRetention);
        if (bufferTtl.compareTo(earliestRemoval) <= 0) {
            throw new IllegalArgumentException(
                    "video.history.buffer.buffer-ttl 必须大于 idle-threshold + poll-interval + max-retention"
                            + "（否则缓冲会在轮到轮询 / 重试窗口内就过期 ⇒ 静默丢数据）: ttl=" + bufferTtl
                            + ", 三者之和=" + earliestRemoval);
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " 必须为正");
        }
    }
}
