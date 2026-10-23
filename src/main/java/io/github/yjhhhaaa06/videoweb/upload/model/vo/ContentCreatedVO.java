package io.github.yjhhhaaa06.videoweb.upload.model.vo;

/**
 * 发布内容成功后的出参（{@code POST /api/v1/contents/videos}、{@code POST /api/v1/contents/posts}）。
 *
 * <p>`T59` 前出参是 {@code Map<String,Object>}（键 {@code contentId} 靠约定）；载荷面按
 * 《接口契约.md》§2.5「出参不得是 {@code Map}」改为具名 record。JSON 形状不变：
 * {@code {"contentId": N}} —— 键名不变，故前端调用点无需改动。
 *
 * @param contentId 新建内容的 id
 */
public record ContentCreatedVO(long contentId) {
}
