package io.github.yjhhhaaa06.videoweb.interaction.model;

/**
 * 一次互动查询的**目标**：内容 id + 作者 id + 内容类型 —— 批量契约的入参载体
 * （R-10，详细设计见 {@code 收藏功能-分期与设计.md} §3.6）。
 *
 * <h2>★ 为什么作者 id 与类型必须由调用方传入（本域刻意不查）</h2>
 * <ul>
 *   <li><b>{@code authorId}</b>：{@code isFollowed} 判的是"我关不关注这条内容的**作者**"。
 *       互动域**不得依赖 content**（否则 {@code content → interaction → content} 成环，
 *       R-10 §3.6 的"无环"收益落空）⇒ 不得反查"这条内容的作者是谁"。调用方（详情 / 列表）
 *       本来就持有它，直传即可。</li>
 *   <li><b>{@code type}</b>：内容类型（取值同 {@code content.type}：1=视频、2=图文）。它是
 *       "**哪些互动维度适用**"的判据位置 —— 今天两种类型都适用三维（赞 / 藏 / 关注作者），
 *       故暂不参与分支；留给"按类型收窄的维度"（如投币 —— {@code FURTHER_ISSUES.md} 登记 F-13）。
 *       ⚠️ 类型只能由调用方传入**数值**：互动域不得 import content 的类型枚举（同成环理由）。</li>
 * </ul>
 *
 * <p>{@code authorId <= 0}（作者未知）时：关注维度**不查询**、按 {@code false} 作答 ——
 * 与既有列表填充口径一致（{@code ContentStatusFiller.fillFollowStatus} 跳过非正作者 id）。
 *
 * @param contentId 内容 id
 * @param authorId  内容作者 id（{@code <= 0} = 未知）
 * @param type      内容类型（1=视频、2=图文；"哪些维度适用"的判据，见上）
 */
public record InteractionTarget(long contentId, long authorId, int type) {
}
