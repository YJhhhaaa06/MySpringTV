package io.github.yjhhhaaa06.videoweb.content.dao;

import io.github.yjhhhaaa06.videoweb.content.model.AuthorContentId;
import io.github.yjhhhaaa06.videoweb.content.model.cache.ContentCacheDTO;
import io.github.yjhhhaaa06.videoweb.content.model.entity.Content;
import io.github.yjhhhaaa06.videoweb.content.model.vo.AdminContentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 内容数据访问。
 *
 * <h2>两段历史，一个类</h2>
 * <ul>
 *   <li><b>S2 的 content-thin</b>（3 方法）：只搬下游**必需**的（存在性 / 评论数联动 / 评论区开关），
 *       避免 content 成为所有下游切片的阻塞源。</li>
 *   <li><b>S5</b>（本切片，+11 方法）：读路径 / 搜索 / 作者写路径。</li>
 *   <li><b>S7</b>（+2 方法）：发布写路径（{@code addContent} / {@code updateFileExists}）。</li>
 *   <li><b>S8</b>（+3 方法）：admin 内容运维（{@code getContentStatus} /
 *       {@code updateContentDeletedState} / {@code findContentForAdmin}）。</li>
 *   <li><b>S9</b>（+4 方法）：feed 读路径（{@code findContentIdsByUsers} /
 *       {@code countContentByUsers} / {@code findRecentContentIdsByUsers} /
 *       {@code findRecentContentIdsByAuthor}，见 {@code :308} 起）。<b>至此本类方法全数搬完。</b>
 *       （2026-10-03 更正：此处原写"S8 后只剩 feed 用的三个方法未搬"，S9 已搬入，
 *       叙述未回填——被复算者读到时会把已搬的方法当成欠账。）</li>
 * </ul>
 *
 * <h2>迁移口径</h2>
 * SQL 文本、表名、列名、排序原样保留（《迁移参照系》§2.1）。机械改动：删 {@code Connection conn} 首参、
 * 删 {@code throws SQLException}、{@code ?} → {@code #{name}}、
 * {@code StringBuilder} 拼 {@code IN (?,?)} → {@code <foreach>}、{@code @Component} → {@code @Mapper}。
 *
 * <p>⚠️ 一处**刻意保留的不一致**：{@code SELECT ... WHERE is_deleted = 0}（{@code findContent}）
 * 与 {@code WHERE c.is_deleted = 0}（本类其它方法）的别名写法**未统一**——SQL 逐字照搬，
 * 便于与 TV 对照复核；统一别名属"顺手美化"，会让逐字 diff 失去意义。
 */
@Mapper
public interface ContentDao {

    // ========================================================================
    // S2 的 content-thin（3 + 1 方法）
    // ========================================================================

    /**
     * 内容是否存在（**未软删**）。
     *
     * <p>TV: {@code SELECT COUNT(*) FROM content WHERE id = ? AND is_deleted = 0}
     *
     * <p>★ 这个 `AND is_deleted = 0` 决定了「给已删除内容评论是否被拒」——**已读旧代码确认，不是推断**
     * （《切片计划》§二 明确要求先确认）。结论：**被拒**（404）。已固化为测试。
     *
     * <p>写法说明：{@code SELECT COUNT(*) > 0} 翻译为 {@code SELECT EXISTS(...)}。
     * 行集完全等价（COUNT 不会返回 NULL，无聚合歧义），且与切片 0 的
     * {@code UserDao.isPhoneUsed} / {@code isUsernameUsed} 保持同一写法——同仓一致性优先，
     * 非"改了 COUNT 口径"（口径指**哪些行被计入**，此处未变）。
     */
    boolean isContentExist(@Param("contentId") long contentId);

    /**
     * 评论数增减。TV: {@code UPDATE content SET comment_count = comment_count + ? WHERE id = ?}
     *
     * <p><b>本方法无防负守卫</b>（对比 {@code CommentDao.updateReplyCount} 有），这是 TV 原样行为，
     * 故保留。⚠️ 已知尖锐处：{@code content.comment_count} 列类型是 {@code int unsigned}，
     * 若计数已是 0 仍执行 -1，MySQL 严格模式下会因 UNSIGNED 下溢报错 → 500。
     * 详见《事务边界决策表》CM-2「已知并接受的旧实现尖锐处」。
     *
     * @return 受影响行数
     */
    int updateCommentCount(@Param("contentId") long contentId, @Param("delta") int delta);

    /**
     * 读评论区开关。TV 侧是 {@code ContentCache.getContent(contentId).isCommentEnabled()}（**读缓存**）。
     *
     * <p>TV: {@code SELECT comment_enabled FROM content WHERE id = ? AND is_deleted = 0}
     *
     * <p>⚠️ **有意改进**：新实现直读 DB，而非走 ContentCache。
     * 旧实现读缓存 ⇒ 作者刚关评论区时缓存可能仍是"开"，存在**陈旧窗口**（靠失效通知 + TTL 自愈兜）。
     * S2 时无读缓存，直读 DB 既无缓存可用、也顺带消除该窗口——语义上不放松（关闭后仍不可发），
     * 只是**更即时**。
     *
     * <p><b>S5 追加表态（CM-1 要点 3 的"接入缓存后评估"结论）：保持直读 DB，不回退读缓存。</b>
     * 理由：① 这条查询是**写路径的门禁**（发评论前判一次），不在热读路径上，直读 DB 成本可忽略；
     * ② 读缓存会重新引入"作者关评论区后仍能发评论"的陈旧窗口，而该窗口的代价（用户违规内容入池）
     * 高于一次主键查询；③ 保持 S2 已固化的测试不变（少一次回归面）。
     *
     * @return {@code true}=开 / {@code false}=关；**内容不存在或已软删 → {@code null}**
     *         （调用方据此跳过门禁，把 404 判定交给事务内的 {@link #isContentExist}——与 TV 同构）
     */
    Boolean findCommentEnabledById(@Param("contentId") long contentId);

    /**
     * 内容点赞数增减。
     *
     * <p>TV: {@code UPDATE content SET like_count = like_count + ? WHERE id = ?}
     *
     * <p>⚠️ 本方法在 **S1 的 content-thin（3 方法）里被有意排除**——当时认定它属 like 切片（S3）的需求。
     * 现由 **S3** 补上，正好印证"薄依赖的口径是**本切片交付所必需**，不是计划里提到过"。
     *
     * <p><b>无防负守卫</b>（对比 {@code CommentDao.updateReplyCount} 有），TV 原样行为，故保留。
     * ⚠️ 已知尖锐处：{@code content.like_count} 列类型是 {@code int unsigned}，
     * 取消未点赞的赞时若计数为 0 会因 UNSIGNED 下溢报错 → 500（与 {@code comment_count} 同款，
     * 已实测复现）。注意 {@code comment.like_count} 是**有符号** int，同场景只会变成 −1 而不报错——
     * 两表这个不对称是 TV 既有行为，不修。详见《事务边界决策表》L-1/L-2。
     *
     * @return 受影响行数
     */
    int updateLikeCount(@Param("contentId") long contentId, @Param("delta") int delta);

    // ========================================================================
    // S5：读路径
    // ========================================================================

    /**
     * 按 id 查内容（**未软删**，JOIN users 取 authorName）。
     *
     * <p>TV: {@code SELECT c.id, c.title, …, u.username, u.id AS user_id FROM content c
     * JOIN users u ON c.user_id = u.id WHERE c.id=? AND is_deleted = 0}
     *
     * <p>列名 {@code u.id AS user_id} 映射到 {@code authorId}——**别改这行的列别名**，
     * 它与 {@code findContentsByIds} / {@code findAllContent} 必须逐字一致（否则批量装载分组错位）。
     *
     * <p>返回行**不含媒体字段**（coverUrl/videoUrl/imageUrls）——由
     * {@code ContentCache.buildContentMedia} 从媒体表补齐。
     */
    ContentCacheDTO findContent(@Param("contentId") long contentId);

    /**
     * 按 id 集合批量查内容（TV 第五期 T2 的装载合并：把 miss/降级的逐 key 查询收敛为一趟）。
     *
     * <p>TV 用 {@code StringBuilder} 拼 {@code IN (?,?,…)}；新写法用 {@code <foreach>}
     * （《迁移参照系》§2.1 的机械改动之一）。
     *
     * <p><b>无匹配行不出现在结果里</b>（调用方按"缺失 = 确认无数据"处理）；空/null 入参**不发 SQL**、
     * 返回空列表。查询顺序不保证，调用方按 id 归位。
     */
    List<ContentCacheDTO> findContentsByIds(@Param("ids") Collection<Long> ids);

    /**
     * 全表未删内容（JOIN users），按 {@code create_time DESC, id DESC}。
     *
     * <p>它服务**索引懒重建**（{@code content:index:*} 缺失时从 DB 重建）。
     * ⚠️ 这是**无上限**查询（TV 原样）。TV 另有启动期 `init()` 全量预热，本切片不搬（决策表 G-6），
     *    **第四批 T7-2 已定为"永不补"**（《遗留台账》B5 → D29）⇒ 本方法只在索引 key 缺失时被
     *    懒重建触发（且受冷却退避与单飞收敛）。
     * ⚠️ 它**不是死代码**：{@code ContentCache.ensureIndex} 与 {@code MediaAuditService.scanAll}
     *    都在用——B5 关闭不会让它变孤。
     */
    List<ContentCacheDTO> findAllContent();

    /**
     * 关键词搜索的**该页 id 列表**（T12：DB 查询与缓存读分离，本方法只在 DB 侧）。
     *
     * <p>TV 两条分支（原样保留）：
     * <ul>
     *   <li><b>单字符</b>：{@code title LIKE '%kw%'} —— 因为 MySQL 的 ngram 全文索引对单字
     *       命中不可靠（TV 的实测结论）；</li>
     *   <li><b>多字符</b>：{@code MATCH(title, description) AGAINST (? IN NATURAL LANGUAGE MODE)}。</li>
     * </ul>
     * 两处 {@code ORDER BY c.create_time DESC, c.id DESC} 都是 **T15 的 tie-breaker 修复**
     * （缺 {@code , c.id DESC} 时同秒内容分页会重复/漏项）——**不得删**。
     *
     * <p>入参 {@code keyword} 必须是**已 trim** 的（调用方负责）；单/多字符分支的判据就是它的长度。
     *
     * @param offset 起始偏移（{@code (page-1)*pageSize}，由 Service 计算并保证 ≥0）
     * @param limit  页大小
     */
    List<Long> keywordSearchInBrief(@Param("keyword") String keyword,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    /** 关键词搜索命中总数。分支判据与 {@link #keywordSearchInBrief} **必须同源**（否则 total 与 list 口径漂移）。 */
    int countKeywordSearch(@Param("keyword") String keyword);

    /** 某作者的内容总数（`/profile` 信封的 total）。TV: {@code SELECT COUNT(*) FROM content WHERE user_id = ? AND is_deleted = 0} */
    int countContentByUser(@Param("userId") long userId);

    /**
     * 某作者的**窗口**内容 id（feed2-25 T25，治 U-24：替代"全量 id 读 + 内存切片"）。
     *
     * <p>{@code ORDER BY c.id DESC LIMIT ? OFFSET ?}——用 contentId 排序的理由（TV 原注释）：
     * {@code content.id} 自增 ⇒ id 越大发布越晚，与 {@code ORDER BY create_time DESC, id DESC}
     * 的运行期次序一致；且 {@code idx_user_id (user_id)} 的 InnoDB 二级索引物理为
     * {@code (user_id, id)} 升序 ⇒ **反向索引扫描、免 filesort**，成本 ∝ offset+pageSize
     * （而非该作者内容总量）。
     *
     * <p>{@code offset < 0} 或 {@code pageSize <= 0} 时**不发 SQL**，返回空列表（TV 原样）。
     */
    List<Long> findContentIdsByUserWindow(@Param("userId") long userId,
                                          @Param("offset") int offset,
                                          @Param("pageSize") int pageSize);

    /**
     * 某作者的全部内容 id（**全量**）。
     *
     * <p>分页装载用 {@link #findContentIdsByUserWindow}；本方法只服务**改名后的级联失效**
     * （要失效该作者的**全部**内容 key，不能只失效一页）。
     */
    List<Long> findContentIdsByUser(@Param("userId") long userId);

    // ========================================================================
    // S5：作者写路径
    // ========================================================================

    /**
     * 作者开关评论区。TV: {@code UPDATE content SET comment_enabled = ? WHERE id = ?}
     *
     * <p>{@code enabled ? 1 : 0} 的类型转换在 XML 里用 {@code #{enabled}} 的 boolean→tinyint 隐式转换，
     * 与 TV 的 {@code ps.setInt(1, enabled ? 1 : 0)} 等价。
     */
    int updateCommentEnabled(@Param("contentId") long contentId, @Param("enabled") boolean enabled);

    /**
     * 作者编辑标题与简介。TV: {@code UPDATE content SET title = ?, description = ? WHERE id = ?}
     *
     * <p>全文索引（ngram）由 MySQL **自动维护**，无额外语句——TV 原注释。
     */
    int updateContentInfo(@Param("contentId") long contentId,
                          @Param("title") String title,
                          @Param("description") String description);

    /** 作者删除作品：软删。TV: {@code UPDATE content SET is_deleted = 1 WHERE id = ?} */
    int softDeleteContent(@Param("contentId") long contentId);

    // ========================================================================
    // S7：upload（发布写路径）
    // ========================================================================

    /**
     * 发布内容：插入一行并回填自增主键（upload 的 {@code doAddContent}）。
     *
     * <p>TV: {@code insert into content (user_id, title,type, description,category_id) values (?, ?, ?,?,?)}
     * （列顺序与写法逐字保留）——{@code Statement.RETURN_GENERATED_KEYS + getGeneratedKeys()}
     * 的骨架删掉，改 {@code useGeneratedKeys="true" keyProperty="id"} 回填 {@link Content#getId()}。
     *
     * <p>插入列**只有这五列**：{@code like_count}/{@code comment_count}/{@code comment_enabled}/
     * {@code file_exists}/{@code create_time} 等全部走表默认值（TV 原样）。
     *
     * @return 受影响行数（主键从入参 {@code content.getId()} 取）
     */
    int addContent(Content content);

    /**
     * 换源后回写内容级"文件完整性聚合"（TV {@code replaceMedia} 的第二条语句）。
     *
     * <p>TV: {@code update content set file_exists=?, last_verify_time=? where id=?}
     */
    int updateFileExists(@Param("contentId") long contentId,
                         @Param("exists") boolean exists,
                         @Param("lastVerifyTime") java.sql.Timestamp lastVerifyTime);

    // ========================================================================
    // S8：admin（内容运维）
    // ========================================================================

    /**
     * 读内容当前 {@code is_deleted} 状态（A2 下架/恢复的前置校验）。
     *
     * <p>TV: {@code SELECT is_deleted FROM content WHERE id = ?}
     * ——**不带 {@code is_deleted = 0} 过滤**（就是要看它当前是几）。
     * 语义：{@code 0} 正常 / {@code 1} 作者删除 / {@code 2} 管理员下架。
     *
     * <p>⚠️ 返回类型是**包装类型** {@code Integer}：无行 ⇒ {@code null}（"不存在"）。
     * TV 用 {@code -1} 哨兵表达同一件事；改用 {@code null} 是为了避免魔法值，
     * 也**必须**是包装类型——若声明成 {@code int}，MyBatis 在无行时返回 null 会 NPE（实测 500）。
     * 调用方（{@code ContentService.checkHideable/checkUnhideable}）判 {@code null} ⇒ 404。
     */
    Integer getContentStatus(@Param("contentId") long contentId);

    /**
     * 设置内容 {@code is_deleted} 状态（A2）：{@code 0} 正常 / {@code 1} 作者删除 / {@code 2} 管理员下架。
     *
     * <p>TV: {@code UPDATE content SET is_deleted = ? WHERE id = ?}
     *
     * <p>只改状态，**不动**评论/点赞/媒体记录与物理文件（TV 原注释）——"隐藏≠删除"。
     */
    int updateContentDeletedState(@Param("contentId") long contentId, @Param("state") int state);

    /**
     * 管理端内容清单（A2）：**含正常与已下架，不含已删除(1)的内容**。
     *
     * <p>TV: {@code SELECT c.id, c.title, c.type, c.is_deleted, u.username AS author_name
     * FROM content c JOIN users u ON c.user_id = u.id WHERE c.is_deleted IN (0, 2)
     * ORDER BY c.create_time DESC, c.id DESC}——SQL 逐字保留（含 {@code IN (0,2)} 与排序）。
     *
     * <p>⚠️ 别被 {@code hidden} 的映射绕进去：查询已把 {@code is_deleted} 限定为 0/2，
     * 故 resultMap 里 {@code is_deleted → boolean hidden} 的 {@code != 0} 语义
     * 与 TV 的 {@code rs.getInt("is_deleted") == 2} **完全等价**。
     */
    List<AdminContentVO> findContentForAdmin();

    // ========================================================================
    // S9：feed（关注动态流的两路读 + 重建 + 补推）
    // ========================================================================

    /**
     * 关注流**纯拉降级**路径的当页 id：关注作者集合的内容，按 {@code create_time DESC, id DESC} 分页。
     *
     * <p>TV: {@code SELECT c.id FROM content c WHERE c.user_id IN (?,…) AND c.is_deleted = 0
     * ORDER BY c.create_time DESC, c.id DESC LIMIT ?, ?}
     * ——SQL 逐字保留（含<span>两个</span>排序键：{@code create_time DESC} 后跟 {@code id DESC} 的
     * tie-breaker，缺后者同秒内容分页会重复/漏项）。
     *
     * <p>{@code userIds} 为空时**不发 SQL**（{@code <foreach>} 不接受空集合，调用方保证非空）。
     *
     * @param offset 起始偏移（{@code (page-1)*pageSize}，调用方保证 ≥0）
     * @param pageSize 页大小
     */
    List<Long> findContentIdsByUsers(@Param("userIds") List<Long> userIds,
                                     @Param("offset") int offset,
                                     @Param("pageSize") int pageSize);

    /**
     * 关注流纯拉降级路径的**命中总数**（与当页 id 同源 SQL，仅 SELECT 列不同）。
     *
     * <p>TV: {@code SELECT COUNT(*) FROM content WHERE user_id IN (?,…) AND is_deleted = 0}
     */
    int countContentByUsers(@Param("userIds") List<Long> userIds);

    /**
     * **每作者各取最近 K 条**内容 id（窗口重建的窗口装载）。
     *
     * <p>TV: 每作者一个 {@code (SELECT id FROM content WHERE user_id = ? AND is_deleted = 0
     * ORDER BY id DESC LIMIT ?)} 分支，{@code UNION ALL} 成一条语句（{@code <foreach>} 承接）。
     *
     * <p>排序口径 = **contentId 降序**（{@code content.id} 自增 ⇒ id 越大越新；与
     * {@code feed_inbox} 不存时间字段、"排序 / 归并 / 裁剪全按 contentId"的口径同源）。
     * ⚠️ {@code is_deleted} 不在 {@code idx_user_id} 中、需回表过滤 ⇒ 每分支最坏上界 = 该作者内容量。
     *
     * @param perAuthorLimit 每作者保留条数 K（调用方保证 &gt; 0）
     */
    List<Long> findRecentContentIdsByUsers(@Param("userIds") List<Long> userIds,
                                           @Param("perAuthorLimit") int perAuthorLimit);

    /**
     * 每作者各取最近 K 条内容 id、**并带回作者归属**（大V发件箱批量回源）。
     *
     * <p>TV: 分支里多选一列 {@code user_id AS author_id}（{@code idx_user_id} 物理为
     * {@code (user_id, id)}，该列随索引即可取到）⇒ 一趟查询即可按作者切分结果、直接用于逐作者缓存回填。
     *
     * <p>⚠️ {@code UNION ALL} 的**外层顺序不保证** ⇒ 调用方需对每个作者的结果**显式降序重排**。
     * 返回**扁平行**（{@link AuthorContentId}），归组由调用方做（见该 record 的说明）；
     * **无内容的作者不出现在结果中**。
     *
     * @param authorIds      作者 id 列表（调用方保证非空）
     * @param perAuthorLimit 每作者保留条数 N（调用方保证 &gt; 0）
     */
    List<AuthorContentId> findRecentContentIdsByAuthor(@Param("authorIds") List<Long> authorIds,
                                                       @Param("perAuthorLimit") int perAuthorLimit);
}

