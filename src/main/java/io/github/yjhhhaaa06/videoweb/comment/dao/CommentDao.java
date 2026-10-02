package io.github.yjhhhaaa06.videoweb.comment.dao;

import io.github.yjhhhaaa06.videoweb.comment.model.cache.CommentCacheDTO;
import io.github.yjhhhaaa06.videoweb.comment.model.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 评论数据访问。
 *
 * <p>迁移自 TV {@code com.itheima.comment.dao.CommentDao}——《迁移参照系》§2.1 的机械改动：
 * <ul>
 *   <li>删掉首参 {@code Connection conn}（连接由 Spring 事务绑定）</li>
 *   <li>删掉 {@code throws SQLException}（{@code DataAccessException} 接管）</li>
 *   <li>{@code ?} + {@code setXxx(n, v)} 改为 {@code #{name}} 命名参数</li>
 *   <li>{@code ResultSet} 手工遍历 / {@code buildComment} → {@code <resultMap>} 显式映射</li>
 *   <li>{@code Statement.RETURN_GENERATED_KEYS} + {@code getGeneratedKeys()} → {@code useGeneratedKeys}</li>
 *   <li>{@code @Component} → {@code @Mapper}</li>
 *   <li>{@code StringBuilder} 拼 {@code IN (?,?,...)} → 本切片无该语句；S5 搬 {@code getRepliesByRootIds} 时用 {@code <foreach>}</li>
 * </ul>
 * <b>SQL 文本、表名、列名、条件、排序——原样保留</b>（见 {@code CommentMapper.xml} 内逐条 TV 对照注释）。
 *
 * <h2>只搬写路径所需的 8 条（能力闭环，不按文件横向搬运）</h2>
 * TV 的 {@code CommentDao} 共 15 个方法。**未搬**的是读路径与别域专用：
 * <ul>
 *   <li>{@code getComments} / {@code getMainCommentsAfter} / {@code countMainComments} /
 *       {@code getRepliesByRootIds} / {@code getRepliesInTreeByRoot} / {@code findMainById}
 *       —— comment 读路径，随 <b>S5</b>（含 CommentCache 两键组）</li>
 *   <li>{@code getContentIdByCommentId} —— 评论点赞缓存失效定位，随 <b>S3</b>（like）</li>
 *   <li>{@code updateLikeCount} —— 评论点赞计数，随 <b>S3</b>（like）</li>
 *   <li>{@code softDeleteByContentId} —— 「删内容级联软删评论」，随 <b>S5</b>（content 删路径）</li>
 * </ul>
 * 见《事务边界决策表》CM-3（读路径划归 S5 的理由与补回位置）。
 */
@Mapper
public interface CommentDao {

    /** {@code getRootIdByCommentId} 的上溯跳数上限（防异常长链/环）。与 TV 的 32 一致。 */
    int MAX_ROOT_HOPS = 32;

    /**
     * 插入评论，返回自增主键。
     *
     * <p>TV: {@code insert into comment (content_id, user_id, content, parent_id, reply_to_user_id)
     * values (?,?,?,?,?)}，用 {@code RETURN_GENERATED_KEYS} 手工取键并额外抛
     * {@code SQLException("获取评论ID失败")}。这些骨架删掉：{@code useGeneratedKeys} 直接回填
     * {@code comment.commentId}，"取不到 ID"在 MyBatis 下不可能发生。
     *
     * <p>{@code parentId} / {@code replyToUserId} 可为 null；XML 里显式标注
     * {@code jdbcType=BIGINT}——这是 TV {@code setNull(n, Types.BIGINT)} 的等价表达，
     * 也避免驱动对"未知类型的 null"报错。
     *
     * @return 受影响行数（正常恒为 1）；自增主键回填到入参对象的 {@code commentId}
     */
    int insert(Comment comment);

    /** 按 id 查评论（**不过滤 {@code is_deleted}**）。TV: {@code findCommentById}。查不到返回 null。 */
    Comment findById(@Param("commentId") long commentId);

    /**
     * 评论是否存在（**已软删视为不存在**）。
     * TV: {@code SELECT COUNT(*) FROM comment WHERE comment_id = ? AND is_deleted = 0}
     * —— 写法翻译同 {@code ContentDao.isContentExist}（COUNT &gt; 0 → EXISTS，行集等价）。
     */
    boolean isCommentExist(@Param("commentId") long commentId);

    /**
     * 主楼 {@code reply_count} 增减。
     *
     * <p>TV: {@code UPDATE comment SET reply_count = reply_count + ? WHERE comment_id = ? AND reply_count + ? >= 0}
     * ——<b>{@code AND reply_count + ? >= 0} 是防负守卫，一字未改</b>。
     * 它同时承担"防负"与"是否命中"两种作用，勿简化掉。
     *
     * @return 受影响行数（守卫拦住时为 0）
     */
    int updateReplyCount(@Param("rootId") long rootId, @Param("delta") int delta);

    /**
     * 统计主楼**未删除**的楼内回复数。
     *
     * <p>TV: {@code SELECT COUNT(*) FROM comment WHERE parent_id = ? AND is_deleted = 0}
     *
     * <p>★ 口径（TV 原注释）：单删回复时 {@code content.comment_count} 已 -1；
     * 删主楼时再扣 "1(主楼) + 剩余未删回复数"，与递增口径对称，
     * **避免对已单删回复二次扣减导致负数**。故 CM-2 必须"先计数再软删"。
     */
    int countFloorReplies(@Param("mainCommentId") long mainCommentId);

    /** 删主楼：整栋软删（主楼 + 楼内回复）。TV: {@code UPDATE comment SET is_deleted = 1 WHERE comment_id = ? OR parent_id = ?} */
    int softDeleteFloor(@Param("mainCommentId") long mainCommentId);

    /** 删回复：仅软删自己。TV: {@code UPDATE comment SET is_deleted = 1 WHERE comment_id = ?} */
    int softDeleteOne(@Param("commentId") long commentId);

    /**
     * 评论点赞数增减。
     *
     * <p>TV: {@code UPDATE comment SET like_count = like_count + ? WHERE comment_id = ?}
     *
     * <p>⚠️ 本方法在 **S2 里被有意排除**（当时列为"评论点赞计数，随 S3(like)"）。现由 **S3** 补上。
     *
     * <p><b>无防负守卫</b>（对比同类的 {@link #updateReplyCount} 有），TV 原样行为，故保留。
     * 注意 {@code comment.like_count} 是**有符号** {@code int}（而 {@code content.like_count} 是
     * {@code int unsigned}）：同一场景（计数为 0 时再 −1）此处**静默变成 −1 而不报错**，
     * 内容侧则会因 UNSIGNED 下溢报 500。这个不对称是 TV 既有行为，不修。
     */
    int updateLikeCount(@Param("commentId") long commentId, @Param("delta") int delta);

    /**
     * 单跳取"父指针"，语义由返回值**三态**表达：
     * <ul>
     *   <li>{@code null} —— 该评论行**不存在**（链断）</li>
     *   <li>{@code 0} —— 已到主楼（{@code parent_id IS NULL}）</li>
     *   <li>{@code >0} —— 上一级评论 id，继续上溯</li>
     * </ul>
     *
     * <p>TV 用 {@code rs.next()}（行在否）+ {@code rs.getLong} + {@code rs.wasNull()}（父指针空否）
     * 两个信号区分三种情况；此处用 {@code COALESCE(parent_id, 0)} 把"父指针空"折叠成
     * {@code 0}，把"行不存在"留给 MyBatis 的"无行 → null"——**一列表达三态，跳数不变**。
     */
    Long findParentOrDefault(@Param("commentId") long commentId);

    /**
     * 评论 → 所属主楼 id：沿 {@code parent_id} 链上溯到顶。
     *
     * <p>主楼自身返回自身 id；**链断（某级评论行不存在）返回 null**；超过
     * {@link #MAX_ROOT_HOPS} 跳（异常长链/环）也返回 null，由调用方按"无法定位"兜底。
     * 三态与跳数上限**逐条沿袭 TV {@code getRootIdByCommentId}**。
     *
     * <p>实现为 mapper 的 {@code default} 方法：循环属**数据访问层**的遍历细节（TB 的循环本就在 DAO 内），
     * 放这里可让 Service 只见"给我主楼 id"这一件事。MyBatis 对 default 方法直接本地调用、不查语句绑定。
     */
    default Long getRootIdByCommentId(long commentId) {
        long cursor = commentId;
        for (int hops = 0; hops < MAX_ROOT_HOPS; hops++) {
            Long parent = findParentOrDefault(cursor);
            if (parent == null) {
                return null;          // 链断：该评论行不存在（含被硬删，理论上不会发生）
            }
            if (parent == 0L) {
                return cursor;        // 已到主楼
            }
            cursor = parent;
        }
        return null;                  // 异常长链 / 环，放弃
    }

    // ========================================================================
    // S5：读路径（6 个方法，S2 已显式声明划归 S5 —— 见决策表 CM-3）
    // ========================================================================

    /**
     * 评论点赞缓存失效定位用：查评论所属内容 id；不存在/已删除返回 null。
     *
     * <p>TV: {@code SELECT content_id FROM comment WHERE comment_id = ? AND is_deleted = 0}
     *
     * <p>⚠️ 归属变更说明：S2 把它标为"随 S3（like）"，但 **S3 实际未搬**——因为它的真实调用方
     * 只有 {@code CommentCache.notifyCommentLikeChanged}（评论点赞后要定位"失效哪条主楼的
     * replies field"），而 CommentCache 属 S5。故本切片补搬，归属随使用方走。
     */
    Long getContentIdByCommentId(@Param("commentId") long commentId);

    /**
     * 缺省全量路径的整表查询（T10-B）：该内容**全部未删**评论，{@code comment_id} 升序。
     *
     * <p>TV: {@code SELECT c.*, u.username, r.username AS reply_to_username FROM comment c
     * LEFT JOIN users u … LEFT JOIN users r … WHERE c.content_id=? AND c.is_deleted=0
     * ORDER BY c.comment_id}
     *
     * <p>它只服务**不传分页参数**的兼容路径（{@code /comment/show} 缺省返回全量数组），
     * 建树在 Java 侧（{@code CommentCache.buildCommentTree} 上溯归一）。不占两键组。
     */
    List<CommentCacheDTO> getComments(@Param("contentId") long contentId);

    /**
     * 主楼窗口查询（keyset，T10-A）：{@code comment_id > afterCommentId} 升序取前 {@code limit} 条**主楼**。
     *
     * <p>TV: {@code WHERE c.content_id=? AND c.parent_id IS NULL AND c.is_deleted=0
     * AND c.comment_id > ? ORDER BY c.comment_id LIMIT ?}
     *
     * <p>★ 过滤条件是 {@code parent_id IS NULL}（**不是** {@code = 0}）——与
     * {@link #findMainById} 的判据（{@code IS NULL OR = 0}）**刻意不同**：前者是窗口装载的
     * keyset 谓词（走 {@code (content_id, parent_id)} 索引），后者是"单条定位"的宽松判据。
     * 两者都照搬 TV，不改。
     */
    List<CommentCacheDTO> getMainCommentsAfter(@Param("contentId") long contentId,
                                                @Param("afterCommentId") long afterCommentId,
                                                @Param("limit") int limit);

    /** 主楼条数（T10-A 的 total 来源：窗口装载**首装**时惰性 COUNT 一次，写 count key）。 */
    int countMainComments(@Param("contentId") long contentId);

    /**
     * 楼中楼按主楼批量取（T10-A）：{@code parent_id IN (rootIds)} 升序。
     *
     * <p>TV 用 {@code StringBuilder} 拼 {@code IN (?,?,…)}；新写法用 {@code <foreach>}
     * （《迁移参照系》§2.1 的机械改动）。空入参不发 SQL。
     */
    List<CommentCacheDTO> getRepliesByRootIds(@Param("contentId") long contentId,
                                              @Param("rootIds") List<Long> rootIds);

    /**
     * 按主楼展开全部回复（T10-B 的 {@code /comment/replies}）：直接回复 + 二级间接回复，
     * {@code comment_id} 升序 keyset。
     *
     * <p>TV 的子查询形态**逐字保留**（{@code parent_id=? OR parent_id IN (SELECT ...)}）——
     * 它与"addComment 把 parent 归一为主楼"的写入口径配套；存量脏数据最多二级。
     */
    List<CommentCacheDTO> getRepliesInTreeByRoot(@Param("contentId") long contentId,
                                                  @Param("rootId") long rootId,
                                                  @Param("afterCommentId") long afterCommentId,
                                                  @Param("limit") int limit);

    /** 定位**未删除主楼**（T10-B 展开接口的前置校验）：主楼被删 / 非主楼 → null。 */
    CommentCacheDTO findMainById(@Param("commentId") long commentId);
}
