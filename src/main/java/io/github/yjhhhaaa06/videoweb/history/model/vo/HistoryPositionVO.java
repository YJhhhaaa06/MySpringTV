package io.github.yjhhhaaa06.videoweb.history.model.vo;

/**
 * 续播位置（{@code GET /api/v1/contents/{id}/progress} 的 {@code data}，播放记录专项）。
 *
 * <p>JSON 形状：{@code {"progress": 123}}。只给进度：总时长随内容详情返回
 * （{@code durationSeconds}），这里**不重复读一次内容**
 * （{@code architecture/domain/history/history.md} §三）。
 *
 * @param progress 上次看到第几秒；**无记录 ⇒ 0**（不是 404、也不是 {@code null}）
 */
public record HistoryPositionVO(int progress) {
}
