package io.github.yjhhhaaa06.videoweb.user.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户实体。
 *
 * <p>从 TV {@code com.itheima.user.model.entity.User} 承接。TV 用多个构造器区分
 * 「登录用（含 hashedPassword）」与「资料用（含计数）」两种装配形态，字段含义靠构造器
 * 参数顺序约定——这里改为具名属性装配，由 MyBatis resultMap 显式映射，消除顺序约定。
 *
 * <p>{@code role}：0=普通 1=管理员，对应决策③「角色起步 RBAC（ROLE_USER / ROLE_ADMIN）」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {

    private Long id;
    private String username;

    /**
     * 头像选择（`R-27`）：{@code default:1..6} 或 {@code /upload/avatar/...}；{@code NULL}=从没选过。
     *
     * <p>⚠️ 这是**存储值**，不是对外 URL。资料出口必须经
     * {@code AvatarRefs#resolveUrl(userId, avatarRef)} 解析成生效 `avatarUrl`
     * （未选过 ⇒ 按 {@code userId % 6} 回落默认）。
     */
    private String avatarRef;

    /** BCrypt 散列后的密码。**任何情况下不得序列化进 VO**。 */
    private String hashedPassword;

    private String phone;
    private Integer followerCount;
    private Integer followCount;

    /** 0=普通 1=管理员 */
    private Integer role;

    public boolean isAdmin() {
        return role != null && role == 1;
    }
}
