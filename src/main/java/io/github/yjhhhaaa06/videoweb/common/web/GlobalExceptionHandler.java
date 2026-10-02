package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.common.exception.BusinessException;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
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
 * 统一异常出口（决策⑧：错误出口统一 + 两层语义对齐）。
 *
 * <p>从 TV 的 {@code ExceptionFilter} 承接职责。旧的出口是 Servlet Filter 里的
 * 手写 type-dispatch；此处改为 {@code @RestControllerAdvice}，注册在 Spring MVC 的
 * 异常解析链上，与 {@code @Valid} 校验失败共用同一个出口。
 *
 * <p><b>HTTP 状态码正确化</b>：TV 现状是「协议层恒 200、语义码在 body」。
 * 这里把 HTTP status 与业务 code 对齐（401→401、403→403、404→404、409→409），
 * 同时**保留 body 里的 code**，两层语义一致。
 *
 * <p>注意：Spring Security 未接入（决策③后置），因此暂无
 * {@code AuthenticationEntryPoint}/{@code AccessDeniedHandler} 需要对齐；
 * 接入时须回到本类补齐 401/403 的出口一致性。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：业务码已是对外语义，直接映射为 HTTP 状态。
     * 若业务码不是合法 HTTP 状态（例如自定义 1001），回落到 400 以免产生非法响应。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex, HttpServletRequest req) {
        int code = ex.getCode();
        HttpStatus status = HttpStatus.resolve(code);
        if (status == null) {
            log.warn("业务码 {} 不是合法 HTTP 状态，回落 400: {} {}", code, req.getMethod(), req.getRequestURI());
            status = HttpStatus.BAD_REQUEST;
        }
        // 可预期的业务拒绝：只记结论，不记堆栈（沿袭 TV LOG_CONVENTION「包装点即源头」）
        log.info("业务异常 {} {} -> code={}, msg={}", req.getMethod(), req.getRequestURI(), code, ex.getMessage());
        return ResponseEntity.status(status).body(ApiResponse.error(code, ex.getMessage()));
    }

    /** {@code @Valid} 校验失败：取第一条字段错误作为提示（对齐 TV 单条 message 的形状）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest req) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + defaultMessage(fe))
                .collect(Collectors.joining("; "));
        log.info("参数校验失败 {} {} -> {}", req.getMethod(), req.getRequestURI(), detail);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.PARAM_ERROR.getCode(), detail));
    }

    /**
     * 缺参 / 类型不匹配 / 请求体不可解析 / **缺 multipart part**：均归为参数错误。
     *
     * <p>⚠️ 最后一类（{@link MissingServletRequestPartException}）是 S7 加入的：
     * 上传端点的必填文件 part 缺失时 Spring 抛它。TV 侧同场景是
     * {@code getPart → null → NPE 未捕获 → 500}（《决策留痕表》D-11 的记录来源），
     * 本实现按 D-3 先例统一归 400。
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestPartException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex, HttpServletRequest req) {
        log.info("请求参数错误 {} {} -> {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.PARAM_ERROR.getCode(), ErrorCode.PARAM_ERROR.getMessage()));
    }

    /**
     * 唯一键冲突：DB 层兜底竞态（决策②：SQLException 手工包装退役，DataAccessException 接管）。
     *
     * <p>业务层已先查重，但并发下仍可能撞唯一键。此处映射为 409，与业务层的
     * {@code ConflictException}/{@code DuplicatePhoneException} 保持同一对外语义，
     * 使「先查后插」的竞态对客户端不可见（回应《事务边界决策表》U-2 的补注）。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateKey(DuplicateKeyException ex,
                                                                HttpServletRequest req) {
        log.warn("唯一键冲突 {} {} -> {}", req.getMethod(), req.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ErrorCode.CONFLICT.getCode(), ErrorCode.CONFLICT.getMessage()));
    }

    /**
     * 其余数据访问异常：TV 里这些被各 Service 手工 {@code catch (SQLException)} 包成
     * DatabaseException 并**在包装点记堆栈**。新实现集中在唯一出口记录，业务代码不再需要 try/catch。
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccess(DataAccessException ex, HttpServletRequest req) {
        log.error("数据访问异常 {} {}", req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.DATABASE_ERROR.getCode(), ErrorCode.DATABASE_ERROR.getMessage()));
    }

    /** 兜底：未预期异常一律 500，且不把内部细节泄露到响应体。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("未预期异常 {} {}", req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.SERVER_ERROR.getCode(), ErrorCode.SERVER_ERROR.getMessage()));
    }

    private static String defaultMessage(FieldError fe) {
        return fe.getDefaultMessage() == null ? "非法值" : fe.getDefaultMessage();
    }
}
