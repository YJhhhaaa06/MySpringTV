package io.github.yjhhhaaa06.videoweb.favorite.model.dto;

/**
 * 更新收藏夹请求（{@code PUT /api/v1/favorite-folders/{id}}，`T59` 载荷面）。
 *
 * <p>`T59` 前是 query/form 参数 {@code name} / {@code isPrivate}；载荷面按
 * 《接口契约.md》§2.5 统一为 JSON body {@code {"name": "…", "isPrivate": true}}。
 *
 * <p><b>部分更新</b>：{@code null} = 本次不改该字段（两个都缺 → 400，由 Service 判定）；
 * {@code isPrivate} 改为**布尔真类型**，旧的 {@code 1/true/0/false} 字符串形态退役
 * （非布尔 ⇒ 400 {@code 参数取值不合法}）。
 */
public record UpdateFavoriteFolderRequest(String name, Boolean isPrivate) {
}
