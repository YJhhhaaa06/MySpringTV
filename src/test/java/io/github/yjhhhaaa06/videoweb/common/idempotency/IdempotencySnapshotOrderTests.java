package io.github.yjhhhaaa06.videoweb.common.idempotency;

import io.github.yjhhhaaa06.videoweb.common.security.JwtAuthFilter;
import io.github.yjhhhaaa06.videoweb.common.web.ApiErrorWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * **顺序约束的可观测证明**（写接口幂等专项 `T66`；口径 = {@code architecture/tech/idempotency/写接口幂等.md} §5.1）。
 *
 * <p>设计里唯一不可调的实现细节是"<b>快照必须先于响应体 flush</b>"，它换来的对外承诺是：
 * <b>客户端收到 2xx ⇒ 快照已写</b>（因此"响应在网络上丢了"/"flush 时对端已断开"这两种最常见的
 * 重试成因都能被覆盖）。
 *
 * <h2>为什么不能靠 HTTP 用例钉住它</h2>
 * 把 {@code COMPLETE} 挪到 {@code copyBodyToResponse()} 之后，第二个请求到来时占位仍是
 * {@code PENDING} ⇒ 过滤器会**短轮询 2×100 ms**，那时快照早写完了 ⇒ 用例 1 照样绿
 * （实测确认：注入后 8 条运行期用例全绿）。即"重试最终能成功"是轮询给的，
 * 不是顺序给的；顺序保证的是**进程在 flush 之后崩掉**那一刻的数据完整性——这在集成测试里不可造。
 *
 * <p>⇒ 只剩一个诚实的观测点：**在把字节写进原始响应流的那一刻，{@code COMPLETE} 有没有发生过**。
 * 本用例用"写字节时回访 store 的完成标记"来断言它，不断言相对调用次序之外的任何内部细节，
 * 也不依赖线程调度（同步调用链，无竞态）。
 */
class IdempotencySnapshotOrderTests {

    @Test
    @DisplayName("§5.1：把响应体写进原始流的那一刻，快照必须已经写完（否则 crash/断连后重发会再执行一次）")
    void 写快照必须早于响应体flush() throws Exception {
        AtomicBoolean snapshotWritten = new AtomicBoolean(false);
        IdempotencyStore store = mock(IdempotencyStore.class);
        when(store.acquire(anyString(), anyString(), anyInt()))
                .thenReturn(new IdempotencyStore.AcquireResult(IdempotencyStore.Kind.ACQUIRED, null));
        when(store.complete(anyString(), anyString(), anyInt(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    snapshotWritten.set(true);
                    return true;
                });

        IdempotencyFilter filter = new IdempotencyFilter(properties(), store,
                new IdempotencyMetrics(new SimpleMeterRegistry()), mock(ApiErrorWriter.class));

        // 原始响应流：每次写字节都把"此刻快照写没写"记下来
        AtomicBoolean snapshotReadyWhenFlushing = new AtomicBoolean(false);
        ServletOutputStream rawStream = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                // 测试里不会走异步 IO
            }

            @Override
            public void write(int b) {
                snapshotReadyWhenFlushing.set(snapshotWritten.get());
            }

            @Override
            public void write(byte[] b, int off, int len) {
                snapshotReadyWhenFlushing.set(snapshotWritten.get());
            }
        };
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(rawStream);
        // ContentCachingResponseWrapper#getStatus 直接委托原始响应（自己不缓存状态）⇒ 必须打在原始 mock 上
        when(response.getStatus()).thenReturn(200);

        // 模拟控制器：写一个 body（够 copyBodyToResponse 真的往原始流写）
        FilterChain chain = mock(FilterChain.class);
        doAnswer(invocation -> {
            HttpServletResponse chainResponse = invocation.getArgument(1);
            chainResponse.getOutputStream().write("{\"code\":\"OK\"}".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(matchedRequest(), response, chain);

        verify(store).complete(anyString(), anyString(), anyInt(), any(), any(), any());
        assertThat(snapshotReadyWhenFlushing)
                .as("响应体写进原始流时快照必须已在盘上（§5.1 的顺序约束）")
                .isTrue();
    }

    private static IdempotencyProperties properties() {
        return new IdempotencyProperties(
                new IdempotencyProperties.Ttl(180, 5),
                new IdempotencyProperties.Poll(2, 100),
                new IdempotencyProperties.Limits(8192, 65536));
    }

    /** 一个"路由属性已就位、已鉴权、带合法 key、content-type 为空（不算指纹）"的请求。 */
    private static HttpServletRequest matchedRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/comments");
        when(request.getContextPath()).thenReturn("");
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).thenReturn("/api/v1/comments");
        when(request.getAttribute(JwtAuthFilter.ATTR_USER_ID)).thenReturn(42L);
        when(request.getHeader(IdempotencyFilter.HEADER_KEY)).thenReturn("unit-order-key");
        return request;
    }
}
