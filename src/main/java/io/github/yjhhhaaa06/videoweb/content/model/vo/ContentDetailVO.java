package io.github.yjhhhaaa06.videoweb.content.model.vo;

import io.github.yjhhhaaa06.videoweb.content.model.cache.ContentCacheDTO;
import io.github.yjhhhaaa06.videoweb.interaction.model.vo.InteractionStatusVO;

/**
 * 内容**详情**（承接 TV {@code ContentDetailVO}；T10 起内联互动态，见
 * {@code 收藏功能-分期与设计.md} §3.6，编号 R-10）。
 *
 * <p>与父类（{@link ContentCacheDTO}）的差异只有一处：内嵌子对象 {@link #interaction}
 * （互动态）。**媒体字段（videoUrl / imageUrls）在详情里是要返回的**——这正是它与
 * {@link ContentVO}（列表，屏蔽媒体）的区别。
 *
 * <h2>★ 对外 JSON 键集 = 父类全部键 + {@code interaction}（T10 的 breaking 面）</h2>
 * 顶层 {@code isLiked} / {@code isFollowed} **已于 T10 搬进** {@code interaction} 子对象
 * （用户 2026-10-11 明确接受 breaking）；子对象键集恰好
 * {@code {isLiked, isFavorited, isFollowed}}，逐字段口径见 {@link InteractionStatusVO}。
 * ⚠️ **匿名（无有效 token）⇒ {@code "interaction": null}**（不是 {@code {}}、不是三个
 * {@code false}）——"没登录"与"登录但零互动"由此可区分，是 R-10 相对顶层平铺的**表达力增益**。
 * ⚠️ 公共计数 / 内容属性（{@code likeCount} / {@code commentCount} / {@code createTime} …）
 * **刻意不在子对象内**：它们是**请求无关**的，只有"我的状态"才属于互动域。
 * ⚠️ 列表 VO（{@link ContentVO}）**刻意不改**——它仍把 {@code isLiked} / {@code isFollowed}
 * 放在顶层（两者不同，别一起改）。
 *
 * <p>旧 pytest {@code S-06}/{@code S-08} 断言 {@code title} 存在、且
 * {@code videoUrl} 或 {@code imageUrls} 至少有一个存在（按 type 决定）——已固化为测试。
 */
public class ContentDetailVO extends ContentCacheDTO {

    /** 互动态（R-10）；{@code null} = 匿名请求（互动域对 {@code userId == null} 短路，不查任何维度）。 */
    private InteractionStatusVO interaction;

    public ContentDetailVO() {
    }

    public InteractionStatusVO getInteraction() {
        return interaction;
    }

    public void setInteraction(InteractionStatusVO interaction) {
        this.interaction = interaction;
    }
}