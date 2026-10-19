package io.github.yjhhhaaa06.videoweb.coin.model;

/**
 * 内容 id → 我投了几枚的**成对投影**（{@code content_coin} 的用户维度装载行）。
 *
 * <h2>用途</h2>
 * {@code ContentCoinDao.findAmountsByUser} 一趟查出"该用户投过币的全部内容及枚数"
 * （只取 {@code amount > 0} 的行 —— 那才是"投过币"的口径）。缓存层（T39）拿它做
 * {@code user:coinAmount:{userId}} HASH 的**全量回填**，因此这里刻意保持"扁平行"，
 * 不返回 {@code Map}：MyBatis 无法把一个 select 直接映射成"一行 → HASH 的一项"，
 * 归组交给调用方（同 {@code content.model.AuthorContentId} 的分工）。
 *
 * <p>放在 {@code coin.model}（本域）而不是别处：它是 {@code content_coin} 表查询的投影，
 * 生产方与消费方都在本域，没有跨域共享的动机。
 *
 * @param contentId 内容 id（{@code content_coin.content_id}）
 * @param amount    我投的枚数（{@code 1} 或 {@code 2}；查询已过滤 {@code amount > 0}）
 *                  <p>⚠️ 两个组件都用**包装类型**（{@code Long} / {@code Integer}）：
 *                  MyBatis 的构造器映射按 {@code Class} 找构造器，原始类型与包装类型不相等 ⇒
 *                  会报 {@code NoSuchMethodException}（实测口径同 {@code AuthorContentId}）。
 */
public record ContentCoinAmount(Long contentId, Integer amount) {
}
