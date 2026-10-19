package io.github.yjhhhaaa06.videoweb.interaction.model;

/**
 * 内容的**公共计数**三件套（点赞 / 收藏 / 投币）—— 请求无关、匿名可读（T43）。
 *
 * <p>★ 与 {@code interaction.model.vo.InteractionStatusVO} 的区别：
 * 那个是"**我的**状态"（登录才有），本 record 是"**所有人**都看到同一个数"。
 * 两者刻意分开 —— 匿名详情要给计数但不给互动态，混在一起就会出现"要么少计数、
 * 要么多一个空壳对象"（{@code ContentStatusFiller} 曾为此把填充拆成两条，T40）。
 *
 * <p>★ <b>为什么只有三个、没有评论数</b>：评论数来自 {@code content.comment_count}
 * （内容域自己的列），由 {@code content.cache.ContentCountCache} 直接读；
 * 若把它也放进本 record，互动域就要依赖评论域 —— 而评论域的 service 依赖内容域，
 * 会与 {@code content → interaction} 一起闭成装配环。故本 record 只收
 * **like / favorite / coin** 三个已在本域聚合的域。
 *
 * @param likeCount     点赞数（{@code content:likeCount}，回源 {@code COUNT(*) content_like}）
 * @param favoriteCount 收藏数（{@code content:favoriteCount}，按人去重）
 * @param coinCount     累计投币数（{@code content:coinCount}，回源 {@code content.coin_count}）
 */
public record ContentPublicCounts(int likeCount, long favoriteCount, long coinCount) {
}
