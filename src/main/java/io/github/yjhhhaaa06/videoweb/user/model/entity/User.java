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
