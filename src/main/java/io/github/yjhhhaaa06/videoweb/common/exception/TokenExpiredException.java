package io.github.yjhhhaaa06.videoweb.common.exception;

/**
 * 令牌已过期（401）。从 TV {@code com.itheima.exception.TokenExpiredException} 承接。
 *
 * <p>迁移注意：TV 的 {@code JwtUtil.isTokenValid} 把 {@code JWTVerificationException}
 * 一律吞成 false，过期与伪造不区分。新实现的 JwtAuthFilter 保留这一外部可观察行为，
 * 但内部区分 TokenExpiredException 以便日志与后续双 Token 方案（决策③留后项）。
 */
public class TokenExpiredException extends AuthException {

    public TokenExpiredException() {
        super(ErrorCode.TOKEN_EXPIRED, ErrorCode.TOKEN_EXPIRED.getMessage());
    }

    public TokenExpiredException(String message) {
        super(ErrorCode.TOKEN_EXPIRED, message);
    }
}
