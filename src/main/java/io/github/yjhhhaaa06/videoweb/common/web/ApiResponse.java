package io.github.yjhhhaaa06.videoweb.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;

import java.util.List;

/**
 * 统一响应信封。
 *
 * <h2>形状（{@code T55} 定稿、{@code T57} 补 {@code errors[]}，契约见
 * {@code architecture/tech/api/接口契约.md} §2.1 / §2.5）</h2>
 * <ul>
 *   <li><b>成功</b>：{@code {code:"OK", msg:"success", data}}；{@code data} 为空时**不出现**
 *       （{@link JsonInclude.Include#NON_NULL}）。</li>
 *   <li><b>失败</b>：{@code {code:"<域>_<原因>", msg, errors?}}；{@code data} 不出现。
 *       {@code errors} 只在**校验族**（{@code @Valid} / 缺参 / 类型不匹配 / 请求体不可解析）出现，
 *       元素见 {@link ApiFieldError}；业务拒绝（如"标题不能为空"这类 Service 内守卫）仍只有 {@code msg}。</li>
 * </ul>
 *
 * <h2>为什么 {@code code} 从 int 改成 String</h2>
 * 旧实现 {@code code} 就是 HTTP 状态码本身（{@code 401} / {@code 404}…），与响应行完全重复
 * ⇒ body 零信息增量，前端唯一能判别的东西是中文 {@code msg}。现在 {@code code} 是**稳定字符串枚举**
 * （{@link ErrorCode}），前端拿它翻字典；HTTP status 只表类别，做通用分支。
 *
 * <p><b>两个刻意的契约细节</b>（勿"顺手优化"，否则破坏与旧前端的字段约定）：
 * 字段名是 {@code msg} 而非 {@code message}；成功码是字面量 {@code "OK"} 而不是 {@code 200}。
 *
 * @param code   业务码。成功恒为 {@link #OK}；失败为 {@link ErrorCode#getCode()}
 * @param msg    提示消息：成功恒为 {@code "success"}；失败是给人读的兜底文案（前端可覆盖）
 * @param data   业务数据，失败时为 null（且不序列化）
 * @param errors 字段级错误（`T57`）；只在**校验族**出现，为空时 null（且不序列化）。
 *               与 {@code data} 互斥：失败体永不出现 {@code data}，成功体永不出现 {@code errors}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(String code, String msg, T data, List<ApiFieldError> errors) {

    /** 成功码（字面量，不随 HTTP status 变：201 也回 {@code "OK"}，前端只判这一处）。 */
    public static final String OK = "OK";

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(OK, "success", data, null);
    }

    /**
     * 带自定义 {@code msg} 且**同时带 data** 的成功响应。
     *
     * <p>投币签到（{@code POST /api/v1/users/me/check-ins}）要在回带新余额的同时区分
     * 「签到成功」与「今日已签到」（{@code architecture/domain/coin/coin.md} §二）——
     * 幂等不是错误，故不能走 {@link #error}；而 {@link #success(Object)} 的 msg 恒为
     * {@code "success"}。两参形态是这两条约束的唯一交点。
     *
     * <p>★ <b>不产生重载歧义</b>：{@code ApiResponse.success("删除成功")} 只有**一个**实参，
     * 编译期只能落到 {@link #success(Object)}（{@code T = String}），结果仍是
     * {@code msg="success", data="删除成功"}。只有写满两个实参时才落到本方法。
     *
     * @param msg  给用户看的提示（本仓只用于签到；不要把它当"第二套错误文案"用）
     * @param data 业务数据
     */
    public static <T> ApiResponse<T> success(String msg, T data) {
        return new ApiResponse<>(OK, msg, data, null);
    }

    /** 无数据体的成功响应（如修改密码）。 */
    public static ApiResponse<Void> success() {
        return new ApiResponse<>(OK, "success", null, null);
    }

    /**
     * 失败信封。
     *
     * @param code 字符串码（必须来自 {@link ErrorCode}，不许就地写字面量）
     * @param msg  兜底中文文案
     */
    public static <T> ApiResponse<T> error(String code, String msg) {
        return new ApiResponse<>(code, msg, null, null);
    }

    /** 失败信封（码与默认文案都取自枚举）。 */
    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return error(errorCode.getCode(), errorCode.getMessage());
    }

    /**
     * 带字段级错误的失败信封（`T57`）：{@code {code, msg, errors}}。
     *
     * @param errors 至少一条；调用方负责保证非空（空列表会序列化成 {@code "errors":[]}，
     *               那等于"我失败了但列不出原因"，前端无法区分"没有字段错误"与"忘了填"）
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String msg, List<ApiFieldError> errors) {
        if (errors == null || errors.isEmpty()) {
            throw new IllegalArgumentException("errors 必须非空：没有字段级原因时用不带 errors 的失败信封");
        }
        return new ApiResponse<>(errorCode.getCode(), msg, null, List.copyOf(errors));
    }
}
