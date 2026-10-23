package io.github.yjhhhaaa06.videoweb.favorite.model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 新建收藏夹请求（{@code POST /api/v1/favorite-folders}，`T59` 载荷面）。
 *
 * <p>`T59` 前是 query/form 参数 {@code name}；载荷面按《接口契约.md》§2.5 统一为
 * JSON body：{@code {"name": "…"}}。
 *
 * <p>{@code @NotBlank} 承接旧「缺 {@code name} → 400」并覆盖"全空白"；文案与
 * {@code FavoriteService} 的同名守卫逐字一致（"收藏夹名称不能为空"），长度上限仍由
 * Service 校验（业务拒绝只有 {@code msg}）。
 */
public record CreateFavoriteFolderRequest(
        @NotBlank(message = "收藏夹名称不能为空")
        String name
) {
}
