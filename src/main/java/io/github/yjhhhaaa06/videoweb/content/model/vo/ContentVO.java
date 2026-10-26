package io.github.yjhhhaaa06.videoweb.content.model.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.yjhhhaaa06.videoweb.content.model.cache.ContentCacheDTO;
import io.github.yjhhhaaa06.videoweb.interaction.model.vo.InteractionStatusVO;

import java.util.List;

/**
 * 内容**列表条目**（承接 TV {@code ContentVO}）。
 *
 * <h2>相对父类的两个差异</h2>
 * <ol>
 *   <li>★ 内嵌子对象 {@link #interaction}（互动态）。T13（R-13，2026-10-13）起两个 VO 内嵌同一个
 *       子对象；此处在此之前 的顶层 {@code isLiked} / {@code isFollowed} **已删除**
 *       （breaking，用户接受）。逐字段口径见
 *       {@link InteractionStatusVO}：**四维恒在**——前三维值三态（{@code true/false/null}），
 *       第四维 {@code coin} 是 {@code 0/1/2} 或 {@code null}；
 *       **匿名（无有效 token）⇒ {@code "interaction": null}**（不是 {@code {}}、
 *       不是四个 {@code false}、也不是"四维全 null 的空壳"）。</li>
 *   <li>用 {@code @JsonIgnore} 屏蔽 {@code videoUrl} / {@code imageUrls}——
 *       **列表不带媒体地址**（TV 的设计：列表只需要封面，媒体地址属详情）</li>
 * </ol>
 * ⚠️ T43 起两个 VO **不再逐字段同形**；GAP-01（2026-10-26）起列表也带**三个可空公共计数**
 * （见下一节），但仍不含详情独有的媒体字段与投币/收藏数。
 * 于是对外 JSON 的键集 = 父类键集 − {videoUrl, imageUrls}
 * + {interaction, likeCount, commentCount, playCount}，
 * 即 {@code {id, authorId, type, title, description, categoryId, commentEnabled, authorName,
 * coverUrl, createTime, interaction, likeCount, commentCount, playCount}}。
 *
 * <h2>★ GAP-01（2026-10-26）：列表平铺三个**可空**计数，按面按需填充</h2>
 * T43 的"列表零计数"已被按面推翻。三类卡片对计数的需求不同（B站对照见
 * {@code .docs/temp/gap01-backend-note.md}），故这里只平铺**当前有展示位**的三维，
 * 且全部用包装类型 {@link Long}：
 * <pre>
 *   网格卡片（首页 / 搜索 / 相关推荐 / 空间投稿） ⇒ playCount
 *   动态卡片（/feed）                              ⇒ playCount + likeCount + commentCount
 * </pre>
 * ★ {@code null} = **本面不查这一维**，{@code 0} = 真实值 —— 两者必须可区分，
 * 这正是选包装类型而不是 {@code long} 的理由（互动态三态踩过同款坑）。
 * 没有展示位的 {@code coinCount} / {@code favoriteCount} **连字段都不加**：
 * 加了但恒 {@code null} 的键等于让契约说谎（要加时先有展示位）。
 *
 * <p>★ 计数仍是**独立 key**、仍然**不进** {@link ContentCacheDTO}（T43 的核心口径未变）：
 * 列表带计数只多一趟批量读，内容载荷不会因点赞 / 评论而失效。
 * 读取通道见 {@code ContentCountBatchAggregate}，维度常量写在四个调用点上。
 *
 * <h2>★ 列表里 {@code interaction.coin} **恒 {@code null}** 是契约，不是漏填</h2>
 * 列表四个面没有任何投币展示位 ⇒ 批量契约不查该维（{@code InteractionDimension} 刻意没有
 * {@code COIN} 常量，见其类注释）。客户端按既有三态口径静默忽略 {@code null}。
 *
 * <h2>★ 四个列表面各带哪几维（R-13；**编译期常量**，写在各自调用点上）</h2>
 * 初衷：简略 VO 不需要的维度**就不查**，省下的那一位是 {@code null}、前端静默忽略
 * （互动域因此**不再按固定全集一起答**，见 {@code InteractionDimension}）。
 * <pre>
 *   /api/v1/contents/recommended（首页） ⇒ {关注}   （对标 B站 首页卡片只显示「已关注」）
 *   /api/v1/contents（搜索）             ⇒ {}（0 维）（B站 搜索结果卡片不显示任何互动态）
 *   /api/v1/feed                         ⇒ {赞}
 *   /api/v1/users/{id}.contentPage（主页）⇒ {赞}
 *   /api/v1/contents/{id}（详情）        ⇒ 四维（经 getStatus 单条，**不经本类**）
 *   （以上为 `T58` 资源化后的路径，原 /start、/search/keywordSearch、/feed、/profile、/search/IdSearch）
 * </pre>
 * ⚠️ 0 维**不等于"不填"**：仍要调互动域（它一趟都不查），拿到"对象在、四维全 {@code null}"
 * —— 直接留 {@code null} 会与"匿名"不可区分。
 *
 * <h2>★⚠️ 为什么 {@code interaction} 只能挂在**本类**、绝不进 {@code ContentCacheDTO}</h2>
 * {@link ContentCacheDTO} 是**缓存形状**（一条内容被所有用户共享的那份），
 * 而互动态是"**我的**状态"、是**请求态** —— 放进去等于把"我点过赞吗"缓存起来给所有人看
 * ⇒ **跨用户污染**。故它只在 {@code ContentVO} / {@code ContentDetailVO} 这两个
 * **响应**子类上，且 {@code ContentCache.toContentVO} **恒产出 {@code null}**
 * （互动态只能在**请求期**由 {@code ContentStatusFiller} / {@code FeedService} /
 * {@code ProfileService} 填）。
 *
 * <h2>⚠️ 为什么手写 getter 而不挂 Lombok {@code @Getter}</h2>
 * 本类曾经的 {@code isLiked} 字段就是前车之鉴：Lombok 对 {@code boolean isLiked} 生成
 * {@code isLiked()} ⇒ Jackson 推导出属性名 <b>{@code liked}</b>，与契约名 {@code isLiked}
 * 不符，两者**同时存在**时会输出**两个**字段。如今互动态收进 {@link InteractionStatusVO}
 * （那里同样手写 getter），本类只剩 {@code interaction} 一个普通属性名
 * —— 命名天然安全，但**键集仍是冻结契约**（旧 pytest 对 {@code authorName} 有逐字断言；
 * 缺/多字段都算回归）。
 */
