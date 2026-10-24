package io.github.yjhhhaaa06.videoweb.common.exception;

/**
 * 已认证但无权操作该资源（403）。
 *
 * <p>码恒为 {@link ErrorCode#AUTH_FORBIDDEN}：具体是哪条规则（不是自己的评论 / 作品 / 收藏夹）
 * 只影响文案，前端处置恒为「提示无权限」—— 不需要为它们各开一个码。
 * 与 {@link AuthException}（401 = 没通过身份校验）的分界：这里身份是**有效的**，只是没有这个资源的处置权。
 */
public class ForbiddenException extends BusinessException {

    public ForbiddenException(String message) {
        super(ErrorCode.AUTH_FORBIDDEN, message);
    }

    public ForbiddenException(String message, Throwable cause) {
        super(ErrorCode.AUTH_FORBIDDEN, message, cause);
    }
}
