package io.github.yjhhhaaa06.videoweb.content.model.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 作者开关评论区请求（{@code PUT /api/v1/contents/{id}/comment-enabled}，`T59` 载荷面）。
 *
 * <p>`T59` 前是 query/form 里的字符串 {@code enabled}（接受 {@code 1/true/0/false}）；
 * 载荷面按《接口契约.md》§2.5「布尔用真类型」改为 JSON 布尔：
 * {@code {"enabled": true}}。旧的字面量 {@code "1"}/{@code "0"} 不再被接受
 * （类型不匹配 ⇒ 400 {@code 参数取值不合法}），这是**契约变更**，前端重制按布尔传。
 *
 * <p>{@code @NotNull} 承接旧「{@code enabled} 不能为空」的 400；文案与旧版一致。
 */
public record CommentEnabledRequest(
        @NotNull(message = "enabled不能为空")
        Boolean enabled
) {
}
