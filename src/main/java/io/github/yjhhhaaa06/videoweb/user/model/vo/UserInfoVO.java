package io.github.yjhhhaaa06.videoweb.user.model.vo;

import java.math.BigDecimal;

/**
 * 当前用户信息。**不含 hashedPassword / phone**。
 *
 * <p>TV 没有独立的 /user/me 接口（前端靠登录响应的 username 维持），
 * 本接口是切片 0 为「持 token 访问受保护接口」验收闭环新增的最小端点。
 * 新增理由与差异登记见《事务边界决策表》U 系列。
 *
 * <h2>★ 不返回 {@code phone}（2026-11-08）</h2>
 * 它是**唯一一个前端从不消费**的字段（{@code toCurrentUser} 根本不映射它，全仓也没有第二处读它），
 * 而这个端点每个页面装载都要打一次 ⇒ 等于把手机号按页面浏览量散播。
 * 本端点的职责是"渲染当前用户"，不是"账户详情"；真要展示手机号应另开账户端点（现无消费方）。
 * 同一条判据此前已用于"不返回 {@code hashedPassword}"——区别只是散列是灾难、手机号是隐私。
 *
 * @param coin       硬币余额，**一位小数的 {@link BigDecimal}**（如 {@code 1.0} / {@code 1.5}）。
 *                   ⚠️ 不用 {@code double}：{@code 0.1} 在二进制浮点下带尾差，而硬币余额需要
 *                   与数据库的十分位整数精确对应（展示口径 = 精确到一位小数、不舍入，
 *                   {@code architecture/domain/coin/coin.md} §三）。
 *                   本字段由 {@code UserService#getProfile} 在 VO 出口换算
 *                   （{@code coin_deci / 10}），DAO / 实体全程保持十分位整数。
 * @param signedToday 今天（**上海自然日**）是否已签到（{@code last_sign_date == SiteTime.today()}）。
 *                   ★ 它是给签到按钮**进页就置灰**用的：只看余额无法判断"今天签过没"，
 *                   否则用户只能点一下、拿到「今日已签到」才知道（{@code coin.md} §二）。
 *                   这只是同一次资料查询多带一列，不新增"签到状态"端点。
 * @param historyRecording 是否**记录**观看历史（{@code users.history_recording}，{@code R-25}）。
 *                   ★ 它是「记录浏览历史」开关的**唯一读入口**：客户端每次整页装载随本端点读一次，
 *                   拨动开关只写 {@code PUT /users/me/history-recording}。**不**新增"设置"端点族，
 *                   也**不**把用户偏好塞进内容载荷（那是跨用户共享的缓存）。
 *                   ⚠️ 服务端**不**据此校验 history 写端点 —— "不记录"由客户端不发写请求保证，
 *                   代价见 {@code architecture/domain/history/history-已知代价.md}。
 * @param avatarUrl 生效头像 URL（`R-27`）：用户选过的默认 / 自定义 URL，或按
 *                  {@code userId % 6} 回落的默认 URL。**对真实用户恒非空**（不是三态）。
 *                  DB 里存的是 {@code users.avatar_ref}（选择），解析规则只在
 *                  {@code AvatarRefs} 一处；前端顶栏 / 创作中心 / 评论框直接用它。
 */
public record UserInfoVO(long id, String username, boolean admin,
                         BigDecimal coin, boolean signedToday, boolean historyRecording,
                         String avatarUrl) {
}
