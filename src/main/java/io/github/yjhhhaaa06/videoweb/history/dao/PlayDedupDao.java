package io.github.yjhhhaaa06.videoweb.history.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 播放量去重账本 {@code content_play_dedup} 的数据访问（`R-26` 起，本域自有表）。
 *
 * <h2>它替代了什么</h2>
 * 旧口径（`R-18`：一人一次、永久）把去重标记 {@code watch_history.play_counted} 长在历史行上，
 * 于是"历史可删"与"播放量可复算"直接互斥。`R-26` 把标记搬到这里：**历史行只存进度，可以删；
 * 账本只增，删除碰不到它** ⇒ 删完再播不会重复计数。
 *
 * <h2>★ 为什么还是 {@code INSERT IGNORE}，而不是 ODKU</h2>
 * 与 {@link WatchHistoryDao} 同一条理由：Connector/J 默认 {@code useAffectedRows=false} ⇒ ODKU 的
 * "插入"与"值没变的更新"**都返回 1**，无法区分"今天首播"与"今天已计"。
 * {@code INSERT IGNORE} 撞主键时返回 **0**，与插入的 **1** 无歧义 —— 首播判定就靠这一个数。
 *
 * <h2>★ 主键里为什么有 {@code play_date}</h2>
 * 口径是"每人·每内容·**每自然日**一次"。日期进主键 ⇒ 同一天第二次插不进去（拦重复），
 * 换一天就插得进去（允许再计一次）；而"删历史"只动 {@code watch_history}，
 * 账本里的行**一个都不少**。★ 只有"今天"的行有约束力，所以将来的清理不会改变语义。
 */
@Mapper
public interface PlayDedupDao {

    /**
     * 记一笔"今天计过数"。
     *
     * <p>★ {@code playDate} 必须由调用方按**业务时区**算好传进来（{@code PlayDay}），
     * 不能在这里用 {@code CURDATE()}：后者取 MySQL 的 `time_zone`（容器里常是 UTC），
     * 会让每天 0:00–8:00 的自然日与《接口契约》§2.7 的 +08:00 口径错开一天。
     *
     * @param playDate 计过数的自然日（业务时区 Asia/Shanghai 的日历日）
     * @return 1 = 今天首次计数（即"首播"）；0 = 今天已经计过，幂等返回
     */
    int insertIgnore(@Param("userId") long userId,
                     @Param("contentId") long contentId,
                     @Param("playDate") LocalDate playDate);
}
