package io.github.yjhhhaaa06.videoweb.common.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.github.yjhhhaaa06.videoweb.common.config.JwtProperties;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * JWT 签发与校验。
 *
 * <p>从 TV {@code com.itheima.util.JwtUtil} 承接：算法 HMAC256、{@code sub} 存 userId、自动校验 exp。
 *
 * <p><b>两处必要的改写</b>（其余逻辑逐行保真）：
 * <ol>
 *   <li>旧版是**全静态**工具类，secret/expire 在类初始化时从 {@code AppConfig} 静态读取——
 *       这在测试里无法覆盖（改配置要重启 JVM）。改为 Spring 组件 + 构造器注入
 *       {@link JwtProperties}，使测试能注入短 TTL 来验证过期分支。</li>
 *   <li>旧版 {@code isTokenValid} 把 {@code JWTVerificationException} 一律吞成 false。
 *       新实现区分「过期」与「其他非法」，以便 (a) 记录可诊断的日志、
 *       (b) 为决策③留后的双 Token 方案预留区分点。
 *       <b>对外行为保持不变</b>——两者在 HTTP 层都是 401。</li>
 * </ol>
 */
@Component
public class JwtService {

    private final Algorithm algorithm;
    private final long expireMillis;

    public JwtService(JwtProperties properties) {
        this.algorithm = Algorithm.HMAC256(properties.secret());
        this.expireMillis = properties.expireHours().toMillis();
    }

    /** 生成 token：sub = userId，带 iat / exp（与 TV 一致）。 */
    public String generateToken(Long userId) {
        long now = System.currentTimeMillis();
        return JWT.create()
                .withSubject(userId.toString())
                .withIssuedAt(new Date(now))
                .withExpiresAt(new Date(now + expireMillis))
                .sign(algorithm);
    }

    /**
     * 校验并解出 userId。
     *
     * @throws io.github.yjhhhaaa06.videoweb.common.exception.TokenExpiredException 令牌过期（401）
     * @throws InvalidTokenException 签名不符 / 格式非法 / 缺 sub（401）
     */
    public Long verifyAndGetUserId(String token) {
        DecodedJWT jwt;
        try {
            jwt = JWT.require(algorithm).build().verify(token);
        } catch (TokenExpiredException e) {
            // 注意：此处 catch 的是 auth0 的 TokenExpiredException，抛的是本项目的同名异常
            throw new io.github.yjhhhaaa06.videoweb.common.exception.TokenExpiredException();
        } catch (Exception e) {
            throw new InvalidTokenException();
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            throw new InvalidTokenException();
        }
    }
}
