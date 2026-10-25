package io.github.yjhhhaaa06.videoweb.common.idempotency;

import io.github.yjhhhaaa06.videoweb.common.security.JwtAuthFilter;
import io.github.yjhhhaaa06.videoweb.common.web.ApiErrorWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 指纹**公式本身**的单元证明（写接口幂等专项 `T69`；口径 = 设计篇 §九）。
 *
 * <h2>为什么必须有一份单测：三条性质在 HTTP 层不可观测</h2>
 * <ol>
 *   <li><b>multipart 的指纹非 {@code null}</b>：本仓两条 multipart 端点的路径都是**字面量**
 *       （{@code /contents/videos}、{@code /contents/posts}）且各自有独立 slug ⇒ "路径级指纹"与
 *       "无指纹"在 HTTP 层表现完全相同（同 path 换文件都重放）。唯一能分开两者的地方是
 *       {@code store.complete(...)} 拿到的那个参数。</li>
 *   <li><b>"同一登记、不同 URI" 的 multipart 请求</b>：本仓没有带路径变量的 multipart 端点，
 *       所以真实路由造不出"同 slug 不同 URI"。这里手工构造（URI 与路由模式分开给）——
 *       它正是 {@code POST /contents/{id}/coins} 的形状，只是换成 multipart。</li>
 *   <li><b>JSON 算不出 body ⇒ fp 为 {@code null}</b>（空 body / 达到 {@code fingerprint-bytes}）：
 *       这两种请求到不了业务 2xx，HTTP 用例造不出 {@code DONE}。</li>
 * </ol>
 *
 * <p>⚠️ 断言的是"写进快照的指纹"这个**边界值**，不是内部调用顺序：公式改了这里必红，
 * 而 HTTP 层用例（coin 的"换 {@code {id}} ⇒ 422"）负责证明这个值真的被用来判冲突。
 */
class IdempotencyFingerprintUnitTests {

    private static final IdempotencyProperties PROPERTIES = new IdempotencyProperties(
            new IdempotencyProperties.Ttl(180, 5),
            new IdempotencyProperties.Poll(2, 100),
            new IdempotencyProperties.Limits(8192, 65536));

    // ==================== multipart：仅路径级 ====================

    @Test
    @DisplayName("multipart：指纹非 null（T69 前是 null），且只由 method + 路径决定 —— 换 body 不变、换路径才变")
    void multipart_仅路径级指纹() throws Exception {
        String first = fingerprintOf(multipart("/api/v1/contents/videos", "/api/v1/contents/videos", "第一份载荷"));

        assertThat(first)
                .as("★ multipart 现在必须写**非 null** 的路径级指纹（否则'换端点'这类冲突永远无从判定）")
                .isNotNull()
                .hasSize(64);

        assertThat(fingerprintOf(multipart("/api/v1/contents/videos", "/api/v1/contents/videos", "完全不同的第二份载荷")))
                .as("multipart 指纹不含 body ⇒ 换文件/换标题必须得到**同一个**指纹（这是 §十三 第 3 条的已知代价，不是 bug）")
                .isEqualTo(first);

        // ★ 同一登记（slug=video）下的不同 URI —— 本仓 multipart 路由是字面量、真实流量里到不了，
        //   这里手工构造：proves 路径真的进了公式（若公式只算 method，这两个值会相同）。
        assertThat(fingerprintOf(multipart("/api/v1/contents/videos/77", "/api/v1/contents/videos", "第一份载荷")))
                .as("路径参与 ⇒ 同一 method 下换路径必须换指纹（与 coin 的 {id} 用例同形）")
                .isNotEqualTo(first);
    }

    @Test
    @DisplayName("非 JSON 且无 Content-Type：同样走路径级（不会因为算不出 body 就退化成 null）")
    void 无contentType_仍为路径级指纹() throws Exception {
        MockHttpServletRequest request = base("POST", "/api/v1/contents/videos", "/api/v1/contents/videos");
        request.setContent("raw".getBytes(StandardCharsets.UTF_8));

        assertThat(fingerprintOf(request)).isNotNull().hasSize(64);
    }

    // ==================== JSON：method + 路径 + body ====================

