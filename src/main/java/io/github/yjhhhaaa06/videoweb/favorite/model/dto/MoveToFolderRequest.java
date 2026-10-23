package io.github.yjhhhaaa06.videoweb.favorite.model.dto;

/**
 * 稍后再看移入收藏夹请求（{@code PUT /api/v1/watch-later/{id}/folder}，`T59` 载荷面）。
 *
 * <p>`T59` 前是 query/form 参数 {@code folderId}；载荷面按《接口契约.md》§2.5 统一为
 * JSON body {@code {"folderId": N}}。
 *
 * <p>{@code folderId} **可缺省 = 默认夹（必要时懒建）** —— 与
 * {@code POST /api/v1/favorites} 同口径；故不约束，{@code null} 是合法值。
 */
public record MoveToFolderRequest(Long folderId) {
}
