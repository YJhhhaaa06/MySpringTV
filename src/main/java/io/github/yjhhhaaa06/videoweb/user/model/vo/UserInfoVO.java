package io.github.yjhhhaaa06.videoweb.user.model.vo;

/**
 * 当前用户信息。**不含 hashedPassword**。
 *
 * <p>TV 没有独立的 /user/me 接口（前端靠登录响应的 username 维持），
 * 本接口是切片 0 为「持 token 访问受保护接口」验收闭环新增的最小端点。
 * 新增理由与差异登记见《事务边界决策表》U 系列。
 */
public record UserInfoVO(long id, String username, String phone, boolean admin) {
}
