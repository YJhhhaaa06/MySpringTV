package io.github.yjhhhaaa06.videoweb.common.exception;

/**
 * 令牌已过期（401）。
 *
 * <p>迁移注意：TV 的 {@code JwtUtil.isTokenValid} 把 {@code JWTVerificationException}
 * 一律吞成 false，过期与伪造不区分。新实现的 JwtAuthFilter 保留「都拒绝」的外部行为，
 * 但**码分开**（{@code AUTH_TOKEN_EXPIRED} vs {@code AUTH_UNAUTHORIZED}）：
 * 前者前端提示「已过期，请重新登录」，是将来双 Token 自动刷新的判据；后者是「压根没带/带了个假的」。
 */
public class TokenExpiredException extends AuthException {

    public TokenExpiredException() {
        super(ErrorCode.AUTH_TOKEN_EXPIRED);
    }

    public TokenExpiredException(String message) {
        super(ErrorCode.AUTH_TOKEN_EXPIRED, message);
    }
}

