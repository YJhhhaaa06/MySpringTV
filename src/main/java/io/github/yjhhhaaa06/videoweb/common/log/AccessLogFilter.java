package io.github.yjhhhaaa06.videoweb.common.log;

import io.github.yjhhhaaa06.videoweb.common.config.LogProperties;
import io.github.yjhhhaaa06.videoweb.common.security.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 访问日志过滤器（第三批 T2 / 账 B12）——承接 TV {@code filter.AccessLogFilter}，
 * 为**每个请求**在 {@code access} 输出端落一行结构化记录。
 *
 * <h2>行形态（msg 内为本行专属 key=value；时间与 reqId 由 pattern 前缀提供）</h2>
 * <pre>{@code ts=… level=INFO logger=access req=6a1b2c3d0f3e0001 msg=method=GET path=/start status=200 code=200 userId=- cost=3ms slow=0}</pre>
 *
 * <h2>★ status 与 code 是**两个**字段（不是冗余）</h2>
 * <ul>
 *   <li>{@code status} = **HTTP 状态码**，恒有值。T2 要求"含…状态码"。</li>
 *   <li>{@code code} = **业务码**，与 HTTP 状态对齐（决策⑧「HTTP 状态码正确化」），
 *       <b>缺省 0</b> = 该请求**从未产生业务信封**（静态资源 / 未进 DispatcherServlet 的
 *       短路响应）。这正是 TV 访问日志 {@code code} 字段的语义（TV 恒 200 + body code，
 *       静态资源为 0），也是"静态资源与业务请求可区分"的判据——旧 pytest
 *       {@code test_static_resource_gets_code_zero} 的意图在此兑现。</li>
 * </ul>
 * 业务码由 {@link io.github.yjhhhaaa06.videoweb.common.web.BusinessCodeAdvice}（正常响应）
 * 与 {@link JwtAuthFilter}（鉴权拒绝）写入请求属性 {@link #ATTR_BUSINESS_CODE}。
 *
 * <h2>★ 脱敏口径（TV D7「绝不记」原样沿袭）</h2>
 * {@code path} 取自 {@code getRequestURI()} 去掉 contextPath——<b>不含 query 串</b>
 * （servlet 规范保证）。**不记** query 串 / header / 请求体：token、手机号明文、密码
 * 一律不进访问日志。第二道防线是日志 pattern 的 {@code %maskedMsg} 出口脱敏（{@link LogMasker}）。
 *
 * <h2>顺序</h2>
 * 紧贴 {@link RequestIdFilter} 之内（{@code HIGHEST_PRECEDENCE + 1}）：reqId 已就位，
 * 且仍包住 Spring Security 链与 {@link JwtAuthFilter}——于是 401/403 这类**被过滤器短路**的
 * 请求同样有访问日志（TV 的 {@code AuthFilter} 也在访问日志内层）。
 *
 * <h2>已知边界（如实声明）</h2>
 * <ul>
 *   <li><b>异常逃出过滤器链时状态码可能不准</b>：{@code status} 取自
 *       {@code response.getStatus()}，在 {@code finally} 里读。若异常从链中**逃逸**
 *       （未进入 DispatcherServlet 的异常解析），容器会在之后才置 500，此处可能仍读到 200。
 *       本仓当前唯一链内短路的过滤器 {@code JwtAuthFilter} 只 catch {@code BusinessException}
 *       （不逃逸）；其余异常都进 {@code GlobalExceptionHandler}（状态码已置）。属**潜伏**边界，
 *       将来若新增"可能抛未捕获异常的过滤器"，需回到这里处理。</li>
 *   <li>异步 / error dispatch 不覆盖：{@code OncePerRequestFilter} 默认
 *       {@code shouldNotFilterAsyncDispatch()/shouldNotFilterErrorDispatch()} 为 true，
 *       而本项目当前无异步用法（引入时需一起重新评估）。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AccessLogFilter extends OncePerRequestFilter {

    /** 访问输出端专属 logger 名（{@code logback-spring.xml} 按此名装配 appender）。 */
    public static final String ACCESS_LOGGER_NAME = "access";

    /** 业务码请求属性名：由业务码写入方（advice / 鉴权过滤器）写，本类在链尾读一次。 */
    public static final String ATTR_BUSINESS_CODE = "videoweb.accessLog.businessCode";

    private static final Logger ACCESS_LOGGER = LoggerFactory.getLogger(ACCESS_LOGGER_NAME);

    private final LogProperties logProperties;

    public AccessLogFilter(LogProperties logProperties) {
        this.logProperties = logProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long costMillis = (System.nanoTime() - startNanos) / 1_000_000;
            Object userId = request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
            Object businessCode = request.getAttribute(ATTR_BUSINESS_CODE);
            ACCESS_LOGGER.info(buildLine(
                    request.getMethod(),
                    path(request),
                    response.getStatus(),
                    businessCode instanceof Integer code ? code : 0,
                    userId instanceof Long id ? id : null,
                    costMillis,
                    logProperties.slowRequestMs()));
        }
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /**
     * 访问行 msg 内容（**纯函数**，JUnit 直测）：
     * {@code method=… path=… status=… code=… userId=… cost=…ms slow=0|1}。
     *
     * <p>{@code userId == null}（未登录 / 非业务路径）→ {@code userId=-}（TV 同款）；
     * {@code slow} = 耗时是否达到阈值（打标记，不另起一行、不设独立性能日志文件）。
     */
    static String buildLine(String method, String path, int status, int code, Long userId,
                            long costMillis, long slowThresholdMillis) {
        return "method=" + method
                + " path=" + path
                + " status=" + status
                + " code=" + code
                + " userId=" + (userId == null ? "-" : userId)
                + " cost=" + costMillis + "ms"
                + " slow=" + (costMillis >= slowThresholdMillis ? 1 : 0);
    }
}
