package io.github.yjhhhaaa06.videoweb.content.model.vo;

import io.github.yjhhhaaa06.videoweb.content.model.cache.ContentCacheDTO;
import io.github.yjhhhaaa06.videoweb.interaction.model.vo.InteractionStatusVO;

/**
 * 内容**详情**（承接 TV {@code ContentDetailVO}；T10 起内联互动态，见
 * {@code favorite.md} §六，编号 R-10）。
 *
 * <p>与父类（{@link ContentCacheDTO}）的差异：内嵌子对象 {@link #interaction}（互动态）
 * 与**四个平铺的公共计数**（{@code likeCount} / {@code commentCount} / {@code favoriteCount} /
 * {@code coinCount}，T43 定形）。**媒体字段（videoUrl / imageUrls）在详情里
 * 是要返回的**——这正是它与 {@link ContentVO}（列表，屏蔽媒体）的区别。
 *
 * <h2>★ 对外 JSON 键集 = 父类全部键 + {@code interaction} + 四个公共计数（T10 / T40 / T43）</h2>
 * 顶层 {@code isLiked} / {@code isFollowed} **已于 T10 搬进** {@code interaction} 子对象
 * （用户 2026-10-11 明确接受 breaking）；子对象键集恰好
 * {@code {isLiked, isFavorited, isFollowed, coin}}，逐字段口径见 {@link InteractionStatusVO}。
 * ⚠️ **匿名（无有效 token）⇒ {@code "interaction": null}**（不是 {@code {}}、不是四个
 * {@code false}）——"没登录"与"登录但零互动"由此可区分，是 R-10 相对顶层平铺的**表达力增益**。
 * ⚠️ 公共计数**刻意不在子对象内**（也**不**收成一个 {@code counts} 子对象，T43 用户拍板）：
 * 它们是**请求无关**的、每个数一个来源域，平铺后可单独演进（例如以后只给详情加"播放量"）。
 *
 * <h2>★ T43：四个计数为什么都在这里、且都不进 {@link ContentCacheDTO}</h2>
 * 计数的规范形态 = **一个计数一个独立 key**：
 * {@code content:likeCount}（like 域）/ {@code content:commentCount}（content 域）/
 * {@code content:favoriteCount}（favorite 域）/ {@code content:coinCount}（coin 域）。
 * 内容缓存 {@code content:{id}} 只放**慢变**字段（标题 / 描述 / 媒体 / 作者 / 分区）——
 * 否则每一次点赞或评论都要 {@code DEL} 整份载荷，让媒体装配与 JOIN 陪葬。
 * 三个来源由互动域一趟聚合（{@code InteractionService#getPublicCounts}），
 * 评论数由内容域自己的 {@code ContentCountCache} 填（{@code content} 不得依赖 {@code comment}，
 * 见 {@code ArchitectureTests} 规则 4）。
 *
 * <p>⚠️ T43 起**列表 VO 与本类不再同形**：列表没有任何计数（用户 2026-10-19 拍板），
 * 见 {@link ContentVO}。互动态仍在两处共用同一个子对象定义。
 * ⚠️ 本类走 {@code InteractionService.getStatus}（单条、**无**维度入参 —— R-13 刻意
 * 不为详情扩大契约面，见 {@code InteractionService} 类注释）。
 *
 * <p>旧 pytest {@code S-06}/{@code S-08} 断言 {@code title} 存在、且
 * {@code videoUrl} 或 {@code imageUrls} 至少有一个存在（按 type 决定）——已固化为测试。
 */
public class ContentDetailVO extends ContentCacheDTO {

    /** 互动态（R-10）；{@code null} = 匿名请求（互动域对 {@code userId == null} 短路，不查任何维度）。 */
    private InteractionStatusVO interaction;

    /**
     * 内容的**点赞数**（公共计数，请求无关；T43）。
     *
     * <p>★ 绝不进 {@link ContentCacheDTO}：来源是 {@code content:likeCount:{id}} 独立缓存 key
     * （点赞域持有，回源 {@code COUNT(*) content_like}）。平铺在本类上、**不进** {@code interaction}
     * —— 它是请求无关的公共数字，"我的状态"才属于互动域。
     */
    private long likeCount;

    /**
     * 内容的**评论总数**（公共计数；T43）。
     *
     * <p>★ 来源是 {@code content:commentCount:{id}}（内容域自持，回源 {@code content.comment_count}
     * 单列）。⚠️ 不是 {@code content:comments:{id}:count}（那个是**主楼数**，不含回复）。
     */
    private long commentCount;

    /**
     * 内容的**收藏数**（按人去重；T43）。
     *
     * <p>★ 来源是 {@code content:favoriteCount:{id}}（收藏域持有）。此前详情由前端另调
     * {@code GET /favorite/count} 取；T43 起随详情一次返回（端点保留，供不取详情的调用方）。
     */
    private long favoriteCount;

    /**
     * 内容的**累计投币数**（公共计数，请求无关；T40）。
     *
     * <p>★ 绝不进 {@link ContentCacheDTO}：来源是 {@code content.coin_count} 这一列 /
     * coin 域的独立缓存 key，**不由内容缓存形状承载**（见类注释）。
     */
    private long coinCount;

    public ContentDetailVO() {
    }

    public InteractionStatusVO getInteraction() {
        return interaction;
    }

    public void setInteraction(InteractionStatusVO interaction) {
        this.interaction = interaction;
    }

    public long getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(long likeCount) {
        this.likeCount = likeCount;
    }

    public long getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(long commentCount) {
        this.commentCount = commentCount;
    }

    public long getFavoriteCount() {
        return favoriteCount;
    }

    public void setFavoriteCount(long favoriteCount) {
        this.favoriteCount = favoriteCount;
    }

    public long getCoinCount() {
        return coinCount;
    }

    public void setCoinCount(long coinCount) {
        this.coinCount = coinCount;
    }
}
