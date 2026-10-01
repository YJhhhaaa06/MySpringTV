package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT 配置（决策③：「保留 jwt，暂不迁移 Spring Security」）。
 *
 * <p>对应 TV 的 {@code AppConfig.getJwtSecret()} / {@code getJwtExpireMillis()}。
 * 旧实现把 secret 硬编码为字面量 "STONE" 且无覆盖链，这里改为标准配置绑定 +
 * 环境变量可覆盖（决策⑨：敏感值走环境变量）。
 *
 * @param secret       HMAC256 签名密钥
 * @param expireHours  token 有效期（小时），TV 原值 2
 */
@ConfigurationProperties(prefix = "video.jwt")
public record JwtProperties(String secret, Duration expireHours) {

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("video.jwt.secret 不能为空");
        }
        if (expireHours == null || expireHours.isZero() || expireHours.isNegative()) {
            throw new IllegalArgumentException("video.jwt.expire-hours 必须为正");
        }
    }
}
