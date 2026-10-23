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
 * ⚠️ T43 起两个 VO **不再逐字段同形**：详情多出公共计数（T48 起五个；列表一个都没有，见下一节）。
 * 于是对外 JSON 的键集 = 父类键集 − {videoUrl, imageUrls} + {interaction}，
 * 即 {@code {id, authorId, type, title, description, categoryId, commentEnabled, authorName,
 * coverUrl, createTime, interaction}}。
 *
 * <h2>★ T43：列表**不带任何公共计数**（用户 2026-10-19 拍板）</h2>
 * 点赞 / 评论 / 收藏 / 投币 / 播放五个计数一律独立 key，且**只在详情响应平铺返回**
 * （见 {@link ContentDetailVO}）。列表卡片不再显示数字 —— 于是本类**没有** {@code coinCount}
 * （T40 曾加过，T43 撤掉）、也没有 {@code likeCount} / {@code commentCount}（它们已从父类移出）。
 * <p>口径与理由见 {@code architecture/tech/cache/缓存与数据一致性.md} §五：计数的读放大
 * 换"内容缓存不再被高频计数失效"，列表页不为展示位付 N 趟批量。
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

    public ContentVO() {
    }

    public InteractionStatusVO getInteraction() {
        return interaction;
    }

    public void setInteraction(InteractionStatusVO interaction) {
        this.interaction = interaction;
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
