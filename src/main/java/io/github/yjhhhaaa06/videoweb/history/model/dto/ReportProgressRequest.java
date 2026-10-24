package io.github.yjhhhaaa06.videoweb.history.model.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 播放进度上报请求（{@code PUT /api/v1/contents/{id}/progress}，`T59` 载荷面）。
 *
 * <p>`T59` 前是 query/form 参数 {@code progress}；载荷面按《接口契约.md》§2.5 统一为
 * JSON body {@code {"progress": N}}（单位：秒）。
 *
 * <p>只约束"必须给"；负数拒绝与"超过时长截断"仍由 {@code HistoryService} 判定
 * （业务拒绝只有 {@code msg}，`T57` 边界）。
 */
public record ReportProgressRequest(
        @NotNull(message = "进度不能为空")
        Integer progress
) {
}
