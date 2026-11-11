package io.github.yjhhhaaa06.videoweb.favorite.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 收藏夹封面自动解析的中间行：**每个夹最新一条收藏**的 {@code contentId}（R-33）。
 *
 * <p>只服务 {@code FavoriteItemDao#findLatestContentIdByFolderIds} 的 Mapper 投影：
 * 一条窗口查询取回「一组夹 → 各自最新 contentId」，再由
 * {@code FavoriteFolderCoverService} 经 {@code ContentService.loadContentVOs} 批量取封面。
 *
 * <p>⚠️ 它不是 API 形状、不进任何 VO；{@code folderId} 是分组键，{@code contentId} 是
 * 交给 content 装载契约的唯一标识。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FolderLatestContent {

    private Long folderId;

    private Long contentId;
}
