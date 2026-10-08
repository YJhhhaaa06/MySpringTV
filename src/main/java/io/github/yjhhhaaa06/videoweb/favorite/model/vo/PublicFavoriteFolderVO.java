package io.github.yjhhhaaa06.videoweb.favorite.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 他人视角的公开收藏夹列表项（{@code GET /favorite/folder/public?userId=X} 的条目）。
 *
 * <h2>★ 为什么单独一个 VO，不复用 {@link FavoriteFolderVO}</h2>
 * 两条端点的**信任边界不同**（分期篇 §3.3 / R-08）：
 * <ul>
 *   <li>{@code /folder/list} 是"我的夹"——含私密、带 {@code isDefault}（前端据此不渲染删除按钮）；
 *   <li>{@code /folder/public} 是**他人视角**——R-08 原话"一期只给「名称 + 视频数」"。
 * </ul>
 * 复用一个形状意味着"我的夹"将来加字段时**自动泄露到公开面**。拆开后，
 * 公开面的字段集由本类**逐字段显式**决定，加字段必须动本类（契约测试还会对着键集断言，
 * 见 {@code FavoritePrivacyTests}）。
 *
 * <h2>字段口径（就这两个，别顺手加）</h2>
 * <ul>
 *   <li>{@code name} —— 夹名；</li>
 *   <li>{@code itemCount} —— 夹内**收藏记录数**（含失效内容的记录，口径与
 *       {@link FavoriteFolderVO#getItemCount()} 完全一致：数的是 {@code favorite_item} 行，
 *       不感知内容有效性）。</li>
 * </ul>
 * <b>刻意不含</b> {@code id} —— 一期他人公开夹只展示（名称 + 视频数），没有任何"按 id 打开 /
 * 操作"的消费方；id 是操作类端点的入口，不是展示字段（同域先例：{@code FavoriteFolderVO} 带 id
 * 的理由正是"改名 / 删除要按 id 定位"）。将来若真要做"打开他人公开夹"，届时加 id 时
 * 必须同步改键集断言（那正是"公开面加字段要想一次"的检查点）。
 * <b>不含</b> {@code isPrivate} / {@code isDefault} —— 前者恒为 {@code false}（查询已过滤），
 * 放进去只是噪声；后者是"我的夹"给前端渲染用的类别信息，与他人视角无关。
 *
 * <p>JSON 形状（{@code data} 是数组）：
 * <pre>
 * [{"name":"公开夹","itemCount":3}]
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PublicFavoriteFolderVO {

    private String name;

    /** 夹内**收藏记录数**（含已失效内容的记录 —— 与"我的夹"列表同一口径）。 */
    private Integer itemCount;
}