package io.github.yjhhhaaa06.videoweb.content.event;

/**
 * 内容缓存的**提交后**变更事件（决策表 G-3，模式 A 的第 3 次落地）。
 *
 * <h2>为什么是"意图"而不是"动作"</h2>
 * 事件只声明"内容 key 现在处于什么状态"，由 {@link ContentCacheChangedListener} 决定怎么改缓存：
 * <ul>
 *   <li>{@link Op#INVALIDATE} —— 失效（DEL）。用于**只变了计数字段**的场景：
 *       点赞数 / 评论数 / 评论区开关。读自愈会重新回源 DB 回填最新值。</li>
 *   <li>{@link Op#REFRESH} —— 重载并回写。用于**内容本身变了**的场景：标题/简介、媒体换源。</li>
 *   <li>{@link Op#REMOVE} —— 剔除（DEL + 从全部索引里摘掉）。用于软删 / 下架。</li>
 * </ul>
 * 分成三种而不是统一"一律 DEL"：{@code REFRESH} 与 {@code REMOVE} 的差别是**索引**——
 * 内容被删后若只 DEL 内容 key，它仍留在 {@code content:index:*} 里，
 * 首页推荐会反复探测到一个永远 null 的 id（TV 的三期 T4/N4 记录过）。
 *
 * <h2>为什么不用"缓存失效"以外的表达</h2>
 * 刻意**不**把"要失效哪个 key"写进事件：key 形状是缓存层的实现细节，
 * 事件只带业务身份（contentId）。这样将来 key 改名不影响事件契约。
 *
 * <h2>发布纪律（模式 A）</h2>
 * 事件必须在 {@code @Transactional} 方法**内**发布；监听器是
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} ⇒ 事务回滚则**完全不触发**。
 * "缓存写在事务内 / 事务后"这个曾经靠在 `transactionTemplate.execute(...)` 后面按行序写、
 * 靠注释维系的东西，从此是框架保证（TV 的踩坑清单 §5）。
 *
 * @param contentId 内容 id（业务身份，不含 key 形状）
 * @param op        期望的缓存变更意图
 */
public record ContentCacheChangedEvent(long contentId, Op op) {

    /** 缓存变更意图。 */
    public enum Op {
        /** 失效内容 key（读自愈回填 DB 最新值）：点赞数 / 评论数 / 评论区开关变化。 */
        INVALIDATE,
        /** 重载并回写内容 key + 刷新索引：标题/简介或媒体变了。 */
        REFRESH,
        /** 剔除内容 key 与全部索引：内容被软删（或下架）。 */
        REMOVE
    }

    public static ContentCacheChangedEvent invalidate(long contentId) {
        return new ContentCacheChangedEvent(contentId, Op.INVALIDATE);
    }

    public static ContentCacheChangedEvent refresh(long contentId) {
        return new ContentCacheChangedEvent(contentId, Op.REFRESH);
    }

    public static ContentCacheChangedEvent remove(long contentId) {
        return new ContentCacheChangedEvent(contentId, Op.REMOVE);
    }
}
