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
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface CurrentUserId {
}
