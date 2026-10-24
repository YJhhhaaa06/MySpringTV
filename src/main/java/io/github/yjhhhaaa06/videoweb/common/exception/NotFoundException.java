package io.github.yjhhhaaa06.videoweb.common.exception;

/**
 * 资源不存在（404 族，**语义分类**）。
 *
 * <p>⚠️ 必须显式给码（{@code CONTENT_NOT_FOUND} / {@code COMMENT_NOT_FOUND} / {@code USER_NOT_FOUND} …）：
 * 旧构造器默认 {@code NOT_FOUND} 泛码，是当年 {@code CONTENT_NOT_FOUND} 与 {@code COMMENT_NOT_FOUND}
 * 沦为死码的**直接原因** —— 调用方写不出「是哪种资源」，只好回一句「资源不存在」。
 * 前端据此无法决定是「刷新详情」还是「刷新评论列表」。
 */
public class NotFoundException extends BusinessException {

    public NotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public NotFoundException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
