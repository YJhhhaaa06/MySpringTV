package io.github.yjhhhaaa06.videoweb.feed.event;

import io.github.yjhhhaaa06.videoweb.feed.mq.AuthorBackfillNotifier;
import io.github.yjhhhaaa06.videoweb.feed.mq.InboxRebuildNotifier;
import io.github.yjhhhaaa06.videoweb.follow.event.FollowChangedEvent;
import io.github.yjhhhaaa06.videoweb.user.service.AutoBigVStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 「关注关系变更」→ 收件箱重建 / 降级补推（S9）：订阅 follow 域的 {@link FollowChangedEvent}，
 * 在**提交后**投递两条 feed 消息。**兑现 S4 有意裁剪的两处 {@code TODO(feed 切片)}**（台账 A4 闭合）。
 *
 * <h2>★ A4 的由来：这不是"状态没维护"，而是"信号没有订阅者"</h2>
 * S4 迁移关注域时，{@code FollowChangedListener} 里留了两处 {@code TODO(feed 切片)}：
 * <ul>
 *   <li>{@code inboxRebuildNotifier.publishInboxRebuild(userId)}——关注改变"我关注的博主集合"，
 *       我自己的收件箱快照失效 ⇒ 投重建消息；</li>
 *   <li>{@code authorBackfillNotifier.publishAuthorBackfill(followedUserId)}（**仅 {@code DOWNGRADED} 时**）
 *       ——作者脱离大V ⇒ 把其存量内容补进现任粉丝收件箱。</li>
 * </ul>
 * S4 当年裁它们的理由是"依赖 mq 包、feed 域本批不做"，并明确写了**补回位置**。
 * 本次兑现：**把订阅行为放进 feed 自己的 {@code event} 包**（而非塞回 {@code FollowChangedListener}）——
 * 于是 follow 域**不必知道** feed 存在（它只声明 {@code FollowChangedEvent}），
 * 依赖方向仍是 follow → （事件）← feed，零包环。
 *
 * <h2>为什么 {@code DOWNGRADED} 才补推</h2>
 * edge（{@code affected == 1}）天然唯一（并发"恰一次"由 {@code AutoBigVStateService} 的行锁 +
 * {@code affected rows} 保证），故**无需去抖 / 补偿**。且只在降级沿触发——升级沿不需要（大V内容本就进发件箱）。
 *
 * <h2>去抖落差</h2>
 * 重建投递经 {@link InboxRebuildNotifier} 的**去抖器**（per-user 尾沿合并）；补推**不去抖**
 * （edge 天然稀疏）。两条消息都"绝不抛"（失败只降级），不影响关注 / 取关接口。
 */
@Slf4j
@Component
public class FollowChangedFeedListener {

    private final InboxRebuildNotifier inboxRebuildNotifier;
    private final AuthorBackfillNotifier authorBackfillNotifier;

    public FollowChangedFeedListener(InboxRebuildNotifier inboxRebuildNotifier,
                                     AuthorBackfillNotifier authorBackfillNotifier) {
        this.inboxRebuildNotifier = inboxRebuildNotifier;
        this.authorBackfillNotifier = authorBackfillNotifier;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFollowChanged(FollowChangedEvent event) {
        // ① 降级补推（仅 edge）：作者脱离大V ⇒ 补其存量内容进现任粉丝收件箱
        if (event.transition() == AutoBigVStateService.Transition.DOWNGRADED) {
            authorBackfillNotifier.publishAuthorBackfill(event.followedUserId());
        }
        // ② 收件箱重建：关注 / 取关都令**操作者本人**的收件箱快照失效（去抖合并）
        inboxRebuildNotifier.publishInboxRebuild(event.userId());
    }
}
