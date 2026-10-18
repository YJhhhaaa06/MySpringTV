package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 放行 Spring Security 的默认保护链 —— **这是终态，不是临时态**（2026-10-18 拍板）。
 *
 * <h2>为什么需要这个类</h2>
 * 本项目引入了 {@code spring-boot-starter-security}（为用其 BCrypt），
 * 但鉴权**不**走 Spring Security 的过滤器链——由自研
 * {@link io.github.yjhhhaaa06.videoweb.common.security.JwtAuthFilter} 承担。
 *
 * <p>只要 Security 在类路径上，{@code SecurityAutoConfiguration} 就会装配一条
 * **默认保护链**：全端点要求认证，并打印
 * {@code Using generated security password: ...}。它排在自研过滤器之前，
 * 于是所有请求（含 {@code /user/login}）都先被它拦成 401——
 * <b>症状很像"自己的鉴权写错了"，实际是 Security 的默认行为</b>。
 *
 * <p>本类显式提供一条全部放行的链以**替换**默认链，把鉴权交还给 JwtAuthFilter。
 *
 * <h2>★ 2026-10-18：从"临时态，接入决策③时必须删掉"改为"终态"</h2>
 * F-01 / F-02 已结案，结论是**不迁移到 Spring Security**（方案原貌 / 六条否决理由 / 五条推翻条件
 * 见 {@code 安全与鉴权.md} §六）。故本类不会被删除，它承担两件长期职责：
 * <ol>
 *   <li><b>压掉默认保护链</b>——否则一切都变 401；</li>
 *   <li><b>保留 Security 链本身在链上</b>——请求合法性（{@code //}、{@code ..}、编码斜杠等
 *       规范化绕过）由它自带的 {@code StrictHttpFirewall} 拦截，而这件事在业务代码里搜不到
 *       （见 {@code 安全与鉴权-已知代价.md} 第 2 条）。</li>
 * </ol>
 *
 * <h2>⚠️ 仍然不要往这里加规则</h2>
 * 授权规则的唯一事实源是三个注解（{@code @Public} / {@code @RequiresLogin} / {@code @RequiresAdmin}），
 * 由 {@code RequestMappingLookup} 读处理器得出。两条事实源一旦并存，就会出现
 * "注释说一堆、注解说另一堆"的分裂。
 */
@Configuration
public class SecurityPassthroughConfig {

    @Bean
    SecurityFilterChain passthroughFilterChain(HttpSecurity http) throws Exception {
        // 全放行：真正的鉴权在 JwtAuthFilter。关掉 Security 自带的表单/基本认证与 CSRF，
        // 避免它们对无状态 JSON API 产生干扰（CSRF 对纯 token 认证的 API 无意义）。
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
