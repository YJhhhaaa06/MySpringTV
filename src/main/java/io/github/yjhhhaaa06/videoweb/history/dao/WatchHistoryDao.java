package io.github.yjhhhaaa06.videoweb.history.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 播放记录数据访问（{@code watch_history}，本域自有表）。
 *
 * <h2>三个方法 = 写路径的全部 SQL</h2>
 * <ul>
 *   <li>{@link #upsertProgress}：{@code /history/report} 的**一条** ODKU（不判首播、不读后写）；</li>
 *   <li>{@link #insertIgnoreCounted} / {@link #markCountedIfNotCounted}：{@code /history/play}
 *       的**首播判定**两步，靠 affected-rows 的 1/0 判别。</li>
 * </ul>
 *
 * <h2>★ 为什么 {@code /play} 不用 {@code ON DUPLICATE KEY UPDATE} 判首播</h2>
 * Connector/J 默认 {@code useAffectedRows=false} ⇒ ODKU 的"插入"与"值没变的更新"**都返回 1**
 * ⇒ 重复播放会被误判成首播、播放量被反复累加。{@code INSERT IGNORE} 的 {@code 1/0} 没有这种歧义
 * （与投币的 {@code insertIgnoreZero} 同形）。权衡与代价见
 * {@code architecture/domain/history/history.md} §四.2。
 */
@Mapper
public interface WatchHistoryDao {

    /**
     * 上报进度：不存在则建行、存在则**覆盖**（后到即新 / LWW）。
     *
     * <p>★ {@code update_time} 必须**显式** {@code SET ... = CURRENT_TIMESTAMP}：
     * 列上的 {@code ON UPDATE} 在"值没变"（同一秒重复上报）时**不触发** ⇒ 该行不会移到列表最前。
     *
     * @return 受影响行数。⚠️ **调用方不得据此判"是否首播"**——ODKU 的 1/0/2 在
     *         {@code useAffectedRows=false} 下无法区分"插入"与"值没变的更新"（见类注释）
     */
    int upsertProgress(@Param("userId") long userId,
                       @Param("contentId") long contentId,
                       @Param("progressSeconds") int progressSeconds);

    /**
     * 首次播放建行并**直接**标记已计（{@code progress_seconds = 0}）。
     *
     * <p>行不存在 ⇒ 插入 1 行（{@code play_counted=1}）并返回 1；行已存在 ⇒ 唯一键冲突被
     * {@code IGNORE}、返回 0。★ 两种结果**无歧义**，这正是本方法存在的理由。
     *
     * <p>为什么在这里建行（而不是等 {@code /report}）：否则"先 play 后 report"会丢播放量；
     * 历史里出现一条 00:00 的记录是预期行为（口径见 {@code history.md} §四.2）。
     */
    int insertIgnoreCounted(@Param("userId") long userId, @Param("contentId") long contentId);

    /**
     * 行已存在时的**条件抢占**：只在 {@code play_counted = 0} 时置 1。
     *
     * @return 1 = 本次抢占成功（首播）；0 = 别人已经计过（幂等返回）
     */
    int markCountedIfNotCounted(@Param("userId") long userId, @Param("contentId") long contentId);
}
