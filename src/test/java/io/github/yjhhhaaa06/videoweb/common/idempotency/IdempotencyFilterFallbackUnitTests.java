package io.github.yjhhhaaa06.videoweb.common.idempotency;

import io.github.yjhhhaaa06.videoweb.common.security.JwtAuthFilter;
import io.github.yjhhhaaa06.videoweb.common.web.ApiErrorWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link IdempotencyFilter} 两条**结构上难用 HTTP 复现**的分支（写接口幂等专项 `T66` 的收尾缺口）。
 *
 * <h2>为什么必须是单测而不是集成用例</h2>
 * <ol>
 *   <li><b>重放时写响应体抛 {@link IOException}</b>：客户端的连接已经断了才会出现——用真实
 *       HTTP 客户端制造"服务端开始写、客户端恰好消失"是概率事件，不可复现。
 *       本类直接让 {@code getOutputStream().write(...)} 抛 {@code IOException}，
 *       断言<b>异常不冒出过滤器</b>且判定仍记 {@code hit}（判据取自
 *       {@code architecture/tech/idempotency/写接口幂等-测试与契约.md} §三"断连场景怎么测"）。</li>
 *   <li><b>取不到 {@code BEST_MATCHING_PATTERN_ATTRIBUTE}</b>：正常启动下它恒存在
 *       （由 {@code JwtAuthFilter} 的 {@code RequestMappingLookup} 写入）⇒ 只能手工构造一个
 *       "属性缺失"的请求来驱动回退分支。它钉的是"回退不许静默"：
 *       {@code idempotency.decisions{result=degraded}} 必须 +1。</li>
 * </ol>
 *
 * <p>★ 两条用例都不启动 Spring 上下文：它们测的是**一个类内部的兜底语义**，
 * 起容器只会让"为什么红"变难读。
 */
class IdempotencyFilterFallbackUnitTests {

    private static final long UID = 42L;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final IdempotencyMetrics metrics = new IdempotencyMetrics(registry);
    private final IdempotencyStore store = mock(IdempotencyStore.class);
    private final IdempotencyFilter filter = new IdempotencyFilter(properties(), store, metrics,
            mock(ApiErrorWriter.class));

    @Test
    @DisplayName("重放时写响应体抛 IOException：不冒出过滤器（否则一次成功重放会被记成 5xx），判定仍记 hit")
    void 重放写体失败_不得冒出异常() throws Exception {
        when(store.acquire(anyString(), anyString(), anyInt()))
                .thenReturn(new IdempotencyStore.AcquireResult(IdempotencyStore.Kind.DONE,
                        new IdempotencyRedisOps.Snapshot(null, 200, "application/json", "{\"code\":\"OK\"}")));

        HttpServletRequest request = matchedRequest("/api/v1/comments");
        HttpServletResponse response = mock(HttpServletResponse.class);
        ServletOutputStream brokenStream = mock(ServletOutputStream.class);
        doThrow(new IOException("broken pipe")).when(brokenStream).write(any(byte[].class));
        when(response.getOutputStream()).thenReturn(brokenStream);
        FilterChain chain = mock(FilterChain.class);

        assertThatCode(() -> filter.doFilter(request, response, chain))
                .as("客户端断连不得让重放路径抛异常（容器的错误分发会把它记成 5xx）")
                .doesNotThrowAnyException();

        verify(brokenStream).write(any(byte[].class));
        verifyNoInteractions(chain);
        assertThat(count("hit")).as("重放确实发生了 ⇒ 判定是 hit（写体失败不改变判定）").isEqualTo(1.0);
    }

    @Test
    @DisplayName("取不到路由模式属性：回退原始路径查表，但必须记 degraded（不许静默）")
    void 查表回退_必须记降级指标() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/nothing-here");
        when(request.getContextPath()).thenReturn("");
        // ★ 属性缺失（回退分支的充分条件）
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).thenReturn(null);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        // 未登记的端点仍然放行（口径不变：未登记 ⇒ 放行），但降级必须可观测
        verify(chain).doFilter(request, response);
        assertThat(count("degraded")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("安全方法（GET）直通：页面导航取不到路由模式也**不记 degraded**（那个信号只属于写请求）")
    void 安全方法直通不记降级() throws Exception {
        // 真实形态：/video/92 由 ViewController 处理（不是 HandlerMethod）⇒ 没有
        // BEST_MATCHING_PATTERN_ATTRIBUTE。若在这里记 degraded，每一次页面导航都会污染
        // "幂等降级"指标，并把"链序被改坏"的 WARN 淹掉。
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/video/92");
        when(request.getContextPath()).thenReturn("");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(store);
        assertThat(count("degraded")).as("安全方法不查幂等表 ⇒ 不得记降级").isZero();
    }

    // ==================== 工具 ====================

    private static IdempotencyProperties properties() {
        return new IdempotencyProperties(
                new IdempotencyProperties.Ttl(180, 5),
                new IdempotencyProperties.Poll(2, 100),
                new IdempotencyProperties.Limits(8192, 65536));
    }

    /** 一个"路由属性已就位、已鉴权、带合法 key"的请求（正常链序下的形态）。 */
    private static HttpServletRequest matchedRequest(String pattern) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn(pattern);
        when(request.getContextPath()).thenReturn("");
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).thenReturn(pattern);
        when(request.getAttribute(JwtAuthFilter.ATTR_USER_ID)).thenReturn(UID);
        when(request.getHeader(IdempotencyFilter.HEADER_KEY)).thenReturn("unit-test-key");
        return request;
    }

    private double count(String result) {
        Counter counter = registry.find(IdempotencyMetrics.METRIC_DECISIONS)
                .tag(IdempotencyMetrics.TAG_RESULT, result)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
