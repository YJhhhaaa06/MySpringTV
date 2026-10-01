package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 临时放行 Spring Security 的默认保护链。
 *
 * <h2>为什么需要这个类</h2>
 * 本项目引入了 {@code spring-boot-starter-security}（决策③需要其 BCrypt 等能力），
 * 但**尚未迁移到 Spring Security 的过滤器链**——切片 0 的鉴权由自研
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
 * <h2>⚠️ 这是临时状态，接入决策③时必须删掉</h2>
 * 迁移 Spring Security 时，这个类要被真正的授权规则取代——
 * 届时 {@code @RequiresLogin} 注解应映射为 {@code authorizeHttpRequests} 的规则来源
 * （见 {@link io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin} 的说明）。
 * 在那之前，本类保证"能跑"但不提供任何 Security 层保护——不要再往这里加规则，
 * 授权规则的唯一事实源是 {@code @RequiresLogin}。
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
