package io.github.yjhhhaaa06.videoweb.common.exception;

/**
 * 令牌有效、但该账号已不存在（401）。
 *
 * <p>⚠️ **只用于「用持有者自己的令牌查自己」**（{@code /user/me}、改密）：请求者本来就持有该 userId
 * 的令牌，报出来不增加信息量，前端处置 = 清态重新登录。
 *
 * <p>⚠️⚠️ **绝对不要用在登录路径上**。登录时回答「用户不存在」= 给出手机号是否注册过的探测口
 * （脚本可批量筛选），登录失败一律 {@link ErrorCode#AUTH_BAD_CREDENTIALS}。
 * 旧版类注释写着「登录/鉴权场景下不区分探测面」，但实现区分了两个码 —— 那正是本专项修掉的缺陷。
 */
public class UserNotFoundException extends AuthException {

    public UserNotFoundException() {
        super(ErrorCode.AUTH_USER_NOT_FOUND);
    }

    public UserNotFoundException(String message) {
        super(ErrorCode.AUTH_USER_NOT_FOUND, message);
    }
}
