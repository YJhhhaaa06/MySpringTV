package io.github.yjhhhaaa06.videoweb.common.exception;

/**
 * 状态冲突类拒绝（409 族，**语义分类，不再隐含状态**）。
 *
 * <p>判据：参数合法，但**当前状态**不允许（重复点赞、已关注、夹已满、手机号占用…）。
 * 状态以携带的 {@link ErrorCode#getStatus()} 为准（{@code SYSTEM_DUPLICATE_KEY} 也走这里）。
 *
 * <p>⚠️ 必须显式给码：旧构造器默认 {@code CONFLICT} 泛码，前端拿它无法区分
 * 「已点过赞」和「夹满了」这两种需要完全不同 UI 反馈的情况。
 */
public class ConflictException extends BusinessException {

    public ConflictException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public ConflictException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
