package io.github.yjhhhaaa06.videoweb.content.event;

/**
 * 内容侧**里程碑事实**（第三批 T2 / 账 B12）。
 *
 * <h2>为什么要有这个事件，而不是在 ContentService 里直接 log</h2>
 * {@code addVideo} / {@code addPost} / {@code deleteContent} 都是 {@code @Transactional}，
 * 在方法体里打 "添加视频成功" 意味着**事务还没提交就宣告成功**——提交失败（或后续语句失败）
 * 会留下一条假成功行。本项目的既有口径是"提交后副作用走
 * {@code @TransactionalEventListener(AFTER_COMMIT)}"（S9 起成为纪律，follow 的里程碑就是那么写的），
 * 故这里沿用同一形态：事务内**只声明"发生了什么"**，由
 * {@link ContentMilestoneListener} 在提交后落日志。
 *
 * <p>与 {@link ContentPublishedEvent} 的分工：后者是**业务语义事件**（"内容已发布"，
 * feed 域订阅后投写扩散消息），本事件只承载"要记一条里程碑"这一观测事实。
 * 把两者合并会让 feed 的投递语义与日志需求纠缠在一起——分开后任何一方变化都不牵动另一方。
 *
 * @param contentId 受影响的内容 id
 * @param action    动作（决定日志文案；用枚举而不是字符串，防止文案在调用点各写一遍）
 */
public record ContentMilestoneEvent(long contentId, Action action) {

    /** 与 TV {@code test_milestone_log.py} 的 7 个补点里的三个内容点一一对应。 */
    public enum Action {
        VIDEO_ADDED,
        POST_ADDED,
        DELETED
    }
}
