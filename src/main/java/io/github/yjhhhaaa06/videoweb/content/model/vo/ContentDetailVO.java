package io.github.yjhhhaaa06.videoweb.content.model.vo;

import io.github.yjhhhaaa06.videoweb.content.model.cache.ContentCacheDTO;
import io.github.yjhhhaaa06.videoweb.interaction.model.vo.InteractionStatusVO;

/**
 * 内容**详情**（承接 TV {@code ContentDetailVO}；T10 起内联互动态，见
 * {@code favorite.md} §六，编号 R-10）。
 *
 * <p>与父类（{@link ContentCacheDTO}）的差异：内嵌子对象 {@link #interaction}
 * （互动态）与公共计数 {@link #coinCount}（T40）。**媒体字段（videoUrl / imageUrls）在详情里
 * 是要返回的**——这正是它与 {@link ContentVO}（列表，屏蔽媒体）的区别。
 *
 * <h2>★ 对外 JSON 键集 = 父类全部键 + {@code interaction} + {@code coinCount}（T10 / T40）</h2>
 * 顶层 {@code isLiked} / {@code isFollowed} **已于 T10 搬进** {@code interaction} 子对象
 * （用户 2026-10-11 明确接受 breaking）；子对象键集恰好
 * {@code {isLiked, isFavorited, isFollowed, coin}}，逐字段口径见 {@link InteractionStatusVO}。
 * ⚠️ **匿名（无有效 token）⇒ {@code "interaction": null}**（不是 {@code {}}、不是四个
 * {@code false}）——"没登录"与"登录但零互动"由此可区分，是 R-10 相对顶层平铺的**表达力增益**。
 * ⚠️ 公共计数 / 内容属性（{@code likeCount} / {@code commentCount} / {@code createTime} …）
 * **刻意不在子对象内**：它们是**请求无关**的，只有"我的状态"才属于互动域。
 * {@code coinCount} 因此是**顶层的兄弟字段**（与 {@code interaction} 平级），不是子对象的一员。
 *
 * <h2>★ {@code coinCount} 绝不进 {@link ContentCacheDTO}（缓存形状冻结）</h2>
 * 公共计数是请求无关的，但它来自 {@code content.coin_count} 这一列 / coin 域的**独立缓存**
 * （{@code content:coinCount:{id}}），**不由内容缓存形状承载** —— 加进 DTO 会改
 * {@code content:{id}} 的 JSON 形状，破坏冻结契约。详情由
 * {@code ContentService#getContentDetailVO} 单条填（{@code CoinService#getCoinCount}）。
 *
 * <p>★ T13 起**列表 VO（{@link ContentVO}）与本类同形**（同样内嵌 {@code interaction}、
 * 同样匿名 {@code null}）—— 互动态在全仓**只有一处定义**；差别只在"**各带哪几维**"
 * （详情恒四维，列表按面选且 {@code coin} 恒 {@code null}，见 {@link ContentVO} 类注释的常量表）。
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
     * 内容的**累计投币数**（公共计数，请求无关；T40）。
     *
     * <p>★ 绝不进 {@link ContentCacheDTO}：来源是 {@code content.coin_count} 这一列 /
     * coin 域的独立缓存 key，**不由内容缓存形状承载**（见类注释）。
     */
    private Long coinCount;

    public ContentDetailVO() {
    }

    public InteractionStatusVO getInteraction() {
        return interaction;
    }

    public void setInteraction(InteractionStatusVO interaction) {
        this.interaction = interaction;
    }

    public Long getCoinCount() {
        return coinCount;
    }

    public void setCoinCount(Long coinCount) {
        this.coinCount = coinCount;
    }
}
