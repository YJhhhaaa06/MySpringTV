package io.github.yjhhhaaa06.videoweb.observability;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheMetrics;
import io.github.yjhhhaaa06.videoweb.content.AbstractContentIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存打点 → Actuator / Micrometer（第三批 T2 / 账 B3）。
 *
 * <h2>为什么这条要"前端到后端"地测一遍</h2>
 * B3 的原始诉求只是"能看见"，但它最容易被做成"加了一行 counter.increment() 就宣布完成"——
 * 而那行代码是否真的被**真实读路径**走到、指标是否真的能被 Actuator 读到，都没人验证。
 * 本类把三件事连起来：
 * <pre>
 *   真实 HTTP 请求（/search/IdSearch） → CacheAside 三态读 → 计数器自增 → /actuator/metrics 可读
 * </pre>
 *
 * <h2>判据：miss 与 hit_data 两次读必须各自增长</h2>
 * 第一次读该内容必然 miss（清过 Redis）⇒ {@code miss} 与 {@code load} 各 +1；
 * 第二次读必然命中 ⇒ {@code hit_data} +1。若有人把打点接到别处（或接错事件），这里立刻红。
 *
 * <h2>覆盖边界（如实声明）</h2>
 * 打点只在共享三态引擎 {@code CacheAside}（content 域）；like / follow / comment / feed
 * 四域自持三态机、未接线——理由与边界登记见 {@code CacheMetrics} 类注释与《决策留痕表》。
 */
@Tag("observability")
class CacheMetricsTests extends AbstractContentIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("★ 一次 miss + 一次 hit：打点自增，且 /actuator/metrics 可读")
    void 缓存事件可在Actuator看到() {
        String phone = "13800009901";
        registerAndGetToken(phone, "obs-metrics-author");
        long authorId = userIdOf(phone);
        long contentId = insertVideoContent(authorId, "obs-metrics-content");

        double missBefore = cacheEventCount("miss");
        double loadBefore = cacheEventCount("load");
        double hitBefore = cacheEventCount("hit_data");

        // ① 冷读：Redis 已清 ⇒ 必然 miss
        ResponseEntity<String> first = getQuery("/search/IdSearch", query("contentId", String.valueOf(contentId)), null);
        assertThat(first.getStatusCode().value()).isEqualTo(200);

        assertThat(cacheEventCount("miss"))
                .as("冷读必须记一次 miss")
                .isGreaterThan(missBefore);
        assertThat(cacheEventCount("load"))
                .as("miss 之后必然回源装载，记一次 load")
                .isGreaterThan(loadBefore);

        // ② 热读：数据 key 已回填 ⇒ 必然 hit_data
        ResponseEntity<String> second = getQuery("/search/IdSearch", query("contentId", String.valueOf(contentId)), null);
        assertThat(second.getStatusCode().value()).isEqualTo(200);

        assertThat(cacheEventCount("hit_data"))
                .as("第二次读必须命中数据 key")
                .isGreaterThan(hitBefore);

        // ③ 指标经 Actuator 可抓取（这是 B3 的验收面：不是"代码里有 counter"，而是"运维能读到"）
        ResponseEntity<String> metrics = get("/actuator/metrics/" + CacheMetrics.METRIC_NAME
                + "?tag=" + CacheMetrics.TAG_EVENT + ":hit_data", null);
        assertThat(metrics.getStatusCode().value()).isEqualTo(200);
        assertThat(metrics.getBody()).contains("COUNT");
    }

    @Test
    @DisplayName("指标名与事件 tag 与 TV CacheStats 六事件同名（口径冻结）")
    void 事件口径与TV同名() {
        assertThat(CacheMetrics.METRIC_NAME).isEqualTo("cache.events");
        assertThat(CacheMetrics.Event.values())
                .extracting(CacheMetrics.Event::tag)
                .containsExactlyInAnyOrder(
                        "hit_data", "hit_empty", "miss", "load", "degrade", "write_fail");
    }

    /** 当前计数（meter 尚不存在时记 0——"没发生过某事件"是合法状态，不该让测试爆）。 */
    private double cacheEventCount(String tag) {
        Counter counter = meterRegistry.find(CacheMetrics.METRIC_NAME)
                .tag(CacheMetrics.TAG_EVENT, tag)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
