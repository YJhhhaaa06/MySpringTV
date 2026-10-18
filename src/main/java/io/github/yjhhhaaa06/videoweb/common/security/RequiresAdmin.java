package io.github.yjhhhaaa06.videoweb.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「该端点仅管理员可访问」—— 角色判定的**唯一事实源**。
 *
 * <p>可标在方法或类上（类上表示该 Controller 全部端点仅管理员可访问）。
 *
 * <h2>本注解同时蕴含「需要登录」</h2>
 * {@code requiresLogin = 标了 @RequiresAdmin || 标了 @RequiresLogin || 两者都没标}，
 * 因此 <b>不需要也不应该再叠 {@link RequiresLogin}</b>。
 * 这一条消掉了一个旧陷阱：早先管理员判定靠 {@code path.startsWith("/api/admin")} 前缀，
 * 控制器若忘了标 {@code @RequiresLogin}，**匿名**请求会跳过登录判定、掉进角色判定得到
 * **403**（而契约要求 401）—— "拦住了，但状态码错"。现在"仅管理员"在语义上必然包含
 * "需登录"，401/403 的分工不再依赖两个注解同时写对。
 *
 * <h2>为什么不继续用路径前缀</h2>
 * 旧实现 {@code isAdminPath} 读的是**未解码**的 {@code getRequestURI()} 前缀，而路由匹配用的是
 * 解码后的路径（PathPattern）—— **两个判据语义不同**，且"新增 admin 端点必须落在某前缀下"
 * 是一条只能靠人记住的不变量。改为"跟着命中的处理器走"后，**规则与路由是同一份事实**。
 *
 * <h2>代价（已登记）</h2>
 * 权威源从"路径前缀"变成"注解" ⇒ 新增 {@code /api/admin/**} 控制器若漏标本注解，
 * 它就只要求登录、不要求管理员。该缺口由
 * {@code SecurityContractTests#所有admin前缀控制器都必须标RequiresAdmin} 兜住
 * （新增一个漏标的 admin 控制器 ⇒ 该用例立刻变红）。台账见 {@code 安全与鉴权-已知代价.md}。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresAdmin {
}
