package io.github.yjhhhaaa06.videoweb.feed.model.message;

/**
 * 收件箱重建指令（承接 TV {@code follow.model.dto.InboxRebuildMessage}）：{@code userId} 的收件箱需要
 * 按当前关注关系**重算窗口**。
 *
 * <p><b>投递点</b>：关注 / 取关**事务提交后**——{@code follow} 域发 {@code FollowChangedEvent}，
 * {@code feed/event/FollowChangedFeedListener} 订阅后投递（不新增 {@code follow → feed} 反向依赖）。
 *
 * <p><b>载荷只放 userId</b>（不带内容快照、不带关注关系数据）：收件箱是**派生产物**，
 * 内容由接收侧按 DB 真相重算（每关注作者最近 K → 归并裁剪 C），故载荷不含任何不可重算状态——
 * 这是"收件箱可由 DB 重算"这一性质在消息契约上的体现。
 *
 * <p><b>为什么落 feed 域</b>：S9 起 follow 域**只声明</b> {@code FollowChangedEvent}，不引用本消息；
 * 由 feed 域订阅并投递 ⇒ 本消息归 feed 所有（与 TV"放 follow 域以避免 follow→feed 环"的目标一致，
 * 只是载体从"消息 DTO 放 follow"换成"事件由 follow 发、feed 订阅"，更贴合 A-3/A-4 范式）。
 */
public record InboxRebuildMessage(long userId) {
}
