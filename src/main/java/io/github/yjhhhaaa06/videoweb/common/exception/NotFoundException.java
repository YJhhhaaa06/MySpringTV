package io.github.yjhhhaaa06.videoweb.common.exception;

/**
 * 资源不存在（404）。从 TV {@code com.itheima.exception.NotFoundException} 原样承接。
 *
 * <p>业务码 {@code NOT_FOUND = 404}，由 {@link GlobalExceptionHandler} 与 HTTP 状态对齐
 * （决策⑧「HTTP 状态码正确化」）。
 */
public class NotFoundException extends BusinessException {

    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }

    public NotFoundException(String message, Throwable cause) {
        super(ErrorCode.NOT_FOUND, message, cause);
    }
}
