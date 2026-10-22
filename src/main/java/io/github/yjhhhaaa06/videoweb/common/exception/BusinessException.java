package io.github.yjhhhaaa06.videoweb.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类：**携带一个 {@link ErrorCode}**（字符串码 + HTTP 状态 + 默认文案）。
 *
 * <p>三层语义在这里第一次对齐：抛出点选「哪个码」= 决定 HTTP 类别 + 前端能翻到的字符串码 + 兜底文案。
 * 因此**没有**「只给 message、码由类决定」的构造器 —— 泛码（旧的 {@code NOT_FOUND} / {@code CONFLICT}）
 * 正是因为这种构造器才活下来的。
 *
 * <p>子类（{@link ParamException} / {@link ConflictException} / {@link NotFoundException} …）
 * 只承担**可读性**：让抛出点一眼看出是哪一类拒绝。**状态不再由类推断**，
 * 一律以携带的 {@link ErrorCode#getStatus()} 为准（例：{@code COIN_INSUFFICIENT} 是 400，
 * 即便它被写成 {@code ParamException}；{@code COUPON_UNAVAILABLE} 是 409）。
 *
 * <p>唯一的例外是 {@link ServiceBusyException}：它多带一个机器可读的 {@code Retry-After} 秒数，
 * 必须由专属 {@code @ExceptionHandler} 处理（父类那个会照常回 503 但丢掉响应头）。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** 对外字符串码（= {@link ErrorCode#name()}）。 */
    public String getCode() {
        return errorCode.getCode();
    }

    /** 对外 HTTP 状态（口径见 {@code architecture/tech/api/接口契约.md} §2.1）。 */
    public HttpStatus getStatus() {
        return errorCode.getStatus();
    }
}
