package io.github.yjhhhaaa06.videoweb.common.exception;

/**
 * 503 服务繁忙（可重试），**带 {@code Retry-After} 秒数**。
 *
 * <p>为什么不直接用 {@code BusinessException(ErrorCode.SYSTEM_UNAVAILABLE, …)}：
 * 需要把一个**机器可读的退避时长**带到响应头上。只靠消息文本让客户端自己猜退避节奏，
 * 恢复后它可能几分钟才回来一次 —— 那段时间的进度就白白丢了。
 *
 * <p>首个使用者是播放记录上报缓冲（`T51-2`）：Redis 熔断打开时 `/history/report` 返回它，
 * 让客户端按 {@code Retry-After} 线性退避；收到 200 再恢复原频率。
 * 服务端口径见 {@code history-上报缓冲.md} §5；
 * 客户端该怎么做见 {@code history-客户端契约.md} §五。
 */
public class ServiceBusyException extends BusinessException {

    /** 建议客户端等待的秒数（{@code Retry-After}，HTTP 标准头，单位秒）。 */
    private final long retryAfterSeconds;

    public ServiceBusyException(String message, long retryAfterSeconds) {
        super(ErrorCode.SYSTEM_UNAVAILABLE, message);
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
