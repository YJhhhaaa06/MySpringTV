package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import io.github.yjhhhaaa06.videoweb.support.LogCapture;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流的**可观测面**（限流专项 T28；口径见 {@code architecture/tech/rate-limit/限流.md} §五）。
 *
 * <h2>本类钉住两件事</h2>
 * <ol>
 *   <li><b>指标是逐桶的真值</b>：{@code rate.limit.decisions{scope,result}} 一次请求记**两条**样本。
 *       判别力最强的用例是 {@link #服务器桶挡_只有server那条记rejected()}——若有人把 scope 写死成
 *       {@code user}、或把"被拒"粗暴地记到两个 scope 上，它立刻红。</li>
 *   <li><b>拒绝日志的形态</b>：WARN 结论行、不持栈、<b>不落桶 key、不落匿名 IP</b>、登录态有 {@code userId}。
 *       "不落 IP"用**字节级否证**（{@code doesNotContain("127.0.0.1")}）——不是"我没看见"。</li>
 * </ol>
 *
 * <h2>夹具口径</h2>
 * 小桶（cap=50 / rate=1 / cost=default-base=1 / discount=0.3）+ 信号② pin 成 1.0（关闭）：
 * 本类只关心"一次请求产出几条样本"，冷读回源的差额会污染计数（同 {@code RateLimitFilterContractTests}）。
 * 匿名桶在集成测试里全是 {@code 127.0.0.1} ⇒ 等价全局限流（D-3），故每测清桶、每用例自己设夹具。
 * <p>指标断言一律用**增量**（{@code MeterRegistry} 是上下文级共享对象，跨用例累积）。
 *
 * <h2>★ 反向验证（实测口径见 {@code architecture/tech/rate-limit/限流.md} §五 与 {@code RateLimitMetrics}）</h2>
 * <ul>
 *   <li>删掉 {@code recordDecisions} 调用 ⇒ 本类 **5 例**红（四条指标用例 + Actuator 用例）；</li>
 *   <li>把拒绝日志的调用删掉 ⇒ 两条日志用例红；把档位改成 ERROR ⇒ 档位断言红；</li>
 *   <li>把 {@code recordDecisions} 两侧都记 {@code allowed}（= scope 退化成请求级）⇒
 *       {@link #用户桶挡_只有user那条记rejected()} / {@link #服务器桶挡_只有server那条记rejected()} 红；</li>
 *   <li>{@code blockedBy} 的 {@code both} 分支退化 ⇒ {@link #两桶都不够_blockedBy取both()} 红。</li>
 * </ul>
 */
@TestPropertySource(properties = {
        // ★ 小桶（同 RateLimitFilterContractTests 的口径）：register = 5，桶要有头部空间
        "video.rate-limit.server.capacity=50",
        "video.rate-limit.server.refill-per-sec=1",
        "video.rate-limit.user.authed.capacity=50",
        "video.rate-limit.user.authed.refill-per-sec=1",
        "video.rate-limit.user.anon.capacity=50",
        "video.rate-limit.user.anon.refill-per-sec=1",
        "video.rate-limit.cost.default-base=1",
        "video.rate-limit.cost.reject-discount=0.3",
        // ★ 信号② pin 关闭：本类数的是"决策样本条数"，冷读的差额补扣不产生决策样本，
        //   但会让桶值变化、影响下一条用例的夹具推导——隔离掉更省心（同契约类的做法）。
        "video.rate-limit.cost.miss-multiplier=1.0",
        // ★ T27 起的桶断言规则：异步重建链会扣服务器桶，关掉本上下文的消费者防污染
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class RateLimitObservabilityTests extends AbstractHttpIntegrationTest {

    /** 与 {@code @TestPropertySource} 一一对应的 oracle 常量。 */
    private static final int CAP = 50;
    private static final String ANON_KEY = "ratelimit:anon:127.0.0.1";
    private static final String SERVER_KEY = "ratelimit:server";

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private StringRedisTemplate redis;

    /** root 输出端的 sink（限流器的 logger 走 root）；用完必须 close。 */
    private LogCapture system;

    @BeforeEach
    void attachLogCapture() {
        system = LogCapture.root();
        system.clear();
    }

    @AfterEach
    void detachLogCapture() {
        if (system != null) {
            system.close();
        }
    }

    @BeforeEach
    void clearBuckets() {
        Set<String> keys = redis.keys("ratelimit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ==================== 夹具 ====================

    /** 直写一个桶（与实现共用 {@code "tokens:lastRefillMs"} 格式——格式即契约）。 */
    private void putBucket(String key, double tokens, long lastRefillMs) {
        redis.opsForValue().set(key, tokens + ":" + lastRefillMs);
    }

    /** 决策计数（meter 尚不存在 ⇒ 0.0：**没发生过**是合法状态，不该让测试爆）。 */
    private double decisions(RateLimitMetrics.Scope scope, String result) {
        Counter counter = registry.find(RateLimitMetrics.METRIC_DECISIONS)
                .tag(RateLimitMetrics.TAG_SCOPE, scope.tag())
                .tag(RateLimitMetrics.TAG_RESULT, result)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long acquireCount() {
        Timer timer = registry.find(RateLimitMetrics.METRIC_ACQUIRE).timer();
        return timer == null ? 0L : timer.count();
    }

    private String rejectionLine() {
        return system.awaitLine(line -> line.contains("限流拒绝"),
                "限流拒绝的 WARN 结论行", Duration.ofSeconds(3));
    }

    // ==================== 指标：逐桶真值 ====================

    @Test
    @DisplayName("放行：user 与 server 各记一条 allowed；过闸命令耗时被采样")
    void 放行_两个scope各记一条allowed() {
        long now = System.currentTimeMillis();
        putBucket(ANON_KEY, CAP, now);      // 满桶起步 ⇒ 放行（无时钟近似：cap 充足，refill 被 min 截断）
        putBucket(SERVER_KEY, CAP, now);
        double userAllowedBefore = decisions(RateLimitMetrics.Scope.USER, RateLimitMetrics.RESULT_ALLOWED);
        double serverAllowedBefore = decisions(RateLimitMetrics.Scope.SERVER, RateLimitMetrics.RESULT_ALLOWED);
        long acquireBefore = acquireCount();

        ResponseEntity<String> resp = get("/api/v1/contents/recommended", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(decisions(RateLimitMetrics.Scope.USER, RateLimitMetrics.RESULT_ALLOWED) - userAllowedBefore)
                .as("放行 ⇒ scope=user 记一条 allowed").isEqualTo(1.0);
        assertThat(decisions(RateLimitMetrics.Scope.SERVER, RateLimitMetrics.RESULT_ALLOWED) - serverAllowedBefore)
                .as("放行 ⇒ scope=server 记一条 allowed（一次请求两条样本）").isEqualTo(1.0);
        assertThat(acquireCount() - acquireBefore)
                .as("一次请求 ⇒ 一次过闸命令耗时样本").isEqualTo(1L);
    }

    @Test
    @DisplayName("★ 用户桶挡：只有 user 那条记 rejected，server 仍记 allowed（两桶判定不同不是异常）")
    void 用户桶挡_只有user那条记rejected() {
        long now = System.currentTimeMillis();
        // ⚠️ 瓶颈桶用**负数**起步（而不是 0.2）：rate=1/s 下 0.2 距离判定线只有 0.8s 余量，
        //   慢机会偶发翻转成"补够了"；-5 把余量拉到 6s，且不影响"谁挡的"这个判定。
        putBucket(ANON_KEY, -5, now);       // -5 − 0.3 < 尾款 0.7 ⇒ 用户桶不过
        putBucket(SERVER_KEY, CAP, now);    // 服务器桶满 ⇒ 过
        double userRejectedBefore = decisions(RateLimitMetrics.Scope.USER, RateLimitMetrics.RESULT_REJECTED);
        double userAllowedBefore = decisions(RateLimitMetrics.Scope.USER, RateLimitMetrics.RESULT_ALLOWED);
        double serverRejectedBefore = decisions(RateLimitMetrics.Scope.SERVER, RateLimitMetrics.RESULT_REJECTED);
        double serverAllowedBefore = decisions(RateLimitMetrics.Scope.SERVER, RateLimitMetrics.RESULT_ALLOWED);

        ResponseEntity<String> resp = get("/api/v1/contents/recommended", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(429);
        assertThat(decisions(RateLimitMetrics.Scope.USER, RateLimitMetrics.RESULT_REJECTED) - userRejectedBefore)
                .as("用户桶是瓶颈 ⇒ scope=user 记 rejected").isEqualTo(1.0);
        assertThat(decisions(RateLimitMetrics.Scope.USER, RateLimitMetrics.RESULT_ALLOWED) - userAllowedBefore)
                .as("同一时刻不可能又 allowed 又 rejected").isEqualTo(0.0);
        assertThat(decisions(RateLimitMetrics.Scope.SERVER, RateLimitMetrics.RESULT_ALLOWED) - serverAllowedBefore)
                .as("服务器桶自身够 ⇒ 它那条记 allowed（否则 scope 维度就是假的）").isEqualTo(1.0);
        assertThat(decisions(RateLimitMetrics.Scope.SERVER, RateLimitMetrics.RESULT_REJECTED) - serverRejectedBefore)
                .as("服务器桶没挡 ⇒ 它不得记 rejected").isEqualTo(0.0);
    }

    @Test
    @DisplayName("★ 服务器桶挡：只有 server 那条记 rejected —— scope 真值的判别面")
    void 服务器桶挡_只有server那条记rejected() {
        long now = System.currentTimeMillis();
        putBucket(ANON_KEY, CAP, now);      // 用户桶满 ⇒ 过
        putBucket(SERVER_KEY, -5, now);     // -5 < cost 1 ⇒ 服务器桶不过（负数起步：留足时间余量）
        double userRejectedBefore = decisions(RateLimitMetrics.Scope.USER, RateLimitMetrics.RESULT_REJECTED);
        double userAllowedBefore = decisions(RateLimitMetrics.Scope.USER, RateLimitMetrics.RESULT_ALLOWED);
        double serverRejectedBefore = decisions(RateLimitMetrics.Scope.SERVER, RateLimitMetrics.RESULT_REJECTED);

        ResponseEntity<String> resp = get("/api/v1/contents/recommended", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(429);
        assertThat(decisions(RateLimitMetrics.Scope.SERVER, RateLimitMetrics.RESULT_REJECTED) - serverRejectedBefore)
                .as("服务器桶是瓶颈 ⇒ scope=server 记 rejected").isEqualTo(1.0);
        assertThat(decisions(RateLimitMetrics.Scope.USER, RateLimitMetrics.RESULT_ALLOWED) - userAllowedBefore)
                .as("用户桶自身够 ⇒ 它那条记 allowed").isEqualTo(1.0);
        assertThat(decisions(RateLimitMetrics.Scope.USER, RateLimitMetrics.RESULT_REJECTED) - userRejectedBefore)
                .as("★ 若 scope 被写死成 user（或「拒绝就两侧都记 rejected」），本断言必红")
                .isEqualTo(0.0);
        assertThat(rejectionLine()).as("日志侧同口径：挡的是服务器桶").contains("blockedBy=server");
    }

    @Test
    @DisplayName("两桶都不够：blockedBy=both，且两侧各记一条 rejected（不塌成单一 side）")
    void 两桶都不够_blockedBy取both() {
        long now = System.currentTimeMillis();
        putBucket(ANON_KEY, -5, now);
        putBucket(SERVER_KEY, -5, now);
        double userRejectedBefore = decisions(RateLimitMetrics.Scope.USER, RateLimitMetrics.RESULT_REJECTED);
        double serverRejectedBefore = decisions(RateLimitMetrics.Scope.SERVER, RateLimitMetrics.RESULT_REJECTED);

        ResponseEntity<String> resp = get("/api/v1/contents/recommended", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(429);
        assertThat(decisions(RateLimitMetrics.Scope.USER, RateLimitMetrics.RESULT_REJECTED) - userRejectedBefore)
                .as("两桶都不够 ⇒ 两侧各记一条 rejected（这就是「桶级失败数 ≠ 请求数」的来源）")
                .isEqualTo(1.0);
        assertThat(decisions(RateLimitMetrics.Scope.SERVER, RateLimitMetrics.RESULT_REJECTED) - serverRejectedBefore)
                .isEqualTo(1.0);
        assertThat(rejectionLine())
                .as("三值之一的 both 也要被走到（否则「blockedBy 是契约」只是空话）")
                .contains("blockedBy=both");
    }

    @Test
    @DisplayName("指标名与两个维度取值是冻结契约（改字 = 抓取端静默失联）")
    void 指标名与维度取值冻结() {
        assertThat(RateLimitMetrics.METRIC_DECISIONS).isEqualTo("rate.limit.decisions");
        assertThat(RateLimitMetrics.METRIC_ACQUIRE).isEqualTo("rate.limit.acquire");
        assertThat(new String[]{RateLimitMetrics.Scope.USER.tag(), RateLimitMetrics.Scope.SERVER.tag()})
                .containsExactly("user", "server");
        assertThat(new String[]{RateLimitMetrics.RESULT_ALLOWED, RateLimitMetrics.RESULT_REJECTED})
                .containsExactly("allowed", "rejected");
    }

    // ==================== 日志：拒绝 = WARN 结论行 ====================

    @Test
    @DisplayName("★ 拒绝日志：WARN 结论行、不持栈、不落桶 key、匿名不落 IP")
    void 拒绝日志_WARN结论行_不落key不落IP() {
        long now = System.currentTimeMillis();
        putBucket(ANON_KEY, -5, now);
        putBucket(SERVER_KEY, CAP, now);

        ResponseEntity<String> resp = get("/api/v1/contents/recommended", null);
        assertThat(resp.getStatusCode().value()).isEqualTo(429);
        assertThat(Envelope.code(resp)).isEqualTo("RATE_LIMITED");

        String line = rejectionLine();
        assertThat(line).as("拒绝是「预期业务拒绝」⇒ WARN（对齐《可观测》§六）").contains("level=WARN ");
        assertThat(line).as("结论行不持栈（WARN 不当运维事故报）").doesNotContain("\tat ");
        // T58 契约变更 + 依据《接口契约.md》§2.3：探针端点 /start 资源化为 /api/v1/contents/recommended，
        // 访问/拒绝日志的 path 字段取自真实请求 URI ⇒ 期望值随之更新。
        assertThat(line).contains("path=/api/v1/contents/recommended").contains("userId=- ").contains("blockedBy=user");
        assertThat(line)
                .as("★ 不落原始 key（匿名桶 key 里有 IP —— 这条同时守住「匿名不落 IP」）")
                .doesNotContain("ratelimit:").doesNotContain("127.0.0.1");
        assertThat(system.countLinesContaining("限流拒绝"))
                .as("一次拒绝恰好一条结论行（不重复记）").isEqualTo(1L);
    }

    @Test
    @DisplayName("登录态拒绝：结论行记 userId（不是原始 key，也不带匿名 IP）")
    void 登录态拒绝_结论行记userId() {
        String token = registerAndGetToken("13800002601", "rlObs", "Password123");
        long userId = userIdOf("13800002601");
        // 直接把这个用户的桶压到不足（注册本身是匿名请求，不会建用户桶）
        putBucket("ratelimit:user:" + userId, -5, System.currentTimeMillis());
        system.clear();     // 注册的里程碑日志不进本用例的断言窗口

        ResponseEntity<String> resp = get("/api/v1/contents/recommended", token);

        assertThat(resp.getStatusCode().value()).isEqualTo(429);
        String line = rejectionLine();
        assertThat(line).contains("userId=" + userId + " ").contains("blockedBy=user");
        assertThat(line)
                .as("登录态拒绝也不得把桶 key 落盘")
                .doesNotContain("ratelimit:user:").doesNotContain("127.0.0.1");
    }

    // ==================== 指标可被抓取（Actuator） ====================

    @Test
    @DisplayName("指标经 /actuator/metrics 可读到（B3 同款验收面：不是「代码里有 counter」，而是「运维能读到」）")
    void 指标经Actuator可读() {
        long now = System.currentTimeMillis();
        putBucket(ANON_KEY, -5, now);
        putBucket(SERVER_KEY, CAP, now);
        // ★ 先取增量基线：`@TestPropertySource` 与 `RateLimitFilterContractTests` 逐条相同 ⇒
        //   两者**共用同一个上下文与 MeterRegistry**（Spring TestContext 缓存），
        //   `(scope=user,result=rejected)` 这个 meter 很可能已被别的类建出来——
        //   只断言"200 + 含 COUNT"的话，删掉 `recordDecisions` 也照样绿（恒真断言）。
        double rejectedBefore = decisions(RateLimitMetrics.Scope.USER, RateLimitMetrics.RESULT_REJECTED);

        assertThat(get("/api/v1/contents/recommended", null).getStatusCode().value()).isEqualTo(429);

        assertThat(decisions(RateLimitMetrics.Scope.USER, RateLimitMetrics.RESULT_REJECTED) - rejectedBefore)
                .as("本用例自己的请求必须真的把该计量推了一格").isEqualTo(1.0);

        ResponseEntity<String> resp = get("/actuator/metrics/" + RateLimitMetrics.METRIC_DECISIONS
                + "?tag=" + RateLimitMetrics.TAG_SCOPE + ":" + RateLimitMetrics.Scope.USER.tag()
                + "&tag=" + RateLimitMetrics.TAG_RESULT + ":" + RateLimitMetrics.RESULT_REJECTED, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody())
                .as("带两个 tag 过滤仍能抓到该计量，说明维度真的落在 meter 上")
                .contains("COUNT");
    }
}
