package io.github.yjhhhaaa06.videoweb.common.exception;

/**
 * 注册时手机号已被占用（409）。
 *
 * <p>⚠️ 这是**已知**的手机号探测面：注册必须告诉用户「这个号注册过了」，否则无法完成注册。
 * 真正收口要靠短信验证（本期不做）。口径见 {@code architecture/tech/api/接口契约.md} §2.2「已知代价」。
 */
public class DuplicatePhoneException extends ConflictException {

    public DuplicatePhoneException() {
        super(ErrorCode.USER_PHONE_TAKEN, ErrorCode.USER_PHONE_TAKEN.getMessage());
    }

    public DuplicatePhoneException(String message) {
        super(ErrorCode.USER_PHONE_TAKEN, message);
    }
}
