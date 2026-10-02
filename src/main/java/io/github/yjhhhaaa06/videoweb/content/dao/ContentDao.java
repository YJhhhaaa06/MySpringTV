package io.github.yjhhhaaa06.videoweb.content.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 内容数据访问 —— **content-thin（S2 薄依赖版）**。
 *
 * <h2>为什么只有 3 个方法</h2>
 * 按《切片计划》§〇：`content` 是 23 文件 / 3,806 行 / 11 处事务的巨模块，
 * 若整体先做会成为所有下游的阻塞源。故拆成：
 * <ul>
 *   <li><b>content-thin</b>（本类）：只搬下游**必需**的方法。</li>
 *   <li><b>content-full</b>（S5）：读路径 / 搜索 / admin / 媒体，约 21 个 DAO 方法。</li>
 * </ul>
 *
 * <p>S2（comment 写路径）实际只需要这 3 个：
 * <ol>
 *   <li>{@link #isContentExist} —— "被评论的内容是否存在"（CM-1 的 404 判据）</li>
 *   <li>{@link #updateCommentCount} —— 评论数联动（CM-1 +1 / CM-2 回减，**与评论写同事务**）</li>
 *   <li>{@link #findCommentEnabledById} —— 评论区开关门禁（CM-1，409 判据）</li>
 * </ol>
 *
 * <p>⚠️ 《切片计划》§二 原列的 `updateLikeCount` 属 **like 切片（S3）** 的需求，S2 用不到，**故不搬**——
 * 薄依赖的口径是"本切片交付所必需"，不是"计划里提到过"。
 *
 * <h2>迁移口径</h2>
 * SQL 文本、表名、列名原样保留（《迁移参照系》§2.1）。机械改动：删 `Connection conn` 首参、
 * 删 `throws SQLException`、`?` → `#{name}`、`@Component` → `@Mapper`。
 */
@Mapper
public interface ContentDao {

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
     * 本切片无读缓存（ContentCache 属 S5），直读 DB 既无缓存可用、也顺带消除该窗口——
     * 语义上不放松（关闭后仍不可发），只是**更即时**。S5 接入缓存后可评估是否改回（见决策表 CM-1 要点 3）。
     *
     * @return {@code true}=开 / {@code false}=关；**内容不存在或已软删 → {@code null}**
     *         （调用方据此跳过门禁，把 404 判定交给事务内的 {@link #isContentExist}——与 TV 同构）
     */
    Boolean findCommentEnabledById(@Param("contentId") long contentId);
}
