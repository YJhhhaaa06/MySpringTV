package io.github.yjhhhaaa06.videoweb.user.model.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 选择默认头像（{@code PUT /api/v1/users/me/avatar/default}，`R-27`）。
 *
 * <p>载荷 = {@code {"avatarId": 1..6}}。用包装 {@link Integer} + {@code @NotNull}：
 * 缺字段时原生 {@code int} 会静默绑成 0，再由范围校验报成"编号不合法"；
 * 这里要让"没传"与"传了 0"都能明确落到 {@code errors[field=avatarId]}。
 *
 * <p>范围（1..6）由 {@code AvatarRefs#validateDefaultId} 再校验一次 ——
 * 声明式校验负责"字段在不在"，服务层负责"值域与 DB 语义"，两层都不省。
 */
public record UpdateDefaultAvatarRequest(
        @NotNull(message = "头像编号不能为空")
        Integer avatarId
) {
}
