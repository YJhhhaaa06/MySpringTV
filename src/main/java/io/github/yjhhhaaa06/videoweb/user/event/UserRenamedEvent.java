package io.github.yjhhhaaa06.videoweb.user.event;

/**
 * 用户改名事件（S5 引入，承接 TV {@code UserService.changeUserName} 的提交后级联失效）。
 *
 * <h2>为什么用事件而不是"在 UserService 里直接调 ContentCache"</h2>
 * TV 的写法是 {@code UserService} 依赖 {@code ContentCache}
 * （user 域 → content 域）。那会让 user 域知道"内容有一条按作者冗余的缓存"这件事——
 * 而当 content 域将来再加缓存（评论树、索引），这种直接依赖会继续增长。
 *
 * <p>改为发事件后：user 域只声明"有人的名字变了"这一**业务事实**；
 * 谁需要因此做缓存维护，由各自域在自己的监听器里决定
 * （见 {@code ContentCacheChangedListener.onUserRenamed}）。
 * 依赖方向变成"两域各自依赖事件类型"。
 *
 * <p>⚠️ 与 {@code PasswordChangedEvent}（暂无）等一样：**只在需要时引入**，
 * 不为"将来可能"预先建一整套事件总线。
 *
 * @param userId      改名的用户 id
 * @param newUsername 新用户名（供日志与将来可能的"内容归档"类订阅者使用）
 */
public record UserRenamedEvent(long userId, String newUsername) {
}
