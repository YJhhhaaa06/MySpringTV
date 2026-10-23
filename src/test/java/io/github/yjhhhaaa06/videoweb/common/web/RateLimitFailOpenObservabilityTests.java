package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.cache.RateLimitRedisOps;
import io.github.yjhhhaaa06.videoweb.common.cache.RateLimitScriptException;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.LogCapture;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * fail-open 的**日志档位**与**指标口径**（限流专项 T28；{@code architecture/tech/rate-limit/限流.md} §五）。
 *
 * <h2>为什么这条必须单独钉</h2>
 * 两种失败都放行是 T25 的行为契约（{@code RateLimitFailOpenTests}），但**放行之后**两条路径
 * 长得一模一样：都是 200、都是"没限住"。它们的区别只剩两个可观测面：
 * <table>
 *   <caption>两条 fail-open 的可区分性</caption>
 *   <tr><th>注入</th><th>日志档位</th><th>持栈</th><th>决策指标</th></tr>
 *   <tr><td>{@link CacheUnavailableException}（外部故障）</td><td>WARN</td><td>否</td><td>不记</td></tr>
 *   <tr><td>{@link RateLimitScriptException}（**自身缺陷**）</td><td><b>ERROR</b></td><td>是</td><td>不记</td></tr>
 * </table>
 * ★ 若两档合并（都 WARN 或都 ERROR），"限流器自己坏了"就会被伪装成"Redis 挂了"⇒
 * **限流静默消失而无人知**（{@code architecture/tech/rate-limit/限流.md} §五 的三档口径）。
 *
 * <h2>★ 反向验证</h2>
 * <ul>
 *   <li>把 {@code CacheUnavailableException} 的档位改成 ERROR（或把异常传给 warn ⇒ 带栈）⇒
 *       {@link #redis不可达_WARN结论行_不持栈_不记决策指标()} 必红；</li>
 *   <li>把 {@code RateLimitScriptException} 的档位降成 WARN ⇒
 *       {@link #脚本异常_ERROR档_带栈()} 必红；</li>
 *   <li>在 fail-open 分支里补记决策指标 ⇒ 两个用例的"指标零增量"断言必红。</li>
 * </ul>
 *
 * <h2>夹具说明</h2>
 * 打桩在**任何 Redis 命令之前**抛出 ⇒ 不需要清桶（一个字都没写）；但指标是**上下文级**共享对象，
 * 断言一律用**增量**。{@code auto-startup=false} 关掉本上下文的 MQ 消费者：任务自付费会在
 * 异步线程扣服务器桶（与指标无关，但与本类"请求是唯一动作"的前提对齐，同 T27 起的开关规则）。
 */
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@Tag("resilience")
class RateLimitFailOpenObservabilityTests extends AbstractHttpIntegrationTest {

    @MockitoSpyBean
    private RateLimitRedisOps ops;

    @Autowired
    private MeterRegistry registry;

    @BeforeEach
    void resetSpy() {
        reset(ops);
    }

    private void stubFailure(RuntimeException failure) {
        doThrow(failure).when(ops).tryAcquire(anyString(), anyString(), any(), any(),
                anyDouble(), anyDouble());
    }

    // ==================== 指标读数（增量断言） ====================

    private double decisions(String scope, String result) {
        Counter counter = registry.find(RateLimitMetrics.METRIC_DECISIONS)
                .tag(RateLimitMetrics.TAG_SCOPE, scope)
                .tag(RateLimitMetrics.TAG_RESULT, result)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long acquireCount() {
        Timer timer = registry.find(RateLimitMetrics.METRIC_ACQUIRE).timer();
        return timer == null ? 0L : timer.count();
    }

    /** 四个决策计数 + 过闸耗时的快照（指标是上下文级共享对象 ⇒ 断言一律看增量）。 */
    private record MetricSnapshot(double userAllowed, double userRejected,
                                  double serverAllowed, double serverRejected, long acquire) {

        MetricSnapshot minus(MetricSnapshot before) {
            return new MetricSnapshot(userAllowed - before.userAllowed,
                    userRejected - before.userRejected,
                    serverAllowed - before.serverAllowed,
                    serverRejected - before.serverRejected,
                    acquire - before.acquire);
        }
    }

    private MetricSnapshot snapshot() {
        return new MetricSnapshot(decisions("user", "allowed"), decisions("user", "rejected"),
                decisions("server", "allowed"), decisions("server", "rejected"), acquireCount());
    }

    /**
     * fail-open **不产生任何决策样本**：判都没判出来，记什么都是假的。
     * 过闸耗时同理不采样——含 Jedis 超时的样本会把"Redis 挂了"读成"限流器变慢"。
     */
    private void assertNoDecisionSamples(MetricSnapshot delta, String when) {
        assertThat(delta.userAllowed()).as(when + "：user/allowed 不得增长").isEqualTo(0.0);
        assertThat(delta.userRejected()).as(when + "：user/rejected 不得增长").isEqualTo(0.0);
        assertThat(delta.serverAllowed()).as(when + "：server/allowed 不得增长").isEqualTo(0.0);
        assertThat(delta.serverRejected()).as(when + "：server/rejected 不得增长").isEqualTo(0.0);
        assertThat(delta.acquire()).as(when + "：过闸耗时也不得采样").isEqualTo(0L);
    }

    // ==================== 两档 ====================

    @Test
    @DisplayName("Redis 不可达：WARN 结论行、不持栈、不落桶 key；且不记决策指标")
    void redis不可达_WARN结论行_不持栈_不记决策指标() {
        stubFailure(new CacheUnavailableException("模拟 Redis 不可达"));
        MetricSnapshot before = snapshot();

        try (LogCapture system = LogCapture.root()) {
            system.clear();

            ResponseEntity<String> resp = get("/api/v1/contents/recommended", null);

            assertThat(resp.getStatusCode().value()).as("fail-open：不得让业务接口失败（D-5）").isEqualTo(200);
            String line = system.awaitLine(l -> l.contains("限流器不可用"),
                    "Redis 不可达的 WARN 结论行", Duration.ofSeconds(3));
            assertThat(line).as("外部故障 ⇒ WARN（运维知道，但不是事故）").contains("level=WARN ");
            assertThat(line)
                    .as("结论行不持栈 —— 判据用**真实制表符**（写成 \"\\\\tat \" 会变成恒真的假绿）")
                    .doesNotContain("\tat ");
            // T58 契约变更 + 依据《接口契约.md》§2.3：探针端点 /start 资源化为 /api/v1/contents/recommended，
            // WARN 结论行的 path 字段取自真实请求 URI ⇒ 期望值随之更新。
            assertThat(line).contains("path=/api/v1/contents/recommended").contains("userId=- ");
            assertThat(line)
                    .as("不落原始 key（本用例的原因文案是合成串；真实 Redis 异常文案可能带 **Redis 端点**"
                            + "地址——基础设施地址、非用户 PII，口径见 architecture/tech/rate-limit/限流.md §五）")
                    .doesNotContain("ratelimit:").doesNotContain("127.0.0.1");
            assertThat(system.countLinesContaining("限流脚本/返回值异常"))
                    .as("★ 档位必须可区分：外部故障不得同时被记成自身缺陷").isZero();
        }

        assertNoDecisionSamples(snapshot().minus(before), "Redis 不可达");
    }

    @Test
    @DisplayName("★ 脚本/返回值解析异常：ERROR 档且**带栈**（限流静默消失的唯一出口）")
    void 脚本异常_ERROR档_带栈() {
        stubFailure(new RateLimitScriptException("模拟脚本返回值无法解析"));
        MetricSnapshot before = snapshot();

        try (LogCapture system = LogCapture.root()) {
            system.clear();

            ResponseEntity<String> resp = get("/api/v1/contents/recommended", null);

            assertThat(resp.getStatusCode().value()).as("自身缺陷也放行（500 会让限流器成单点故障）").isEqualTo(200);
            String line = system.awaitLine(l -> l.contains("限流脚本/返回值异常"),
                    "脚本异常的 ERROR 行", Duration.ofSeconds(3));
            assertThat(line).as("★ 自身缺陷 ⇒ ERROR（若降成 WARN 就与「Redis 挂了」不可分）")
                    .contains("level=ERROR ");
            assertThat(line)
                    .as("这是缺陷 ⇒ 必须带栈（与 WARN 结论行相反）")
                    .contains("\tat ");
            assertThat(line).as("带栈也走 %maskedMsg 出口，且不落 key").doesNotContain("ratelimit:");
            assertThat(system.countLinesContaining("限流器不可用"))
                    .as("★ 档位必须可区分：自身缺陷不得同时被记成外部故障").isZero();
        }

        assertNoDecisionSamples(snapshot().minus(before), "脚本异常");
    }
}
