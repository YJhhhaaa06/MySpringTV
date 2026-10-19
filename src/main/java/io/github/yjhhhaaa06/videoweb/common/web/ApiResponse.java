package io.github.yjhhhaaa06.videoweb.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应信封（决策⑧：保留 code/msg/data 形状）。
 *
 * <p>从 TV {@code com.itheima.util.ResultUtil} 的 {@code Map<String,Object>} 改为类型化记录：
 * 编译期即可约束形状，避免旧实现里 {@code success(data, username)} 这种「往信封里塞额外键」
 * 的隐式约定（该重载在 TV 中用于登录接口，新实现把 username 放进 data 内，语义更清晰）。
 *
 * <p><b>两个刻意的契约细节</b>（勿"顺手优化"，否则破坏与旧前端的兼容）：
 * <ul>
 *   <li>字段名是 {@code msg} 而非 {@code message} —— TV 信封用 msg。</li>
 *   <li>{@code NON_NULL}：JSON 中不出现 null 字段。旧实现的 {@code error()} **不含 data 键**，
 *       若改成输出 {@code "data": null} 会改变响应体形状。</li>
 * </ul>
 *
 * @param code 业务码。成功恒为 200；失败时与 HTTP 状态码对齐（决策⑧「HTTP 状态码正确化」）
 * @param msg  提示消息，成功恒为 "success"（沿袭 TV）
 * @param data 业务数据，失败时为 null（且不序列化）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int code, String msg, T data) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    /**
     * 带自定义 {@code msg} 且**同时带 data** 的成功响应。
     *
     * <p>投币签到（{@code POST /coin/signIn}）要在回带新余额的同时区分
     * 「签到成功」与「今日已签到」（{@code architecture/domain/coin/coin.md} §三）——
     * 幂等不是错误，故不能走 {@link #error}；而 {@link #success(Object)} 的 msg 恒为
     * {@code "success"}。两参形态是这两条约束的唯一交点。
     *
     * <p>★ <b>不产生重载歧义</b>：既有的 {@code ApiResponse.success("删除成功")} 只有**一个**
     * 实参，编译期只能落到 {@link #success(Object)}（{@code T = String}），
     * 结果仍是 {@code msg="success", data="删除成功"}——与加重载之前逐字节一致。
     * 只有写满两个实参时才会落到本方法。
     *
     * @param msg  给用户看的提示（本仓只用于签到；不要把它当"第二套错误文案"用）
     * @param data 业务数据
     */
    public static <T> ApiResponse<T> success(String msg, T data) {
        return new ApiResponse<>(200, msg, data);
    }

    /** 无数据体的成功响应（如修改密码）。 */
    public static ApiResponse<Void> success() {
        return new ApiResponse<>(200, "success", null);
    }

    public static <T> ApiResponse<T> error(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }
}
