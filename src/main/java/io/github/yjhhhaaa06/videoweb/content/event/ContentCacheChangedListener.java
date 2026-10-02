package io.github.yjhhhaaa06.videoweb.content.event;

import io.github.yjhhhaaa06.videoweb.content.cache.ContentCache;
import io.github.yjhhhaaa06.videoweb.user.event.UserRenamedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 内容缓存的**提交后**处理器（决策表 G-3）。
 *
 * <h2>★ {@code AFTER_COMMIT} 的语义与存在证明</h2>
 * 事件在事务内发布，监听器**只在事务成功提交后**才被调用；事务回滚 ⇒ **完全不调用**。
 * 配套测试（{@code ContentCacheTests.事务回滚后缓存未被污染}）：
 * 让事务内某一步抛 {@code DataAccessException} ⇒ 回滚 ⇒ 断言 Redis 里相关 key 保持预热原值 /
 * 根本不存在。**改回"写在事务方法体末尾"或"事务内更新"即失败**——这是该机制的存在证明。
 *
 * <h2>两处刻意的实现选择</h2>
 * <ol>
 *   <li><b>不用 {@code @Async}</b>：TV 的副作用就在提交线程同步执行，语义一致；
 *       异步会引入"提交后缓存短暂未更新"的新窗口，且让测试需要等待。</li>
 *   <li><b>{@code fallbackExecution} 保持默认 false</b>：本项目的写操作**一律**在
 *       {@code @Transactional} 方法内发布事件。若将来出现"无事务时也要失效缓存"的场景，
 *       必须显式打开并在此说明理由（而不是默默打开）。</li>
 * </ol>
 *
 * <h2>本监听器还承接 TV 的一处跨域级联</h2>
 * {@code UserService.changeUserName} 改名后必须失效该作者的**全部内容 key**
 * （否则内容详情/主页里的 {@code authorName} 会一直显示旧名，直到 TTL 到期）。
 * TV 在 {@code UserService} 里直接调 {@code contentCache.invalidateAuthorContentKeys(userId)}。
 * 本实现改为**发事件**（{@code UserRenamedEvent}）+ 在这里处理 ——
 * user 域不必知道 content 域有缓存（依赖方向从"user → content"变成"两边都只依赖事件"）。
 * 旧 pytest {@code test_change_user_name.py} 对这条级联有逐字断言，已固化为本仓测试。
 */
@Slf4j
@Component
public class ContentCacheChangedListener {

    private final ContentCache contentCache;

    public ContentCacheChangedListener(ContentCache contentCache) {
        this.contentCache = contentCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentCacheChanged(ContentCacheChangedEvent event) {
        switch (event.op()) {
            case INVALIDATE -> contentCache.invalidateContent(event.contentId());
            case REFRESH -> contentCache.refreshContent(event.contentId());
            case REMOVE -> contentCache.removeContent(event.contentId());
        }
    }

    /**
     * ⚠️ <b>S6-B2c</b>：{@code REMOVE} 分支原先还调了 {@code likeService.deleteContentLike(contentId)}
     * 来失效点赞计数缓存——本监听器因此依赖了 {@code like} 域（又一个跨域缓存耦合）。
     * 现已交给**点赞域自己订阅本事件**：{@code like.event.ContentRemovedLikeListener}。
     *
     * <p>依赖方向由"content → like.service"变成"like → content.event"。
     * 两者其实**都已存在**（{@code LikeService} 本就依赖 {@code content.dao} 做存在性检查与计数更新），
     * 故没有新增环；变的是"谁订阅谁"。
     */

    /**
     * ⚠️ 一个**不能做**的事（写在这里防止后来者"顺手重构"）：
     * 不要在本方法里 {@code publishEvent(...)} 来"转发"别的域的失效。
     * AFTER_COMMIT 回调执行时**事务已经结束**，此时发布的事件不会再被
     * {@code @TransactionalEventListener(AFTER_COMMIT)}（{@code fallbackExecution} 默认 false）接收
     * ——转发会被**静默丢弃**。
     *
     * <p><b>S6-B2d 起的正确做法是"让需要的人自己订阅"</b>，而不是由本域转发：
     * {@code ContentService.deleteContent} 在**事务内**发一次
     * {@code ContentCacheChangedEvent.remove(contentId)}，然后
     * <ul>
     *   <li>{@code comment.event.ContentRemovedCommentListener} —— 整组失效评论两键组；</li>
     *   <li>{@code like.event.ContentRemovedLikeListener} —— 失效点赞计数 key。</li>
     * </ul>
     * 两个订阅方都在**事务内发布的那个事件**上被 AFTER_COMMIT 触发，因此不会遇到"静默丢弃"；
     * 内容域也不必知道谁有缓存。
     */

    /**
     * 用户改名 → 失效该作者的全部内容 key（读自愈重新 JOIN users 回填新 authorName）。
     *
     * <p>⚠️ 注意：这里**不能**用 {@code contentCache.removeContent}——改名不是删除，
     * 索引 {@code content:index:*} 只存 id 不含 authorName，无需也不应被剔除。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRenamed(UserRenamedEvent event) {
        log.debug("用户改名事务已提交，失效该作者内容缓存: userId={}", event.userId());
        contentCache.invalidateAuthorContentKeys(event.userId());
    }
}
