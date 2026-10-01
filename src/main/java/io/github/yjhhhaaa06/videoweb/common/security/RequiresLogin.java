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
 * <p>决策③ 明确要根治 TV「两份硬编码清单」；本注解是那一步在"暂不迁移 Spring Security"
 * 前提下的落地形式。将来接入 Security 时，本注解可直接映射为
 * {@code authorizeHttpRequests} 的规则来源，语义不变。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresLogin {
}
