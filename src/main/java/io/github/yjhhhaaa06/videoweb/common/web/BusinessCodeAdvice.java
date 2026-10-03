package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.common.log.AccessLogFilter;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 业务码记录器（第三批 T2）：把**每一个真正产生的业务信封**里的 {@code code} 记到请求属性上，
 * 供 {@link AccessLogFilter} 在链尾读取。
 *
 * <h2>它替代了 TV 的什么</h2>
 * TV 的访问日志带着一个 {@code code} 字段，由 {@code BaseServletUtil.writeSuccess/writeError}
 * **在统一出口写法**时塞进 {@code LogContext}；静态资源没走那个出口 ⇒ {@code code} 缺省 0。
 * 新项目没有"统一写法的方法"（响应由消息转换器直接写出），等价位置就是
 * {@link ResponseBodyAdvice}——它在**所有** {@code @ResponseBody} 返回值经过消息转换器之前被调用，
 * 天然覆盖"控制器正常返回"与"{@code @ExceptionHandler} 返回错误信封"两条路。
 *
 * <h2>为什么"没被调用过"恰好等于 TV 的 {@code code=0}</h2>
 * 静态资源（{@code ResourceHttpRequestHandler}）、被过滤器短路（保留 401/403）、
 * Spring 自己产生的错误响应，都**不经过**本 advice ⇒ 请求属性不存在 ⇒ 访问日志记 0。
 * 判据不是"路径像不像静态资源"，而是"**有没有真的产生业务信封**"——这与 TV 同义，
 * 也避免维护一张"静态前缀清单"。
 *
 * <h2>为什么用请求属性而不是 MDC</h2>
 * MDC 是**线程**维度、访问日志是**请求**维度；同线程上两者当前等价，但请求属性语义更准
 * （不污染其它日志行的字段），也与 {@code JwtAuthFilter} 已经在用的
 * {@code request.setAttribute(ATTR_USER_ID, …)} 保持同一手法。
 *
 * <h2>零行为影响</h2>
 * {@code beforeBodyWrite} **原样返回 body**，不改形状、不改状态码、不改 Content-Type——
 * 它只读不写。{@code supports} 恒真但 {@code instanceof} 判空，故对非 {@code ApiResponse}
 * 的返回值（如 {@code String}）也只是"多一次空判断"。
 */
@ControllerAdvice
public class BusinessCodeAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body instanceof ApiResponse<?> envelope && request instanceof ServletServerHttpRequest servlet) {
            servlet.getServletRequest().setAttribute(AccessLogFilter.ATTR_BUSINESS_CODE, envelope.code());
        }
        return body;
    }
}
