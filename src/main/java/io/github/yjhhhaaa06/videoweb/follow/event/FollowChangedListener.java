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
 * <h2>⚠️ 两处裁剪（补回位置；对应《切片计划》§四 要求记录的裁剪风险）</h2>
 * TV 在这里还有两个**下游投递**，本批不搬（它们依赖 {@code mq} 包，且 feed 域本批明确不做）：
 * <ul>
 *   <li>{@code inboxRebuildNotifier.publishInboxRebuild(userId)} —— 关注改变了"我关注的博主集合"，
 *       我自己的收件箱快照失效 ⇒ 投递重建消息。
 *       <b>影响</b>：关注关系变化**不会触达 feed 重建**。当前无 feed ⇒ 无可观察差异。
 *       <b>补回位置</b>：feed 切片时在本监听器内补这一行（{@code userId} 即 {@code event.userId()}）。</li>
 *   <li>{@code authorBackfillNotifier.publishAuthorBackfill(followedUserId)}（**仅 {@code DOWNGRADED} 时**）
 *       —— 作者脱离大V ⇒ 把其存量内容补进现任粉丝收件箱。
 *       <b>影响</b>：降级 edge 不触发补推。注意 {@code auto_bigv} 状态行仍被**正确删除**
 *       （F-6 保留了 {@code evaluate}），只是没人消费该信号——所以这不是"状态没维护"，
 *       而是"信号没有订阅者"。
 *       <b>补回位置</b>：同上。</li>
 * </ul>
 * 与 S2/CM-3、S3/L-6 的裁剪**同性质同纪律**：不为尚不存在的下游预埋投递逻辑。
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

        // ② 自动大V状态迁移的提交后副作用（记日志；降级补推见类注释的裁剪说明）。
        //    NONE 是绝大多数请求（带内 / 状态本就一致）⇒ 不记，保持 INFO 稀疏（TV 原注释口径）。
        AutoBigVStateService.Transition transition = event.transition();
        if (transition != AutoBigVStateService.Transition.NONE) {
            log.info("自动大V状态迁移, followedUserId={}, transition={}", event.followedUserId(), transition);
            // TODO(feed 切片)：transition == DOWNGRADED 时补 authorBackfillNotifier.publishAuthorBackfill(followedUserId)
        }

        // ③ DB 提交后缓存双写（NEEDS 4.10：条件双写 + 失败失效自愈，不影响主流程）
        if (event.follow()) {
            followCache.cacheFollow(event.userId(), event.followedUserId());
        } else {
            followCache.cacheUnfollow(event.userId(), event.followedUserId());
        }

        // TODO(feed 切片)：补 inboxRebuildNotifier.publishInboxRebuild(event.userId())
    }
}
