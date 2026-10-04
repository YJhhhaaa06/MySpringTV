package io.github.yjhhhaaa06.videoweb.config;

import io.github.yjhhhaaa06.videoweb.common.config.FeedDeliveryProperties;
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

    /** 投递线程池参数（第三批 T5 / 账 B8）：独立前缀 {@code video.feed.delivery}。 */
    @Autowired
    private FeedDeliveryProperties deliveryProperties;

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

    // ==================== video.feed.delivery.*（第三批 T5 / 账 B8） ====================

    @Test
    @DisplayName("★投递线程池：嵌套键 {@code video.feed.delivery.queue-capacity} 真的绑到了独立 record")
    void 投递线程池参数绑定正确() {
        // 这条用例的真正价值：record 只认**自身前缀下的扁平键**，把 delivery 那段塞进
        // video.feed 的扁平记录里会被 ignoreUnknownFields 静默忽略并绑成 0 —— 于是
        // requirePositive 在启动期才炸（且报错点是"必须为正数: 0"，离病因很远）。
        // 这里把"嵌套键确实被独立 record 接住"钉死。
        assertThat(deliveryProperties.queueCapacity())
                .as("异步投递队列容量默认 1000（TV feed.delivery.queueCapacity）")
                .isEqualTo(1000);
        assertThat(deliveryProperties.drainTimeout())
                .as("投递 drain 上界默认 5 秒（TV feed.delivery.drainTimeoutMillis=5000）")
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("投递线程池：非正容量 / 非正 drain 上界拒绝启动")
    void 投递线程池参数非正即拒() {
        assertThatThrownBy(() -> new FeedDeliveryProperties(0, Duration.ofSeconds(5)))
                .as("容量非正会让投递永远无法入队").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeedDeliveryProperties(1000, Duration.ZERO))
                .as("drain 上界为 0 = 关停等待无上界").isInstanceOf(IllegalArgumentException.class);
    }
}
