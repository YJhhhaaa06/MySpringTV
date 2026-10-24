package io.github.yjhhhaaa06.videoweb.common.exception;

/**
 * 参数类拒绝（**语义分类，不再隐含状态**）。
 *
 * <p>状态以携带的 {@link ErrorCode} 为准 —— 本类里既有 400 的 {@code PARAM_INVALID}，
 * 也有同为 400 但前端处置不同的 {@code COIN_INSUFFICIENT} / {@code FAVORITE_FOLDER_FULL}。
 * 之所以不把它们也塞进 {@code PARAM_INVALID}：那些码要回答「该引导用户做什么」，
 * 而不是「哪个字段没填对」。
 *
 * <p>⚠️ 必须显式给码：旧构造器默认 {@code PARAM_ERROR}，等于给全站 30 多个抛出点共用一枚泛码。
 */
public class ParamException extends BusinessException {

    public ParamException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public ParamException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
