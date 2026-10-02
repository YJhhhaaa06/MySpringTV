package io.github.yjhhhaaa06.videoweb.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入当前登录用户 id 到控制器方法参数。
 *
 * <p>由 {@link CurrentUserIdArgumentResolver} 从请求属性读取
 * （{@link JwtAuthFilter#ATTR_USER_ID}）。
 *
 * <p>取代 TV 的 {@code (Long) req.getAttribute("userId")} 手工取值——
 * 那个写法把类型转换与空判断散落在每个 Controller 里（{@code changePassword} /
 * {@code changeUserName} 各写一遍 null 检查）。用参数解析器后，
 * 「需要登录」由 {@link RequiresLogin} 声明、userId 由本注解注入，控制器只剩业务。
 *
 * <h2>{@link #required()} —— S5 新增的"匿名可见但已登录则个性化"</h2>
 * TV 有一批端点**公开可访问**，但"带了 token 就返回个性化字段"：
 * {@code /search/IdSearch}（isLiked / isFollowed）、{@code /start}、{@code /search/keywordSearch}、
 * {@code /profile}、{@code /comment/show}、{@code /comment/replies}。
 * TV 的写法是 {@code Long userId = (Long) req.getAttribute("userId")}——**天然可空**。
 *
 * <p>原实现的注解语义是"必须有身份"（取不到就抛 401，防止"忘了标 {@code @RequiresLogin}"
 * 时把未登录静默变成 {@code userId = 0}）。对上面这批端点，那个语义恰好相反：
 * 它们**本来就不该要求登录**。故新增 {@code required = false}：
 * <ul>
 *   <li>{@code required = true}（默认）—— 取不到 ⇒ 401。语义与原来**完全一致**，
 *       现有端点的行为零变化；</li>
 *   <li>{@code required = false} —— 取不到 ⇒ 注入 {@code null}（参数类型须为包装类型
 *       {@code Long}）。用于"匿名可见 + 已登录则个性化"。</li>
 * </ul>
 * ★ 注意：{@code required = false} 的参数**不得**同时依赖 {@code @RequiresLogin}——
 * 两者语义相反（前者是"可有可无"，后者是"必须有"）。测试里对公开端点断言
 * {@code requiresLogin == false} 就是这条纪律的固化（见 {@code SecurityContractTests}）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface CurrentUserId {

    /**
     * 是否**必须**有登录身份。
     *
     * @return {@code true}（默认）= 取不到就 401；{@code false} = 取不到则注入 {@code null}
     */
    boolean required() default true;
}
