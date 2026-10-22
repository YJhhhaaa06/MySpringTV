package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.log.AccessLogFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * **全站错误响应的唯一写出点**（`T56`）：状态、信封、头、内容类型都只在这里决定。
 *
 * <h2>为什么需要它</h2>
 * 本仓的错误响应有三条互不相通的出口（契约见 {@code architecture/tech/api/接口契约.md} §2.1）：
 * <ol>
 *   <li><b>控制器层</b>：{@link GlobalExceptionHandler}（进了 {@code DispatcherServlet} 之后的异常）；</li>
 *   <li><b>鉴权过滤器</b>：{@code JwtAuthFilter}——401 / 403 必须在 servlet 之前短路；</li>
 *   <li><b>限流过滤器</b>：{@code RateLimitFilter}——429 同理。</li>
 * </ol>
 * 三者**不能合并成一个方法**：后两条发生在 {@code DispatcherServlet} 之前，{@code @RestControllerAdvice}
 * 物理上覆盖不到（见 {@code architecture/tech/rate-limit/限流.md}）。过去三处各写各的
 * "序列化 + setStatus + setHeader"，于是**加一个错误字段要改三处、漏一处不会有任何测试变红**——
 * 那时候只有各自的状态码断言，形状漂移不可见。现在的分法是：
 * <b>形状归本类，出口归各自的调用点</b>；两条通道（MVC 的 {@code ResponseEntity} / servlet 的直接写）
 * 共用同一个 {@link #envelope} 与同一份头构造。
 *
 * <h2>为什么有两个入口</h2>
 * {@code @ExceptionHandler} 必须**返回** {@code ResponseEntity} 才能让 Spring 走消息转换器
 * （保留内容协商、{@code Content-Length} 等容器行为）；过滤器则**必须直接写**
 * {@code HttpServletResponse}。所以本类提供 {@link #toResponse} 与 {@link #write} 两个薄壳，
 * 它们不各自拼装任何字段。
 *
 * <h2>与访问日志的口径</h2>
 * {@link #write} 顺手把业务码记进请求属性（{@link AccessLogFilter#ATTR_BUSINESS_CODE}）：
 * 过滤器短路的响应**不经过** {@link BusinessCodeAdvice}（那个 advice 只对 MVC 的
 * {@code @ResponseBody} 生效），不补记就会让 401/403/429 的访问行退化成 {@code code=0}
 * ——与"静态资源"混为一谈。MVC 通道不需要补：{@link BusinessCodeAdvice} 已经从信封本身取值。
 *
 * <h2>一处刻意的"不写"</h2>
 * 静态资源与 {@code /upload/**} 缺文件时的 404 **不产信封**（{@link #toBodylessResponse}）：
 * 那些请求的消费方是 {@code <img>} / {@code <video>}，给它们一段 JSON 既无用又会让
 * "解析响应体"的调试路径误以为那是业务错误。判据在调用点（路径前缀），不在本类——
 * 本类只提供"要写就按同一形状写"。
 */
@Component
public class ApiErrorWriter {

    /** 重试节奏头（秒）。429 与 503 都必须带（§2.1）。 */
    public static final String HEADER_RETRY_AFTER = HttpHeaders.RETRY_AFTER;

    /**
     * 错误响应的内容类型（字面量与改造前保持一致，避免顺手改动客户端已依赖的头）。
     * ⚠️ 不要改成 {@code application/problem+json}：前端已在按 {@code application/json} 判断。
     */
    private static final MediaType CONTENT_TYPE = MediaType.APPLICATION_JSON;

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== 通道①：控制器层（MVC） ====================

    /** 默认文案的失败响应。 */
    public ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode errorCode) {
        return toResponse(errorCode, errorCode.getMessage(), HttpHeaders.EMPTY);
    }

    /** 覆盖文案的失败响应（业务侧更具体的说法）。 */
    public ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode errorCode, String msg) {
        return toResponse(errorCode, msg, HttpHeaders.EMPTY);
    }

    /**
     * 覆盖文案 + 额外响应头的失败响应（503 的 {@code Retry-After} 走这里）。
     *
     * @param extraHeaders 额外头（只读或可变均可，本方法不持有它）
     */
    public ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode errorCode, String msg,
                                                        HttpHeaders extraHeaders) {
        return ResponseEntity.status(errorCode.getStatus())
                .headers(extraHeaders)
                .body(envelope(errorCode, msg));
    }

    /**
     * 只有状态、没有响应体的错误（唯一使用者：静态资源 / 媒体文件 404）。
     *
     * <p>这不是"特例分支"，而是与信封并列的**第二种合法形状**：媒体请求的客户端不看 body。
     * 副作用：这类响应不产业务信封 ⇒ 访问日志记 {@code code=0}（"从未产生业务信封"哨兵），
     * 这正是该哨兵存在的场景。
     */
    public <T> ResponseEntity<T> toBodylessResponse(HttpStatus status) {
        return ResponseEntity.status(status).build();
    }

    // ==================== 通道②：过滤器（servlet 直接写） ====================

    /** 默认文案。 */
    public void write(HttpServletRequest request, HttpServletResponse response,
                      ErrorCode errorCode) throws IOException {
        write(request, response, errorCode, errorCode.getMessage(), HttpHeaders.EMPTY);
    }

    /** 覆盖文案。 */
    public void write(HttpServletRequest request, HttpServletResponse response,
                      ErrorCode errorCode, String msg) throws IOException {
        write(request, response, errorCode, msg, HttpHeaders.EMPTY);
    }

    /**
     * 覆盖文案 + 额外头（429 的 {@code Retry-After} 走这里）。
     *
     * <p>★ 这里**必须**是唯一一次 {@code setStatus} / {@code setContentType} / 写 body：
     * 改了这里就等于改了三处出口的行为（这正是本类存在的意义）。
     */
    public void write(HttpServletRequest request, HttpServletResponse response,
                      ErrorCode errorCode, String msg, HttpHeaders extraHeaders) throws IOException {
        request.setAttribute(AccessLogFilter.ATTR_BUSINESS_CODE, errorCode.getCode());
        response.setStatus(errorCode.getStatus().value());
        extraHeaders.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.setContentType(CONTENT_TYPE.toString());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), envelope(errorCode, msg));
    }

    // ==================== 共用构件 ====================

    /**
     * 失败信封（**唯一构造点**）：{@code {code, msg}}，不含 {@code data}。
     *
     * @param msg {@code null} ⇒ 回落到 {@link ErrorCode#getMessage()}（默认文案）
     */
    public ApiResponse<Void> envelope(ErrorCode errorCode, String msg) {
        return msg == null
                ? ApiResponse.error(errorCode)
                : ApiResponse.error(errorCode.getCode(), msg);
    }

    /**
     * 构造 {@code Retry-After} 头（秒）。
     *
     * <p>统一在这里造：调用点写 {@code ApiErrorWriter.retryAfter(3)}，避免有人写成
     * {@code setHeader("Retry-After", "3s")} —— 那是非法值，客户端会整条头丢掉。
     */
    public static HttpHeaders retryAfter(long seconds) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_RETRY_AFTER, String.valueOf(seconds));
        return headers;
    }
}
