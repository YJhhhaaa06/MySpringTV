package io.github.yjhhhaaa06.videoweb.user.model.vo;

/**
 * 「记录浏览历史」开关的写回执（{@code PUT /api/v1/users/me/history-recording}，{@code R-25}）。
 *
 * <p>回带**服务端已落库的值**，形状与 {@code UserInfoVO.historyRecording} 同名字段一致。
 *
 * <p>⚠️ 客户端**不**用它做回滚或提示（`R-25` 的拨动是乐观生效、失败不提示的，
 * 见 architecture/domain/history/history-需求.md §2.1）；它存在的理由是
 * "PUT 返回被更新的子资源"这条 REST 形态，以及 curl / 手工验收时能直接看到落库结果。
 */
public record HistoryRecordingVO(boolean historyRecording) {
}
