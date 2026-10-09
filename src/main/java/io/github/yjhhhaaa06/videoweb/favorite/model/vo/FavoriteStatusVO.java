package io.github.yjhhhaaa06.videoweb.favorite.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 收藏状态（{@code GET /favorite/status} 的 {@code data}）——**当前用户**对某内容
 * "是否已收藏 + 收在哪些夹"（需求篇 §三「打开内容页能看到"已收藏"，并知道**收在了哪些夹**」）。
 *
 * <h2>★ 两个字段各自服务前端的一个动作</h2>
 * <ul>
 *   <li>{@code isFavorited} —— 收藏按钮的**初始态**（与 {@code /like/content/status} 的布尔同款）；</li>
 *   <li>{@code folders} —— 收藏弹窗的**勾选态**：这些是"已勾上"的夹，前端拿它与我全部夹
 *       （{@code /favorite/folder/list}）做差集即可渲染勾选框。</li>
 * </ul>
 *
 * <h2>⚠️ {@code isFavorited} 恒等于 {@code !folders.isEmpty()} —— 它不是第二处事实源</h2>
 * 收藏**必须有归属**（一期无"无夹收藏"形态：没选夹也进默认夹）⇒ 一条收藏记录必属于某个夹。
 * 故"已收藏 ⟺ 至少落在一个夹里"是**结构性成立**的，本类里 {@code isFavorited} 由
 * {@code folders.isEmpty()} **推导**而来（{@code FavoriteService.getFavoriteStatus}），
 * 不是另查一次得来的：<b>没有可漂移的余地</b>。留这个字段纯粹为前端省一次
 * {@code folders.length > 0} 的推导，并为"按钮态"提供与点赞同形的直接读法。
 * {@code FavoriteStatusAndCountTests} 里有一条把该不变量钉死。
 *
 * <p>JSON（{@code data}）：
 * <pre>
 * {"isFavorited":true,"folders":[{"id":1,"name":"默认收藏夹"},{"id":5,"name":"待看"}]}
 * </pre>
 * 未收藏时 {@code {"isFavorited":false,"folders":[]}}（**空数组**，不是 null、不是 404）。
 *
 * <h2>顺序</h2>
 * {@code folders} 的顺序 = "我的夹列表"同一契约（**默认夹置顶 + 创建序**，见
 * {@code FavoriteItemDao.findFoldersByUserAndContent}），两处不许各排一半。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteStatusVO {

    /**
     * 当前用户是否收藏了该内容（{@code = !folders.isEmpty()}，见类注释）。
     *
     * <p>⚠️ 用包装类型 {@code Boolean}（同 {@code FavoriteFolderVO} 的口径）：属性名
     * {@code isFavorited} 配 {@code boolean} 会让 Lombok 生成 {@code isFavorited()} ⇒ Jackson 属性名
     * 漂成 {@code favorited}；包装类型下 getter 是 {@code getIsFavorited()} ⇒ JSON 键逐字
     * {@code isFavorited}（与详情页的 {@code isLiked} 同形）。
     */
    private Boolean isFavorited;

    /** 该内容收在我的哪些夹（空列表 = 未收藏）；顺序见类注释。 */
    private List<FavoriteFolderBriefVO> folders;
}
