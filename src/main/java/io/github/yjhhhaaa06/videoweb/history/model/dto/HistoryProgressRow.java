package io.github.yjhhhaaa06.videoweb.history.model.dto;

import java.sql.Timestamp;

/**
 * 批量落库的一行（`T51-2`）：一条"进度 + 该次上报的服务端接收时刻"。
 *
 * <p>★ 为什么 `update_time` 由缓冲携带、而不是 SQL 里写 `CURRENT_TIMESTAMP`：
 * 一批里各条的接收时刻不同，用 `CURRENT_TIMESTAMP` 会把它们压成同一秒 ⇒ 历史列表的
 * "最后观看倒序"退化成"批内 contentId 倒序"（`history-上报缓冲.md` §4）。
 *
 * <p>★ 为什么是 POJO 而不是 record：本类出现在 **Mapper 参数位**（`<foreach>` 里取
 * {@code it.contentId} 等），本仓已实测 MyBatis 的 record **参数位**不可靠
 * （只适合结果位配 {@code <constructor>}）。
 */
public class HistoryProgressRow {

    private long contentId;

    private int progressSeconds;

    /** 该条上报的 {@code serverReceiveTs}（毫秒转来；列是 {@code datetime}，秒精度）。 */
    private Timestamp updateTime;

    public HistoryProgressRow() {
    }

    public HistoryProgressRow(long contentId, int progressSeconds, Timestamp updateTime) {
        this.contentId = contentId;
        this.progressSeconds = progressSeconds;
        this.updateTime = updateTime;
    }

    public long getContentId() {
        return contentId;
    }

    public void setContentId(long contentId) {
        this.contentId = contentId;
    }

    public int getProgressSeconds() {
        return progressSeconds;
    }

    public void setProgressSeconds(int progressSeconds) {
        this.progressSeconds = progressSeconds;
    }

    public Timestamp getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Timestamp updateTime) {
        this.updateTime = updateTime;
    }
}
