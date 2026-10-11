package io.github.yjhhhaaa06.videoweb.interaction.model.vo;

/**
 * 当前用户对一条内容的**互动态**（R-10，详细设计见 {@code 收藏功能-分期与设计.md} §3.6）。
 *
 * <p>三个字段都是"**与请求者有关**"的状态（请求无关的内容属性 / 公共计数 —— 如
 * likeCount / commentCount / createTime —— **刻意不搬进本对象**）：
 * <ul>
 *   <li>{@code isLiked} —— 我点过赞吗（like 域的**内容赞**；评论赞不在本域范围内）；</li>
 *   <li>{@code isFavorited} —— 我收藏过吗（favorite 域）；</li>
 *   <li>{@code isFollowed} —— 我关注了这条内容的**作者**吗（follow 域）。</li>
 * </ul>
 *
 * <h2>★ 匿名语义：本对象的"缺席"也是契约</h2>
 * 无有效登录态时**没有本对象** —— 互动域返回 {@code null}（聚合到响应里就是
 * {@code interaction: null}），而**不是**三个 {@code false}。这是它相对"顶层平铺字段"的
 * 一处**表达力增益**：匿名与"登录但未点赞"由此可区分（R-10 §3.6）。
 * ⚠️ 别为了"调用方少判空"改成三个 false —— 那正是这套结构要治的表达力损失。
 *
 * <h2>⚠️ 手写 getter 的理由同 {@code ContentVO}</h2>
 * Lombok 对 {@code boolean isLiked} 生成 {@code isLiked()} ⇒ Jackson 推导出的属性名是
 * {@code liked}，与契约名 {@code isLiked} 不符。故这里显式写 {@code getIsLiked()} /
 * {@code setIsLiked()}，把对外 JSON 的名字钉死。
 *
 * <p>对外 JSON 键集 = {@code {isLiked, isFavorited, isFollowed}}（T10 之后内嵌为内容详情
 * 响应的 {@code interaction} 子对象；列表顶层键集不变，只换填充来源）。
 */
public class InteractionStatusVO {

    private boolean isLiked;
    private boolean isFavorited;
    private boolean isFollowed;

    public InteractionStatusVO() {
    }

    public boolean getIsLiked() {
        return isLiked;
    }

    public void setIsLiked(boolean isLiked) {
        this.isLiked = isLiked;
    }

    public boolean getIsFavorited() {
        return isFavorited;
    }

    public void setIsFavorited(boolean isFavorited) {
        this.isFavorited = isFavorited;
    }

    public boolean getIsFollowed() {
        return isFollowed;
    }

    public void setIsFollowed(boolean isFollowed) {
        this.isFollowed = isFollowed;
    }
}