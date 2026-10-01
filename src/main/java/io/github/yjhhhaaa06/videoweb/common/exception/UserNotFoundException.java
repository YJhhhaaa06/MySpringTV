package io.github.yjhhhaaa06.videoweb.common.exception;

/**
 * 用户不存在（401，**不是 404**）。
 * 从 TV {@code com.itheima.exception.UserNotFoundException} 承接。
 * 认证语义：登录/鉴权场景下不区分「用户不存在」与「密码错误」的探测面。
 */
public class UserNotFoundException extends AuthException {

    public UserNotFoundException() {
        super(ErrorCode.USER_NOT_FOUND, ErrorCode.USER_NOT_FOUND.getMessage());
    }

    public UserNotFoundException(String message) {
        super(ErrorCode.USER_NOT_FOUND, message);
    }
}
