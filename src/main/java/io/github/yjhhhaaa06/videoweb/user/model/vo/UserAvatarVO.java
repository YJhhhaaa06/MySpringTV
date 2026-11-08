package io.github.yjhhhaaa06.videoweb.user.model.vo;

/**
 * 用户头像的对外形状（`R-27`）：`{userId, avatarUrl}`。
 *
 * <p>`R-37` 起只用于两个**写**端点的回执：
 * <ul>
 *   <li>{@code PUT /api/v1/users/me/avatar}（自定义上传回执）；</li>
 *   <li>{@code PUT /api/v1/users/me/avatar/default}（默认切换回执）。</li>
 * </ul>
 * 作者快照面的批量读端点已退役（`R-37`），头像改为随各响应内联 `avatarUrl`。
 *
 * <p>★ `avatarUrl` 是**生效值**：默认头像已解析成 `/avatars/default-N.svg`，
 * 从没选过的用户也已按 `userId % 6` 回落 ⇒ 对真实存在的用户**恒非空**。
 */
public record UserAvatarVO(long userId, String avatarUrl) {
}
