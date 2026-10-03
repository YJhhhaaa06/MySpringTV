package io.github.yjhhhaaa06.videoweb.feed.model.message;

/**
 * 写扩散消息载荷（承接 TV {@code feed.model.dto.FeedPushMessage}）：内容发布事件。
 *
 * <p>字段只放**接收侧真正需要的标识**——{@code contentId}（要写进收件箱的成员，同时充当 ZSet 的
 * score）与 {@code authorId}（据此窗口迭代取粉丝列表）。刻意**不放**标题 / 类型 / 时间等内容快照：
 * 收件箱只存 id、展示时走内容缓存读——收件箱一旦塞入不可重算的快照，就从"派生副本"变成"真相源"，
 * 无法再靠重建自愈。
 *
 * <p>序列化 / 反序列化走 {@code common.cache.JsonCodec}（JSON、UTF-8），与发布侧
 * {@code contentType=application/json} 的约定一致；载荷**只存在于 MQ 传输中**，不落 Redis / DB。
 *
 * <p>Jackson 3（Boot 4）原生支持 record；反序列化依赖编译参数 {@code -parameters}
 * （Boot 父 POM 已开），无需额外注解。
 */
public record FeedPushMessage(long contentId, long authorId) {
}
