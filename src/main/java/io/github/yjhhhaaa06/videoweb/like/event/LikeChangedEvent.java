package io.github.yjhhhaaa06.videoweb.like.event;

/**
 * 点赞状态变更事件 —— 用于把"缓存更新"从**注释纪律**变成**类型保证**。
 *
 * <h2>为什么需要它（决策表 §三 模式 A 在 S3 的落地，见 L-6）</h2>
 * TV 的写法是把副作用紧跟在事务 lambda **之后**：
 *
 * <pre>
 * transactionTemplate.execute(conn -&gt; { ...写库... });
 * // 缓存更新放在事务提交后      ← 靠这行注释维系
 * cache.likeContent(userId, contentId);
 * contentCache.notifyLikeCountChanged(contentId);
 * </pre>
 *
 * 这有两个脆弱点：写错位置（挪进 lambda）就是"事务回滚但缓存已改"；
 * 而"是否在提交后"只由代码行序 + 注释表达，评审之外没有任何机制兜住
 * （《迁移参照系》§2.4 已定性为【妥协】）。
 *
 * <p>改为事件后，时序由框架保证：监听器标 {@code @TransactionalEventListener(AFTER_COMMIT)}
 * ⇒ **事务没提交就绝不会被调用**。发布方只管"我改了点赞状态"，不再操心时机。
 *
 * @param userId   点赞的人
 * @param targetId 被点赞的内容 id 或评论 id（由 {@link Target} 区分）
 * @param target   目标类型
 * @param liked    {@code true}=点赞 / {@code false}=取消点赞
 */
public record LikeChangedEvent(long userId, long targetId, Target target, boolean liked) {

    /** 目标类型。 */
    public enum Target {
        CONTENT,
        COMMENT
    }

    public static LikeChangedEvent content(long userId, long contentId, boolean liked) {
        return new LikeChangedEvent(userId, contentId, Target.CONTENT, liked);
    }

    public static LikeChangedEvent comment(long userId, long commentId, boolean liked) {
        return new LikeChangedEvent(userId, commentId, Target.COMMENT, liked);
    }
}
