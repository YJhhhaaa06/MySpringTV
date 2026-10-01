package io.github.yjhhhaaa06.videoweb.common.exception;

/** 密码错误（401）。从 TV {@code com.itheima.exception.PasswordIncorrectException} 承接。 */
public class PasswordIncorrectException extends AuthException {

    public PasswordIncorrectException() {
        super(ErrorCode.WRONG_PASSWORD, ErrorCode.WRONG_PASSWORD.getMessage());
    }

    public PasswordIncorrectException(String message) {
        super(ErrorCode.WRONG_PASSWORD, message);
    }
}
