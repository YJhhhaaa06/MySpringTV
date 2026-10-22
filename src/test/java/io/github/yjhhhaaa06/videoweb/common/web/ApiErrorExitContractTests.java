package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **三个错误出口的同一契约**（`T56`）：控制器层 / 鉴权过滤器 / 限流过滤器产出的错误响应，
 * 必须是**同一个形状**——同一份信封字段、同一内容类型、都带请求标识头。
 *
 * <h2>三个出口为什么不能合并成一个方法</h2>
 * {@code JwtAuthFilter}（401/403）与 {@code RateLimitFilter}（429）在 {@code DispatcherServlet}
 * **之前**短路，{@code @RestControllerAdvice} 物理上覆盖不到（口径见
 * {@code architecture/tech/api/接口契约.md} §2.1）。所以"三处合一"合的是**产物**，不是**时机**：
 * 时机各就各位（红线：401/403 必须仍在过滤器内），产物一律由 {@code ApiErrorWriter} 出。
 *
 * <h2>期望值从哪来（不抄实现）</h2>
 * 每条断言的期望值都由 {@code architecture/tech/api/接口契约.md} §2.1 的口径推出，
 * 与实现无关：失败体 = {@code {code,msg}}（**没有** {@code data}）、{@code code} 取
 * {@link ErrorCode} 的枚举名、{@code msg} 取该码的默认文案、内容类型 JSON、所有错误响应都带
 * {@code X-Request-Id}（16 位十六进制，见 {@code RequestIdFilter#newRequestId}）。
 *
 * <h2>★ 为什么"同形"用**同一个序列化骨架**断言，而不是"两条响应体逐字节相等"</h2>
 * 三条出口产生的是**不同的码**（{@code PARAM_INVALID} / {@code AUTH_UNAUTHORIZED} / {@code RATE_LIMITED}），
 * 逐字节相等在数学上不可能。所以判别"同一个 writer"的方式是钉住**唯一的合法骨架**：
 * {@code {"code":"<码>","msg":"<文案>"}}——字段集合、字段顺序、无 {@code data}/{@code errors}。
 * 任何一处自己拼装（多一个 {@code timestamp}、少一个 {@code msg}、把 {@code code} 变回数字、
 * 把 {@code data:null} 带上）都会破坏这个骨架。反向验证的做法与结果见 `T56` 回写。
 *
 * <h2>为什么本类要改小桶参数</h2>
 * 429 是**唯一**能证明限流出口也走同一 writer 的手段；基线的桶是 1M/百万每秒，
 * 打不空也补得回。这里把补充速率调到 1/s 并直接写一个空桶
 * （格式 {@code "tokens:lastRefillMs"}，即契约本身），于是"下一枚令牌要等 1 秒"是可推导的。
 */
@TestPropertySource(properties = {
        // 基线是 1M 桶 + 1M/s 补充（见 AbstractIntegrationTest）——这里必须压到"空桶不会立刻补满"。
        "video.rate-limit.server.capacity=1000",
        "video.rate-limit.server.refill-per-sec=1",
        "video.rate-limit.user.authed.capacity=1000",
        "video.rate-limit.user.authed.refill-per-sec=1",
        "video.rate-limit.user.anon.capacity=1000",
        "video.rate-limit.user.anon.refill-per-sec=1",
        "video.rate-limit.cost.default-base=1",
        // 信号②（缓存 miss 差额补扣）与本次断言无关，关掉以免冷态请求改变桶里的小数部分。
        "video.rate-limit.cost.miss-multiplier=1.0",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class ApiErrorExitContractTests extends AbstractHttpIntegrationTest {

    /** 请求标识形态：16 位小写十六进制（`RequestIdFilter#newRequestId` 的公开契约，日志夹具同款正则）。 */
    private static final Pattern REQUEST_ID = Pattern.compile("^[0-9a-f]{16}$");

    /** 唯一的合法失败体骨架（§2.1）。中文文案里不会有引号，故用这个窄正则即可判别多字段/少字段。 */
    private static final Pattern FAILURE_BODY = Pattern.compile("^\\{\"code\":\"[A-Z][A-Z0-9_]*\",\"msg\":\"[^\"]*\"}$");

    /** 直写桶（夹具）。格式 {@code "tokens:lastRefillMs"} 与实现共用是**故意**的（格式即契约）。 */
    private static final String ANON_KEY = "ratelimit:anon:127.0.0.1";
    private static final String SERVER_KEY = "ratelimit:server";

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clearBuckets() {
        Set<String> keys = redis.keys("ratelimit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ==================== 逐出口：形状 + 请求标识头 ====================

    @Test
    @DisplayName("出口①控制器层：400 失败信封 = {code,msg}，内容类型 JSON，带 X-Request-Id")
    void 控制器层出口() {
        // 三个字段全不合法 ⇒ @Valid 拦下 ⇒ 400 PARAM_INVALID（advice 出口）。
        ResponseEntity<String> resp = post("/user/register",
                Map.of("phone", "1", "username", "x", "password", "1"), null);

        assertFailureEnvelope(resp, 400, ErrorCode.PARAM_INVALID);
        assertThat(Envelope.msg(resp)).as("校验失败的人读摘要（字段级定位归 T57 的 errors[]）").isNotBlank();
    }

    @Test
    @DisplayName("出口②鉴权过滤器：401 失败信封同形，且 401 仍在过滤器内短路")
    void 鉴权过滤器出口() {
        // 未带令牌访问受保护端点 ⇒ 在 JwtAuthFilter 短路（不是控制器层抛的）。
        // 判别力来自 RateLimitFilterContractTests：同一请求不消耗任何限流令牌。
        ResponseEntity<String> resp = post("/user/changePassword", Map.of(), null);

        assertFailureEnvelope(resp, 401, ErrorCode.AUTH_UNAUTHORIZED);
        assertThat(Envelope.msg(resp)).isEqualTo(ErrorCode.AUTH_UNAUTHORIZED.getMessage());
    }

    @Test
    @DisplayName("出口③限流过滤器：429 失败信封同形 + Retry-After（唯一带该头的非 503 出口）")
    void 限流过滤器出口() {
        seedEmptyBuckets();

        ResponseEntity<String> resp = get("/start", null);

        assertFailureEnvelope(resp, 429, ErrorCode.RATE_LIMITED);
        assertThat(resp.getHeaders().getFirst(ApiErrorWriter.HEADER_RETRY_AFTER))
                .as("429 必须带 Retry-After（秒）；丢了客户端只能盲猜退避节奏")
                .isNotNull()
                .matches("\\d+");
        assertThat(Long.parseLong(resp.getHeaders().getFirst(ApiErrorWriter.HEADER_RETRY_AFTER)))
                .as("空桶 + 1 枚/秒 ⇒ 补齐至少 1 秒").isGreaterThanOrEqualTo(1L);
    }

    // ==================== 跨出口：同一个骨架 ====================

    @Test
    @DisplayName("★ 三出口的失败体骨架完全一致（字段集/顺序/无 data）——任一处自拼装即破")
    void 三出口骨架一致() {
        // ⚠️ 顺序有讲究：429 是**被制造出来的**（先把桶清空），若提前 seed 就会把前两个请求也打成 429。
        ResponseEntity<String> controllerExit = post("/user/register",
                Map.of("phone", "1", "username", "x", "password", "1"), null);
        ResponseEntity<String> authExit = post("/user/changePassword", Map.of(), null);
        seedEmptyBuckets();
        ResponseEntity<String> rateLimitExit = get("/start", null);

        List<ResponseEntity<String>> responses = List.of(controllerExit, authExit, rateLimitExit);
        assertThat(responses).extracting(r -> r.getStatusCode().value())
                .as("三条出口各触发一次（400 / 401 / 429）")
                .containsExactly(400, 401, 429);
        for (ResponseEntity<String> resp : responses) {
            assertThat(resp.getBody())
                    .as("失败体只允许 {code,msg} 这一个骨架（§2.1）")
                    .matches(FAILURE_BODY);
            assertThat(resp.getHeaders().getFirst("X-Request-Id"))
                    .as("错误响应也必须能对上服务端日志（X-Request-Id）")
                    .matches(REQUEST_ID);
        }
    }

    @Test
    @DisplayName("成功响应同样带 X-Request-Id（证明该头来自链最外层的过滤器，不是错误出口补的）")
    void 成功响应也带请求标识() {
        ResponseEntity<String> resp = get("/start", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.code(resp)).isEqualTo("OK");
        assertThat(resp.getHeaders().getFirst("X-Request-Id")).matches(REQUEST_ID);
    }

    // ==================== 辅助 ====================

    /** 断言"某出口的错误响应符合 §2.1 的失败体契约"。 */
    private void assertFailureEnvelope(ResponseEntity<String> resp, int expectedStatus, ErrorCode expectedCode) {
        assertThat(resp.getStatusCode().value()).isEqualTo(expectedStatus);

        MediaType contentType = resp.getHeaders().getContentType();
        assertThat(contentType).as("错误响应必须有 JSON 内容类型").isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        // ⚠️ 不断言 charset 的**存在**：两条通道的 Content-Type 文本本来就可能不同——
        //    servlet 直接写要显式 setCharacterEncoding，Spring 的 Jackson 转换器则按 RFC 8259
        //    （JSON 默认 UTF-8）省略 charset 参数。断言"若声明了就必须是 UTF-8"，
        //    真正的可读性由下面「中文 msg 必须原样取回」来证（乱码会当场红）。
        assertThat(contentType.getCharset() == null || StandardCharsets.UTF_8.equals(contentType.getCharset()))
                .as("若声明 charset 就必须是 UTF-8，实际 %s", contentType).isTrue();

        JsonNode body = Envelope.parse(resp);
        assertThat(body.size()).as("失败体只有 code / msg 两个字段：%s", resp.getBody()).isEqualTo(2);
        assertThat(body.has("code")).isTrue();
        assertThat(body.has("msg")).isTrue();
        assertThat(Envelope.hasDataField(resp)).as("失败体不得出现 data（NON_NULL 的失败分支）").isFalse();

        assertThat(Envelope.code(resp)).isEqualTo(expectedCode.getCode());
        assertThat(Envelope.msg(resp)).isNotBlank();

        assertThat(resp.getHeaders().getFirst("X-Request-Id"))
                .as("错误响应必须带 X-Request-Id（%d 出口）", expectedStatus)
                .matches(REQUEST_ID);
    }

    /** 把两个桶写成"刚补充完、存量 0"——下一枚令牌要等 1/rate 秒（本类已把 rate 压到 1/s）。 */
    private void seedEmptyBuckets() {
        String now = String.valueOf(System.currentTimeMillis());
        redis.opsForValue().set(ANON_KEY, "0.0:" + now);
        redis.opsForValue().set(SERVER_KEY, "0.0:" + now);
    }
}
