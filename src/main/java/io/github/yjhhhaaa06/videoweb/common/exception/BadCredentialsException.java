package io.github.yjhhhaaa06.videoweb.common.exception;

/**
 * 登录失败（401）—— **账号不存在与密码错误共用此码，且文案也共用**。
 *
 * <p>★ 为什么要合并：分开回答等于提供「这个手机号注册过没有」的探测口，一个脚本就能批量筛出
 * 全站已注册手机号。这与收藏域「收藏夹不存在 / 收藏夹不公开 ⇒ 同 404 同文案」是同一条原则。
 *
 * <p>★ 只统一 code + msg 是**不够的**：账号不存在时若跳过 bcrypt 校验，响应会快 50–100ms，
 * 时间差本身就构成同一个探测口。故 {@code UserService#doLogin} 在用户不存在时也对一个
 * 固定哈希跑一次 {@code matches}（见该方法注释）。
 *
 * <p>⚠️ 不要为了「让前端提示更友好」把它拆回两个码。
 */
public class BadCredentialsException extends AuthException {

    public BadCredentialsException() {
        super(ErrorCode.AUTH_BAD_CREDENTIALS);
    }

    public BadCredentialsException(String message) {
        super(ErrorCode.AUTH_BAD_CREDENTIALS, message);
    }
}
