package io.github.yjhhhaaa06.videoweb.user.model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 修改用户名请求（{@code PUT /api/v1/users/me/username}，`T59` 载荷面）。
 *
 * <p>`T59` 前是 query/form 参数 {@code userName}；载荷面按《接口契约.md》§2.5 统一为
 * JSON body：{@code {"userName": "…"}}。约束仍是 {@code @NotBlank}，文案逐字保留，
 * 失败体由 `T57` 的出口产出 {@code errors[field=userName]}。
 *
 * <p>★ 字段名必须逐字是 {@code userName}（不是 {@code username}）：它同时是
 * `T57` 口径里"{@code field} = 请求里的字段名"的一端，改名会同时改变对外错误体的
 * {@code field}。与响应体 {@code UserInfoVO.userName} 保持同名。
 */
public record ChangeUserNameRequest(
        @NotBlank(message = "用户名不能为空")
        String userName
) {
}
