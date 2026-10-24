package io.github.yjhhhaaa06.videoweb.favorite.model.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 收藏内容请求（{@code POST /api/v1/favorites}，`T59` 载荷面）。
 *
 * <p>`T59` 前是 query/form 参数；载荷面按《接口契约.md》§2.5 统一为
 * JSON body {@code {"contentId": N, "folderId": N?}}。
 *
 * <p>{@code folderId} 可缺省 = 收进默认夹（必要时懒建，R-01）；{@code contentId}
 * 必填，缺失 → 400（{@code @NotNull}）。
 */
public record AddFavoriteRequest(
        @NotNull(message = "内容不能为空")
        Long contentId,
        Long folderId
) {
}
