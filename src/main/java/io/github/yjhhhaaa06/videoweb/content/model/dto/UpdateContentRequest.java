package io.github.yjhhhaaa06.videoweb.content.model.dto;

/**
 * 作者编辑作品请求（{@code PUT /api/v1/contents/{id}}，`T59` 载荷面）。
 *
 * <p>`T59` 前是 query/form 里的 {@code title} / {@code description}；载荷面按
 * 《接口契约.md》§2.5 统一为 JSON body：{@code {"title": "…", "description": "…"}}。
 *
 * <p>字段可缺省：缺 {@code title} 时由 {@code ContentService.updateContentInfo} 按空串处理
 * ⇒ 400「标题不能为空」；缺 {@code description} 时按空串处理（清空简介）。
 * 长度校验（标题 ≤50 / 简介 ≤5000）仍在 Service（业务拒绝只有 {@code msg}，`T57` 边界）。
 */
public record UpdateContentRequest(String title, String description) {
}
