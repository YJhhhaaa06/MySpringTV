package io.github.yjhhhaaa06.videoweb.content.model;

/**
 * 内容 id → 收藏数的**成对投影**（收藏二期 T12：事务内批量重算的行载体）。
 *
 * <h2>为什么需要这个类型</h2>
 * {@code recountFavoriteCounts}（{@code FavoriteService}，T12）分两步：
 * <ol>
 *   <li>{@code FavoriteItemDao.countDistinctUsersByContentIds} —— 本域一趟聚合
 *       {@code SELECT content_id, COUNT(DISTINCT user_id) … WHERE content_id IN (…) GROUP BY content_id}，
 *       返回本类型的列表（SQL 里用别名对齐属性名，自动映射）；</li>
 *   <li>{@code ContentDao.updateFavoriteCounts} —— 一趟批量 {@code UPDATE … CASE id WHEN ? THEN ? … END}
 *       写回 {@code content.favorite_count}，入参也是本类型的列表。</li>
 * </ol>
 * 两条 SQL 分属两个域的表（{@code favorite_item} 归 favorite、{@code content} 归 content），
 * 但一对值要跨过这两条 SQL —— 故投影类型必须放在一个**双方都合法引用**的位置：
 * {@code content.model}（favorite → content 方向本就存在；反过来放会让 content 新增
 * {@code → favorite.model} 依赖边）。与 {@link AuthorContentId} 的放置理由同构。
 *
 * <h2>为什么是 POJO 而不是 record</h2>
 * record 的组件访问器无 {@code get} 前缀，MyBatis 的参数求值（{@code #{row.contentId}}，
 * 走 MetaObject/Reflector）按 getter 约定解析 ⇒ record 在**参数位**不可靠（结果位可用构造器映射，
 * 即 {@link AuthorContentId} 注释里的实测口径）。本类型一身两职（聚合查询的**结果行** +
 * 批量 UPDATE 的**入参行**），取两端都稳的 POJO 形态。
 *
 * <p>⚠️ 聚合不出行的 contentId（该内容收藏清空 / 从未有人收藏 / 幽灵 id）**不会**出现在第 1 步
 * 的结果里，由调用方补 {@code 0} 后再进第 2 步 —— 不变量 {@code favorite_count == COUNT(DISTINCT user_id)}
 * 对"清零"同样成立（favorite.md §四）。
 */
public class ContentFavoriteCount {

    /** 内容 id（{@code content.id} / {@code favorite_item.content_id}）。 */
    private long contentId;

    /** 收藏数（按人去重 {@code COUNT(DISTINCT user_id)}；恒 ≥ 0，列本身 {@code int unsigned}）。 */
    private long favoriteCount;

    public ContentFavoriteCount() {
    }

    public ContentFavoriteCount(long contentId, long favoriteCount) {
        this.contentId = contentId;
        this.favoriteCount = favoriteCount;
    }

    public long getContentId() {
        return contentId;
    }

    public void setContentId(long contentId) {
        this.contentId = contentId;
    }

    public long getFavoriteCount() {
        return favoriteCount;
    }

    public void setFavoriteCount(long favoriteCount) {
        this.favoriteCount = favoriteCount;
    }
}
