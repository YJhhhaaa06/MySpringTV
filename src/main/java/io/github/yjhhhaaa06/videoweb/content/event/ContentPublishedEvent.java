package io.github.yjhhhaaa06.videoweb.content.event;

/**
 * 「内容已发布」**提交后**事件（S9 新增，兑现 S7 遗留的 {@code TODO(feed 切片)}，台账 A3 闭合）。
 *
 * <h2>为什么是事件而不是"content 直接调 feed 的 notifier"</h2>
 * TV 在 {@code ContentService.addVideo/addPost} 的事务后**直接**调
 * {@code feedPushNotifier.publishContentPublished(contentId, userId)}——于是 content **编译期依赖 feed**
 * （而 feed 又依赖 content 的 DAO），构成 {@code content ⇄ feed} 双向环，正是 S6 收口要消的那类边。
 *
 * <p>本实现按 {@code A-3 / A-4} 的范式：**每个域只声明"自己发生了什么"，需要响应的人自己订阅**。
 * content 只发本事件；{@code feed/event/ContentPublishedFeedListener} 订阅后投 MQ。
 * 于是 **content 不再依赖 feed**，依赖方向在包结构上一眼可见。
 *
 * @param contentId 新内容 id（收件箱成员 / ZSet score）
 * @param authorId  作者 id（feed 侧据此窗口迭代取粉丝列表）
 */
public record ContentPublishedEvent(long contentId, long authorId) {
}
