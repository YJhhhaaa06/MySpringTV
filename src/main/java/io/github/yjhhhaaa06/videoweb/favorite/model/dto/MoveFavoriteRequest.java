package io.github.yjhhhaaa06.videoweb.favorite.model.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 移动收藏请求（{@code PUT /api/v1/favorites/{id}}，`T59` 载荷面）。
 *
 * <p>`T59` 前是 query/form 参数 {@code fromFolderId} / {@code toFolderId}；载荷面按
 * 《接口契约.md》§2.5 统一为 JSON body {@code {"fromFolderId": N, "toFolderId": N}}。
 * 路径上的 {@code {id}} 是**被移动的那条收藏**（contentId），两个夹只是参数。
 */
public record MoveFavoriteRequest(
        @NotNull(message = "源收藏夹不能为空")
        Long fromFolderId,
        @NotNull(message = "目标收藏夹不能为空")
        Long toFolderId
) {
}
