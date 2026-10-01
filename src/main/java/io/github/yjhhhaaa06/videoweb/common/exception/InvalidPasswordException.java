package io.github.yjhhhaaa06.videoweb.common.exception;

/** 密码格式不合法（400）。从 TV {@code com.itheima.exception.InvalidPasswordException} 承接。 */
public class InvalidPasswordException extends ParamException {

    public InvalidPasswordException() {
        super(ErrorCode.INVALID_PASSWORD.getMessage());
    }

    public InvalidPasswordException(String message) {
        super(message);
    }
}
