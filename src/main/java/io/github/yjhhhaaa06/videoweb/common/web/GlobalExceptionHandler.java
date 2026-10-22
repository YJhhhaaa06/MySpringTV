package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.common.exception.BusinessException;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.exception.ServiceBusyException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.stream.Collectors;

/**
 * 统一异常出口（控制器层）。
 *
 * <p><b>两层语义</b>（{@code architecture/tech/api/接口契约.md} §2.1）：
 * HTTP status 表**类别**（前端做通用分支），body {@code code} 是**字符串码**（前端翻字典做精细分支），
 * {@code msg} 只是兜底文案。状态与码**同源于异常携带的 {@link ErrorCode}**，本类不再
 * {@code HttpStatus.resolve(code)} —— 那正是"码与状态重复"的旧形态。
 *
 * <p>⚠️ 本类只管**进了 {@code DispatcherServlet} 之后的**错误。过滤器链上的 401/403/429
 * 在 servlet 之前短路（{@code JwtAuthFilter} / {@code RateLimitFilter}），advice 覆盖不到；
 * 三处的信封形状已由 {@link ApiErrorWriter} 收敛为**同一份**，**链序不变**。
 * 本类只决定"哪个异常归哪个码"，不再拼装 status / body / 头。
 *
 * <p>注意：Spring Security **不接管授权**（F-01/F-02 于 2026-10-18 拍板不迁），
 * 因此没有 {@code AuthenticationEntryPoint}/{@code AccessDeniedHandler} 需要对齐。
 * ⚠️ 若将来真的迁到方法安全（推翻条件见 {@code 安全与鉴权.md} §六），本类的
 * {@code @ExceptionHandler(Exception.class)} 会把 {@code @PreAuthorize} 抛出的
 * {@code AccessDeniedException} **吞成 500**（{@code ExceptionTranslationFilter} 在外侧，轮不到），
 * 届时必须先补一条显式重抛。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ApiErrorWriter errorWriter;

    public GlobalExceptionHandler(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    /**
     * 业务异常：状态与码都来自异常携带的 {@link ErrorCode}。
     *
     * <p>可预期的业务拒绝：**WARN + 只记结论，不记堆栈**。
     * 级别是 WARN 而非 INFO/ERROR：INFO 会让"正常成功"与"被拒绝"在级别上不可分；
     * ERROR 会把无需介入的输入错误报成运维事故。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex, HttpServletRequest req) {
        log.warn("业务异常 {} {} -> code={}, msg={}",
                req.getMethod(), req.getRequestURI(), ex.getCode(), ex.getMessage());
        return errorWriter.toResponse(ex.getErrorCode(), ex.getMessage());
    }

    /**
     * 503 服务繁忙：**带 {@code Retry-After}**（`T51-2`）。
     *
     * <p>★ 必须是比 {@link BusinessException} **更具体**的处理器，Spring 才会选中它——
     * 否则父类那个 handler 会照常回 503 但**丢掉响应头**，客户端只能自己猜退避节奏。
     *
     * <p>级别 WARN（预期内的降级，不是运维事故）：Redis 挂了要报的是"缓存不可用"这件事本身，
     * 不该因为每次上报都打 ERROR 把 error.log 刷爆。
     */
    @ExceptionHandler(ServiceBusyException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusy(ServiceBusyException ex, HttpServletRequest req) {
        log.warn("服务繁忙 {} {} -> code={}, retryAfter={}s, msg={}",
                req.getMethod(), req.getRequestURI(), ex.getCode(), ex.getRetryAfterSeconds(), ex.getMessage());
        return errorWriter.toResponse(ex.getErrorCode(), ex.getMessage(),
                ApiErrorWriter.retryAfter(ex.getRetryAfterSeconds()));
    }

    /**
     * {@code @Valid} 校验失败：码 {@code PARAM_INVALID}，{@code msg} 是全部字段错误的拼接串。
     *
     * <p>⚠️ 拼接串只是**过渡态**：{@code T57} 会补 {@code errors:[{field,code,message}]}
     * 把字段级定位交给机器读；{@code msg} 保留为"人读摘要"。
     * 现在就把 {@code field: message} 的形式保留着，前端至少能靠它做临时解析。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest req) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + defaultMessage(fe))
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败 {} {} -> {}", req.getMethod(), req.getRequestURI(), detail);
        return errorWriter.toResponse(ErrorCode.PARAM_INVALID, detail);
    }

    /**
     * 缺参 / 类型不匹配 / 请求体不可解析 / **缺 multipart part**：均归为参数错误。
     *
     * <p>⚠️ 最后一类（{@link MissingServletRequestPartException}）是 S7 加入的：
     * 上传端点的必填文件 part 缺失时 Spring 抛它。TV 侧同场景是
     * {@code getPart → null → NPE 未捕获 → 500}（《决策留痕表》D-11 的记录来源），本实现按 D-3 先例统一归 400。
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestPartException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex, HttpServletRequest req) {
        // ⚠️ 本条消息会**回显被拒的参数值**（如 MethodArgumentTypeMismatch 的 "for value [xxx]"）——
        // 它正是 PII 进日志的一条真实路径。出口脱敏（logback 的 %maskedMsg）负责把它按形态掩掉，
        // 故此处**不**在调用点手写脱敏（否则就是两套机制、两处要维护）。
        // ★ 响应体**只给稳定文案**：异常 message 可能含入参回显，不许进 body。
        log.warn("请求参数错误 {} {} -> {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return errorWriter.toResponse(ErrorCode.PARAM_INVALID);
    }

    /**
     * 唯一键冲突：DB 层兜底竞态（决策②：SQLException 手工包装退役，DataAccessException 接管）。
     *
     * <p>业务层已先查重，但并发下仍可能撞唯一键。★ 这里**只能**回
     * {@link ErrorCode#SYSTEM_DUPLICATE_KEY}：拿到的是 {@code DuplicateKeyException}，
     * 不知道撞的是 {@code users.phone} 还是 {@code users.username}，硬猜会把两种 409 报反。
     * 前端处置（提示换值 / 重试）对两种都对。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateKey(DuplicateKeyException ex,
                                                                HttpServletRequest req) {
        log.warn("唯一键冲突 {} {} -> {}", req.getMethod(), req.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return errorWriter.toResponse(ErrorCode.SYSTEM_DUPLICATE_KEY);
    }

    /**
     * 其余数据访问异常：TV 里这些被各 Service 手工 {@code catch (SQLException)} 包成 DatabaseException
     * 并**在包装点记堆栈**。新实现集中在唯一出口记录，业务代码不再需要 try/catch。
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccess(DataAccessException ex, HttpServletRequest req) {
        log.error("数据访问异常 {} {}", req.getMethod(), req.getRequestURI(), ex);
        return errorWriter.toResponse(ErrorCode.SYSTEM_DATABASE);
    }

    /** 兜底：未预期异常一律 500，且不把内部细节泄露到响应体。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("未预期异常 {} {}", req.getMethod(), req.getRequestURI(), ex);
        return errorWriter.toResponse(ErrorCode.SYSTEM_INTERNAL);
    }

    private static String defaultMessage(FieldError fe) {
        return fe.getDefaultMessage() == null ? "非法值" : fe.getDefaultMessage();
    }
}
