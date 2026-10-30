package io.github.yjhhhaaa06.videoweb.content.model.cache;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 内容缓存载荷（承接 TV {@code com.itheima.content.model.cache.ContentCacheDTO}）。
 *
 * <h2>它同时是三种东西（TV 原样，保留这个"一身三职"是有意的）</h2>
 * <ol>
 *   <li><b>DB 行</b>：{@code ContentDao.findContent} / {@code findContentsByIds} /
 *       {@code findAllContent} 的结果类型（{@code resultMap} 显式映射）；</li>
 *   <li><b>Redis 值</b>：{@code content:{id}} 的 JSON 载荷（{@link io.github.yjhhhaaa06.videoweb.common.cache.JsonCodec}）；</li>
 *   <li><b>VO 的父类</b>：{@link io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO}
 *       / {@code ContentDetailVO} 在此基础上加 {@code interaction} 子对象与**公共计数**
 *       （互动态见 {@code InteractionStatusVO}）。</li>
 * </ol>
 *
 * <h2>★ 字段集是冻结契约（JSON 形状）</h2>
 * 对外 JSON 的键 = 本类属性名（由 Getter 名推导）：
 * {@code id / authorId / type / title / description / categoryId / commentEnabled /
 * authorName / coverUrl / videoUrl / imageUrls / durationSeconds / createTime}。
 * 其中 {@code videoUrl} / {@code imageUrls} 只在 {@code ContentDetailVO} 上出现
 * （列表 VO 用 {@code @JsonIgnore} 屏蔽，见 {@code ContentVO}）；{@code durationSeconds}
 * 是 T46 新增的**所有内容响应共有**的键（列表也带 —— 它是慢变字段，不是计数）。
 * 旧 pytest 对 {@code title} / {@code videoUrl} / {@code imageUrls} / {@code authorName} /
 * {@code commentEnabled} 有逐字断言，故**属性名不得改**。
 *
 * <h2>★ T43：{@code likeCount} / {@code commentCount} 已从本载荷移出（用户 2026-10-19 拍板）</h2>
 * 它们是**高频变化**的计数：留在本载荷里，每次点赞 / 发评论都必须 {@code DEL content:{id}}，
 * 让标题、媒体 URL、作者名这些**慢变**字段陪葬（热内容上内容缓存近乎永久失效）。
 * 现在五个计数一律走独立 key（{@code content:likeCount} / {@code content:commentCount} /
 * {@code content:favoriteCount} / {@code content:coinCount} / {@code content:playCount}），
 * 详情五维平铺；GAP-01（2026-10-26）起列表按面平铺其中三维（play / like / comment，均可空），
 * 但同样**不进本载荷**。口径见 {@code architecture/tech/cache/缓存与数据一致性.md} §五。
 * <p>⚠️ 于是本载荷只承载"**改一次要写库**"的字段：编辑内容 / 删改媒体 / 改名 / 删除。
 * 若将来又想往这里塞计数，先读上面那条口径。
 *
 * <p>另两个只有缓存才有的字段（{@code coverUrl} / {@code videoUrl} / {@code imageUrls}）是
 * **媒体聚合产物**：DB 查询不返回它们，由 {@code ContentCache.buildContentMedia} 从
 * {@code content_media} 行的 type/sort 拼出。所以"从 DB 装载"与"从缓存反序列化"拿到的
 * 是同一个类型但**媒体字段只有后者完整**——TV 原样，靠 `buildContentMedia` 在两条路径上统一补齐。
 */
@Data
public class ContentCacheDTO {

    private long id;

    /** 作者 id（列名 {@code user_id}；SQL 里 {@code u.id AS user_id}）。 */
    private long authorId;

    /** 内容类型：1=视频 2=图文（{@code ContentMedia} 的代码 1 视频 / 2 图片 / 3 封面）。 */
    private int type;

    private String title;
    private String description;
    private int categoryId;

    /** 评论区开关：1=开 0=关（列 {@code comment_enabled}）。默认 true（TV 的字段初值）。 */
    private boolean commentEnabled = true;

    /** 作者名（{@code JOIN users u} 的 {@code u.username}）。改名后靠失效内容 key 自愈。 */
    private String authorName;

    // ---- 以下三项是媒体聚合产物，DB 查询不返回，由 buildContentMedia 补齐 ----

    private String coverUrl;
    private String videoUrl;
    private List<String> imageUrls;

    /**
     * 视频时长（秒）—— **媒体聚合产物**，由 {@code ContentCache#buildContentMedia}
     * 从 {@code type=1} 的那条媒体行投影（**零额外查询**：媒体行本来就要装配）。
     *
     * <p>★ 它是**慢变字段**（只在发布 / 换源时变）⇒ 留在本载荷里是安全的，
     * 不影响 T43 "计数搬出内容缓存" 的口径（计数的特征是高频变化）。
     *
     * <p>{@code null} = 未知：图文 / 解析失败 / 存量未回填（{@code history.md} §六.2）。
     * 用包装类型而非 {@code int}：{@code 0} 是"0 秒的视频"，与"不知道"是两回事。
     */
    private Integer durationSeconds;

    private LocalDateTime createTime;
}
