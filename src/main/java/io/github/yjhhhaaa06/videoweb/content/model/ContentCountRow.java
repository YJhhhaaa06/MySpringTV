package io.github.yjhhhaaa06.videoweb.content.model;

/**
 * 批量计数回源的一行：{@code id} + content 行上的五个公共计数列（GAP-01）。
 *
 * <p>与 {@link ContentCountColumns} 的区别只有一个：多带 {@code id}。列表批量回源是一条
 * {@code WHERE id IN (...)} 的多行查询，必须能按 id 归位；详情点查天然只有一行，故那边不需要。
 *
 * <p>五列同在 {@code content} 行上 ⇒ 一条 IN 取齐（不按"要哪几维"动态拼列：
 * 一次 SQL 的列数差异对成本没有意义，反而让 mapper 出现 N 个变体）。
 * 调用的聚合层只消费被请求的维度，其余列为 {@code null}。
 *
 * <p>⚠️ record ⇒ 必须用 {@code <constructor>} 映射（{@code ContentMapper.xml}），
 * 且组件**必须用包装类型** {@link Long}（同 {@link ContentCountColumns} 的实测先例：
 * 原生 {@code long} 构造器与 {@code javaType="java.lang.Long"} 不匹配会抛
 * {@code NoSuchMethodException}）。
 */
public record ContentCountRow(Long id, Long likeCount, Long commentCount, Long favoriteCount,
                              Long coinCount, Long playCount) {
}
