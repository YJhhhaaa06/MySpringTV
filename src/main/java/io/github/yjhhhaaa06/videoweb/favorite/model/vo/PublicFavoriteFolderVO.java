package io.github.yjhhhaaa06.videoweb.favorite.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 他人视角的公开收藏夹列表项（{@code GET /favorite/folder/public?userId=X} 的条目）。
 *
 * <h2>★ 为什么单独一个 VO，不复用 {@link FavoriteFolderVO}</h2>
 * 两条端点的**信任边界不同**（favorite.md §三 / R-08）：
 * <ul>
 *   <li>{@code /folder/list} 是"我的夹"——含私密、带 {@code isDefault}（前端据此不渲染删除按钮）；
 *   <li>{@code /folder/public} 是**他人视角**——R-08 原话"一期只给「名称 + 视频数」"。
 * </ul>
 * 复用一个形状意味着"我的夹"将来加字段时**自动泄露到公开面**。拆开后，
 * 公开面的字段集由本类**逐字段显式**决定，加字段必须动本类（契约测试还会对着键集断言，
 * 见 {@code FavoritePrivacyTests}）。
 *
 * <h2>字段口径（就这三个，别顺手加）</h2>
 * <ul>
 *   <li>★ {@code id} —— 夹 id，**T18 新加**：消费方是"点开他人公开夹看内容"
 *       （前端据此调 {@code GET /favorite/folder/items?folderId=X}）。一期刻意不给 id
 *       （R-08「一期只给名称 + 视频数」，当时没有"按 id 打开"的消费方），本类注释当时已预告
 *       "将来真要做打开他人公开夹，加 id 时必须同步改键集断言" —— 现在就是那一刻
 *       （键集断言已同步到 {@code id + name + itemCount}，见 {@code FavoritePrivacyTests}）；</li>
 *   <li>{@code name} —— 夹名；</li>
 *   <li>{@code itemCount} —— 夹内**收藏记录数**（含失效内容的记录，口径与
 *       {@link FavoriteFolderVO#getItemCount()} 完全一致：数的是 {@code favorite_item} 行，
 *       不感知内容有效性）。</li>
 * </ul>
 * ★ <b>为什么现在可以给 {@code id} 而不泄露隐私</b>：id 只用来"按 folderId 打开"，
 * 而"能不能打开"由服务端在 {@code GET /favorite/folder/items} 上**强制 {@code is_private = 0}**
 * 兜着（私密夹与不存在的夹返回同一口径，见 {@code FavoriteService.listPublicFolderItems}）——
 * 与本 VO 的"不含私密"是同一道过滤的两侧。id 本身不是秘密（公开夹任何人本就该打得开）。
 * <b>不含</b> {@code isPrivate} / {@code isDefault} —— 前者恒为 {@code false}（查询已过滤），
 * 放进去只是噪声；后者是"我的夹"给前端渲染用的类别信息，与他人视角无关。
 *
 * <p>JSON 形状（{@code data} 是数组）：
 * <pre>
 * [{"id":1,"name":"公开夹","itemCount":3}]
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PublicFavoriteFolderVO {

    /** 夹 id（T18：供"点开他人公开夹"按 folderId 打开内容）。 */
    private Long id;

    private String name;

    /** 夹内**收藏记录数**（含已失效内容的记录 —— 与"我的夹"列表同一口径）。 */
    private Integer itemCount;
}