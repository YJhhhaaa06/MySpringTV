package io.github.yjhhhaaa06.videoweb.common.exception;

/**
 * 认证类异常（401 族）。状态取携带的 {@link ErrorCode}，**不从类推断**。
 *
 * <p>⚠️ 不要给它加「码由类决定」的构造器：登录失败必须是 {@link ErrorCode#AUTH_BAD_CREDENTIALS}
 * 一个码，而改密失败是 {@link ErrorCode#AUTH_OLD_PASSWORD_WRONG} —— 同一类、两个码。
 */
public class AuthException extends BusinessException {

    public AuthException(ErrorCode errorCode) {
        super(errorCode);
    }

    public AuthException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public AuthException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
