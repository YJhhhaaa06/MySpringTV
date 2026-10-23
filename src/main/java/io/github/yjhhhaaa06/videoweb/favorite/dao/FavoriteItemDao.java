package io.github.yjhhhaaa06.videoweb.favorite.dao;

import io.github.yjhhhaaa06.videoweb.content.model.ContentFavoriteCount;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteFolderBriefVO;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

/**
 * 收藏记录数据访问（表 {@code favorite_item}，V2 建）。
 *
 * <h2>本接口的 SQL 随任务逐个补</h2>
 * T1 只确立包与 mapper 注册路径（当时是空接口）；**T2 补入第一条** ——
 * {@link #deleteByFolderId}（删夹一并删条目，R-02）；**T4 补入收藏与移出** ——
 * {@link #insertItem} / {@link #deleteByFolderAndContentIds}；
 * **T5 补入状态与计数** —— {@link #findFoldersByUserAndContent}（"收在哪些夹"）与
 * 单内容实时计数（收藏数，按人去重；★ T12 读路径切列时已退役删除，oracle 口径由测试 SQL 承接）；
 * **T6 补入夹内分页** —— {@link #countByFolderId}（total）+ {@link #findPageByFolderId}（页内行）；
 * **T9 补入判存在**（互动域组装详情 / 列表用）—— {@link #existsByUserAndContent} +
 * {@link #findFavoritedContentIds}（**不 JOIN**、走 {@code idx_user_content} 等值命中）；
 * **T11 补入为缓存而加的两条读** —— {@link #findAllFavoritedContentIds}（回填全量）/
 * {@link #findContentIdsByFolderId}（删夹前取失效集）；
 * **T12 补入批量重算的聚合** —— {@link #countDistinctUsersByContentIds}（一趟 GROUP BY，
 * 与第 2 步 {@code ContentDao.updateFavoriteCounts} 组成「事务内批量重算」，§四）。
 * 一期读路径（T5 / T6）已齐；**不提前写无主 SQL**（每条 SQL 随任务补入）。
 *
 * <h2>写 SQL 前必须先读的四条口径（都是本域特有的坑，别照抄 like）</h2>
 * <ol>
 *   <li>★ <b>{@code remove} 不得校验内容存在性</b>（G11 红线 —— 见 {@code favorite.md 附录「编号速查」}）：
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
 *   <li><b>收藏数按人去重</b>（R-06）：{@code COUNT(DISTINCT user_id)} —— 同一人进多个夹只算 1。
 *       故 {@code user_id} 是本表的**冗余列**，判定"同一人是否收藏过"必须按
 *       {@code (user_id, content_id)}，**不能按夹判**。
 *       ⚠️ 仍不加 {@code content_id} 前导索引：单内容版的耗时基线（loose index scan ≈17ms）
 *       是**二期优化的对照**（T5 已实测记录，见 §四 与 {@link #countDistinctUsersByContentIds}），
 *       先加索引就把基线毁了。</li>
 * </ol>
 *
 * <h2>表上的两个索引（各自服务什么）</h2>
 * <ul>
 *   <li>{@code uk_folder_content (folder_id, content_id)} —— 同一夹内同一内容至多一条：
 *       重复收藏**撞唯一键** ⇒ {@code DuplicateKeyException} ⇒ 全局出口 409，
 *       不靠"先查后插"（并发下先查后插必然漏）。</li>
 *   <li>{@code idx_user_content (user_id, content_id)} —— 按人去重（上条口径 4）与
 *       {@code /api/v1/contents/{id}/favorite} 的成员判定。</li>
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
     * 收藏一条内容进一个夹（T4，{@code POST /api/v1/favorites} 与 {@code PUT /api/v1/favorites/{id}} 共用）。
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
     * 幂等收藏一条（`T59`）：目标夹已有该内容 / 并发重复 ⇒ 0 行，调用方视为成功。
     *
     * <p>仅用于「幂等设置」语义的两个 PUT（{@code PUT /favorites/{id}} 的移动、
     * {@code PUT /watch-later/{id}/folder}）；{@code POST /favorites} 仍走
     * {@link #insertItem}（创建语义，重复 = 409）。
     *
     * @return 受影响行数（首次收进 1；已在目标夹 / 并发重复 0）
     */
    int insertItemIgnore(@Param("folderId") long folderId,
                         @Param("userId") long userId,
                         @Param("contentId") long contentId);

    /**
     * 该内容是否已在指定夹里（`T59` 幂等移动的判定）。
     *
     * <p>用于"目标夹已有 ⇒ 跳过容量判定与插入、只清源夹"——否则满夹时重复移动会被
     * 容量闸误判成 400，幂等语义不成立。走 {@code uk_folder_content (folder_id, content_id)} 点查。
     */
    boolean existsByFolderAndContent(@Param("folderId") long folderId,
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
     * 某内容**收在我的哪些夹**（T5，{@code GET /api/v1/contents/{id}/favorite} 的 {@code folders}）。
     *
     * <h2>判据是 {@code (user_id, content_id)}，不是"按夹判"</h2>
     * 同一内容可以躺在我的多个夹里（唯一键是 {@code (folder_id, content_id)}），故"在哪些夹"
     * 必须按**用户维度**取行 —— 正是 {@code idx_user_content (user_id, content_id)} 服务的查询
     * （该索引两列都被等值命中，**走索引**）。
     * ⚠️ **别据此推断"计数查询用不上索引"** —— 一期的单内容计数（实时 COUNT，T12 切列时已退役）
     * 同样能借这个索引（loose index scan），T5 实测非全表扫（数字见favorite.md §四）。
     *
     * <h2>★ 为什么 {@code JOIN favorite_folder} 是安全的 —— ⚠️ 前提是"删夹事务在场"</h2>
     * {@code favorite_folder} 与 {@code favorite_item} **同属本域**，且删夹时条目**同事务一并删**
     * （R-02，{@link #deleteByFolderId}）⇒ 正常路径下不存在"条目指向已删夹"的孤儿，INNER JOIN 不丢行。
     * <b>⚠️ 但本表**没有外键**（V2 刻意与 V1 一致），DB 不兜底</b>：该前提**只由
     * {@code FavoriteService.deleteFolder} 的 {@code @Transactional} 一处承担** —— 而那正是
     * {@code favorite.md 附录「编号速查」} <b>I-04</b> 登记为"零判别力、可被静默误删"的那处。
     * 前提失效（孤儿行 {@code folder_id} 指向已删夹）时可观察症状是**跨端点不一致**：这里 JOIN 不上
     * ⇒ 丢行 ⇒ {@code status} 报"未收藏"；而收藏数查询**不 JOIN**（一期实时版与 T12 列读同此口径）
     * ⇒ 仍把该孤儿算进收藏数（根治见 I-04：加外键或补失败注入用例）。
     * ⚠️ 本 SQL 不碰 content 表；T6 夹内列表**现在也不碰**（失效判定已整体走 content 装载契约
     * 折叠，见 {@code favorite.md 附录「编号速查」} R-11）—— 两条都不要回流成跨域 JOIN（I-07 ③）。
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

    // ========================================================================
    // 夹内分页（T6：GET /api/v1/favorites）
    // ========================================================================

    /**
     * 夹内收藏记录总数（T6，{@code GET /api/v1/favorites} 的 {@code total}）——
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
     * <h2>★ 访问计划（实测，2026-10-14；★ T16 起它多了第二个消费方 = 容量判定）</h2>
     * dev 库 {@code EXPLAIN}：{@code type=ref} / {@code possible_keys=uk_folder_content,idx_folder_time}
     * / {@code Extra="Using index"} —— <b>覆盖索引扫描</b>（{@code folder_id} 是这两个索引的共同前导列，
     * 所需列都在索引里 ⇒ 不回表）。
     * ⚠️ <b>别把"选中哪一个 key"写进契约</b>：两个索引代价相同 ⇒ 优化器会在它们之间<b>摇摆</b>
     * （2026-10-14 实测先得 {@code idx_folder_time}、后得 {@code uk_folder_content}，同一张表、同一条 SQL）。
     * ⇒ 本行只承诺 **plan 形态**（{@code ref} + {@code Using index}），不承诺 key 名
     * （这正是"实测值"与"契约"的分界：会漂的实测值只能当证据，不能当断言 —— 独立审查抓到的第一版就是这个问题）。
     * 代价量级 = <b>∝ 夹内条数</b>，而条数上限本身就是 500 / 5000 ⇒ 单次判定最坏也只扫 5000 个索引项。
     * ⚠️ <b>耗时未计时</b>（T16 的 5000 行夹具只证明功能、没做计时）—— 将来若要论证
     * "给 {@code favorite_folder} 加 {@code item_count} 计数列"，按本仓纪律「无基线不得宣称优化生效」
     * <b>先补耗时基线</b>再下结论（本行只是计划形态，不是性能结论）。
     * 口径与取舍见 {@code favorite.md} §八。
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
     * 已整体删除，见 {@code favorite.md 附录「编号速查」} I-07）。
     *
     * <h2>★ 三条口径（写改动前先读，每一条都有对应的注入点）</h2>
     * <ol>
     *   <li><b>判失效不在这里</b>：内容"可不可用"由 content 装载契约决定 —— 不存在 / 已删下架
     *       （{@code is_deleted != 0}）/ 媒体损坏（视频行缺失）/ 未知 type 统一折叠为"取不到"
     *       ⇒ 该行渲染占位。口径"看不了 ≈ 失效"（{@code favorite.md 附录「编号速查」} **R-11**）。
     *       ⚠️ 别回流成"JOIN content + is_deleted 判定"：那**测不出**"视频行缺失 / 未知 type"，
     *       会与新口径分叉，还把跨域直连他域表（I-07 ③）重新引进来；</li>
     *   <li><b>分页单位是 {@code favorite_item} 行</b>（G12）：{@code LIMIT/OFFSET} 直接作用在
     *       收藏记录上，一整页都是失效条目也**照常返回整页**（不补位、不跳过）——
     *       跳过的后果是用户看不到 ⇒ 也就**永远清不掉**（R-07 的"可移出"落空）；
     *       ⚠️ 因此**不要复用 {@code ProfileService} 的填充逻辑**：那处是
     *       "取不到就 {@code continue}，{@code total} 不变"（{@code ProfileService.java:109}，
     *       TV 原样语义 ⇒ 分页出空洞），与收藏夹的"占位"是两套口径；</li>
     *   <li><b>顺序 {@code create_time DESC, id DESC}</b>（favorite-需求.md §三「按收藏时间倒序」）：
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

    // ========================================================================
    // 判存在（T9：互动域组装互动态用 —— 不 JOIN 的轻量版）
    // ========================================================================

    /**
     * 当前用户**是否收藏过**某内容（T9，供互动域组装详情响应）。
     *
     * <h2>★ 与 {@link #findFoldersByUserAndContent} 的区别：**不 JOIN**、只判存在</h2>
     * "我是否收藏过" ⟺ 存在一条 {@code (user_id, content_id)} 的记录 —— **不需要**知道
     * 收在哪些夹，也就不需要 JOIN {@code favorite_folder}（那条 JOIN 只是为取夹名，
     * 服务 {@code /api/v1/contents/{id}/favorite} 的"收在哪些夹"）。本查询两列**等值**，正好命中
     * {@code idx_user_content (user_id, content_id)} 的点查。
     *
     * <h2>口径：判"记录存在"，与收藏数同侧</h2>
     * 判据是 {@code favorite_item} 行本身，**不判内容有效性**（失效内容的记录照样在，
     * R-03 / R-07 —— "我还收着它"是事实）。
     * ⚠️ 与 JOIN 版的唯一已知分叉 = "**孤儿行**"边缘态（夹被删而条目未删，无 API 可达；
     * 其前提由 {@code deleteFolder} 的 {@code @Transactional} 一处承担，判别力已由
     * {@code favorite.md 附录「编号速查」} **I-04** 在 T8 收口 —— 打桩"最后一步"的失败注入用例）：
     * 孤儿行下 JOIN 版会丢行（status 报"未收藏"），而本查询与 count 一样**仍算已收藏**。
     * 本查询取"记录存在性"侧是有意的 —— 它服务的是"我收藏过没有"这个事实，
     * 不是"这条记录还能不能挂到某个夹上"。
     *
     * @param userId    当前登录用户
     * @param contentId 内容 id
     * @return 存在任意一条记录 ⇒ {@code true}（同一人收进多个夹也只答 true）
     */
    boolean existsByUserAndContent(@Param("userId") long userId, @Param("contentId") long contentId);

    /**
     * 当前用户在给定内容子集中**已收藏**的那些 content_id（T9，供互动域的**批量**契约）。
     *
     * <p>返回**命中子集**（不是完整映射）：调用方按 {@code in set = true} 组装，
     * "缺失 = 未收藏"由调用方补 false —— 与 {@code ContentLikeDao.findLikedContentIds}
     * 的形态一致（那条服务点赞降级路径）。
     *
     * <p>SQL 形态：{@code user_id} 等值 + {@code content_id IN (...)}，命中
     * {@code idx_user_content}；{@code DISTINCT} 保证"同一人进多个夹"只回一次。
     * 不 JOIN（理由同 {@link #existsByUserAndContent}）。
     *
     * @param userId     当前登录用户
     * @param contentIds 内容 id 集（**非空**；空集由 service 拦掉，否则 {@code IN ()} 是语法错）
     * @return 该用户已收藏的 content_id 子集（可能为空集）
     */
    Set<Long> findFavoritedContentIds(@Param("userId") long userId,
                                      @Param("contentIds") List<Long> contentIds);

    // ========================================================================
    // 收藏缓存（T11：二期-2）—— 两条**为缓存而加**的读
    // ========================================================================

    /**
     * 该用户收藏过的**全部** content_id（T11：缓存 miss 时回填用户维度成员集）。
     *
     * <h2>为什么不复用 {@link #findFavoritedContentIds}</h2>
     * 那条是"给定子集里命中的部分"（降级路径用，避免为 3 个 id 拉全量）；
     * 本查询是"**该用户的全量收藏**"——它只服务**回填**：成员 key
     * （{@code user:favoriteSet:{userId}}）断言的是"这个人的收藏全集"，
     * miss 时必须一次装全，否则缓存里是一个**残缺集合**（把已收藏判成未收藏）。
     * 两条查询的负载形态不同（全集 vs 子集），刻意分开而不是互相凑。
     *
     * <h2>口径</h2>
     * <ul>
     *   <li>按 {@code user_id} 等值（命中 {@code idx_user_content} 前导列）；
     *       {@code DISTINCT} 保证"同一内容收进多个夹"只回一次
     *       —— 成员集表达的是"我收藏过"，与夹数无关（DAO 类注释口径 4）；</li>
     *   <li><b>不 JOIN {@code favorite_folder}</b>：同 {@link #existsByUserAndContent}
     *       ——"我收藏过没有"与"收在哪些夹"是两个问题；</li>
     *   <li><b>不判内容有效性</b>：失效内容的记录照样在（R-03 / R-07）⇒ 照样进集合
     *       （"我还收着它"是事实）；</li>
     *   <li>⚠️ <b>本查询没有分页/上限</b>：装载量 = 该用户的收藏数。这与 like 域的成员集
     *       （{@code findLikedContentIdsByUser}）同一形态、同一取舍 —— 用户维度反转的目的正是
     *       让装载量与**内容热度**解耦。真出现"一个人的收藏上百万"时才需要窗口装载
     *       （那要引入 {@code partial:} 前缀态与四处特判，见缓存文档 §六.2，**现在不做**）。</li>
     * </ul>
     *
     * @param userId 当前登录用户
     * @return 该用户收藏过的 content_id 集（可能为空集，**不是** {@code null}）
     */
    Set<Long> findAllFavoritedContentIds(@Param("userId") long userId);

    /**
     * 一个夹内的全部 content_id（T11：删夹前取"受影响的收藏记录"，供**批量失效**收藏数缓存）。
     *
     * <h2>为什么删夹要先把 id 取出来</h2>
     * 删夹删的是**记录行**，但收藏数缓存（{@code content:favoriteCount:{id}}）是**内容维度**的
     * ——记录删掉之后就再也查不出"刚才动了哪些内容" ⇒ 必须在删之前取。取出来的集合**只用于失效**，
     * 不参与任何判定（不判"是否仍是最后一个夹"：见 {@code FavoriteCache} 类注释，
     * 收藏域的写路径一律整体失效，不做增量）。
     *
     * <p>⚠️ <b>必须与两条 DELETE 同事务</b>（事务由 {@code FavoriteService.deleteFolder} 的
     * {@code @Transactional} 提供，本查询在其中）：否则并发收藏会让"取到的 id 集"与
     * "实际删掉的行"不一致 ⇒ 失效集漏项（缓存留着一条已经变了的计数）。
     *
     * <h2>为什么不用 {@code SELECT ... FOR UPDATE}</h2>
     * 本仓明确排除（判"不存在"时 RC 下不加锁形同虚设、RR 下 gap lock 共享 ⇒ 死锁，
     * 见 {@code favorite.md} §四 的排除理由）；MySQL 的 {@code DELETE} 也没有
     * {@code RETURNING} ⇒ 取 id 与删除是**两条语句、一个事务**。
     *
     * <p>⚠️ 与 T12 的关系：T12 的 {@code recountFavoriteCounts(Set&lt;Long&gt;)} 需要同一份
     * "受影响的 contentId 集"（§四 的"`deleteFolder`（批量删条目）⇒ 受影响的全部 contentId"）
     * ⇒ 届时**复用本查询**，别新写一条同形 SQL。
     *
     * <p>⚠️ 本 SQL **不碰 content 表**（只查本域一列）：失效集由 favorite 侧给出，
     * "谁读这个计数"不归本域管。
     *
     * @param folderId 目标夹（调用方保证存在且属于当前用户）
     * @return 该夹内的 content_id（同一内容在同一夹里至多一行 ⇒ 无重复；空夹 ⇒ 空列表）。
     *         失效是幂等的，即便将来出现重复也无害
     */
    List<Long> findContentIdsByFolderId(@Param("folderId") long folderId);

    // ========================================================================
    // 收藏数冗余列的批量重算（T12：二期-3 —— 事务内批量重算，不做增量）
    // ========================================================================

    /**
     * 一批内容的**按人去重收藏数**（T12，{@code recountFavoriteCounts} 的第 1 步）。
     *
     * <h2>★ 一趟聚合，不是 N 次点查</h2>
     * {@code SELECT content_id, COUNT(DISTINCT user_id) … WHERE content_id IN (…) GROUP BY content_id}
     * —— 与一期单内容的实时计数同口径（按人去重，R-06；该查询已随 T12 读切换退役），
     * 但吃一整个 id 集。删夹可一次波及上千条内容 ⇒ "对每个 id 查一次"就是 N 次往返
     * （{@code ContentStatusFiller} 类注释同款纪律：批量语义原样透传，不得循环调单条）。
     * ⚠️ 别写成"逐条调单内容计数"——那正是本方法要治的形态。
     *
     * <h2>口径与分工</h2>
     * <ul>
     *   <li>★ <b>运行在调用方事务里</b>（同 {@link #findContentIdsByFolderId} 的口径）：
     *       入参 id 集与 {@code favorite_item} 的本次增删在同一事务 ⇒ 聚合看到的是
     *       **本事务改完之后**的状态（自己的写对自家事务可见）；</li>
     *   <li>★ <b>聚合不出的 id 不在结果里</b>：该内容收藏清空 / 从未有人收藏 / 幽灵 id ⇒
     *       GROUP BY 无行。调用方（{@code FavoriteService.recountFavoriteCounts}）按 0 补全后再走
     *       第 2 步批量 UPDATE —— 不变量 {@code favorite_count == COUNT(DISTINCT user_id)}
     *       对"清零"同样成立；</li>
     *   <li>「只查本域表」纪律不变：本 SQL 只碰 {@code favorite_item}；content 列的写回是
     *       第 2 步（{@code ContentDao.updateFavoriteCounts}，他域 DAO 薄依赖）；</li>
     *   <li>⭐ 一期的单内容版 {@code countDistinctUserByContentId}（实时 COUNT，T5）在 T12
     *       读路径切换时**退役删除**（无主 SQL 不留）；它的耗时基线是本查询的对照面：
     *       2 万行 / 目标 content 2000 个不同 user ⇒ EXPLAIN {@code type=range, key=idx_user_content,
     *       Extra="Using where; Using index for group-by (scanning)"}（**loose index scan** 的变体，
     *       ≈17ms / 20 次 best≈14ms avg≈19ms）。⚠️ 两个易混措辞：它**不是** skip scan
     *       （MySQL 的 INDEX_SKIP_SCAN 官方不含 GROUP BY/DISTINCT）；也别按"无 content_id 前导
     *       索引 ⇒ 全表扫"推断（T5 实测推翻）——数字与教训全文见favorite.md §四，T12 的
     *       GROUP BY IN 版实测也回写同处。</li>
     * </ul>
     *
     * @param contentIds 内容 id 集（**非空**；空集由 service 拦掉，否则 {@code IN ()} 是语法错；
     *                   重复 id 由 service 去重，重复传入结果亦正确但白耗）
     * @return (contentId → 按人去重收藏数) 的行集（只含**有收藏记录**的内容；可能为空集）
     */
    List<ContentFavoriteCount> countDistinctUsersByContentIds(@Param("contentIds") List<Long> contentIds);
}
