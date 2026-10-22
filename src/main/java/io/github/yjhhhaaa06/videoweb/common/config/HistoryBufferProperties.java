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
 * <p>★ <b>唯一的不变式（构造期校验）</b>：{@code bufferTtl > idleThreshold + maxRetention}。
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
        Duration earliestRemoval = idleThreshold.plus(maxRetention);
        if (bufferTtl.compareTo(earliestRemoval) <= 0) {
            throw new IllegalArgumentException(
                    "video.history.buffer.buffer-ttl 必须大于 idle-threshold + max-retention（否则缓冲会在"
                            + "轮到轮询 / 重试窗口内就过期 ⇒ 静默丢数据）: ttl=" + bufferTtl
                            + ", idle-threshold+max-retention=" + earliestRemoval);
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " 必须为正");
        }
    }
}
