package io.github.yjhhhaaa06.videoweb.common.exception;

/** 参数类异常（400）。从 TV {@code com.itheima.exception.ParamException} 承接。 */
public class ParamException extends BusinessException {

    public ParamException(String message) {
        super(ErrorCode.PARAM_ERROR, message);
    }

    public ParamException(String message, Throwable cause) {
        super(ErrorCode.PARAM_ERROR, message, cause);
    }
}
