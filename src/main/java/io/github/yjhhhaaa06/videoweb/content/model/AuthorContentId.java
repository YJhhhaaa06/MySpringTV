package io.github.yjhhhaaa06.videoweb.content.model;

/**
 * 内容 id 的**带作者归属**投影（feed 大V发件箱批量回源用）。
 *
 * <h2>为什么需要这个类型</h2>
 * TV 的 {@code ContentDao.findRecentContentIdsByAuthor} 在 DAO 内手工遍历 {@code ResultSet}
 * 直接拼出 {@code Map<Long, List<Long>>}（作者 → 最近 N 条）。MyBatis 无法把一个 select 直接
 * 映射成"分组 Map"（{@code @MapKey} 只支持"一行一值"，不支持"一行 → 列表的一项"）。
 * 故让 mapper 返回**扁平行** {@code (authorId, contentId)}，由调用方（{@code FeedOutboxReader}）
 * 归组——这正是 TV"UNION ALL 外层顺序不保证 ⇒ 在 Java 侧显式归组 + 降序重排"的同一分工。
 *
 * <p>放在 {@code content.model}（内容域）而非 feed 域：它是 {@code content} 表查询的投影，
 * 生产方是 {@code ContentDao}——照 feed 放会让 {@code content → feed} 与
 * {@code feed → content.dao} 成环（与 S8 的 {@code AdminContentVO} 同一处置：《决策留痕表》C-6）。
 *
 * @param authorId  作者 id（{@code content.user_id}）
 * @param contentId 内容 id（{@code content.id}）
 *                  <p>⚠️ 组件用**包装类型** {@code Long}（而非 {@code long}）：MyBatis 的构造器映射按
 *                  {@code Class} 找构造器，原始类型 {@code long} 与 {@code Long} 不相等 ⇒ 会报
 *                  {@code NoSuchMethodException: AuthorContentId.<init>(Long,Long)}（实测）。
 */
public record AuthorContentId(Long authorId, Long contentId) {
}
