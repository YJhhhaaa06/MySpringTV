package io.github.yjhhhaaa06.videoweb.favorite.dao;

import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteFolderBriefVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 收藏记录数据访问（表 {@code favorite_item}，V2 建）。
 *
 * <h2>本接口的 SQL 随任务逐个补</h2>
 * T1 只确立包与 mapper 注册路径（当时是空接口）；**T2 补入第一条** ——
 * {@link #deleteByFolderId}（删夹一并删条目，R-02）；**T4 补入收藏与移出** ——
 * {@link #insertItem} / {@link #deleteByFolderAndContentIds}；
 * **T5 补入状态与计数** —— {@link #findFoldersByUserAndContent}（"收在哪些夹"）与
 * {@link #countDistinctUserByContentId}（收藏数，按人去重）。
 * 其余归后续任务：T6 夹内分页。**不提前写无主 SQL**。
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
 *       ⚠️ 一期不加 {@code content_id} 前导索引：这个查询的耗时基线是**二期优化的对照**
 *       （T5 已实测记录，见 {@link #countDistinctUserByContentId}），先加索引就把基线毁了。</li>
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

    /**
     * 收藏一条内容进一个夹（T4，{@code POST /favorite/add} 与 {@code POST /favorite/move} 共用）。
     *
     * <h2>三条口径（写调用方前先读）</h2>
     * <ol>
     *   <li><b>重复收藏不在这里拦</b>：撞唯一键 {@code uk_folder_content} ⇒
     *       {@code DuplicateKeyException} ⇒ 全局出口 409。不写"先查后插"——
     *       并发下先查后插必然漏，且多一次查询（{@code FavoriteFolderDao} 类注释同款理由）。</li>
     *   <li><b>内容存在性由调用方先校验</b>（{@code ContentDao.isContentExist}，带
     *       {@code is_deleted = 0}）——本条 SQL 不碰 content 表，「失效不可移入」由校验
     *       **自然产生**，不写专门分支。</li>
     *   <li><b>{@code userId} 是收藏人（冗余列）</b>：调用方已通过
     *       {@code FavoriteService.requireOwnedFolder} 校验"夹是我的"，故传入的
     *       {@code userId} 恒等于 {@code folder.user_id}——判"同一人是否收藏过"按
     *       {@code (user_id, content_id)} 时两列必然自洽。</li>
     * </ol>
     *
     * @param folderId  目标夹（调用方保证存在且属于当前用户）
     * @param userId    收藏人（调用方保证等于该夹的归属用户）
     * @param contentId 内容 id（调用方保证内容存在且未失效）
     * @return 受影响行数（正常恒为 1；撞唯一键时异常而非 0）
     */
    int insertItem(@Param("folderId") long folderId,
                   @Param("userId") long userId,
                   @Param("contentId") long contentId);

    /**
     * 从一个夹里移出若干条收藏记录（T4：批量移出 + move 的源侧删除）。
     *
     * <p>★ <b>这条 SQL 刻意**不校验**内容存在性</b>（G11 红线）：直接按
     * {@code (folder_id, content_id)} 删行——一加 {@code AND is_deleted = 0} 之类的联结，
     * 失效条目就永远清不掉（用户在夹里看得见占位却删不掉）。这与 {@code /like/*}
     * 写路径「先校验存在性」的惯例**刻意不同**。
     *
     * <h2>为什么是单条 {@code DELETE ... IN} 而不是循环逐条删</h2>
     * 批量移出是**一次请求内的多条写**：IN 版本是**一条 SQL**，原生原子（要么都在
     * 事务语义内成功，要么整体失败），不需要 {@code @Transactional} 兜；
     * 循环逐条删则把"删了 2 条、第 3 条失败"的半删状态暴露给调用方，还得套事务。
     * 命中 {@code uk_folder_content (folder_id, content_id)} 前导列。
     *
     * <h2>幂等：命中的行少给（甚至 0 行）不算错</h2>
     * 删除是单向操作（同 {@link #deleteByFolderId} 的口径）：前端勾选的卡片可能已被
     * 别处移出（陈旧页面 / 双击）。**不做"先 SELECT 记录存在性"的预检**——那既给批量
     * 场景引入"查了之后、删之前被并发移走"的假错，也是多余的往返；0 行受影响时
     * service 照常返回成功。
     *
     * @param folderId   目标夹（调用方保证存在且属于当前用户；只删这个夹里的）
     * @param contentIds 要移出的内容 id 集合（**非空**；空集由 service 拦成 400，
     *                   否则 {@code IN ()} 是 SQL 语法错）
     * @return 实际删除的行数（≤ 列表长度；差异来自不存在的记录，属正常）
     */
    int deleteByFolderAndContentIds(@Param("folderId") long folderId,
                                    @Param("contentIds") List<Long> contentIds);

    /**
     * 某内容**收在我的哪些夹**（T5，{@code GET /favorite/status} 的 {@code folders}）。
     *
     * <h2>判据是 {@code (user_id, content_id)}，不是"按夹判"</h2>
     * 同一内容可以躺在我的多个夹里（唯一键是 {@code (folder_id, content_id)}），故"在哪些夹"
     * 必须按**用户维度**取行 —— 正是 {@code idx_user_content (user_id, content_id)} 服务的查询
     * （该索引两列都被等值命中，走索引；与下面那条 {@code COUNT(DISTINCT)} 不同，那条用不上索引）。
     *
     * <h2>★ 为什么 {@code JOIN favorite_folder} 是安全的（不是 T6 那条 LEFT JOIN 的反例）</h2>
     * {@code favorite_folder} 与 {@code favorite_item} **同属本域**、且删夹时条目**同事务一并删**
     * （R-02，{@link #deleteByFolderId}）⇒ 不存在"条目指向已删夹"的孤儿，INNER JOIN 不会丢行。
     * ⚠️ 别与 T6 夹内列表的 **LEFT JOIN content** 混为一谈：那条防的是**跨域**（内容被软删 /
     * 行真没了）造成的**分页空洞**，与本条无关（本 SQL 根本不碰 content 表）。
     *
     * <p><b>顺序是契约</b>：默认夹置顶 + 创建序（{@code is_default DESC, id ASC}），
     * 与 {@code FavoriteFolderDao.findByUserIdWithItemCount} 复用同一顺序 —— 两处不许各排一半。
     *
     * <p>⚠️ 不判内容有效性：失效内容（{@code is_deleted != 0}）的记录照样在
     * {@code favorite_item} 里（R-03 不自动清理）⇒ 本查询照常返回它所在的夹
     * （"我还收着它"是事实；T6 才在**内容**侧把它渲染成脱敏占位）。
     *
     * @param userId    当前登录用户（只返回他自己的夹）
     * @param contentId 内容 id
     * @return 该内容收在我哪些夹（未收藏 ⇒ **空列表**）；每项只有 {@code id} + {@code name}
     */
    List<FavoriteFolderBriefVO> findFoldersByUserAndContent(@Param("userId") long userId,
                                                            @Param("contentId") long contentId);

    /**
     * 某内容的收藏数（T5，{@code GET /favorite/count}）—— <b>按人去重</b>（R-06）。
     *
     * <h2>★ 口径：数的是"人"，不是"记录"</h2>
     * {@code COUNT(DISTINCT user_id)} —— 同一人把同一内容收进 3 个夹也只算 **1**
     * （需求篇 §六「按人去重」）。⚠️ 写成 {@code COUNT(*)} 会把"一人多夹"重复计数
     * （T5 反向验证的注入点之一：去掉 {@code DISTINCT} ⇒ 计数用例变红）。
     *
     * <h2>★ 这是"唯一事实源"，也是二期冗余列的 oracle（R-06）</h2>
     * 一期不读不写 {@code content.favorite_count} 列，实时算；二期上增量维护后必须满足
     * {@code content.favorite_count == 本查询}。故一期这条"笨查询"**直接就是二期的验收基准**。
     *
     * <h2>⚠️ 刻意不加 {@code content_id} 前导索引 —— 它的耗时基线是二期的对照</h2>
     * 表上只有 {@code idx_user_content (user_id, content_id)}（前导列是 {@code user_id}）、
     * {@code uk_folder_content (folder_id, content_id)} 与 {@code idx_folder_time (folder_id, create_time)}，
     * **没有一条以 {@code content_id} 前导**。这是有意的：先加就把"优化前"的基线毁掉了。
     *
     * <p>★ <b>T5 实测基线（一次性探针，非断言；数字见 {@code CURRENT_TASKS.md} T5 回写）</b>：
     * 2 万行 / 目标 content 2000 个不同 user ⇒ {@code EXPLAIN} 是
     * {@code type=range, key=idx_user_content, key_len=16, Extra="Using where; Using index for group-by (scanning)"}
     * —— 即 MySQL 8 **没有**退化成全表扫，而是借 {@code idx_user_content} 做 **loose index scan /
     * skip scan**（用 {@code user_id} 前导列满足 {@code DISTINCT}、逐段跳扫滤 {@code content_id}）；
     * {@code EXPLAIN ANALYZE} 实际 ≈ 17ms，20 次实测 best ≈ 14ms / avg ≈ 19ms。
     * ⚠️ **别照抄"无 {@code content_id} 索引 ⇒ 全表扫"**：这个查询形态
     * （{@code COUNT(DISTINCT user_id)} + 恰好有 {user_id, content_id} 复合索引）能被 skip scan 接住
     * —— 这是 T5 实测**推翻**的初始假设。二期建 {@code content_id} 前导索引 / 上冗余列时，拿这组数字当对照。
     *
     * <p>⚠️ 不校验 {@code contentId} 存在性：内容不存在 / 从来没人收藏 ⇒ {@code 0}
     * （{@code COUNT} 无匹配行返回 0，不是 null）；返回 0 而不是 404 —— 内容页对"没人收藏"
     * 与"内容不存在"本就给不出不同答案，且 {@code /like/content/count} 同款。
     *
     * @param contentId 内容 id
     * @return 收藏人数（同一人进多夹只算 1）；无人收藏 ⇒ {@code 0}
     */
    long countDistinctUserByContentId(@Param("contentId") long contentId);
}
