package io.github.yjhhhaaa06.videoweb.content.model;

/**
 * 内容 id → 累计投币数的**批量读投影**（coin 缓存批量回填用，T39）。
 *
 * <h2>为什么需要这个类型</h2>
 * {@code ContentDao.findCoinCountsByIds} 一趟 {@code IN} 查出多条内容的
 * {@code coin_count}；缓存层（{@code CoinCache#batchGetCoinCount}）拿它回填
 * {@code content:coinCount:{id}}。这与 {@link AuthorContentId} 的分工同构：
 * MyBatis 返回**扁平行**、归组交给调用方，本 record 只承载"一行 = 一对键值"。
 *
 * <p>放在 {@code content.model}（内容域）而非 coin 域：它是 {@code content} 表查询的投影，
 * 生产方是 {@code ContentDao} —— 照 coin 放会让 {@code content → coin.model} 多一条倒挂边
 * （与 {@link ContentAuthorAndType} / {@link ContentFavoriteCount} 的放置理由同构）。
 *
 * @param contentId 内容 id（{@code content.id}，查询里以 {@code content_id} 为列别名）
 * @param coinCount 累计被投币数（{@code content.coin_count}）
 *                  <p>⚠️ 两个组件都用**包装类型**（{@code Long} / {@code Integer}）：
 *                  MyBatis 的构造器映射按 {@code Class} 找构造器，原始类型与包装类型不相等 ⇒
 *                  会报 {@code NoSuchMethodException}（实测口径同 {@code ContentAuthorAndType}）。
 */
public record ContentCoinCount(Long contentId, Integer coinCount) {
}
