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
 * <h2>★ T15：「我收藏了吗」这件事，本端点与详情页按钮<b>现在是同一个来源</b></h2>
 * T15 之前本端点**只查 DB**（{@code folders} 由 JOIN 得出、布尔再由 {@code folders.isEmpty()} 推导），
 * 而详情页收藏按钮读的是详情内联的 {@code interaction.isFavorited} ——
 * 后者走 {@code FavoriteService.isFavorited} ⇒ {@code FavoriteCache} 的成员集
 * {@code user:favoriteSet:{userId}}（{@code 收藏功能-分期与设计.md} §3.7）。
 * ⚠️ 于是**同一个语义有了两个来源**，且前端正在同一个 UI 元素上混用二者：
 * {@code detail.js} 的 {@code initFavorite()} 拿详情内联点亮按钮，{@code loadPicker()} 又用本端点
 * 的 {@code isFavorited} **覆盖同一个按钮态**（{@code state.isFavorited}）。
 *
 * <p><b>T15 的处置</b>（{@code FavoriteService#getFavoriteStatus}）：本端点**先问成员集**
 * ——未收藏 ⇒ 短路答 {@code {false, []}}（**一次库都不查**）；已收藏 ⇒ 仍**必须**查 {@code folders}，
 * 布尔照旧由 {@code folders.isEmpty()} 推导。
 * <ul>
 *   <li>为什么只在"未收藏"这一支短路：一旦拿到 {@code folders}，布尔就只能由它定 ——
 *       反过来做（照缓存答 true 却不去取 {@code folders}）会得到 {@code {isFavorited:true, folders:[]}}，
 *       <b>打破响应自洽</b>；</li>
 *   <li>为什么这也覆盖冷 key：短路读的就是 {@code FavoriteCache.isFavorited} 那条完整的三态路径
 *       （空标记 → {@code SISMEMBER} → miss 单飞回源回填 → Redis 挂降级），
 *       冷 miss 时它回的是**刚从 DB 读出来的**全量成员集 ⇒ 不是"信缓存"，仍然是"走 DB"。</li>
 * </ul>
 *
 * <h2>⚠️ 「自洽」与「一致」是两件事，别混</h2>
 * <b>① 自洽（响应内，仍然无条件成立）</b>：{@code isFavorited ≡ !folders.isEmpty()} 在**每个分支**都成立
 * （短路分支给的是 {@code false + []}）——T5 钉死的不变量在 T15 后**一字未改**
 * （{@code FavoriteStatusAndCountTests} 把这层钉住）。<br>
 * <b>② 一致（响应 vs DB 真相，★ T15 起<b>不再无条件成立</b>）</b>：成员集陈旧时，本端点会答
 * {@code {false, []}} 而 DB 里其实有记录 —— **这是本次口径变更的边界，由用户 2026-10-13 接受**。
 * <table>
 *   <caption>陈旧的三类触发面（⚠️ 别把三件混成一件）</caption>
 *   <tr><th>#</th><th>触发面</th><th>性质</th></tr>
 *   <tr><td>1</td><td>失效失败后的静默降级（T11 的 {@code warn} ⇒ 靠 TTL 自愈）</td>
 *       <td>真的不一致；<b>本报告书要指的那类</b></td></tr>
 *   <tr><td>2</td><td>绕过写路径直接改库（测试夹具 / 数据修复）</td>
 *       <td>真的不一致；缓存完全不知情</td></tr>
 *   <tr><td>3</td><td>并发读到写提交前的快照</td>
 *       <td>★ <b>不属于</b>这一类：那一瞬间它与 DB <b>一致</b>，只是随即被自己的写覆盖</td></tr>
 *   <tr><td>4</td><td><b>孤儿记录</b>（夹已删、条目残留，{@code favorite_item} 无外键）
 *       —— 本端点 {@code folders} 走 INNER JOIN ⇒ 漏报成"未收藏"，
 *       而 {@code /favorite/count}（不 JOIN）仍把它计入</td>
 *       <td>★ <b>T15 之前就有</b>的已知分叉（{@code 收藏功能-编号索引.md} A.3 <b>I-04</b>；
 *       该不变量的两条端点对照见 {@code FavoriteExistenceTests}），<b>不是</b>本次引入</td></tr>
 * </table>
 * ⚠️ 容忍度边界：错的方向只有"缓存 false / DB true"这一种；且这个 key
 * **详情页按钮本来就在承受**（同一个 {@code user:favoriteSet:{userId}}）⇒ T15
 * **不是新增风险类别**，只是把覆盖面从 1 个端点扩到 2 个。**DB 真相本身不受影响**（写路径一字未动）。
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
     * {@code isFavorited}（与详情页 {@code interaction.isLiked} 同形）。
     */
    private Boolean isFavorited;

    /** 该内容收在我的哪些夹（空列表 = 未收藏）；顺序见类注释。 */
    private List<FavoriteFolderBriefVO> folders;

    /**
     * T15 <b>短路</b>分支的构造点：未收藏（{@code false} + 空 {@code folders}）。
     *
     * <p>收在这里是为了让"自洽"不会被某个分支各自手写时漏掉 ——
     * 短路分支**同样必须**给 {@code folders=[]}（不是 {@code null}、不是省略），
     * 否则前端 {@code st.folders.length} 会 NPE（契约形状见类注释的 JSON 段）。
     * ⚠️ 它**不是**本响应的唯一出口：已收藏那一支要在拿到 DB 的 {@code folders} 之后自行构造
     * （{@code FavoriteService.getFavoriteStatus}）—— 那支必须先赋值 {@code folders}，
     * 布尔随后由 {@code folders.isEmpty()} 推导，构造顺序反过来故不适用本工厂。
     */
    public static FavoriteStatusVO notFavorited() {
        FavoriteStatusVO status = new FavoriteStatusVO();
        status.setIsFavorited(false);
        status.setFolders(List.of());
        return status;
    }
}
