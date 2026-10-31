package io.github.yjhhhaaa06.videoweb.user.model.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 「记录浏览历史」开关请求（{@code PUT /api/v1/users/me/history-recording}，{@code R-25}）。
 *
 * <p>载荷面按《接口契约.md》§2.5 统一为 JSON body：{@code {"historyRecording": false}}。
 * 字段名与响应体 {@code UserInfoVO.historyRecording} **逐字一致** —— 它同时是
 * "{@code field} = 请求里的字段名"的一端（失败体 {@code errors[field=historyRecording]}）。
 *
 * <p>★ 用包装 {@link Boolean} + {@code @NotNull}，不是原生 {@code boolean}：
 * 缺字段时 {@code boolean} 会静默绑成 {@code false}（"没传"被当成"关闭"），
 * 而这是一个**隐私开关** —— 静默取值比报错更坏。缺字段 ⇒ 400。
 */
public record UpdateHistoryRecordingRequest(
        @NotNull(message = "记录开关不能为空")
        Boolean historyRecording
) {
}
