package io.github.yjhhhaaa06.videoweb.history.event;

/**
 * 播放量 +1（本域自己的事实声明）。
 *
 * <p>发布点 = {@code HistoryService#play} 的首播分支（**事务内**）；消费点在
 * {@code content.event.PlayCountChangedListener}（{@code AFTER_COMMIT} ⇒ 条件 {@code INCRBY 1}
 * 维护 {@code content:playCount:{id}}）。
 *
 * <h2>★ 为什么"声明事实"而不是直接调内容域的缓存</h2>
 * 规则 3 / 规则 2：{@code history} 不得触碰 {@code content.cache} 的实现。它只声明
 * "这条内容的播放量变了"，怎么处理是内容域自己的事（同 {@code ContentPublishedEvent} 的形态）。
 * 该边不产生 {@code content → history} 的反向依赖 ⇒ 无装配期环、不需要 {@code @Lazy}
 * （{@code 模块边界与依赖.md} §六）。
 *
 * <h2>分批交付说明</h2>
 * 本事件在 **T47（写路径）** 就发布，而消费它的监听器属于**下一批**（播放量读面：
 * {@code content:playCount} 键 + 5 键聚合 + 详情 VO）。因此在本批里它是**零订阅方**的事件
 * —— 这不是死代码：写路径的口径（"首播成功 ⇒ 声明一次"）在这一批就定终态，
 * 下一批只加消费方，**不改写路径**。事件丢失的代价见 {@code history.md} §十 #2。
 *
 * @param contentId 播放量发生变化的内容 id
 */
public record PlayCountChangedEvent(long contentId) {
}
