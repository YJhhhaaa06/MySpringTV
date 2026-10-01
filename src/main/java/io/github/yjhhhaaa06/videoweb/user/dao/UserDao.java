package io.github.yjhhhaaa06.videoweb.user.dao;

import io.github.yjhhhaaa06.videoweb.user.model.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户数据访问。
 *
 * <p>迁移自 TV {@code com.itheima.user.dao.UserDao}——《迁移参照系》§2.1 的机械改动：
 * <ul>
 *   <li>删掉首参 {@code Connection conn}（连接由 Spring 事务绑定）</li>
 *   <li>删掉 {@code throws SQLException}（{@code DataAccessException} 接管）</li>
 *   <li>{@code ?} + {@code setXxx(n, v)} 改为 {@code #{name}} 命名参数</li>
 *   <li>{@code @Component} 改为 {@code @Mapper}</li>
 * </ul>
 * <b>SQL 文本原样保留</b>（含 TV 的 {@code insert into} 小写风格与 {@code select *}）。
 *
 * <p>切片 0 只搬这三条业务线（注册 / 登录 / 改密改名）所需的方法。
 * TV 的 {@code UserDao} 另有 20 个方法（关注计数、批量查、auto_bigv 等），
 * 属 follow / feed 切片，本切片**不搬**——见《迁移参照系》「按业务纵切」。
 */
@Mapper
public interface UserDao {

    /**
     * 新增用户，返回自增主键。
     *
     * <p>TV 用 {@code Statement.RETURN_GENERATED_KEYS} + {@code getGeneratedKeys()} 手工取键，
     * 并额外抛了两个 {@code SQLException}（"插入失败" / "插入成功，但未获取到ID"）——
     * 这些骨架删掉：MyBatis 的 {@code useGeneratedKeys} 直接回填主键，
     * 而"取不到 ID"在 MyBatis 下不可能发生。
     *
     * <p>注意：必须有 {@code useGeneratedKeys="true"}，否则 {@code user.getId()} 为 null。
     */
    int insert(User user);

    /** 按 id 查用户（登录用，含 hashedPassword）。TV: {@code getUserForLoginById}。 */
    User findByIdForLogin(@Param("id") long id);

    /**
     * 按手机号查用户（登录用，含 hashedPassword）。TV: {@code getUserForLoginByPhone}。
     * 查不到返回 null（**不抛异常**）——异常语义由 Service 的 doLogin 决定，与 TV 一致。
     */
    User findByPhoneForLogin(@Param("phone") String phone);

    /** 按 id 查用户（资料用，不含 hashedPassword）。TV: {@code getUserForProfileById}。 */
    User findByIdForProfile(@Param("id") long id);

    /** 手机号是否已被使用。TV: {@code isPhoneUsed}。 */
    boolean isPhoneUsed(@Param("phone") String phone);

    /** 用户名是否已被使用。TV: {@code isUsernameUsed}。 */
    boolean isUsernameUsed(@Param("username") String username);

    /** 查询角色（0=普通 1=管理员；查无此人返回 0）。TV: {@code getUserRole}。 */
    int findRoleById(@Param("id") long id);

    /** 修改密码（传已散列的新密码）。TV: {@code updateUserPassword}。 */
    int updatePassword(@Param("id") long id, @Param("hashedPassword") String hashedPassword);

    /** 修改用户名。TV: {@code updateUserName}。 */
    int updateUsername(@Param("id") long id, @Param("username") String username);
}
