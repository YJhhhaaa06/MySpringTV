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
 * <h2>⚠️ {@code isFavorited} 由 {@code folders} 推导 —— 字段级恒等成立，但"已收藏"的语义**有前提**</h2>
 * <b>① 字段级（成立）</b>：本类里 {@code isFavorited} 由 {@code folders.isEmpty()} **推导**而来
 * （{@code FavoriteService.getFavoriteStatus}，同一次调用内同源），不另查一次 ⇒ 它**不是**第二处
 * 事实源，这两个字段之间没有可漂移的余地（{@code FavoriteStatusAndCountTests} 把该不变量钉死）。
 * 留它纯粹为前端省一次 {@code folders.length > 0} 的推导，并为"按钮态"提供与点赞同形的直接读法。
 *
 * <p><b>② 语义级（⚠️ 有前提，别当"结构性"）</b>："已收藏 ⟺ 至少落在一个夹里"看着是结构性事实，
 * 实则**依赖"每条收藏记录都指向一个存在的夹"** —— 而 {@code favorite_item} 与
 * {@code favorite_folder} **之间没有外键**（V2 刻意与 V1 一致），DB 不兜底；这个保证**只由
 * {@code FavoriteService.deleteFolder} 的 {@code @Transactional} 一处**承担，而它正是
 * {@code CURRENT_ISSUES.md} <b>I-04</b> 登记为"零判别力、可被静默误删"的那处。
 * ⚠️ 一旦出现孤儿记录（夹已删、条目残留）：本端点（{@code folders} 走 INNER JOIN 夹）会**漏报**
 * 成"未收藏"，而 {@code /favorite/count}（不 JOIN）**仍把它计入** —— 两条读端点口径就此不一致。
 * 根治见 I-04（给 {@code folder_id} 加外键，或补删夹失败的注入用例）。
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
