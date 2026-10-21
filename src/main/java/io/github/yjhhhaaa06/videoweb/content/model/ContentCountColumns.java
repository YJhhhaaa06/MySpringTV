package io.github.yjhhhaaa06.videoweb.content.model;

/**
 * content 行上的**五个公共计数列**（T44-2 详情计数聚合读的回源载体；播放量为 T48 第 5 位）。
 *
 * <p>{@code like_count} / {@code comment_count} / {@code favorite_count} / {@code coin_count} /
 * {@code play_count} 五列同在 {@code content} 行上（like 由点赞写路径 ±1 维护并在删除时归零、
 * comment 由评论域事务 ±、favorite 由收藏域事务内重算、coin 由投币域事务 ±、play 由播放写路径
 * 首播 +1）⇒ 聚合读层一趟主键点查即可取齐，miss 键按"有选择回填"分别写回各自的独立计数键
 * （口径见 {@code 缓存与数据一致性.md §五.2}）。
 *
 * <p>★ 这是 **content → content** 的自有查询结果（{@code ContentDao#findCountColumns} 的返回值），
 * 不涉及跨域；三个外域计数键的**回填**经 {@code InteractionService} 转发到各自域
 * （content 不直接写他域缓存，{@code 模块边界与依赖.md} §六 的登记例外）；comment 与 play
 * 都是本域键，直调 {@code ContentCountCache}。
 *
 * <p>⚠️ record ⇒ 必须用 {@code <constructor>} 映射（{@code ContentMapper.xml}）。
 * ★ 组件**必须用包装类型**（{@link Long}）：MyBatis 的 {@code <constructor>} 按 javaType
 * 反射找构造器，record 的 canonical 构造器是**原生** {@code (long,long,long,long,long)}，
 * 与 {@code javaType="java.lang.Long"} 声明不匹配 ⇒ {@code NoSuchMethodException}
 * （2026-10-20 实测；先例 = {@link ContentCoinCount} 的组件同样是包装类型）。
 * 五列均有 DEFAULT 0 但理论可空 ⇒ 读侧做 {@code null → 0} 防御（聚合层统一处理）。
 */
public record ContentCountColumns(Long likeCount, Long commentCount, Long favoriteCount,
                                  Long coinCount, Long playCount) {
}

