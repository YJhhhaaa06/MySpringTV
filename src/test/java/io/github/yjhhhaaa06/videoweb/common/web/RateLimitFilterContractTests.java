package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.common.config.RateLimitProperties;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import io.github.yjhhhaaa06.videoweb.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link RateLimitFilter}（限流闸门）的 **HTTP 级契约测试**（限流专项 T25；
 * 口径见 {@code architecture/tech/限流.md} §三）。
 *
 * <h2>★ 测试参数整体调小 + 覆盖生效守门（对冲"匿名桶退化为全局限流"，见 {@code architecture/tech/限流.md} §三）</h2>
 * MockMvc / 本机 HTTP 的匿名请求 IP 全是 {@code 127.0.0.1} ⇒ 匿名桶在本环境退化为
 * "全局限流"。本类用 {@link TestPropertySource} **内联属性**把桶参数整体调小
 * （cap=50、rate=1/s）——★ T26 起权重表生效（{@code POST /user/register} = 5）⇒ cap 由 4 上调为 50：
 * 本类的 fixture 注册调用必须装得进桶（cap &lt; 5 会让注册本身被 429 拦下）；{@code /start} 等读端点
 * 仍吃 {@code default-base = 1}，各用例的独立 oracle 口径不变。内联属性"父类先、子类后、同键**子类胜**"是 Spring 文档明文的合并规则，
 * 故子类覆盖父类（{@code AbstractIntegrationTest} 的 1M 大桶基线）是**受保证**的行为；
 * 并用 {@link #测试小参数覆盖确实生效()} 守门。★ 之所以必须有守门：本仓 Boot 4.1.1 实测
 * **子类 {@code @DynamicPropertySource} 覆盖不了父类同键值**（父类源胜出）——T25 首版
 * 就是用那条通道写的，守门用例当场抓到 1M 假绿。属性通道的优先级不能靠猜，
 * 守门用例把"覆盖确实生效"变成可复算事实。
 *
 * <h2>期望值的推导口径（不抄实现）</h2>
 * 全部由四个参数按设计语义推出：cap=50、rate=1/s、cost=default-base=1、discount=0.3
 * ⇒ 询问费 0.3、尾款 0.7。桶状态断言**直读 Redis**（键名字面量硬编码——键形状是冻结契约），
 * 并按惰性公式独立复算；复算的时间参照用**脚本自己写回的 {@code lastRefillMs}**（同 T24 手法：
 * 复算与实现共用同一时间差 ⇒ 无时钟近似，容差 1e-9 远严于"删掉某行差整枚令牌"的判别需求）。
 *
 * <h2>★ 链序的实测证明藏在两条用例里</h2>
 * <ul>
 *   <li>{@link #被拒429的访问行code429_链序实测()}：若 {@code AccessLogFilter} 没有包住限流器，
 *       429 短路响应就**没有访问行**——轮询必超时变红；</li>
 *   <li>{@link #未登录访问受保护接口_不耗令牌()}：401 发生在鉴权层（限流器外侧）——
 *       若有人把限流器挪到鉴权外侧，这条会因匿名桶被扣而变红。</li>
 * </ul>
 *
 * <h2>★ 反向验证指向（清单归宿 = {@code architecture/tech/限流.md} §六）</h2>
 * 把闸门改成"只判服务器桶"（删 user 侧条件）⇒ {@link #两桶AND_服务器桶不足_被拒时服务器桶一个字节都不写()}
 * 必红；把"被拒只扣询问费"改错 ⇒ {@link #不够_429信封与RetryAfter_被拒只扣询问费_独立oracle()} 必红；
 * 删掉跳过清单任一条 ⇒ {@link #跳过清单_桶一个字节都不动()} 必红。
 */
@TestPropertySource(properties = {
        // ★ 子类内联属性覆盖父类（AbstractIntegrationTest 的 1M 大桶基线）同键值——
        //   这是 Spring 文档明文的合并规则（父类先、子类后）；基类 @DynamicPropertySource
        //   通道实测会被父类值压住（见基类注释）。
        // ★ T26-C2：三处 capacity 4 → 50 —— 权重表生效后 register = 5，桶必须有头部空间；
        //   rate 保持 1/s（Retry-After 的独立复算依赖它）。
        "video.rate-limit.server.capacity=50",
        "video.rate-limit.server.refill-per-sec=1",
        "video.rate-limit.user.authed.capacity=50",
        "video.rate-limit.user.authed.refill-per-sec=1",
        "video.rate-limit.user.anon.capacity=50",
        "video.rate-limit.user.anon.refill-per-sec=1",
        "video.rate-limit.cost.default-base=1",
        "video.rate-limit.cost.reject-discount=0.3",
        // ★ T26-C3：本类只测闸门（信号①与折扣），固定隔离信号②——yaml 已启用 3.0，
        //   不 pin 的话，冷态请求（如 /start 首次回源）的差额会打到 CAP − COST 类断言上。
        "video.rate-limit.cost.miss-multiplier=1.0",
        // ★ T27：任务自付费接线后，异步重建链会在执行处扣服务器桶——本类断言小桶的绝对值，
        //   关掉本上下文的 MQ 消费者，防"夹具设值与断言之间"被异步扣减污染。
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class RateLimitFilterContractTests extends AbstractHttpIntegrationTest {

    /** 与 {@code @TestPropertySource} 注入值一一对应的 oracle 常量（改一处必改另一处）。 */
    private static final int CAP = 50;
    private static final int RATE = 1;
    private static final double COST = 1.0;
    private static final double FEE = COST * 0.3;

    /** 键名硬编码字面量（防 {@code CacheKeys} 改名导致测试静默失联）。 */
    private static final String ANON_KEY = "ratelimit:anon:127.0.0.1";
    private static final String SERVER_KEY = "ratelimit:server";

    /** 静态探针（同 AccessLogTests 手法：落在 target/test-media，经 /upload/** 服务）。 */
    private static final String UPLOAD_PROBE = "t25-ratelimit-probe.txt";

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RateLimitProperties properties;

    /** 访问输出端 sink（observability 包的夹具是包私有 ⇒ 本类自挂同款；用完必须 close）。 */
    private LogCapture access;

    @BeforeEach
    void attachAccessCapture() {
        access = LogCapture.access();
        access.clear();
    }

    @AfterEach
    void detachAccessCapture() {
        if (access != null) {
            access.close();
        }
    }

    /** 轮询访问行：命中 {@code path=<pathMarker>} 且含 {@code marker} 的那一行（同 AbstractObservabilityTest）。 */
    private String awaitAccessLine(String pathMarker, String marker) {
        return access.awaitLine(
                line -> line.contains(" path=" + pathMarker + " ") && line.contains(marker),
                "access 行 path=" + pathMarker + " 且含 " + marker,
                Duration.ofSeconds(3));
    }

    @BeforeEach
    void clearRateLimitBuckets() {
        Set<String> keys = redis.keys("ratelimit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ==================== 夹具与 oracle 辅助（直读侧） ====================

    private String rawOf(String key) {
        return redis.opsForValue().get(key);
    }

    /** 直写一个桶（夹具）。与实现共用 {@code "tokens:lastRefillMs"} 格式是**故意**的（格式即契约）。 */
    private void putBucket(String key, double tokens, long lastRefillMs) {
        redis.opsForValue().set(key, tokens + ":" + lastRefillMs);
    }

    /** 哨兵值：够大、够独特——"一个字节都不动"断言的参照物。 */
    private static final String SENTINEL = "99.5:1700000000000";

    private void seedSentinels() {
        putBucket(ANON_KEY, 99.5, 1700000000000L);
        putBucket(SERVER_KEY, 99.5, 1700000000000L);
    }

    /** 按惰性公式复算桶值（时间参照 = 脚本写回的 lastRefillMs ⇒ 与实现同源、无时钟近似）。 */
    private double expectedTokensAfterRefill(double seeded, long seededAtMs, String writtenRaw) {
        long last = Long.parseLong(writtenRaw.substring(writtenRaw.indexOf(':') + 1));
        return Math.min(CAP, seeded + (last - seededAtMs) * RATE / 1000.0);
    }

    private double tokensOf(String raw) {
        return Double.parseDouble(raw.substring(0, raw.indexOf(':')));
    }

    // ==================== 覆盖守门 ====================

    @Test
    @DisplayName("★ 守门：小参数覆盖确实生效（覆盖失效 ⇒ 本类所有用例在 1M 大桶下假绿）")
    void 测试小参数覆盖确实生效() {
        assertThat(properties.user().anon().capacity()).isEqualTo(CAP);
        assertThat(properties.user().anon().refillPerSec()).isEqualTo(RATE);
        assertThat(properties.server().capacity()).isEqualTo(CAP);
        assertThat(properties.cost().missMultiplier())
                .as("信号②隔离 pin 生效（失效 ⇒ 冷态请求的差额会污染本类断言）")
                .isEqualTo(1.0);
    }

    // ==================== 契约①：够令牌放行 ====================

    @Test
    @DisplayName("够令牌：放行 200，两桶各扣 cost，Retry-After 不出现")
    void 够令牌放行_两桶按公式扣减() {
        putBucket(ANON_KEY, CAP, System.currentTimeMillis());   // 满桶起步
        putBucket(SERVER_KEY, CAP, System.currentTimeMillis());

        ResponseEntity<String> resp = get("/start", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.code(resp)).isEqualTo(200);

        String anonRaw = rawOf(ANON_KEY);
        assertThat(anonRaw).as("放行后匿名桶必须被写过").isNotNull();
        assertThat(tokensOf(anonRaw)).isEqualTo(CAP - COST, within(1e-9));
        assertThat(tokensOf(rawOf(SERVER_KEY))).isEqualTo(CAP - COST, within(1e-9));
        assertThat(resp.getHeaders().getFirst("Retry-After"))
                .as("放行路径不写 Retry-After 头").isNull();
    }

    // ==================== 契约②：不够 ⇒ 429 + 信封 + Retry-After（独立 oracle） ====================

    @Test
    @DisplayName("不够：429 + 业务码 429 + Retry-After 独立复算；被拒只扣询问费（尾款不扣）")
    void 不够_429信封与RetryAfter_被拒只扣询问费_独立oracle() {
        long t0 = System.currentTimeMillis();
        putBucket(ANON_KEY, 0.2, t0);
        putBucket(SERVER_KEY, CAP, System.currentTimeMillis());

        ResponseEntity<String> resp = get("/start", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(429);
        assertThat(Envelope.code(resp)).as("业务码与 HTTP 对齐（T23 契约）").isEqualTo(429);
        assertThat(Envelope.msg(resp)).isEqualTo("请求过于频繁");

        // 独立 oracle：被拒 ⇒ 用户桶只扣询问费（0.3），尾款（0.7）不扣；
        // 复算的 elapsed 用脚本写回的 lastRefillMs 反推（同 T24 手法）。
        String anonRaw = rawOf(ANON_KEY);
        assertThat(anonRaw).as("被拒也必须写用户桶（询问费）").isNotNull();
        double expected = expectedTokensAfterRefill(0.2, t0, anonRaw) - FEE;
        assertThat(tokensOf(anonRaw)).isEqualTo(expected, within(1e-9));

        // Retry-After 独立复算：= ceil((cost − 桶存量) / rate)——补到 cost 所需时间向上取整。
        long expectedRetry = (long) Math.ceil((COST - tokensOf(anonRaw)) / RATE);
        assertThat(resp.getHeaders().getFirst("Retry-After"))
                .as("Retry-After 必须等于按桶存量独立复算的秒数")
                .isEqualTo(String.valueOf(expectedRetry));
    }

    // ==================== 契约③：两桶 AND ====================

    @Test
    @DisplayName("两桶 AND：用户桶够、服务器桶不够 ⇒ 429；服务器桶一个字节都不写")
    void 两桶AND_服务器桶不足_被拒时服务器桶一个字节都不写() {
        long t0 = System.currentTimeMillis();
        putBucket(ANON_KEY, CAP, t0);            // 用户桶满
        putBucket(SERVER_KEY, 0.2, t0);          // 服务器桶不足
        String serverBefore = rawOf(SERVER_KEY);

        ResponseEntity<String> resp = get("/start", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(429);
        // ★ 字节级对照：被拒的请求在服务器桶上"一个字节都不写"（非对称扣减的另一半）。
        assertThat(rawOf(SERVER_KEY))
                .as("被拒时服务器桶必须原样保留（含 lastRefillMs）——写回任何字节都算破约")
                .isEqualTo(serverBefore);
        // 用户桶仍被扣询问费：50 − 0.3 = 49.7（新桶 refill=cap，无 elapsed 依赖，精确可推）。
        assertThat(tokensOf(rawOf(ANON_KEY))).isEqualTo(CAP - FEE, within(1e-9));
        // Retry-After 由不足的一侧（服务器桶）决定：ceil((1 − 0.2)/1) = 1。
        assertThat(resp.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    // ==================== 契约④：登录与匿名分桶 ====================

    @Test
    @DisplayName("登录与匿名分桶：登录请求扣 user:{id} 桶，匿名桶不动")
    void 登录与匿名分桶() {
        String token = registerAndGetToken("13800002501", "rlUser", "Password123");
        long userId = userIdOf("13800002501");
        String userKey = "ratelimit:user:" + userId;
        // 对照快照：注册本身就是匿名请求（匿名桶已被扣）——"登录请求不动匿名桶"
        // 要靠"登录请求前后快照不变"来证，而不是"键不存在"。
        String anonBefore = rawOf(ANON_KEY);

        ResponseEntity<String> resp = get("/start", token);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(tokensOf(rawOf(userKey))).as("登录桶按 cost 扣减").isEqualTo(CAP - COST, within(1e-9));
        assertThat(rawOf(ANON_KEY))
                .as("登录请求绝不消耗匿名桶额度（对照快照不变）")
                .isEqualTo(anonBefore);
    }

    // ==================== 覆盖范围 ====================

    @Test
    @DisplayName("跳过清单（/、/index.html、/static/**、/actuator/**、/upload/**、/favicon.ico）：桶一个字节都不动")
    void 跳过清单_桶一个字节都不动() {
        seedSentinels();
        Path probe = Path.of(AbstractIntegrationTest.testMediaRoot())
                .resolve(UPLOAD_PROBE);
        try {
            Files.createDirectories(probe.getParent());
            Files.writeString(probe, "t25 probe", StandardCharsets.UTF_8);

            // 依次命中跳过清单的每一条；每条之后都复核两个桶字节不变（哨兵即对照）。
            hitSkippedAndAssertUntouched("/");
            hitSkippedAndAssertUntouched("/index.html");
            hitSkippedAndAssertUntouched("/static/css/common.css");
            hitSkippedAndAssertUntouched("/actuator/health");
            hitSkippedAndAssertUntouched("/upload/" + UPLOAD_PROBE);
            // /favicon.ico：文件不存在 ⇒ NoResourceFoundException ⇒ 本仓当前经 catch-all 变 500——
            // 那是既有错误处理行为，与本任务无关；此处只证一件事：不耗令牌。
            hitSkippedAndAssertUntouched("/favicon.ico");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // 清理尽力而为
            }
        }
    }

    private void hitSkippedAndAssertUntouched(String path) {
        get(path, null);
        assertThat(rawOf(ANON_KEY)).as("跳过清单路径 %s 不得动匿名桶", path).isEqualTo(SENTINEL);
        assertThat(rawOf(SERVER_KEY)).as("跳过清单路径 %s 不得动服务器桶", path).isEqualTo(SENTINEL);
    }

    @Test
    @DisplayName("未映射路径（扫描流）也过闸：两桶都被扣（放行 oracle），未被闸门拦下")
    void 未映射路径404也过闸() {
        // ⚠️ 后缀用**固定字母**（不用 System.nanoTime()）：数字串会被 SensitiveDataTests 的
        //    `1[3-9]\d{9}` 扫描误判为"明文手机号"（2026-10-16 全量回归实际红过一次——
        //    GlobalExceptionHandler 把未映射路径的 NoResourceFoundException 落进 system.log）。
        ResponseEntity<String> resp = get("/no-such-page-t25-probe", null);

        // ⚠️ 状态码钉的是**既有行为**而非本任务的契约面：本仓未映射路径由
        // NoResourceFoundException 落进 GlobalExceptionHandler 的 catch-all ⇒ 500。
        // T25 的契约只有一条：这类请求**过闸**（"404 扫描流正是要防的对象之一"）。
        // "过闸"的判定不能只看用户桶（被拒也扣询问费）——服务器桶**只在放行时扣**，
        // 故两桶各扣 cost 才是"放行"的完整 oracle；再加一条"未被 429 拦下"。
        assertThat(resp.getStatusCode().value()).as("未被闸门拦下").isNotEqualTo(429);

        String anonRaw = rawOf(ANON_KEY);
        assertThat(anonRaw).as("用户桶过闸被扣").isNotNull();
        assertThat(tokensOf(anonRaw)).isEqualTo(CAP - COST, within(1e-9));
        assertThat(tokensOf(rawOf(SERVER_KEY))).as("服务器桶只在放行时扣").isEqualTo(CAP - COST, within(1e-9));
    }

    @Test
    @DisplayName("★ 未登录访问受保护接口（401 短路在限流器之前）：不耗令牌")
    void 未登录访问受保护接口_不耗令牌() {
        ResponseEntity<String> resp = post("/user/changePassword", Map.of(), null);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        Set<String> keys = redis.keys("ratelimit:*");
        assertThat(keys)
                .as("被鉴权拒绝的请求到不了限流器（拍板 A「先鉴权后扣费」）——限流键必须一个都没有")
                .isNullOrEmpty();
    }

    @Test
    @DisplayName("/api/admin（管理员）也过闸：200 且登录桶被扣 cost")
    void 管理员路径也过闸() {
        String token = registerAndGetToken("13800002502", "rlAdmin", "Password123");
        long userId = userIdOf("13800002502");
        jdbcTemplate.update("UPDATE users SET role = 1 WHERE id = ?", userId);

        ResponseEntity<String> resp = get("/api/admin/content/list", token);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(tokensOf(rawOf("ratelimit:user:" + userId)))
                .as("管理员请求无白名单，同样过闸").isEqualTo(CAP - COST, within(1e-9));
    }

    // ==================== 访问日志（链序实测） ====================

    @Test
    @DisplayName("★ 429 的访问行 code=429（AccessLogFilter 包住限流器的链序实测）")
    void 被拒429的访问行code429_链序实测() {
        putBucket(ANON_KEY, 0.2, System.currentTimeMillis());
        putBucket(SERVER_KEY, CAP, System.currentTimeMillis());

        ResponseEntity<String> resp = get("/start", null);
        assertThat(resp.getStatusCode().value()).isEqualTo(429);

        // 轮询而非直读：访问行写在过滤器 finally 里，可能晚于响应到达客户端。
        // ★ 若 AccessLogFilter 不在限流器外侧，这条 429 根本不会有访问行 ⇒ 轮询超时 ⇒ 本用例红。
        String line = awaitAccessLine("/start", "status=429");
        assertThat(line)
                .as("429 必须补记业务码——否则访问行记成 code=0，三态塌成两态")
                .contains("code=429 ");
        assertThat(line).contains("userId=- ");
    }
}
