package io.github.yjhhhaaa06.videoweb.content.model;

/**
 * 内容 id → （作者 id，类型）的**读投影**（coin 写路径的门禁查询结果）。
 *
 * <h2>为什么需要这个类型</h2>
 * 投币的七级校验里，第 2/3/4 级（存在性 / 只能投视频 / 不能自投）都要先读
 * {@code content.user_id} 与 {@code content.type}。分开查是两趟往返；合成一条窄查询
 * {@code findAliveContentAuthorAndType} 一趟就把三条判据的输入都拿到
 * （同 {@code ContentDao#findAliveContentType} 的"一趟覆盖多条拒因"思路）。
 *
 * <p>放在 {@code content.model}（内容域）而非 coin 域：它是 {@code content} 表查询的投影，
 * 生产方是 {@code ContentDao} —— 照 coin 放会让 {@code content → coin.model} 多一条倒挂边
 * （与 {@link AuthorContentId} / {@link ContentFavoriteCount} 的放置理由同构）。
 *
 * @param authorId 作者 id（{@code content.user_id}）
 * @param type     内容类型（{@code content.type}：1 视频 / 2 图文）
 *                 <p>⚠️ 组件用**包装类型**（{@code Long} / {@code Integer}）：
 *                 MyBatis 的构造器映射按 {@code Class} 找构造器，原始类型与包装类型不相等 ⇒
 *                 会报 {@code NoSuchMethodException}（实测口径同 {@code AuthorContentId}）。
 */
public record ContentAuthorAndType(Long authorId, Integer type) {
}
