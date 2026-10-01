package io.github.yjhhhaaa06.videoweb.common.exception;

/** 认证类异常（401）。从 TV {@code com.itheima.exception.AuthException} 承接。 */
public class AuthException extends BusinessException {

    public AuthException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public AuthException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
