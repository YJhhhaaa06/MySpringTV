package io.github.yjhhhaaa06.videoweb.common.security;

import io.github.yjhhhaaa06.videoweb.common.exception.AuthException;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;

/**
 * 非法令牌（签名不符 / 格式错误 / 缺 sub）。
 *
 * <p>TV 没有这个类型——它把一切 JWT 校验失败都吞成 {@code isTokenValid() == false}，
 * 对外统一 401「请先登录」。本类保持同一对外语义（{@link ErrorCode#UNAUTHORIZED}），
 * 只是给内部一个可区分的类型，便于日志与将来的双 Token 方案（决策③留后项）。
 */
public class InvalidTokenException extends AuthException {

    public InvalidTokenException() {
        super(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getMessage());
    }

    public InvalidTokenException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }
}
