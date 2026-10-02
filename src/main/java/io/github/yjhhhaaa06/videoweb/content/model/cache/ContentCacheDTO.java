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
 *       / {@code ContentDetailVO} 在此基础上加 {@code isLiked} / {@code isFollowed}。</li>
 * </ol>
 *
 * <h2>★ 字段集是冻结契约（JSON 形状）</h2>
 * 对外 JSON 的键 = 本类属性名（由 Getter 名推导）：
 * {@code id / authorId / type / title / description / categoryId / commentCount / likeCount /
 * commentEnabled / authorName / coverUrl / videoUrl / imageUrls / createTime}。
 * 其中 {@code videoUrl} / {@code imageUrls} 只在 {@code ContentDetailVO} 上出现
 * （列表 VO 用 {@code @JsonIgnore} 屏蔽，见 {@code ContentVO}）。
 * 旧 pytest 对 {@code title} / {@code videoUrl} / {@code imageUrls} / {@code authorName} /
 * {@code commentEnabled} 有逐字断言，故**属性名不得改**。
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

    private int commentCount;
    private int likeCount;

    /** 评论区开关：1=开 0=关（列 {@code comment_enabled}）。默认 true（TV 的字段初值）。 */
    private boolean commentEnabled = true;

    /** 作者名（{@code JOIN users u} 的 {@code u.username}）。改名后靠失效内容 key 自愈。 */
    private String authorName;

    // ---- 以下三项是媒体聚合产物，DB 查询不返回，由 buildContentMedia 补齐 ----

    private String coverUrl;
    private String videoUrl;
    private List<String> imageUrls;

    private LocalDateTime createTime;
}
