package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.common.exception.BusinessException;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.exception.ServiceBusyException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Set;

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

    /** 根路径上的静态文件（精确匹配）：缺文件时只回 404 状态、不产信封。 */
    private static final Set<String> STATIC_EXACT = Set.of("/", "/index.html", "/favicon.ico");

    /**
     * 静态媒体 / 样式脚本前缀：缺文件时同样不产信封。
     *
     * <p>⚠️ 与 {@code RateLimitFilter} 的跳过前缀**同集合但不是同一份**（见 {@link #handleNoResource}）。
     */
    private static final List<String> STATIC_PREFIXES = List.of("/static/", "/upload/");

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
     * {@code @Valid} 校验失败：码 {@code PARAM_INVALID}，并给出**字段级**明细
     * {@code errors:[{field, code, message}]}（`T57`）。
     *
     * <h2>{@code msg} 与 {@code errors[]} 的分工</h2>
     * {@code msg} = 人读摘要（各条 {@code field: message} 用 "; " 拼），保留它是为了让**旧前端**
     * 与不翻字典的调用方仍能展示一句话；{@code errors[]} 是**机器读的真源**，前端据此把文案挂到输入框上。
     * 两者**同源**（{@link ApiErrorWriter#summary} 从 {@code errors} 生成），不各拼一套。
     *
     * <h2>为什么 {@code message} 用注解文案、不用异常文案</h2>
     * 注解的 {@code message} 是**源码里的稳定文案**；异常 message 形如
     * {@code Validation failed for argument [...]}，且可能夹带入参回显。红线是"参数回显只进日志"，
     * 故 body 只取注解文案，异常对象只用于日志。
     *
     * <h2>同一字段可能有多条</h2>
     * Bean Validation 是"每条违反一条"，一个字段命中 {@code @NotBlank} 与 {@code @Size} 会产出两条。
     * 这里**不合并**：合并要选"哪条算第一条"，那是不可解释的隐式策略，且会丢掉"还违反了另一条"的信息。
     * 字段级去重交给前端（按 {@code field} 分组，展示第一条即可）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest req) {
        List<ApiFieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> ApiFieldError.of(fe.getField(), ErrorCode.PARAM_INVALID, defaultMessage(fe)))
                .toList();
        log.warn("参数校验失败 {} {} -> {}", req.getMethod(), req.getRequestURI(),
                errors.stream().map(ApiFieldError::toSummary).toList());
        if (errors.isEmpty()) {
            // 理论分支：只有类级（全局）约束、没有字段错误时无法给出 field。
            // 此时退回"只有码 + 默认文案"的信封，**不**造一个 field 为空的假条目
            // （那会让前端以为"某个字段错了"却无从挂载）。
            return errorWriter.toResponse(ErrorCode.PARAM_INVALID);
        }
        return errorWriter.toResponse(ErrorCode.PARAM_INVALID, ApiErrorWriter.summary(errors), errors);
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

    // ========================================================================
    // 框架级：改造前它们全部被上面的 catch-all 吃成 500（§三 #6）
    // ========================================================================

    /**
     * 路由不存在（`T56`）：Spring 6.1 起"没有任何 handler 匹配"由默认资源处理器抛
     * {@link NoResourceFoundException}，而不是 404 —— 它原本落进 {@code handleUnexpected}
     * 变成 **500**（契约与实现漂移的根源，见 {@code architecture/tech/api/接口契约.md} §三 #6）。
     *
     * <h2>为什么静态资源要"只回状态、不回信封"</h2>
     * {@code /upload/**}（媒体）、{@code /static/**}（样式脚本）与三个根路径的缺失文件，
     * 消费方是 {@code <img>} / {@code <video>} / 浏览器加载器 —— 它们**不看 body**，
     * 给一段 JSON 只会让"看一眼响应体"的排查路径误以为那是业务错误。
     * 这也是访问日志 {@code code=0}（"从未产生业务信封"哨兵）**仍然存在**的场景。
     *
     * <p>⚠️ 与 {@code RateLimitFilter#isSkipped} 是**两份清单、两种语义**（"不计成本" vs
     * "不产信封"）：不合并，因为合并后改一处会静默改变另一处的行为。
     *
     * <p>⚠️ 判据为什么只能是**路径清单**（而不是"这条异常来自资源处理器"）：静态缺文件与
     * 未映射的业务路径走的是**同一个** {@code /**} 资源处理器（这正是未映射一度变 500 的原因），
     * 异常层拿不到任何区分信息。代价：新增资产目录要同步本清单，**漂移不会自己变红**——
     * {@code FrameworkErrorContractTests} 只覆盖能造出 404 的三个入口（{@code /favicon.ico}、
     * {@code /static/}、{@code /upload/}）；{@code /} 与 {@code /index.html} 在当前工程里存在（回 200），
     * 无法用 404 覆盖。
     *
     * <p>{@code /actuator/**} **不在**本清单：它由独立的 {@code WebMvcEndpointHandlerMapping}
     * 处理，不会抛本异常。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex,
                                                              HttpServletRequest req) {
        String path = pathOf(req);
        if (isStaticResource(path)) {
            // DEBUG 而非 WARN：扫描器与过期媒体链接都会打到这里，日志量由访问行承担（status=404）。
            log.debug("静态资源不存在 {} {}", req.getMethod(), path);
            return errorWriter.toBodylessResponse(HttpStatus.NOT_FOUND);
        }
        // 同样是 DEBUG：访问行已记 status=404 + code=SYSTEM_ENDPOINT_NOT_FOUND，可复盘、可按码统计。
        log.debug("未映射的接口路径 {} {}", req.getMethod(), path);
        return errorWriter.toResponse(ErrorCode.SYSTEM_ENDPOINT_NOT_FOUND);
    }

    /**
     * 方法不匹配（405，`T56`）：路径命中、HTTP 方法不支持。
     *
     * <p>★ 必须保住 {@code Allow} 头：它由异常携带
     * （{@link HttpRequestMethodNotSupportedException#getSupportedHttpMethods}），
     * 若在这里新建一个"干净"的 {@code ResponseEntity} 就会**丢掉**它——客户端因此无法知道
     * 该用哪个方法，而这正是 405 与 404 的全部区别。{@code FrameworkErrorContractTests} 钉住这条。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                     HttpServletRequest req) {
        log.warn("请求方法不支持 {} {} -> {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        HttpHeaders headers = new HttpHeaders();
        if (ex.getSupportedHttpMethods() != null) {
            headers.setAllow(ex.getSupportedHttpMethods());
        }
        return errorWriter.toResponse(ErrorCode.PARAM_METHOD_NOT_ALLOWED,
                ErrorCode.PARAM_METHOD_NOT_ALLOWED.getMessage(), headers);
    }

    /**
     * 媒体类型不支持（415，`T56`）：Spring 在读取 {@code @RequestBody} 时发现没有匹配的转换器。
     *
     * <p>★ 对外只给稳定文案：异常 message 里带着**客户端发来的** {@code Content-Type}
     * （客户端可控字符串），它只进日志（出口统一脱敏），不许回显进 body。
     *
     * <p>{@code Accept} 头同理由异常携带（可接受类型），保住它客户端才知道该发什么。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
                                                                        HttpServletRequest req) {
        log.warn("Content-Type 不支持 {} {} -> {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        HttpHeaders headers = new HttpHeaders();
        if (ex.getSupportedMediaTypes() != null && !ex.getSupportedMediaTypes().isEmpty()) {
            headers.setAccept(ex.getSupportedMediaTypes());
        }
        return errorWriter.toResponse(ErrorCode.PARAM_MEDIA_TYPE_UNSUPPORTED,
                ErrorCode.PARAM_MEDIA_TYPE_UNSUPPORTED.getMessage(), headers);
    }

    /** 上下文路径已剥的请求路径（**未解码**，与 {@code RateLimitFilter#pathOf} 同口径）。 */
    private static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /**
     * 该路径的 404 是否**不产信封**（静态服务 / 媒体文件）。
     *
     * @see #handleNoResource 为什么是两份清单而不是复用限流的跳过清单
     */
    private static boolean isStaticResource(String path) {
        if (STATIC_EXACT.contains(path)) {
            return true;
        }
        for (String prefix : STATIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String defaultMessage(FieldError fe) {
        return fe.getDefaultMessage() == null ? "非法值" : fe.getDefaultMessage();
    }
}
