package io.github.yjhhhaaa06.videoweb.interaction.model;

/**
 * 互动域的**维度**（R-13，2026-10-13 拍板；形态口径见 {@code favorite.md} §六
 * 「列表侧形态统一」）。
 *
 * <h2>它解决什么</h2>
 * 三维（赞 / 藏 / 关注）不是每个面都要：首页只显示「已关注」、搜索结果一张卡片什么都不显示、
 * 动态流与个人主页只显示点赞态。R-13 之前互动域**只会三维一起答**（每面都白查 2~3 趟），
 * 本枚举让**调用方声明自己要哪几维**，不查的维度在 {@code InteractionStatusVO} 里是
 * {@code null}（前端静默忽略）。
 * <p>⚠️ T40 的第四维投币 {@code coin} **刻意不在本枚举里**（列表各面都不需要它，见下节）。
 *
 * <h2>★ 为什么是「enum + {@code Set}」而不是三个 boolean 开关</h2>
 * <ul>
 *   <li><b>调用点传编译期常量</b>：每面的维度写成 {@code EnumSet.of(...)} 常量
 *       （例：{@code EnumSet.of(FOLLOW)}），编译器就能保证"写不出不存在的维度"；</li>
 *   <li><b>形状是契约、维度是常量</b>：改"某面带哪几维"只是改一行常量、**不破契约**
 *       （响应键集恒四维）；而改 {@code interaction} 的形状才 breaking —— 两者刻意分开。</li>
 * </ul>
 *
 * <h2>★ 为什么没有 {@code COIN}：第四维不走本枚举（T40）</h2>
 * 投币维 {@code coin}（{@code 0/1/2}，见 {@code InteractionStatusVO}）**只出现在详情**，
 * 列表各面没有任何展示位 ⇒ 没有任何列表面需要选它，给它一个常量是纯 YAGNI；
 * 而且往本枚举加值会**扩大 R-13 刚钉死的批量契约面**。故批量契约恒不查该维
 * （返回对象里 {@code coin} 恒 {@code null}），单条契约按 {@code type} 自行分流。
 *
 * <h2>★ 空集（0 维）是合法入参 —— 及它为什么不是 {@code null}</h2>
 * 传 {@code EnumSet.noneOf(InteractionDimension.class)} ⇒ **不查任何域**，但互动域
 * **仍返回一个 {@code InteractionStatusVO} 对象**（四维全 {@code null}），
 * 而不是返回 {@code null}。
 * <ul>
 *   <li>⚠️ {@code null} 在本域**已经被"匿名"占用**（{@code userId == null} ⇒ 返回 {@code null}）；
 *       让"0 维"也返回 {@code null} 会让同一个值有两个含义 ⇒ 登录用户在 0 维面上的响应与
 *       匿名**逐字相同**，无从区分。</li>
 *   <li>★ 这是 2026-10-13 对 R-13 原红线「不许选 0 维」的**解禁**：那条红线括号里的理由
 *       是「{@code interaction: null} 会与匿名不可区分」—— 而"对象仍在、四维全 {@code null}"
 *       **不产生那个后果**，故红线理由在此不成立；解禁只针对**字面**，理由仍有效。</li>
 *   <li>⚠️ 0 维**不等于"跳过调用"**：调用方仍须调批量契约（只是它一趟都不查）。
 *       直接跳过会得到 {@code interaction: null} —— 那是匿名语义，是错的。</li>
 * </ul>
 *
 * <h2>{@code null} 值的两种含义（共用同一表示）</h2>
 * ① **本面不查该维**（本枚举的用途）；② **该内容类型不适用该维**（图文帖的 {@code coin}）。
 * 客户端语义相同 —— 都是"不显示该维度的状态" ⇒ 共用 {@code null}，不另造表示。
 * ⚠️ 键集**恒四维**，不靠"缺键"表达 ⇒ {@code InteractionStatusVO} 上**不加**
 * {@code @JsonInclude(NON_NULL)}（本仓三态惯例，见 §六）。
 */
public enum InteractionDimension {

    /** 内容赞 —— {@code isLiked}（like 域的**内容赞**；评论赞不在本域范围）。 */
    LIKE,

    /** 收藏 —— {@code isFavorited}（favorite 域）。 */
    FAVORITE,

    /** 关注**作者** —— {@code isFollowed}（follow 域；判的是作者不是内容）。 */
    FOLLOW
}
