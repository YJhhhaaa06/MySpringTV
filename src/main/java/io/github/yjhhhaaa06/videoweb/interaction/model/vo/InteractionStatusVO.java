package io.github.yjhhhaaa06.videoweb.interaction.model.vo;

import io.github.yjhhhaaa06.videoweb.interaction.model.InteractionDimension;

/**
 * 当前用户对一条内容的**互动态**（R-10，详细设计见 {@code favorite.md} §六；
 * 三态口径由 R-13 于 2026-10-13 追加；投币维由 T40 追加，见
 * {@code architecture/domain/coin/coin.md} §七）。
 *
 * <p>四个字段都是"**与请求者有关**"的状态（请求无关的内容属性 / 公共计数 —— 如
 * likeCount / coinCount / commentCount / createTime —— **刻意不搬进本对象**）：
 * <ul>
 *   <li>{@code isLiked} —— 我点过赞吗（like 域的**内容赞**；评论赞不在本域范围内）；</li>
 *   <li>{@code isFavorited} —— 我收藏过吗（favorite 域）；</li>
 *   <li>{@code isFollowed} —— 我关注了这条内容的**作者**吗（follow 域）；</li>
 *   <li>{@code coin} —— 我在这条内容上**投了几枚**（coin 域；{@code 0/1/2}，见下）。</li>
 * </ul>
 *
 * <h2>★ 前三维的三态：{@code true} / {@code false} / {@code null}（R-13）</h2>
 * 前三维都是 {@code Boolean}（不是 {@code boolean}）：
 * <ul>
 *   <li>{@code true} / {@code false} —— 查了、且有确定答案；</li>
 *   <li>{@code null} —— **本面不查该维**（调用方没把它放进
 *       {@link InteractionDimension} 集合）或**该内容类型不适用该维**。
 *       两种含义客户端语义相同：不显示该维度的状态 ⇒ 共用 {@code null}，不另造表示。</li>
 * </ul>
 * ⚠️ 键集**恒四维**：不用"缺键"表达"不查"，故本类**不加** {@code @JsonInclude(NON_NULL)}
 * （本仓 {@code ProfileVO.isFollowed} 就是"{@code Boolean} 三态 + **显式输出 {@code null}**"
 * 的既有惯例；且详情契约测试断言**子对象键集恰好四维**）。
 *
 * <h2>★ 第四维 {@code coin}：是**数量**，不是布尔（T40）</h2>
 * {@code coin} 是 {@code Integer}：{@code 0/1/2} = 我投了几枚；{@code null} =
 * **该维不适用**（图文帖）或**本面不查**（列表各面）—— 与前三态的 {@code null} 同义，
 * 共用同一表示，**不另造**（不用 {@code -1} / 省略键之类）。
 * <p>★ 它与另外三维**刻意不同形**，两处理由：
 * <ol>
 *   <li><b>端点位置不同</b>：like / favorite 的"我的状态"有独立 status 端点；
 *       投币**没有**独立状态端点，"我投了几枚"只在**详情内嵌**（{@code coin.md} §三 / §七）。</li>
 *   <li><b>载荷类型不同</b>：前三维答"有没有"（布尔），{@code coin} 答"有几枚"（数量）。
 *       因此 {@code 0} 与 {@code null} 必须分得开：{@code 0} = 视频且没投过，{@code null} = 不适用 / 不查。</li>
 * </ol>
 *
 * <h2>★ 匿名语义：本对象的"缺席"也是契约</h2>
 * 无有效登录态时**没有本对象** —— 互动域返回 {@code null}（聚合到响应里就是
 * {@code interaction: null}），而**不是** 四个 {@code false}、也**不是**"四个 {@code null}"
 * 的空壳对象。这是它相对"顶层平铺字段"的一处**表达力增益**：匿名与"登录但未点赞"由此可区分
 * （R-10 §六）。
 * ⚠️ 别为了"调用方少判空"改成四个 false —— 那正是这套结构要治的表达力损失。
 * ⚠️ 也别用"空壳对象"表达"0 维"以外的任何缺席 —— {@code null} 对象与"对象在但四维全
 * {@code null}"是**两个不同**的意思（匿名 vs 本面一个维度都不查，见 {@link InteractionDimension}
 * 的"空集"节）。
 *
 * <h2>⚠️ 手写 getter 的理由</h2>
 * 字段叫 {@code isLiked}，Lombok 对 {@code boolean} 会生成 {@code isLiked()} ⇒ Jackson
 * 推导出的属性名是 {@code liked}，与契约名 {@code isLiked} 不符（改用 {@code Boolean} 后
 * Lombok 生成 {@code getIsLiked()} 其实是对的，但与 {@code ContentDetailVO} /
 * {@code ProfileVO} 的"显式钉死"口径不一致）⇒ 这里仍显式写 {@code getIsLiked()} /
 * {@code setIsLiked()}。{@code coin} 同为显式 getter，保持本类"全部手写"的一致性。
 *
 * <p>对外 JSON 键集 = {@code {isLiked, isFavorited, isFollowed, coin}}（T10 之后内嵌为内容详情
 * 响应的 {@code interaction} 子对象；T13 起**列表** {@code ContentVO} 同形，见 §六；
 * T40 起多出 {@code coin}，列表各面该键**恒 {@code null}** 是契约而非漏填）。
 */
public class InteractionStatusVO {

    private Boolean isLiked;
    private Boolean isFavorited;
    private Boolean isFollowed;

    /**
     * 我在这条内容上投了几枚：{@code 0/1/2}；{@code null} = 该维不适用（图文帖）或本面不查（列表）。
     *
     * <p>★ {@code Integer} 而非 {@code int}：{@code null} 是"不适用 / 不查"的**唯一**表示，
     * 与前三维共用同一语义，见类注释"第四维"节。
     */
    private Integer coin;

    public InteractionStatusVO() {
    }

    public Boolean getIsLiked() {
        return isLiked;
    }

    public void setIsLiked(Boolean isLiked) {
        this.isLiked = isLiked;
    }

    public Boolean getIsFavorited() {
        return isFavorited;
    }

    public void setIsFavorited(Boolean isFavorited) {
        this.isFavorited = isFavorited;
    }

    public Boolean getIsFollowed() {
        return isFollowed;
    }

    public void setIsFollowed(Boolean isFollowed) {
        this.isFollowed = isFollowed;
    }

    public Integer getCoin() {
        return coin;
    }

    public void setCoin(Integer coin) {
        this.coin = coin;
    }
}