public class ContentVO extends ContentCacheDTO {

    /**
     * 互动态（R-10 / R-13）；{@code null} = 匿名请求（互动域对 {@code userId == null} 短路，
     * 不查任何维度）。⚠️ **只在本响应子类上**，绝不进 {@link ContentCacheDTO}（见类注释）。
     */
    private InteractionStatusVO interaction;

    /**
     * 内容的**点赞数**（公共计数，请求无关；GAP-01 起列表按面可空填充）。
     *
     * <p>★ 只有动态卡片（{@code /feed}）查这一维；网格卡片为 {@code null}（= 本面不查，**不是 0**）。
     * 来源是 {@code content:likeCount:{id}}（点赞域持有），与详情同名同源。
     */
    private Long likeCount;

    /**
     * 内容的**评论总数**（公共计数，请求无关；GAP-01）。
     *
     * <p>★ 只有动态卡片（{@code /feed}）查这一维；网格卡片为 {@code null}。
     * 口径同详情：含楼中楼的**评论总数**（{@code content.comment_count}），不是主楼数。
     */
    private Long commentCount;

    /**
     * 内容的**播放量**（公共计数，请求无关；GAP-01）。
     *
     * <p>★ **所有列表面都查**（网格卡片封面角标 / 动态卡片嵌入块）⇒ 正常响应里不会是 {@code null}；
     * 该包装类型只为与另外两维保持同一契约形态（未请求时统一 {@code null}）。
     * 图文内容的语义是「浏览」而非「播放」（口径见 GAP-05）。
     */
    private Long playCount;

    public ContentVO() {
    }

    public InteractionStatusVO getInteraction() {
        return interaction;
    }

    public void setInteraction(InteractionStatusVO interaction) {
        this.interaction = interaction;
    }

    public Long getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(Long likeCount) {
        this.likeCount = likeCount;
    }

    public Long getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(Long commentCount) {
        this.commentCount = commentCount;
    }

    public Long getPlayCount() {
        return playCount;
    }

    public void setPlayCount(Long playCount) {
        this.playCount = playCount;
    }

    /** 列表不返回视频地址（TV {@code @JsonIgnore} 原样）。 */
    @Override
    @JsonIgnore
    public String getVideoUrl() {
        return super.getVideoUrl();
    }

    /** 列表不返回图片地址组（TV {@code @JsonIgnore} 原样）。 */
    @Override
    @JsonIgnore
    public List<String> getImageUrls() {
        return super.getImageUrls();
    }
}
