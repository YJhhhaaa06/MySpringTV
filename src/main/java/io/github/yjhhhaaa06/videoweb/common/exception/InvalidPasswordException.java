package io.github.yjhhhaaa06.videoweb.common.exception;

/**
 * 密码格式不合法（400）。
 *
 * <p>★ 携带的是 {@link ErrorCode#PARAM_INVALID}，**不是**一个独立的码：注册与改密的请求记录都带
 * {@code @Pattern}，这个异常只在"Service 内部守卫兜底"时才抛出 —— 若给它单独一个码，
 * 同一个原因（密码格式不合格）会因"被 `@Valid` 还是被 Service 拦下"而给出两个不同的码。
 * 字段级定位由 {@code T57} 的 {@code errors[].field} 负责。
 *
 * <p>保留这个类型本身仍然有用：它是"密码规则"这件事在代码里的**具名落点**
 * （规则改一处、两个入口同时生效），也是《事务边界决策表》里 U-2 校验顺序的可读标记。
 */
public class InvalidPasswordException extends ParamException {

    public InvalidPasswordException() {
        super(ErrorCode.PARAM_INVALID, "密码格式错误");
    }

    public InvalidPasswordException(String message) {
        super(ErrorCode.PARAM_INVALID, message);
    }
}
