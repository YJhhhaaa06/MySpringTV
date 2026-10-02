package io.github.yjhhhaaa06.videoweb.common.exception;

/**
 * 无权操作（403）。从 TV {@code com.itheima.exception.ForbiddenException} 原样承接。
 *
 * <p>业务码 {@code FORBIDDEN = 403}，由 {@link GlobalExceptionHandler} 与 HTTP 状态对齐。
 * 与 {@link AuthException}（401，未登录）的区别：本异常表示**已登录但无权操作该资源**
 * （例如"只能删除自己的评论"）。
 */
public class ForbiddenException extends BusinessException {

    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }

    public ForbiddenException(String message, Throwable cause) {
        super(ErrorCode.FORBIDDEN, message, cause);
    }
}
