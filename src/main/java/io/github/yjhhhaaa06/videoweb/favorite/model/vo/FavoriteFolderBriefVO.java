package io.github.yjhhhaaa06.videoweb.favorite.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 「收在哪个夹」的条目（{@code GET /favorite/status} 的 {@code folders} 元素）。
 *
 * <h2>★ 为什么单独一个 VO，不复用 {@link FavoriteFolderVO} / {@link PublicFavoriteFolderVO}</h2>
 * 三个形状服务三个不同的读者视角（本域一贯的"一篇只服务一个读者视角"落法）：
 * <ul>
 *   <li>{@link FavoriteFolderVO} —— "我的夹列表"：多 {@code isDefault} / {@code isPrivate} /
 *       {@code itemCount}，是**管理**视角（前端据 {@code isDefault} 不渲染删除按钮）；</li>
 *   <li>{@link PublicFavoriteFolderVO} —— **他人**视角：只有 {@code name} + {@code itemCount}；</li>
 *   <li>本类 —— "某内容收在我的哪些夹"：只需 {@code id}（<b>操作定位</b>：移出 / 移动都按
 *       {@code (folderId, contentId)} 定位 —— 见 {@code FavoriteController.removeItems} /
 *       {@code moveItem}）+ {@code name}（<b>显示</b>：需求篇 §三「知道收在了哪些夹」）。</li>
 * </ul>
 * 复用 {@code FavoriteFolderVO} 会把 {@code itemCount}（每夹总条目数，与"这条内容"无关）和
 * {@code isPrivate} 一起带出来 —— 那是噪声，且将来给它加字段会**自动**流进本端点。
 *
 * <p>JSON（{@code /favorite/status} 的 {@code data.folders} 数组元素）：
 * <pre>
 * {"id":1,"name":"默认收藏夹"}
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteFolderBriefVO {

    /** 夹 id —— 供前端做移出 / 移动到另一个夹（那些端点的入参是 {@code folderId}）。 */
    private Long id;

    /** 夹名 —— 供显示（用户要"知道"收在了哪些夹，光有 id 不算知道）。 */
    private String name;
}
