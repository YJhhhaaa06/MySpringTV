package io.github.yjhhhaaa06.videoweb.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;

/**
 * 字段级错误条目（`T57`）：{@code errors:[{field, code, message}]} 的元素。
 *
 * <p>契约见 {@code architecture/tech/api/接口契约.md} §2.5 ——
 * 顶层 {@code msg} 是**人读摘要**，本类型是**机器读的真源**：前端据此把错误挂到具体输入框上。
 *
 * <h2>三个字段的口径</h2>
 * <ul>
 *   <li>{@code field} = **请求里的字段名**（不是 Java 属性名，也不是方法参数名）。
 *       本仓请求体没有 {@code @JsonProperty} / 命名策略改写 ⇒ 与属性名同形；
 *       若将来引入了蛇形转换，**必须**在这里改成"请求名"（前端认的是它）。
 *       请求整体不可解析时**无法**定位字段 ⇒ 该条不出现 {@code field}
 *       （{@link JsonInclude.Include#NON_NULL}，与 {@code data} 的缺省口径一致）。</li>
 *   <li>{@code code} = {@link ErrorCode}，**不许**就地写字符串常量。
 *       目前校验族恒为 {@link ErrorCode#PARAM_INVALID}：粒度判据（§2.2）是"前端会不会做出不同决定"，
 *       而字段级定位已由 {@code field} 承担，给每条约束造一个码只会让字典膨胀（没人翻）。
 *       本字段保留是为了**形状守恒**：将来真出现"某字段要不同处置"（判据①/③），只需换码、不改形状。</li>
 *   <li>{@code message} = **稳定文案**（不随入参变化）。异常 message 里可能带着客户端发来的值
 *       （{@code for value [xxx]}），那类文本**只进日志**，绝不进响应体 —— 否则 PII 会经错误信封回显给调用方。</li>
 * </ul>
 *
 * <p>★ 反序列化不是契约的一部分（前端只读）：本类型只用于服务端构造与序列化。
 *
 * @param field   请求字段名；整体不可解析时为 {@code null}（不序列化）
 * @param code    拒绝原因所属的码（取自 {@link ErrorCode}）
 * @param message 该字段的稳定中文文案（人读，前端可覆盖）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiFieldError(String field, ErrorCode code, String message) {

    /** 字段级错误的构造入口（保证 {@code code} 只能来自码表）。 */
    public static ApiFieldError of(String field, ErrorCode code, String message) {
        return new ApiFieldError(field, code, message);
    }

    /** 无法定位字段时的条目（整体请求体不可解析 / 反序列化失败在根上）。 */
    public static ApiFieldError ofWholeRequest(ErrorCode code, String message) {
        return new ApiFieldError(null, code, message);
    }

    /**
     * 人读摘要用的单行文本：{@code field: message}，无字段时只有 {@code message}。
     *
     * <p>顶层 {@code msg} 由各条摘要拼接而成（见 {@link ApiErrorWriter#summary}）——
     * 摘要与 {@code errors[]} **同源**，不各拼一套。
     */
    public String toSummary() {
        return field == null ? message : field + ": " + message;
    }
}
