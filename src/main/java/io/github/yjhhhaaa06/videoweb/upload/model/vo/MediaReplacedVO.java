package io.github.yjhhhaaa06.videoweb.upload.model.vo;

/**
 * 换源成功后的出参（{@code PUT /api/v1/contents/{id}/media}）。
 *
 * <p>`T59` 前出参是 {@code Map<String,Object>}（键 {@code url} 靠约定）；载荷面按
 * 《接口契约.md》§2.5「出参不得是 {@code Map}」改为具名 record。JSON 形状不变：
 * {@code {"url": "…"}}。
 *
 * @param url 替换后的新文件 URL
 */
public record MediaReplacedVO(String url) {
}
