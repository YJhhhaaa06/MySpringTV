package io.github.yjhhhaaa06.videoweb.history.dao;

import io.github.yjhhhaaa06.videoweb.history.model.dto.HistoryProgressRow;
import io.github.yjhhhaaa06.videoweb.history.model.vo.HistoryItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 播放记录数据访问（{@code watch_history}，本域自有表）。
 *
 * <h2>写路径</h2>
 * <ul>
 *   <li>{@link #upsertProgress}：进度上报（{@code PUT /api/v1/contents/{id}/progress}）的**一条** ODKU（不判首播、不读后写）；</li>
 *   <li>{@link #insertIgnoreEmpty}：播放事件（{@code POST /api/v1/contents/{id}/play}）**只建行**
 *       （{@code progress=0}），不判首播 —— 判首播是 {@link PlayDedupDao} 的职责（`R-26`）；</li>
 *   <li>{@link #deleteByUserIdAndContentIds} / {@link #deleteAllByUserId}：历史删除
 *       （{@code DELETE /api/v1/history}，`R-26`）。★ 删行**不影响播放量**：计数证据在账本里。</li>
 * </ul>
 *
 * <h2>读路径（历史列表 {@code GET /api/v1/history} 与续播位置 {@code GET /api/v1/contents/{id}/progress}）</h2>
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
     * <p>⚠️ 本方法**绝不写**去重账本 {@code content_play_dedup}（那是
     * {@link PlayDedupDao#insertIgnore} 的唯一职责）。
     *
     * <p>⚠️ 一条语句 = 一个隐式事务：**任一行失败整批回滚**（如唯一键之外的约束、列截断）。
     * 调用方必须能降级到逐条重试，把坏行摘出去（{@code HistoryFlushService}）。
     *
     * @param items 同一用户的若干行（每条含 contentId / progress / updateTime）
     */
    int upsertProgressBatch(@Param("userId") long userId,
                            @Param("items") List<HistoryProgressRow> items);

    /**
     * 播放事件**只建行**（{@code progress_seconds = 0}）：行不存在 ⇒ 插 1 行，已存在 ⇒ 唯一键冲突被
     * {@code IGNORE}、返回 0。
     *
     * <p>★ 这里**不再判首播**（`R-26` 起判据在 {@link PlayDedupDao#insertIgnore}）：本方法只负责
     * "只 play 不 report 的视频也要出现在历史里"。**必须无条件调用**（不能只在首播时调）——
     * 用户删掉历史后重看，同一个"今天"不会再计播放量，但历史行**应该重新出现**
     * （否则页面在播、列表里却没有它）。
     *
     * <p>为什么在 `/play` 建行（而不是等 {@code /report}）：否则"先 play 后 report"会丢播放量；
     * 历史里出现一条 00:00 的记录是预期行为（口径见 {@code history.md} §四.2）。
     */
    int insertIgnoreEmpty(@Param("userId") long userId, @Param("contentId") long contentId);

    /**
     * 批量删除历史（{@code DELETE /api/v1/history?contentIds=}，`R-26`）。
     *
     * <p>★ {@code userId} 必须在 WHERE 里（作用域 = 当前登录用户，天然无越权面）；
     * ★ 命中 0 行**不是错误**（幂等 204）：陈旧页面、别的标签页已经删过、id 打错都走这条路。
     *
     * <p>★ 本方法**不碰** {@code content.play_count}、也不碰 {@code content_play_dedup}：
     * 删历史不减播放量（B站 同口径），而"今天计过数"的证据在账本里，删行删不到它。
     *
     * @param contentIds 去重后的内容 id（调用方保证非空；空 IN 是 SQL 语法错）
     */
    int deleteByUserIdAndContentIds(@Param("userId") long userId,
                                    @Param("contentIds") List<Long> contentIds);

    /**
     * 清空该用户的全部历史（{@code DELETE /api/v1/history?all=true}，`R-26`）。
     *
     * <p>★ 与 {@link #deleteByUserIdAndContentIds} 同口径：幂等（空历史也成功）、不动播放量、不动账本。
     * 没有上限是因为作用域天然收窄到一个人（{@code user_id} 是主键前缀，走 {@code uk_user_content}）。
     */
    int deleteAllByUserId(@Param("userId") long userId);

    /**
     * 该用户的历史行数（列表 {@code total}）。
     *
     * <p>★ <b>包含失效条目</b>：失效内容的历史行不随内容删除而消失（`R-18` 的失效占位口径），
     * 也照样占一条 —— 否则 {@code total} 与页内条数会打架（{@code architecture/domain/history/history.md} §七.1）。
     * ★ 用户主动删除（`R-26`）之后当然不再计入 —— 那是"用户要它消失"，与"内容失效"是两回事。
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
     * 续播位置（{@code GET /api/v1/contents/{id}/progress}）：{@code (user_id, content_id)} 点查进度。
     *
     * <p>★ <b>不判内容存在性</b>：内容已删也照样回答（"内容是死是活"由详情 404 负责，
     * 这里只回答"我的进度是多少"）。无行 ⇒ {@code null}，由调用方折成 {@code 0}。
     *
     * @return 进度秒数；{@code null} = 该用户对这个内容没有历史
     */
    Integer findProgress(@Param("userId") long userId, @Param("contentId") long contentId);
}
