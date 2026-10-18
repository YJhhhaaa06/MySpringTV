package io.github.yjhhhaaa06.videoweb.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「该端点对匿名可见」—— 授权判定的**唯一放行口**。
 *
 * <p>可标在方法或类上（类上表示该 Controller 全部端点公开）。
 *
 * <h2>为什么需要它：默认拒绝（fail-closed）</h2>
 * 本仓的授权判定在 2026-10-18 从「**声明才拦**」改为「**声明才放**」：
 *
 * <pre>
 * 旧：requiresLogin = 标了 {@link RequiresLogin}            ⇒ 忘标注解 = 端点对全世界开放（fail-open）
 * 新：requiresLogin = !标了 {@code @Public}（@RequiresAdmin 亦要求登录）⇒ 忘标注解 = 401（fail-closed）
 * </pre>
 *
 * <p><b>为什么必须改</b>：旧口径的漏标**不会响亮失败**。若漏标端点恰好接收
 * {@code @CurrentUserId(required = true)}，参数解析器会兜出 401 —— 看起来是对的，
 * 掩盖了"注解没生效"这个事实（正是 {@link RequestMappingLookup} 类注释记录的那次"机制完全失效"的
 * 同一类假象）。真正敞开的只有**不接收 userId** 的端点（如历史上 `/like/content/count`）。
 * 默认拒绝把"漏标"从**静默开放**变成**立刻可见的 401**。
 *
 * <h2>代价（已登记）</h2>
 * 新增公开端点必须显式标本注解，漏标 = 匿名访问得 401（可见故障，不是安全漏洞）。
 * 台账见 {@code 安全与鉴权-已知代价.md}。
 *
 * <h2>作用域边界（别把它读成"整个应用只有这一个放行口"）</h2>
 * 默认拒绝的保护域 = {@code requestMappingHandlerMapping} 里的**控制器端点**。
 * 静态资源（{@code /upload/**}、{@code /static/**}）、{@code /actuator/**}、未映射路径
 * 由**别的** {@code HandlerMapping} 承接，本注解与判定机制都不参与（现状见
 * {@code 安全与鉴权.md} §2.3 与 §七）。
 *
 * <h2>冲突语义</h2>
 * 同一处理器同时出现 {@code @Public} 与 {@link RequiresLogin} / {@link RequiresAdmin} 时
 * **取更严的一条**（要求登录）并打 WARN —— 声明矛盾属于配置错误，不允许"安静的宽松"。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Public {
}
