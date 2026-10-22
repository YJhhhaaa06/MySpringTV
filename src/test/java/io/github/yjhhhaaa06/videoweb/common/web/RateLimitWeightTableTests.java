package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.common.config.RateLimitProperties;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 端点权重表（成本信号①）的契约测试（限流专项 T26；口径的唯一事实源 =
 * {@code architecture/tech/rate-limit/限流.md} §四）。
 *
 * <h2>三个用例各证什么</h2>
 * <ul>
 *   <li>{@link #登记端点按权重扣费()}：登记端点**按表值**扣（注册 = 5）——
 *       判别面是"5 而不是兜底的 1"；</li>
 *   <li>{@link #未登记端点落兜底权重()}：未登记 ⇒ {@code default-base}（★ 不是 0，
 *       "漏配 = 不限"正是要防的）；</li>
 *   <li>★ {@link #完整性_全部非GET端点都已登记()}：扫描全部 handler 映射 ⇒ **非 GET 必须全部登记**
 *       ——把"权重表随代码演进不漂移"变成会失败的测试（新端点漏配 ⇒ 变红，逼一次定价决策）。</li>
 * </ul>
 *
 * <h2>夹具口径（与 T25 契约测试同款对冲）</h2>
 * 桶参数整体调小（cap=50、rate=1/s）——基线类的大桶（10^6 级 cap/rate）会把字面量播种值**瞬间补满**
 * （{@code min} 截断只有在 cap 小时才吸收补充量）；播种值 = cap ⇒ 补充被截断 ⇒ 扣减结果**精确可推**
 * （同 T25 的"新桶起步"手法）。仅钉与本类语义相关的两键：{@code default-base=1}（兜底口径）与
 * {@code miss-multiplier=1.0}（**隔离信号②**——本类只测"按权重扣费"；缓存 miss 差额补扣的契约归
 * {@code RateLimitMissPenaltyTests}）。
 *
 * <h2>★ 反向验证指向</h2>
 * 把过滤器成本改回固定取 {@code default-base}（不查表）⇒ 用例 1 必红；把兜底权重改成 0
 * ⇒ 用例 2 必红；从 {@code application.yaml} 删掉任一非 GET 端点 ⇒ 用例 3 必红。
 */
@TestPropertySource(properties = {
        // 桶参数调小（覆盖基类 1M 基线）：cap=50、rate=1/s——播种=cap ⇒ 补充被 min 截断 ⇒ 断言精确。
        "video.rate-limit.server.capacity=50",
        "video.rate-limit.server.refill-per-sec=1",
        "video.rate-limit.user.authed.capacity=50",
        "video.rate-limit.user.authed.refill-per-sec=1",
        "video.rate-limit.user.anon.capacity=50",
        "video.rate-limit.user.anon.refill-per-sec=1",
        "video.rate-limit.cost.default-base=1",
        "video.rate-limit.cost.miss-multiplier=1.0",
        // ★ T27：任务自付费接线后，异步重建链会在执行处扣服务器桶——本类断言小桶绝对值，
        //   关掉本上下文的 MQ 消费者，防"夹具设值与断言之间"被异步扣减污染。
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class RateLimitWeightTableTests extends AbstractHttpIntegrationTest {

    /** 键名字面量硬编码（防 {@code CacheKeys} 改名导致测试静默失联，同 T25 手法）。 */
    private static final String ANON_KEY = "ratelimit:anon:127.0.0.1";

    /** 播种值 = 桶容量（cap=50）：补充被 {@code min} 截断 ⇒ 扣减结果精确可推。 */
    private static final double SEED = 50;

    /** 非 GET 端点的**实际总数**（本类扫描面的 sanity 下界；新增端点后只增不减）。 */
    private static final int EXPECTED_NON_GET_AT_LEAST = 39;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RateLimitProperties properties;

    /** ★ 必须指名标准 MVC 的那个：同为该类型的还有 actuator 的 {@code controllerEndpointHandlerMapping}。 */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @BeforeEach
    void clearRateLimitBuckets() {
        Set<String> keys = redis.keys("ratelimit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private void seedAnonBucket() {
        redis.opsForValue().set(ANON_KEY, SEED + ":" + System.currentTimeMillis());
    }

    private double tokensOf(String raw) {
        assertThat(raw).as("桶必须被写过（否则下面的 oracle 读不到账）").isNotNull();
        return Double.parseDouble(raw.substring(0, raw.indexOf(':')));
    }

    // ==================== 用例 1：登记端点按权重扣 ====================

    @Test
    @DisplayName("登记端点按权重扣：注册 = 5（不是兜底的 1）——期望值取自真实配置表")
    void 登记端点按权重扣费() {
        // 前置钉住：本用例的 oracle 是"表值 5"——若有人改了表而没改用例意图，这里先红
        assertThat(properties.cost().base())
                .as("注册端点必须已登记且为 5（锚点：匿名可达的认证入口比普通写贵）")
                .containsEntry("POST /user/register", 5);
        seedAnonBucket();

        ResponseEntity<String> resp = post("/user/register",
                Map.of("phone", "13800002601", "username", "t26w", "password", "Password123"), null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        // 播种值即桶当前值（补充被 min 截断）⇒ 允许的请求扣全款 5，精确可推
        assertThat(tokensOf(redis.opsForValue().get(ANON_KEY)))
                .as("注册按权重 5 扣（若为 default 的 1 ⇒ 说明查表没生效）")
                .isEqualTo(SEED - 5, within(1e-9));
    }

    // ==================== 用例 2：未登记 ⇒ 兜底 ====================

    @Test
    @DisplayName("未登记端点落 default-base（不是 0）：未映射路径也过闸、按兜底单位扣")
    void 未登记端点落兜底权重() {
        seedAnonBucket();

        // ⚠️ 后缀用**固定字母**：数字串（如 nanoTime）会被 SensitiveDataTests 的 `1[3-9]\d{9}`
        //    扫描误判为"明文手机号"（未映射路径的 ERROR 行会落进 system.log——2026-10-16 实测）。
        ResponseEntity<String> resp = get("/no-such-page-t26-probe", null);

        // ⚠️ 未映射路径本仓响应 500（NoResourceFoundException 落 catch-all 的既有行为）——
        //    本用例不钉状态码，只钉"过闸 + 按兜底权重扣"（T25 同款口径）。
        assertThat(resp.getStatusCode().value()).as("未被闸门拦下").isNotEqualTo(429);
        assertThat(tokensOf(redis.opsForValue().get(ANON_KEY)))
                .as("未登记路由必须落 default-base = 1，而不是 0（0 = 变相不限）")
                .isEqualTo(SEED - 1, within(1e-9));
    }

    // ==================== 用例 3：完整性（扫描全部 handler 映射） ====================

    @Test
    @DisplayName("★ 完整性：全部非 GET 端点都已登记权重（新端点漏配 ⇒ 本用例变红）")
    void 完整性_全部非GET端点都已登记() {
        Set<String> missing = new TreeSet<>();
        Set<String> methodUnlimited = new TreeSet<>();
        int seen = 0;
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (pattern.startsWith("/error")) {
                    continue;   // Spring 默认错误分发（BasicErrorController），不是业务端点
                }
                if (methods.isEmpty()) {
                    // 方法级 @RequestMapping 未限定 HTTP 方法（匹配所有方法）——本用例无法判定它是否
                    // 需要登记 ⇒ 显式报出，逼调用方改用 @GetMapping/@PostMapping（否则是一条扫描盲区）
                    methodUnlimited.add(pattern);
                    continue;
                }
                for (RequestMethod method : methods) {
                    if (!需要登记(method)) {
                        continue;   // GET / HEAD / OPTIONS / TRACE：读一律吃兜底，不登记（口径见 yaml 注释）
                    }
                    seen++;
                    String key = method.name() + " " + pattern;
                    if (!properties.cost().base().containsKey(key)) {
                        missing.add(key);
                    }
                }
            }
        }

        // sanity：扫描面必须真的看到端点（introspection 失效 ⇒ 本用例会"全绿"但什么都没查——假绿防线）
        assertThat(seen)
                .as("扫描面 sanity：handler 映射里应看到全部非 GET 端点（本仓当前 ≥ 38；T47 加 /history/report|play）")
                .isGreaterThanOrEqualTo(EXPECTED_NON_GET_AT_LEAST);
        assertThat(missing)
                .as("非 GET 端点必须全部登记（漏配 ⇒ 只吃 default-base = 1，权重静默失效）")
                .isEmpty();
        assertThat(methodUnlimited)
                .as("存在'方法级未限定 HTTP 方法'的映射——本检查无法判定其是否需要登记，"
                        + "请改用 @GetMapping/@PostMapping（否则它是权重表完整性的一条盲区）")
                .isEmpty();
    }

    /** 需要登记的"写方法"：非 GET 的方法面（本仓当前只有 POST，规则面向未来）。 */
    private static boolean 需要登记(RequestMethod method) {
        return switch (method) {
            case GET, HEAD, OPTIONS, TRACE -> false;
            case POST, PUT, PATCH, DELETE -> true;
        };
    }
}
