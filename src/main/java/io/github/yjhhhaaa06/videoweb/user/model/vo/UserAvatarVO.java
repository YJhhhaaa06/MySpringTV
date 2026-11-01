package io.github.yjhhhaaa06.videoweb.user.model.vo;

/**
 * 用户头像的对外形状（`R-27`）：`{userId, avatarUrl}`。
 *
 * <p>用于三个端点：
 * <ul>
 *   <li>{@code PUT /api/v1/users/me/avatar}（自定义上传回执）；</li>
 *   <li>{@code PUT /api/v1/users/me/avatar/default}（默认切换回执）；</li>
 *   <li>{@code GET /api/v1/users/avatars?userIds=}（作者快照面的批量读）。</li>
 * </ul>
 *
 * <p>★ `avatarUrl` 是**生效值**：默认头像已解析成 `/avatars/default-N.svg`，
 * 从没选过的用户也已按 `userId % 6` 回落 ⇒ 对真实存在的用户**恒非空**。
 * 批量端点对不存在的 id **不返回条目**（不是返回 null）。
 */
public record UserAvatarVO(long userId, String avatarUrl) {
}
