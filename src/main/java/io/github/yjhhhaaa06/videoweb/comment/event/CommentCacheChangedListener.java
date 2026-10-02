package io.github.yjhhhaaa06.videoweb.comment.event;

import io.github.yjhhhaaa06.videoweb.comment.cache.CommentCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 评论缓存的**提交后**处理器（决策表 G-3）。
 *
 * <h2>★ {@code AFTER_COMMIT} 的语义与存在证明</h2>
 * 事件在事务内发布，监听器**只在事务成功提交后**才被调用；事务回滚 ⇒ **完全不调用**。
 * 配套测试见 {@code CommentReadTests.事务回滚后评论缓存未被污染}：
 * 让 {@code addComment} 事务内的计数更新抛异常 ⇒ 回滚 ⇒ 断言评论两键组保持预热原值 /
 * 根本不存在。**改回"写在事务内"或"方法体末尾"即失败。**
 *
 * <h2>为什么这条链路特别值得用框架保证</h2>
 * TV 在这里踩过坑（其注释里的 T34/U-22）：原来的失效写在事务回调**内**，
 * 提交前就删了缓存 ⇒ 并发读者在窗口内按**旧计数**回填 ⇒ 表现为"评论后计数短暂陈旧"。
 * 修法是把失效移到事务之后——但那只是"写在 `transactionTemplate.execute(...)` 的下一行"，
 * 靠行序 + 注释维系。本实现把它变成类型保证。
 */
@Slf4j
@Component
public class CommentCacheChangedListener {

    private final CommentCache commentCache;

    public CommentCacheChangedListener(CommentCache commentCache) {
        this.commentCache = commentCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommentCacheChanged(CommentCacheChangedEvent event) {
        switch (event.op()) {
            case INVALIDATE_ROOTS -> commentCache.invalidateRoots(event.contentId());
            case INVALIDATE_REPLY_UNDER ->
                    commentCache.invalidateReplyUnder(event.contentId(), event.rootId());
            case INVALIDATE_COMMENTS -> commentCache.invalidateComments(event.contentId());
        }
    }
}
