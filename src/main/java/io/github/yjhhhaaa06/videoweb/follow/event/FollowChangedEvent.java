package io.github.yjhhhaaa06.videoweb.follow.event;

import io.github.yjhhhaaa06.videoweb.user.service.AutoBigVStateService;

/**
 * 关注关系变更事件 —— 把"提交后副作用"从**注释纪律**变成**类型保证**。
 *
 * <h2>为什么需要它（决策表 §三 模式 A 在 S4 的第 2 次落地，见 F-4）</h2>
 * TV 的写法是把副作用紧跟在事务之后，靠注释维系：
 *
 * <pre>
 * TransactionTemplate.execute(conn -&gt; { ...写关系 + 双方计数 + bigV 判定... });
 * LOGGER.log(INFO, "关注成功, ...");                        // 里程碑（T9）
 * handleBigVTransition(followedUserId, bigVTransition);    // 大V状态迁移的提交后副作用
 * followCache.cacheFollow(userId, followedUserId);         // DB 提交后缓存双写（NEEDS 4.10）
 * inboxRebuildNotifier.publishInboxRebuild(userId);        // 收件箱失效联动
 * </pre>
 *
 * 这有两个脆弱点：写错位置（挪进事务体）就是"事务回滚但缓存已改"；
 * 而"是否在提交后"只由代码行序 + 注释表达（《迁移参照系》§2.4 定性为【妥协】）。
 *
 * <p>事件化的收益在 S3 已实证（L-6），S4 是**第 2 个使用方**——
 * 它验证该模式不是"只对 like 成立的特例"：这里副作用多达 4 类（缓存双写、里程碑日志、
 * 状态迁移日志、MQ 投递），其中 MQ 一路被裁掉，剩下 3 类仍然只在**提交后**执行。
 *
 * <h2>为什么事件要携带 {@code transition}</h2>
 * 大V状态迁移是**事务内**算出来的（{@code AutoBigVStateService.evaluate} 与计数同生共死），
 * 但它的**对外动作**（记日志 / 降级补推）必须发生在**提交后**。
 * 事务内算、事件里带出来、监听器里消费——这是"跨提交边界的值传递"的正当形态，
 * 也避免了在监听器里重算（重算会读到已提交的新状态，得到的 edge 可能不同）。
 *
 * @param userId         关注者（操作人）
 * @param followedUserId 被关注者（关系变更的另一端）
 * @param follow         {@code true}=关注 / {@code false}=取关
 * @param transition     本次事务内得到的大V状态迁移结果（{@code NONE} = 无变化，绝大多数请求）
 */
public record FollowChangedEvent(long userId, long followedUserId, boolean follow,
                                 AutoBigVStateService.Transition transition) {

    public static FollowChangedEvent followed(long userId, long followedUserId,
                                              AutoBigVStateService.Transition transition) {
        return new FollowChangedEvent(userId, followedUserId, true, transition);
    }

    public static FollowChangedEvent unfollowed(long userId, long followedUserId,
                                                AutoBigVStateService.Transition transition) {
        return new FollowChangedEvent(userId, followedUserId, false, transition);
    }
}
