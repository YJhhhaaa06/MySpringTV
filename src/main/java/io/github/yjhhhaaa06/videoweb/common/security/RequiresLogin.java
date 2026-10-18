package io.github.yjhhhaaa06.videoweb.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「该端点需要登录」。
 *
 * <p>可标在方法或类上（类上表示该 Controller 全部端点需要登录）。
 *
 * <p><b>存在理由</b>：TV 用 {@code AuthFilter.PROTECTED_PREFIXES} / {@code PROTECTED_EXACT}
 * 两个硬编码静态集合表达这件事，带来两个问题：
 * <ul>
 *   <li><b>易漏</b>：新增需要登录的接口必须记得去改那张清单，漏改 = 安全缺口，且没有编译期或测试期提示。</li>
 *   <li><b>难复核</b>：清单与端点分处两地，评审时要人工比对。</li>
 * </ul>
 * 改成注解后，鉴权要求的**单一事实源就在方法签名上**——读到控制器即知是否需要登录，
 * 且可被测试自动校验（见 {@code SecurityContractTests}）。
 *
 * <h2>★ 2026-10-18 起它不是"判定来源"，而是"可读性声明"</h2>
 * 授权判定已改为**默认拒绝**（{@code loginRequired = !@Public}，见 {@code RequestMappingLookup#resolve}）：
 * 未标任何注解的端点**同样要求登录**。因此本注解的作用变成
 * <b>把"这里需要登录"写在代码里给人看</b>，而不是"标了才拦"。
 * 它仍然被 {@code RequestMappingLookup} 读取，但读它只为两件事：
 * <ol>
 *   <li>与 {@link Public} 冲突时**取更严**（要求登录）并 WARN；</li>
 *   <li>让"这一族整体受保护"在类级上有显式表达（{@code /like}、{@code /follow}、{@code /feed} 等整族无公开端点）。</li>
 * </ol>
 * ⚠️ 需要管理器角色的端点请用 {@link RequiresAdmin}（它**蕴含**登录，不要叠加本注解）。
 * ⚠️ 公开端点请用 {@link Public} —— 那是默认拒绝下**唯一**的放行口。
 *
 * <p>关于"将来接 Spring Security"：2026-10-18 已拍板**不迁**（方案原貌 / 否决理由 / 推翻条件见
 * {@code 安全与鉴权.md} §六），故本注解不会映射成 {@code authorizeHttpRequests} 的路径规则。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresLogin {
}
