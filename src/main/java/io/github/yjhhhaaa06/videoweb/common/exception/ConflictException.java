package io.github.yjhhhaaa06.videoweb.common.exception;

/** 冲突类异常（409）。从 TV {@code com.itheima.exception.ConflictException} 承接。 */
public class ConflictException extends BusinessException {

    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }

    public ConflictException(String message, Throwable cause) {
        super(ErrorCode.CONFLICT, message, cause);
    }
}
