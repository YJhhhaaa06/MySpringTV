package io.github.yjhhhaaa06.videoweb.common.security;

/**
 * "是否管理员"的**窄端口**（S6-B1）。
 *
 * <h2>它解决什么</h2>
 * {@link JwtAuthFilter} 需要判断调用者是不是管理员（{@code /api/admin} 前缀的路径，
 * 沿袭 TV：需 {@code role == 1}）。此前它直接注入 {@code user.service.UserService}，
 * 于是形成了**基建反向依赖业务模块**的倒挂：
 *
 * <pre>
 *   common.security.JwtAuthFilter  ──►  user.service.UserService     ❌ 倒挂
 * </pre>
 *
 * 倒挂的害处是具体的：{@code common} 是所有模块的公共底座，一旦它依赖 {@code user}，
 * 就再也无法在不牵引 user 的情况下复用或测试 {@code common}；
 * 而且这条边会随"还有谁需要回答这个问题"不断加宽（今天是 isAdmin，明天可能是别的）。
 *
 * <h2>改法：依赖倒置</h2>
 * 在 {@code common} 里声明**接口**（本类），由 {@code user} 提供实现：
 *
 * <pre>
 *   common.security.JwtAuthFilter  ──►  common.security.AdminChecker   ◄──  user.service.UserService
 *                                        （接口在基建）                      （实现在业务）
 * </pre>
 *
 * 依赖方向由"基建 → 业务"变成"业务 → 基建"，与其余所有模块一致。
 *
 * <h2>为什么不直接把 {@code isAdmin} 做成一个 {@code boolean} 参数或配置</h2>
 * 这是**运行期查库**的判断（读 {@code users.role}），不是静态配置——它必须走 Service。
 *
 * <h2>为什么只抽一个方法</h2>
 * 端口宽度以**真实使用面**为准：{@link JwtAuthFilter} 只问这一个问题。
 * 不要因为"user 域还有别的能力"就把它们一并塞进来——那只是把倒挂换个地方。
 */
public interface AdminChecker {

    /**
     * 该用户是否为管理员。
     *
     * @param userId 用户 id（调用方保证非 null）
     * @return {@code true} 表示 {@code users.role == 1}
     */
    boolean isAdmin(long userId);
}