    @Test
    @DisplayName("JSON：同 body 换路径 ⇒ 指纹不同；同路径换 body ⇒ 指纹不同（两条都 feeds coin 的 422 用例）")
    void json_路径与body都参与指纹() throws Exception {
        String base = fingerprintOf(json("/api/v1/contents/1/coins", "/api/v1/contents/{id}/coins", "{\"count\":1}"));

        assertThat(fingerprintOf(json("/api/v1/contents/2/coins", "/api/v1/contents/{id}/coins", "{\"count\":1}")))
                .as("★路由模式相同、URI 不同 ⇒ 指纹必须不同（用 BEST_MATCHING_PATTERN 算就会得到相同值）")
                .isNotEqualTo(base);

        assertThat(fingerprintOf(json("/api/v1/contents/1/coins", "/api/v1/contents/{id}/coins", "{\"count\":2}")))
                .as("同路径换 body ⇒ 指纹不同（用例 4 的机制层证据）")
                .isNotEqualTo(base);

        assertThat(fingerprintOf(json("/api/v1/contents/1/coins", "/api/v1/contents/{id}/coins", "{\"count\":1}")))
                .as("同一份请求逐字节重发 ⇒ 指纹必须相同（否则正常重试会被判 422）")
                .isEqualTo(base);
    }

    @Test
    @DisplayName("JSON 且 body 为空 ⇒ 指纹为 null（★不得退化成'仅路径级'，否则不同 body 会被判成同一份载荷）")
    void json_空body_指纹为null() throws Exception {
        assertThat(fingerprintOf(json("/api/v1/contents/videos", "/api/v1/contents/videos", "")))
                .as("算不出 body 时 fp 必须是 null（= 跳过比对），而不是路径级指纹")
                .isNull();
    }

    @Test
    @DisplayName("JSON 且 body 达到 fingerprint-bytes ⇒ 指纹为 null（不可用截断后的 body 算指纹）")
    void json_body超限_指纹为null() throws Exception {
        // 8192 = 本类 PROPERTIES 的 limits.fingerprint-bytes；封装类超限是静默截断，只能靠长度判定
        String oversized = "x".repeat(8192);

        assertThat(fingerprintOf(json("/api/v1/comments", "/api/v1/comments", oversized)))
                .as("超限 ⇒ null（不是'用截断后的 body 算出来的指纹'）")
                .isNull();
    }

    // ==================== 夹具 ====================

    /**
     * 跑一次完整过滤器，取回写入快照的指纹。
     *
     * <p>store 用 mock：本类只关心"公式产出了什么"，占位/固化本身由集成用例覆盖。
     * chain 会**读完请求体**——模拟 {@code @RequestBody} 解析，否则 JSON 分支的缓存永远是空。
     */
    private String fingerprintOf(MockHttpServletRequest request) throws Exception {
        IdempotencyStore store = mock(IdempotencyStore.class);
        when(store.acquire(anyString(), anyString(), anyInt()))
                .thenReturn(new IdempotencyStore.AcquireResult(IdempotencyStore.Kind.ACQUIRED, null));
        when(store.complete(anyString(), anyString(), anyInt(), any(), any(), any())).thenReturn(true);

        IdempotencyFilter filter = new IdempotencyFilter(PROPERTIES, store,
                new IdempotencyMetrics(new SimpleMeterRegistry()), mock(ApiErrorWriter.class));

        FilterChain chain = mock(FilterChain.class);
        doAnswer(invocation -> {
            HttpServletRequest chainRequest = invocation.getArgument(0);
            chainRequest.getInputStream().readAllBytes();
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
        verify(store).complete(anyString(), anyString(), anyInt(), any(), fingerprint.capture(), any());
        return fingerprint.getValue();
    }

    private static MockHttpServletRequest json(String uri, String pattern, String body) {
        MockHttpServletRequest request = base("POST", uri, pattern);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static MockHttpServletRequest multipart(String uri, String pattern, String body) {
        MockHttpServletRequest request = base("POST", uri, pattern);
        request.setContentType("multipart/form-data; boundary=----T69");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static MockHttpServletRequest base(String method, String uri, String pattern) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader(IdempotencyFilter.HEADER_KEY, "unit-fingerprint-key");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        request.setAttribute(JwtAuthFilter.ATTR_USER_ID, 42L);
        return request;
    }
}
