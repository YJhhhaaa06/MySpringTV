package io.github.yjhhhaaa06.videoweb.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link HistoryBufferProperties} 的构造期不变式（纯单测，不起 Spring 上下文）。
 *
 * <p>★ 这些不变式守的是**静默丢数据**：缓冲比"轮到轮询 / 重试窗口"更早过期时，系统不报任何错，
 * 只是偶尔让用户续播回到旧位置 —— 没有用例钉住就永远查不出来。
 */
class HistoryBufferPropertiesTests {

    @Test
    @DisplayName("默认量级（60s / 15s / 30m / 10m）合法")
    void defaultsAreValid() {
        assertThatCode(() -> new HistoryBufferProperties(
                Duration.ofSeconds(60), Duration.ofSeconds(15),
                Duration.ofMinutes(30), Duration.ofMinutes(10), 200, 50))
                .doesNotThrowAnyException();
    }

    /**
     * ★ 判别力：校验里若漏掉 {@code pollInterval}，这组参数（71m > 60s + 10m）会被放行，
     * 而轮询每 6h 才看一次 ⇒ hash 早在 71m 过期、崩溃会话进度全丢。
     */
    @Test
    @DisplayName("TTL 只覆盖 idle+retention 而不覆盖轮询周期 ⇒ 启动即拒")
    void ttlMustAlsoCoverPollInterval() {
        assertThatThrownBy(() -> new HistoryBufferProperties(
                Duration.ofSeconds(60), Duration.ofHours(6),
                Duration.ofMinutes(71), Duration.ofMinutes(10), 200, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("poll-interval");
    }

    @Test
    @DisplayName("批量参数非正 ⇒ 启动即拒（否则轮询取 0 个 / 永远不落库）")
    void batchSizesMustBePositive() {
        assertThatThrownBy(() -> new HistoryBufferProperties(
                Duration.ofSeconds(60), Duration.ofSeconds(15),
                Duration.ofMinutes(30), Duration.ofMinutes(10), 0, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HistoryBufferProperties(
                Duration.ofSeconds(60), Duration.ofSeconds(15),
                Duration.ofMinutes(30), Duration.ofMinutes(10), 200, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
