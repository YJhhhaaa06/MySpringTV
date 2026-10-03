package io.github.yjhhhaaa06.videoweb.config;

import io.github.yjhhhaaa06.videoweb.common.config.FeedProperties;
import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S9 新增配置 {@code video.feed.*} 的**绑定契约**测试。
 *
 * <h2>为什么要钉 Duration 的"单位后缀"（SOP 坑 4）</h2>
 * {@code Duration} 属性写**裸数字**会被解析成秒级——{@code 60} 变成 {@code PT60S}（1 分钟）而非 60 分钟，
 * **不报任何配置错误**，只是行为诡异（缓存几乎立刻失效 / 去抖窗口变成 60 秒）。
 * 本类把关键 Duration 的**绑定结果**钉死（{@code 60m} → 60 分钟、{@code 7d} → 7 天），
 * 谁把单位后缀改掉，这里立刻失败。
 *
 * <h2>为什么还要钉"非正数 fail-fast"</h2>
 * 口径沿袭 TV {@code validatePositiveBatch}（"宁可启动即拒"）：窗口尺寸 / 批尺寸非正数会让
 * 分块循环无法前进或窗口退化为读全量，故在 {@link FeedProperties} 的紧凑构造器里**绑定期**拒绝。
 */
class FeedPropertiesBindingTests extends AbstractIntegrationTest {

    @Autowired
    private FeedProperties feedProperties;

    @Test
    @DisplayName("★Duration 必须带单位后缀：inbox-ttl=60m / rebuild-debounce=1s / dlq-ttl=7d")
    void duration单位绑定正确() {
        assertThat(feedProperties.inboxTtl())
                .as("60m 必须是 60 分钟（裸数字 60 会变成 PT60S——1 分钟）")
                .isEqualTo(Duration.ofMinutes(60));
        assertThat(feedProperties.outboxTtl()).isEqualTo(Duration.ofMinutes(60));
        assertThat(feedProperties.rebuildDebounce())
                .as("去抖窗口默认 1 秒（裸数字 1 = PT1S，恰好相同；仍显式钉住）")
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(feedProperties.dlqTtl())
                .as("DLQ 消息 TTL 默认 7 天（TV feed.dlq.ttlMillis=604800000）")
                .isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("窗口 / 批尺寸绑定 TV 默认值（20 / 200 / 300 / 200 / 50 / 200）")
    void 窗口与批尺寸绑定正确() {
        assertThat(feedProperties.inboxWindowPerAuthor()).isEqualTo(20);
        assertThat(feedProperties.inboxWindowMax()).isEqualTo(200);
        assertThat(feedProperties.readWindowMax()).isEqualTo(300);
        assertThat(feedProperties.outboxWindowSize()).isEqualTo(20);
        assertThat(feedProperties.fanoutBatch()).isEqualTo(200);
        assertThat(feedProperties.rebuildAuthorBatch()).isEqualTo(50);
        assertThat(feedProperties.bigvQueryBatch()).isEqualTo(200);
    }

    @Test
    @DisplayName("非正尺寸 / 零长 TTL 拒绝启动（宁可启动即拒，沿袭 TV validatePositiveBatch）")
    void 非正参数拒绝启动() {
        Duration ttl = Duration.ofMinutes(1);
        assertThatThrownBy(() -> new FeedProperties(ttl, 0, 200, 300, 20, ttl, 200, 50, 200,
                Duration.ofSeconds(1), ttl))
                .as("inbox-window-per-author 非正").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeedProperties(ttl, 20, 200, 300, 20, ttl, -1, 50, 200,
                Duration.ofSeconds(1), ttl))
                .as("fanout-batch 非正").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeedProperties(Duration.ZERO, 20, 200, 300, 20, ttl, 200, 50, 200,
                Duration.ofSeconds(1), ttl))
                .as("inbox-ttl 为 0 非法（缓存 TTL 必须为正）").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeedProperties(ttl, 20, 200, 300, 20, ttl, 200, 50, 200,
                Duration.ofMillis(-1), ttl))
                .as("rebuild-debounce 为负非法（0 合法 = 关闭去抖）").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("去抖窗口 0 = 合法（显式关闭去抖）——不得被误判为非正")
    void 去抖窗口零合法() {
        Duration ttl = Duration.ofMinutes(1);
        FeedProperties props = new FeedProperties(ttl, 20, 200, 300, 20, ttl, 200, 50, 200,
                Duration.ZERO, ttl);
        assertThat(props.rebuildDebounce()).isZero();
    }
}
