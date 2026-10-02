package io.github.yjhhhaaa06.videoweb.follow.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 关注 / 粉丝列表的条目（{@code /follow/following} 与 {@code /follow/followers} 共用同一形状）。
 *
 * <p>承接 TV {@code FollowService.buildUserViews} 手搭的 {@code Map<String,Object>}
 * （键集合与取值口径逐字对齐：{@code userId / username / isFollowed / isSelf}），
 * 只是把"靠字符串键约定形状"换成编译期可校验的具名类型——与 S1 的 {@code CouponVO} 同款处置。
 *
 * <h2>字段口径（TV 原样）</h2>
 * <ul>
 *   <li>{@code userId} / {@code username} —— 条目本人（条目来自 {@code users} 表）；</li>
 *   <li>{@code isFollowed} —— **当前登录用户**是否关注了该条目本人
 *       （注意不是"条目本人关注了谁"）；</li>
 *   <li>{@code isSelf} —— 该条目本人是否就是当前登录用户。</li>
 * </ul>
 * 三项都由 {@code FollowService.buildUserViews} 组装；{@code isFollowed} 依赖
 * {@code FollowCache.batchIsFollowing}（一趟 pipeline 三态读，Redis 挂则降级 DB）。
 *
 * <h2>⚠️ 为什么组件名带 {@code is} 前缀**并且**显式标 {@code @JsonProperty}</h2>
 * {@code isFollowed} 这种命名在 Java 里自然（读作"是否已关注"），但对依赖"getter 命名法"的
 * JSON 库是**双刃剑**：按 JavaBean 约定，{@code isXxx()} 会被推导成属性名 {@code xxx}，
 * 于是字段会静默变成 {@code followed} —— 而旧 pytest 对键名有逐字断言，前端也按
 * {@code isFollowed} 消费。这里把 JSON 名**显式钉死**，消灭"靠命名法推导"的不确定性。
 */
public record FollowUserVO(
        long userId,
        String username,
        @JsonProperty("isFollowed") boolean isFollowed,
        @JsonProperty("isSelf") boolean isSelf) {
}
