package io.github.yjhhhaaa06.videoweb.common.exception;

/** 手机号已被使用（409）。从 TV {@code com.itheima.exception.DuplicatePhoneException} 承接。 */
public class DuplicatePhoneException extends ConflictException {

    public DuplicatePhoneException() {
        super(ErrorCode.DUPLICATE_PHONE.getMessage());
    }

    public DuplicatePhoneException(String message) {
        super(message);
    }
}
