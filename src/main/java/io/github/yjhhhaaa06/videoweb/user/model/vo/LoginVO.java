package io.github.yjhhhaaa06.videoweb.user.model.vo;

/**
 * 登录/注册响应体——承接 TV {@code com.itheima.user.model.vo.LoginVO}。
 *
 * <p><b>token 可为 null</b>：这是刻意的产品语义，不是缺陷。见《事务边界决策表》U-1：
 * 注册成功但自动登录失败时返回 {@code token == null}，前端据此提示"注册成功，请手动登录"。
 *
 * <p>契约：TV 的 login 接口返回 {@code {code, msg, data:{id, username, token}}}，
 * 本记录即 data 的形状（id/username/token 三字段，与旧版一致）。
 */
public record LoginVO(long id, String username, String token) {
}
