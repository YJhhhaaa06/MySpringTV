package io.github.yjhhhaaa06.videoweb.follow.event;

import io.github.yjhhhaaa06.videoweb.follow.cache.FollowCache;
import io.github.yjhhhaaa06.videoweb.user.service.AutoBigVStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 关注关系变更的**提交后**处理器：把缓存同步进 Redis，并记录里程碑 / 状态迁移日志。
 *
 * <h2>★ {@code AFTER_COMMIT} 是本切片的核心机制（决策表 F-4）</h2>
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 的语义：
 * 事件在事务内发布，但监听器**只在事务成功提交后**才被调用；事务回滚 ⇒ **完全不调用**。
 * 于是"回滚后缓存被污染"这个旧实现靠注释纪律规避的隐患，被变成框架层面不可能发生的事。
 *
 * <h3>配套测试（关键，勿删）</h3>
 * {@code FollowCacheTests.事务回滚后缓存未被污染}：让事务内的计数更新抛异常 ⇒ 回滚 ⇒
 * 断言 Redis 里那两个 key **根本不存在**。这是该机制的存在证明——
 * 若有人把缓存更新改回"写在事务方法体末尾"或"事务内更新"，此用例立刻失败。
 * 只会写"正常关注后缓存被更新"的测试**不足以**证明时序正确（那种测试在两种实现下都会过）。
 *
 * <h3>执行顺序（与 TV 的行序对齐）</h3>
 * <ol>
 *   <li><b>缓存双写</b>——TV 把"里程碑日志"放在"缓存双写"**之前**；这里按 TV 行序：
 *       里程碑 → 状态迁移 → 缓存。三者都在提交后，顺序对可观察行为无影响
 *       （缓存双写本身不抛异常，失败只降级），故保持 TV 行序以便对照。</li>
 *   <li><b>不用 {@code @Async}</b>：TV 的副作用就在提交线程同步执行，语义一致；
 *       异步会引入"提交后缓存短暂未更新"的新窗口，且让测试需要等待，属无谓复杂化。</li>
 *   <li><b>{@code fallbackExecution} 保持默认 false</b>：本项目的关注写操作**一律**在
 *       {@code @Transactional} 方法内发布事件，没有"无事务时也要更新缓存"的场景；
 *       若将来出现，必须显式打开并在此说明理由（而不是默默打开）。</li>
 * </ol>
 *
 * <h2>✅ S9 闭合：原两处 feed 投递裁剪已兑现（不再由本类承担）</h2>
 * S4 时本类曾留两处 {@code TODO(feed 切片)}（{@code inboxRebuildNotifier} / {@code authorBackfillNotifier}），
 * 并在类注释里写明"补回位置"。**S9 已兑现，但落点不在本类**——而是新增
 * {@code feed.event.FollowChangedFeedListener}（feed 域的 {@code event} 包）订阅
 * {@link FollowChangedEvent} 后投递。理由：跨域事件只能由自己的 {@code event} 包消费（ArchUnit 规则 3），
 * 且这样 **follow 域不必依赖 feed**（本域只声明"关注关系变了"这个事实）。
 * 本类的职责收敛为：里程碑日志 → 状态迁移日志 → 缓存双写（三者都在提交后）。
 */
@Slf4j
@Component
public class FollowChangedListener {

    private final FollowCache followCache;

    public FollowChangedListener(FollowCache followCache) {
        this.followCache = followCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFollowChanged(FollowChangedEvent event) {
        // ① 里程碑（T9）：关系状态迁移——DB 已提交即记
        if (event.follow()) {
            log.info("关注成功, userId={}, followedUserId={}", event.userId(), event.followedUserId());
        } else {
            log.info("取关成功, userId={}, followedUserId={}", event.userId(), event.followedUserId());
        }

        // ② 自动大V状态迁移的提交后副作用（记日志）。
        //    NONE 是绝大多数请求（带内 / 状态本就一致）⇒ 不记，保持 INFO 稀疏（TV 原注释口径）。
        AutoBigVStateService.Transition transition = event.transition();
        if (transition != AutoBigVStateService.Transition.NONE) {
            log.info("自动大V状态迁移, followedUserId={}, transition={}", event.followedUserId(), transition);
            // S9 已兑现（原 TODO(feed 切片)）：transition == DOWNGRADED 的降级补推投递，
            // 由 **feed 自己的订阅方** 承接 —— 见 feed.event.FollowChangedFeedListener。
            // 本域不必依赖 feed（它只声明"关注关系变了"这个事实）。
        }

        // ③ DB 提交后缓存双写（NEEDS 4.10：条件双写 + 失败失效自愈，不影响主流程）
        if (event.follow()) {
            followCache.cacheFollow(event.userId(), event.followedUserId());
        } else {
            followCache.cacheUnfollow(event.userId(), event.followedUserId());
        }

        // S9 已兑现（原 TODO(feed 切片)）：收件箱重建投递，同样由 feed.event.FollowChangedFeedListener 承接。
    }
}
