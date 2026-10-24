package io.github.yjhhhaaa06.videoweb.common.exception;

/**
 * 改密时的「原密码不匹配」（401）。
 *
 * <p>与登录失败（{@link BadCredentialsException}）**必须分开**：此时操作者已通过令牌证明身份，
 * 报「原密码错」不构成探测口，而前端要据此把错误挂到「原密码」输入框上。
 *
 * <p>旧名 {@code PasswordIncorrectException} 改名而来 —— 那个名字读起来就是「登录密码错」，
 * 是下一个人把两个码写反的最短路径。
 */
public class OldPasswordMismatchException extends AuthException {

    public OldPasswordMismatchException() {
        super(ErrorCode.AUTH_OLD_PASSWORD_WRONG);
    }

    public OldPasswordMismatchException(String message) {
        super(ErrorCode.AUTH_OLD_PASSWORD_WRONG, message);
    }
}
