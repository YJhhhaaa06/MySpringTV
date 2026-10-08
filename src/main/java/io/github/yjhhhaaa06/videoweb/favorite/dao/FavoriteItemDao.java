package io.github.yjhhhaaa06.videoweb.favorite.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 收藏记录数据访问（表 {@code favorite_item}，V2 建）。
 *
 * <h2>本接口的 SQL 随任务逐个补</h2>
 * T1 只确立包与 mapper 注册路径（当时是空接口）；**T2 补入第一条** ——
 * {@link #deleteByFolderId}（删夹一并删条目，R-02）。
 * 其余归后续任务：T4 收藏/移出/移动、T5 状态与计数、T6 夹内分页。**不提前写无主 SQL**。
 *
 * <h2>写 SQL 前必须先读的四条口径（都是本域特有的坑，别照抄 like）</h2>
 * <ol>
 *   <li>★ <b>{@code remove} 不得校验内容存在性</b>（{@code CURRENT_TASKS.md} G11）：
 *       直接按 {@code (folder_id, content_id)} 删行。这与 {@code /like/*} 写路径
 *       "先校验存在性"的惯例**刻意不同** —— 一加校验，失效条目就**永远清不掉**
 *       （用户在夹里看得见占位却删不掉）。反向：{@code add}/{@code move} 照常校验
 *       （{@code ContentDao.isContentExist} 带 {@code is_deleted = 0}），
 *       于是「失效不可移入」**自然产生**，不需要专门分支。</li>
 *   <li>★ <b>夹内列表用 {@code LEFT JOIN content}</b>，失效判据
 *       {@code c.id IS NULL OR c.is_deleted != 0}（G12）：{@code c.id IS NULL} 覆盖
 *       "内容行真没了" —— INNER JOIN 一旦丢行就会造成**分页空洞**。
 *       本仓 {@code is_deleted} 是**三态**（0 正常 / 1 作者删除 / 2 管理员下架），
 *       判定必须覆盖 1 与 2（写 {@code c.is_deleted != 0}，别写 {@code = 1}）。</li>
 *   <li>★ <b>分页单位是收藏记录行，不是"内容"</b>（G12）：{@code LIMIT/OFFSET} 作用在
 *       {@code favorite_item} 上，失效记录**占一条、返回一条占位**；一整页都是失效条目
 *       也照常返回（否则用户看不到 ⇒ 也就永远清不掉）。{@code total} **包含**失效条目。</li>
 *   <li><b>收藏数按人去重</b>（R-06）：{@code SELECT COUNT(DISTINCT user_id) FROM favorite_item
 *       WHERE content_id = ?} —— 同一人进多个夹只算 1。故 {@code user_id} 是本表的**冗余列**，
 *       判定"同一人是否收藏过"必须按 {@code (user_id, content_id)}，**不能按夹判**。
 *       ⚠️ 一期不加 {@code content_id} 前导索引：这个"笨查询"的耗时基线是**二期优化的对照**
 *       （T5 要求记录），先加索引就把基线毁了。</li>
 * </ol>
 *
 * <h2>表上的两个索引（各自服务什么）</h2>
 * <ul>
 *   <li>{@code uk_folder_content (folder_id, content_id)} —— 同一夹内同一内容至多一条：
 *       重复收藏**撞唯一键** ⇒ {@code DuplicateKeyException} ⇒ 全局出口 409，
 *       不靠"先查后插"（并发下先查后插必然漏）。</li>
 *   <li>{@code idx_user_content (user_id, content_id)} —— 按人去重（上条口径 4）与
 *       {@code /favorite/status} 的成员判定。</li>
 *   <li>{@code idx_folder_time (folder_id, create_time)} —— 夹内列表按收藏时间倒序分页（T6）。</li>
 * </ul>
 *
 * <p>🚫 <b>红线：本域不得接 feed</b>（G10）—— 不得调 {@code FeedDelivery.deliver}、
 * 不得发 {@code ContentPublishedEvent}。它是"不要做某事"，**没有实现可去掉 ⇒ 写成测试必假绿**，
 * 靠 review 守。
 */
@Mapper
public interface FavoriteItemDao {

    /**
     * 删掉一个夹里的**全部**收藏记录（供"删夹一并删条目"，R-02）。
     *
     * <h2>为什么删夹必须连条目一起删（而不是迁进默认夹）</h2>
     * R-02 拍板"一并删除"，理由是"迁进默认夹"会让用户**在自己没动过的夹里看到东西**，
     * 且要处理"默认夹已存在同一条内容"的撞唯一键（{@code uk_folder_content}）——
     * 那是把一个删除动作变成一次数据搬迁。删除是单向、幂等、无冲突的。
     *
     * <h2>★ 为什么这条 SQL 只按 {@code folder_id} 删（不带 {@code user_id}）</h2>
     * 归属校验已在 service 侧**先做**（{@code FavoriteFolderDao.findById} ⇒ 404/403），
     * 此处再带 {@code user_id} 兜一遍是"用第二个判据表达同一件事"：
     * 两个条件若哪天漂移（例如夹被转移到别人名下），症状是**条目删不干净但夹删掉了**（孤儿记录）。
     * 单一判据更好排查，且 {@code idx_folder_time(folder_id, create_time)} 的前导列正好命中。
     *
     * <p>⚠️ 走的是**物理删**（与 {@code favorite_item} 无 {@code is_deleted} 列一致）：
     * "移出收藏夹"在业务上就是记录消失，B 站同样如此；失效条目的保留靠**内容侧**
     * {@code is_deleted}，不由本表表达（R-03）。
     *
     * @param folderId 目标夹（调用方保证该夹存在且属于当前用户）
     * @return 受影响行数（空夹为 0，属正常）
     */
    int deleteByFolderId(@Param("folderId") long folderId);
}
