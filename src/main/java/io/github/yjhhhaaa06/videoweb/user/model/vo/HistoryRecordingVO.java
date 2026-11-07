package io.github.yjhhhaaa06.videoweb.user.model.vo;

/**
 * 「记录浏览历史」开关的写回执（{@code PUT /api/v1/users/me/history-recording}，{@code R-25}）。
 *
 * <p>回带**本次写入的值**（与请求同值；服务端**不二次读库** —— 落库判据由
 * 调用方回读 `GET /users/me` 或直查 DB 提供，别把这个回执当"已落库"的证据）。
 *
 * <p>⚠️ 客户端**不**用它做回滚或提示（`R-25` 的拨动是乐观生效、失败不提示的，
 * 见 architecture/domain/history/history-需求.md §2.1）；它存在的理由是
 * "PUT 返回被更新的子资源"这条 REST 形态，以及 curl / 手工验收时能直接看到写入结果。
 */
public record HistoryRecordingVO(boolean historyRecording) {
}
