package io.github.yjhhhaaa06.videoweb.comment.event;

/**
 * 评论缓存的**提交后**变更事件（决策表 G-3）。
 *
 * <h2>为什么与 {@code ContentCacheChangedEvent} 分成两个事件</h2>
 * 它们属于**不同的缓存**（内容 key 是"一条内容一行 JSON"，评论是"两键组 LIST/HASH"），
 * 失效粒度也不同：评论要区分"失效主楼序列（roots+count）"与"只失效某主楼的楼中楼 field"，
 * 而内容只有"失效/重载/剔除"三态。硬塞进一个事件枚举会让两个类都要理解对方的语义。
 *
 * <p>分成两个事件的代价是：一次"发评论"会发**两个**事件（内容失效 + 评论失效）——
 * 这是诚实的表达，因为确实改了两处缓存（计数在内容行上、树在评论键上）。
 *
 * <h2>三种意图（对应 TV 的三行失效，逐条可对照）</h2>
 * <table>
 *   <caption>TV 的失效 → 本事件</caption>
 *   <tr><th>TV</th><th>本事件</th><th>触发点</th></tr>
 *   <tr><td>{@code commentCache.invalidateRoots(contentId)}</td>
 *       <td>{@link Op#INVALIDATE_ROOTS}</td><td>增主楼 / 删主楼</td></tr>
 *   <tr><td>{@code commentCache.invalidateReplyUnder(contentId, rootId)}</td>
 *       <td>{@link Op#INVALIDATE_REPLY_UNDER}</td><td>增回复 / 删回复</td></tr>
 *   <tr><td>{@code commentCache.invalidateComments(contentId)}</td>
 *       <td>{@link Op#INVALIDATE_COMMENTS}</td><td>内容被删/下架的级联</td></tr>
 * </table>
 *
 * <p>评论**点赞**的失效（{@code notifyCommentLikeChanged}）不走本事件——它由
 * {@code LikeChangedListener}（S3 已有的 AFTER_COMMIT 监听器）直接调用，
 * 见 L-6 的补回说明：那里本就要求"在本监听器内一并补上这两次失效"，
 * 再套一层事件只会多一跳。
 *
 * @param contentId 内容 id
 * @param rootId    主楼 id（仅 {@link Op#INVALIDATE_REPLY_UNDER} 有意义；其余为 {@code null}）
 * @param op        失效意图
 */
public record CommentCacheChangedEvent(long contentId, Long rootId, Op op) {

    /** 失效意图。 */
    public enum Op {
        /** 失效主楼序列 + 计数（DEL roots + count 及其空标记）：增/删主楼。 */
        INVALIDATE_ROOTS,
        /** 定向失效某主楼的楼中楼 field（HDEL replies field）：增/删回复。 */
        INVALIDATE_REPLY_UNDER,
        /** 整组失效（DEL roots + replies + count）：内容被删/下架时级联。 */
        INVALIDATE_COMMENTS
    }

    /** 增/删**主楼**后：失效 roots + count（读懒重建窗口）。 */
    public static CommentCacheChangedEvent roots(long contentId) {
        return new CommentCacheChangedEvent(contentId, null, Op.INVALIDATE_ROOTS);
    }

    /** 增/删**回复**后：定向 HDEL 该主楼的 replies field（懒载刷新）。 */
    public static CommentCacheChangedEvent replyUnder(long contentId, Long rootId) {
        return new CommentCacheChangedEvent(contentId, rootId, Op.INVALIDATE_REPLY_UNDER);
    }

    /** 内容被删/下架：整组失效。 */
    public static CommentCacheChangedEvent comments(long contentId) {
        return new CommentCacheChangedEvent(contentId, null, Op.INVALIDATE_COMMENTS);
    }
}
