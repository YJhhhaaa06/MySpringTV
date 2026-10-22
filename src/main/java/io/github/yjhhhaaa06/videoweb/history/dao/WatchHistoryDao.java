package io.github.yjhhhaaa06.videoweb.history.dao;

import io.github.yjhhhaaa06.videoweb.history.model.dto.HistoryProgressRow;
import io.github.yjhhhaaa06.videoweb.history.model.vo.HistoryItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 播放记录数据访问（{@code watch_history}，本域自有表）。
 *
 * <h2>写路径三个方法</h2>
 * <ul>
 *   <li>{@link #upsertProgress}：{@code /history/report} 的**一条** ODKU（不判首播、不读后写）；</li>
 *   <li>{@link #insertIgnoreCounted} / {@link #markCountedIfNotCounted}：{@code /history/play}
 *       的**首播判定**两步，靠 affected-rows 的 1/0 判别。</li>
 * </ul>
 *
 * <h2>读路径（{@code /history/list} 与 {@code /history/position}）</h2>
 * <ul>
 *   <li>{@link #countByUserId}：列表 {@code total} —— 数的是**行**，失效条目照常计入；</li>
 *   <li>{@link #findPageByUserId}：一页**本域三列**；</li>
 *   <li>{@link #findProgress}：续播位置点查（{@code uk_user_content}，无行 ⇒ {@code null}）。</li>
 * </ul>
 * ★ 读路径**不 join** {@code content} / {@code content_media} / {@code users}：展示字段与
 * 失效判据都由 {@code ContentService#loadContentVOs} 给出（`模块边界与依赖.md` §三；
 * 一期 I-07 的教训）。
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
     * 批量落库（`T51-2`）：一条语句写一批，**`update_time` 来自每条自己的 {@code serverReceiveTs}**。
     *
     * <p>★ 与 {@link #upsertProgress} 的语义必须**逐字一致**（LWW、只改进度与时间），差别只有两点：
     * ① 批量；② 时间由参数给，而不是 `CURRENT_TIMESTAMP`（一批里各条时刻不同，用数据库当前时间会把
     * 它们压成同一秒 ⇒ 列表倒序退化）。
     *
     * <p>⚠️ 本方法**绝不写** {@code play_counted}（那是 `insertIgnoreCounted` /
     * {@code markCountedIfNotCounted` 的唯一职责）。
     *
     * <p>⚠️ 一条语句 = 一个隐式事务：**任一行失败整批回滚**（如唯一键之外的约束、列截断）。
     * 调用方必须能降级到逐条重试，把坏行摘出去（{@code HistoryFlushService}）。
     *
     * @param items 同一用户的若干行（每条含 contentId / progress / updateTime）
     */
    int upsertProgressBatch(@Param("userId") long userId,
                            @Param("items") List<HistoryProgressRow> items);

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

    /**
     * 该用户的历史行数（列表 {@code total}）。
     *
     * <p>★ <b>包含失效条目</b>：失效内容的历史行不删（历史不可删）、也照样占一条 ——
     * 否则 {@code total} 与页内条数会打架（{@code architecture/domain/history/history.md} §七.1）。
     *
     * @param userId 当前登录用户
     */
    int countByUserId(@Param("userId") long userId);

    /**
     * 该用户的一页历史（{@code update_time DESC, content_id DESC}）。
     *
     * <p>★ 只查本域三列（{@code content_id} / {@code update_time} / {@code progress_seconds}）：
     * 标题 / 封面 / 作者 / 时长由 {@code ContentService#loadContentVOs} 组装；
     * 失效判据也在那里（**不是** SQL 的 join 或 {@code is_deleted} 条件）。
     *
     * <p>⚠️ {@code offset} 用 {@code long} 接收：{@code page} 无上限，
     * {@code (page - 1) * pageSize} 在 {@code int} 下会溢出成负数 ⇒ {@code OFFSET -…} 变语法错 ⇒ 500。
     *
     * @param userId 当前登录用户
     * @param offset 跳过行数（调用方用 {@code long} 计算，不为负）
     * @param limit  页大小
     */
    List<HistoryItemVO> findPageByUserId(@Param("userId") long userId,
                                         @Param("offset") long offset,
                                         @Param("limit") int limit);

    /**
     * 续播位置（{@code GET /history/position}）：{@code (user_id, content_id)} 点查进度。
     *
     * <p>★ <b>不判内容存在性</b>：内容已删也照样回答（"内容是死是活"由详情 404 负责，
     * 这里只回答"我的进度是多少"）。无行 ⇒ {@code null}，由调用方折成 {@code 0}。
     *
     * @return 进度秒数；{@code null} = 该用户对这个内容没有历史
     */
    Integer findProgress(@Param("userId") long userId, @Param("contentId") long contentId);
}
