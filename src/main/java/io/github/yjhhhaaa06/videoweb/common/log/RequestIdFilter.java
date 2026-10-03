package io.github.yjhhhaaa06.videoweb.common.log;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 请求标识（reqId）过滤器（第三批 T2 / 账 B12）——承接 TV {@code util.LogContext} 的
 * "每请求一个标识"职责，载体从自建 {@code ThreadLocal} 换成 **SLF4J MDC**。
 *
 * <h2>为什么是最外层</h2>
 * 本过滤器是**唯一**的 set/clear 点，且顺序为 {@link Ordered#HIGHEST_PRECEDENCE}
 * （比 {@link AccessLogFilter} 与 Spring Security 链都靠外）。理由与 TV 的
 * {@code AccessLogFilter} 同款：reqId 必须在**整条链**（含鉴权拒绝、未处理异常）上都可读，
 * 否则"最需要 reqId 的异常日志"反而读不到它。
 *
 * <p>⚠️ {@code HIGHEST_PRECEDENCE} 与 Spring Boot 的 {@code OrderedCharacterEncodingFilter}
 * **同序**（都是 {@code Integer.MIN_VALUE}）。两者之间**没有功能依赖**（编码过滤器不读
 * body/参数，本类也不读），故同序不影响正确性；但"同序"意味着它们之间的**相对次序依赖
 * bean 注册顺序**，不是被保证的——不要在这两个过滤器之间引入顺序假设。
 * 与 {@link AccessLogFilter} 的相对次序是保证的（{@code MIN_VALUE < MIN_VALUE + 1}）。
 *
 * <h2>载体为什么是 MDC 而不是自建 ThreadLocal</h2>
 * logback 的 {@code %X{reqId}} / 本项目自定义的 {@code %reqid} 转换字直接读 MDC，
 * 于是"把请求标识写进日志行"这件事由**日志配置**承担，业务代码零感知——
 * 这正是《迁移参照系》§三"收敛为 MDC"那句话的兑现（TV 是自建 {@code LogFormatter} 读自建
 * ThreadLocal，两者耦合）。
 *
 * <h2>与 TV 的口径差异（有意）</h2>
 * <ul>
 *   <li><b>不读入站 header</b>：TV 也是"无条件新生成"，本项目沿用。若要支持上游透传，
 *       必须同时定义"外部值可信性"口径，否则请求方可伪造 reqId 污染排查——留后。</li>
 *   <li><b>回写响应头 {@code X-Request-Id}</b>（TV 没有）：让调用方/前端也能把
 *       "我这次请求"与后端日志对上，成本一行。</li>
 *   <li><b>清理用 {@code MDC.remove} 而非 {@code MDC.clear}</b>：容器线程是复用的，
 *       必须只清自己写的那把键，避免抹掉别的组件（如将来接入的 tracing）放在 MDC 里的值。</li>
 * </ul>
 *
 * <h2>已知边界</h2>
 * 项目当前**没有**进程内线程池承载请求处理（MQ 消费线程另外成链、不带 reqId，属预期）。
 * 将来若引入异步处理，必须显式传递 MDC（{@code MDC.getCopyOfContextMap()} / 装饰器），
 * 否则异步段的日志不带 reqId——这是 MDC 的固有边界，不是本类的缺陷。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** MDC 键名。日志 pattern 的 {@code %reqid} 转换字读的就是这把键。 */
    public static final String MDC_KEY = "reqId";

    /** 响应头名：把本请求的标识回写给调用方，便于"客户端现象 → 服务端日志"对齐。 */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    /** 进程标识（类初始化时随机一次）：避免"重启后同毫秒 + 同序号"撞号（TV LogContext 同款）。 */
    private static final String PROCESS_TAG =
            String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));

    /** 进程内自增序号：同毫秒内的并发/连续生成靠它区分（TV LogContext 同款）。 */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = newRequestId();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_REQUEST_ID, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 生成请求标识（**唯一源**）：16 位十六进制 = 毫秒时间戳低 32 位（8 位，便于粗排与肉眼区分）
     * + 进程标识（4 位）+ 进程内自增序号低 16 位（4 位）。
     *
     * <p>格式只在**本方法**内定义：调用方只取值、不自造格式（TV {@code LogContext.newRequestId} 同款），
     * 避免生成口径分叉。测试的 {@code req=([0-9a-f]{16})} 正则是这个契约的守门人。
     */
    public static String newRequestId() {
        return String.format("%08x%s%04x",
                (int) System.currentTimeMillis(), PROCESS_TAG, SEQUENCE.incrementAndGet() & 0xFFFF);
    }
}
