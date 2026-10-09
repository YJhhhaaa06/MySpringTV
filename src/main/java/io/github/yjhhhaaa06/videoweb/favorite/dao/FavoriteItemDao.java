package io.github.yjhhhaaa06.videoweb.favorite.dao;

import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteFolderBriefVO;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteItemVO;
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
 * {@link #countDistinctUserByContentId}（收藏数，按人去重）；
 * **T6 补入夹内分页** —— {@link #countByFolderId}（total）+ {@link #findPageByFolderId}（页内行）。
 * 至此本表的一期读路径齐了；**不提前写无主 SQL**。
 *
 * <h2>写 SQL 前必须先读的四条口径（都是本域特有的坑，别照抄 like）</h2>
 * <ol>
 *   <li>★ <b>{@code remove} 不得校验内容存在性</b>（{@code CURRENT_TASKS.md} G11）：
 *       直接按 {@code (folder_id, content_id)} 删行。这与 {@code /like/*} 写路径
 *       "先校验存在性"的惯例**刻意不同** —— 一加校验，失效条目就**永远清不掉**
 *       （用户在夹里看得见占位却删不掉）。反向：{@code add}/{@code move} 照常校验
 *       （{@code ContentDao.isContentExist} 带 {@code is_deleted = 0}），
 *       于是「失效不可移入」**自然产生**，不需要专门分支。</li>
 *   <li>★ <b>夹内列表的失效判据不在本域 SQL</b>（T6 残留提交 2026-10-09）：内容能否展示
 *       由 content 装载契约（{@code ContentService.loadContentVOs}）判定 —— 不存在 / 已删下架 /
 *       媒体损坏 / 未知类型都折叠为"取不到" ⇒ 列表渲染占位（口径"看不了 ≈ 失效"，R-11）。
 *       ⚠️ 别回流成"JOIN content + is_deleted 判定"：它只覆盖"无行 / 已删"两种，**测不出**
 *       "视频行缺失 / 未知 type"，会与新口径分叉；跨域直连他域表本身另违模块边界（I-07 ③）。</li>
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
     * （该索引两列都被等值命中，**走索引**）。
     * ⚠️ **别据此推断"计数那条用不上索引"** —— 见 {@link #countDistinctUserByContentId}：
     * 那条同样能借这个索引（loose index scan），T5 实测非全表扫。
     *
     * <h2>★ 为什么 {@code JOIN favorite_folder} 是安全的 —— ⚠️ 前提是"删夹事务在场"</h2>
     * {@code favorite_folder} 与 {@code favorite_item} **同属本域**，且删夹时条目**同事务一并删**
     * （R-02，{@link #deleteByFolderId}）⇒ 正常路径下不存在"条目指向已删夹"的孤儿，INNER JOIN 不丢行。
     * <b>⚠️ 但本表**没有外键**（V2 刻意与 V1 一致），DB 不兜底</b>：该前提**只由
     * {@code FavoriteService.deleteFolder} 的 {@code @Transactional} 一处承担** —— 而那正是
     * {@code CURRENT_ISSUES.md} <b>I-04</b> 登记为"零判别力、可被静默误删"的那处。
     * 前提失效（孤儿行 {@code folder_id} 指向已删夹）时可观察症状是**跨端点不一致**：这里 JOIN 不上
     * ⇒ 丢行 ⇒ {@code status} 报"未收藏"；而 {@link #countDistinctUserByContentId} **不 JOIN**
     * ⇒ 仍把该孤儿算进收藏数（根治见 I-04：加外键或补失败注入用例）。
     * ⚠️ 本 SQL 不碰 content 表；T6 夹内列表**现在也不碰**（失效判定已整体走 content 装载契约
     * 折叠，见 {@code CURRENT_NEEDS.md} R-11）—— 两条都不要回流成跨域 JOIN（I-07 ③）。
     *
     * <p><b>顺序是契约</b>：默认夹置顶 + 创建序（{@code is_default DESC, id ASC}），
     * 与 {@code FavoriteFolderDao.findByUserIdWithItemCount} 复用同一顺序 —— 两处不许各排一半。
     *
     * <p>⚠️ 不判内容有效性：失效内容（含已删下架与"装不出来"的，R-11）的记录照样在
     * {@code favorite_item} 里（R-03 不自动清理）⇒ 本查询照常返回它所在的夹
     * （"我还收着它"是事实；占位渲染是 T6 夹内列表的事）。
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
     * —— 即 MySQL 8 **没有**退化成全表扫，而是借 {@code idx_user_content} 做 **loose index scan**
     * （{@code DISTINCT} 列 {@code user_id} 恰是索引最左前缀 ⇒ 满足 loose index scan 对
     * {@code COUNT(DISTINCT)} 的适用条件；{@code (scanning)} 是它的一个变体）。
     * ⚠️ 措辞别混：MySQL 的 **{@code INDEX_SKIP_SCAN}（{@code Using index for skip scan}）官方明确
     * 不含 GROUP BY / DISTINCT** —— 本条走的是 loose index scan，**不是** skip scan。
     * {@code EXPLAIN ANALYZE} 实际 ≈ 17ms，20 次实测 best ≈ 14ms / avg ≈ 19ms。
     * ⚠️ **别照抄"无 {@code content_id} 索引 ⇒ 全表扫"**：这个查询形态
     * （{@code COUNT(DISTINCT user_id)} + 恰好有 {user_id, content_id} 复合索引）能被 loose index scan 接住
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

    // ========================================================================
    // 夹内分页（T6：GET /favorite/list）
    // ========================================================================

    /**
     * 夹内收藏记录总数（T6，{@code GET /favorite/list} 的 {@code total}）——
     * 数的是 **{@code favorite_item} 行**，不是"还能打开的内容"。
     *
     * <h2>★ 为什么 {@code total} 必须<b>包含</b>失效条目</h2>
     * R-07（用户 B 站实测）：失效视频**算进**收藏夹视频数，分页同理（占一条、返回一条占位）。
     * 于是 {@code total} 与 {@code /folder/list} 的 {@code itemCount} 必须是同一个数 ——
     * 两条端点各说一套会让用户看到"夹上写 5 条、进去只有 3 条"。
     *
     * <h2>★ 为什么这里<b>不能</b> JOIN content</h2>
     * 一带 {@code content} 联结就必然要回答"失效的算不算"，而答案是**算** ⇒ 联结只会
     * 把计数变少（{@code INNER JOIN} 直接丢行；{@code LEFT JOIN + WHERE is_deleted = 0}
     * 则是把"算"写成了"不算"）。本 SQL **根本不碰 content 表** —— "失效也计入"不是
     * 特意写出来的分支，而是**数对了对象**的自然结果（同 {@code FavoriteFolderVO.itemCount} 的口径）。
     * ⚠️ 反向：谁若"顺手"给这条 SQL 加 {@code JOIN content}，两个端点会一起错，
     * 且错得非常安静（只是数变小）。
     *
     * @param folderId 目标夹（调用方保证存在且属于当前用户）
     * @return 收藏记录条数（**含**失效内容的记录）
     */
    int countByFolderId(@Param("folderId") long folderId);

    /**
     * 夹内一页的收藏记录（T6）—— 按**收藏时间倒序**；只返回**本域三列**
     * （{@code id} / {@code contentId} / {@code favoriteTime}），
     * {@code invalid} / {@code title} / {@code coverUrl} / {@code authorName} 由
     * {@code FavoriteService.listFolderItems} 经 {@code ContentService.loadContentVOs} 组装
     * （T6 残留提交 2026-10-09 —— 旧形态"单条 LEFT JOIN + 封面规则副本 + URL 前缀第二份实现"
     * 已整体删除，见 {@code CURRENT_ISSUES.md} I-07）。
     *
     * <h2>★ 三条口径（写改动前先读，每一条都有对应的注入点）</h2>
     * <ol>
     *   <li><b>判失效不在这里</b>：内容"可不可用"由 content 装载契约决定 —— 不存在 / 已删下架
     *       （{@code is_deleted != 0}）/ 媒体损坏（视频行缺失）/ 未知 type 统一折叠为"取不到"
     *       ⇒ 该行渲染占位。口径"看不了 ≈ 失效"（{@code CURRENT_NEEDS.md} **R-11**）。
     *       ⚠️ 别回流成"JOIN content + is_deleted 判定"：那**测不出**"视频行缺失 / 未知 type"，
     *       会与新口径分叉，还把跨域直连他域表（I-07 ③）重新引进来；</li>
     *   <li><b>分页单位是 {@code favorite_item} 行</b>（G12）：{@code LIMIT/OFFSET} 直接作用在
     *       收藏记录上，一整页都是失效条目也**照常返回整页**（不补位、不跳过）——
     *       跳过的后果是用户看不到 ⇒ 也就**永远清不掉**（R-07 的"可移出"落空）；
     *       ⚠️ 因此**不要复用 {@code ProfileService} 的填充逻辑**：那处是
     *       "取不到就 {@code continue}，{@code total} 不变"（{@code ProfileService.java:109}，
     *       TV 原样语义 ⇒ 分页出空洞），与收藏夹的"占位"是两套口径；</li>
     *   <li><b>顺序 {@code create_time DESC, id DESC}</b>（需求篇 §三「按收藏时间倒序」）：
     *       ⚠️ {@code create_time} 是 {@code datetime}（**秒**精度），同一秒收藏多条会并列，
     *       只按 {@code create_time} 排 ⇒ 页间顺序**不确定**（同一行可能出现在两页、
     *       或某页漏掉一行 —— 正是"分页边界不重不漏"要防的）。故补 {@code id DESC} 作
     *       稳定的 tie-breaker（{@code id} 单调递增 ⇒ 同秒内新的在前，与"倒序"同向）。</li>
     * </ol>
     *
     * <h2>★ 封面 / 作者 / 标题为什么不在本 SQL</h2>
     * 封面选取规则与 URL 前缀各只有一份，都在 content 域（{@code buildContentMedia} 的封面分支
     * 与 {@code jointUrl}）—— 旧的封面子查询副本与 {@code jointMediaUrl} 第二份实现已删
     * （I-07 ①②）。本域 SQL 对 content / content_media / users **零接触**（I-07 ③ 的"通道"
     * 形态一并根治：改为走对方的 Service 公开方法，见 {@code 模块边界与依赖.md} §三 通道①）。
     *
     * <p>⚠️ <b>已知边缘态（R-11 已拍板接受）</b>：数据损坏（如视频行缺失）时本列表渲染占位，
     * 而 {@code add}/{@code move}（{@code isContentExist} 带 {@code is_deleted = 0}）仍收得进 ——
     * 该态无 API 可达（发布必插视频行、删只允许 type=2 图片、换源只改 url），属已登记取舍。
     *
     * @param folderId 目标夹（调用方保证存在且属于当前用户）
     * @param offset   偏移量（service 由 {@code (page-1) * pageSize} 算出；⚠️ 用 {@code long}
     *                  —— {@code page} 无上限，用 int 会在极大页码上溢出成负数 ⇒ SQL 语法错 ⇒ 500）
     * @param limit    页大小（已由 Controller 归一到 1..域级上限）
     * @return 该页的收藏记录（**含**失效行，条数 = {@code limit} 除非已到末尾；本域三列有值，
     *         其余四列待 service 组装）；空夹 ⇒ 空列表（不是 {@code null}）
     */
    List<FavoriteItemVO> findPageByFolderId(@Param("folderId") long folderId,
                                            @Param("offset") long offset,
                                            @Param("limit") int limit);
}
