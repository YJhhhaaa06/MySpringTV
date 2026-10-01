package io.github.yjhhhaaa06.videoweb.common.exception;

/**
 * 业务异常基类——从 TV {@code com.itheima.exception.BusinessException} 原样承接。
 *
 * <p>承载对外的 {@code code}（信封里的业务码）。迁移对照见《迁移参照系》§2.2：
 * 旧代码在各 Service 里手工 {@code catch (SQLException)} 再包成 DatabaseException，
 * 这部分**骨架删掉**——Spring 的 {@code DataAccessException} 已接管，
 * 由 {@link GlobalExceptionHandler} 统一出口。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
