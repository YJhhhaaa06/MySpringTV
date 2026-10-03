package io.github.yjhhhaaa06.videoweb.user.dao;

import io.github.yjhhhaaa06.videoweb.user.model.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

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
 *
 * <p><b>S4（follow）补搬 7 个方法</b>（见下），仍**未搬**的归 follow/feed 之后的切片：
 * {@code findUserIdsByMinFollowerCount} / {@code findAutoBigVUserIdsIn}（读侧大V判定，feed 域）、
 * {@code updateUserPhone}（无端点）、{@code getUserRole}（本切片用不到，admin 切片再说）。
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

    // ========================================================================
    // 以下 7 个方法由 S4（follow 切片）补搬
    // ========================================================================

    /**
     * 关注数增减。TV: {@code updateFollowCount} ——
     * {@code update users set follow_count = follow_count + ? where id=?}（{@code delta} 正负表示增减）。
     *
     * <p>⚠️ <b>无防负守卫</b>（TV 原样）。{@code users.follow_count} 是 {@code int unsigned DEFAULT 0}，
     * 若计数已是 0 仍执行 −1，MySQL 严格模式下会因 UNSIGNED 下溢报错 → 500。
     * 与 S2 实测的 {@code content.comment_count}、S3 记的 {@code content.like_count} 同款尖锐处，
     * 触发前提是数据不自洽（有关系行但计数为 0），正常路径走不到。**不修**（见决策表 F-1 要点 4）。
     *
     * @return 受影响行数
     */
    int updateFollowCount(@Param("userId") long userId, @Param("delta") int delta);

    /** 粉丝数增减。TV: {@code updateFollowerCount}。口径与 {@link #updateFollowCount} 完全一致（含同样的无守卫）。 */
    int updateFollowerCount(@Param("userId") long userId, @Param("delta") int delta);

    /**
     * 批量查用户（关注/粉丝列表装载用，**只装配 id 与 username**）。
     *
     * <p>TV: {@code findUsersByIds} ——
     * {@code SELECT id, username FROM users WHERE id IN (...) ORDER BY id}
     *
     * <p>★ <b>{@code ORDER BY id} 是契约</b>：TV 的 T7 注释写明"关注/粉丝列表顺序由此处决定
     * （上层 ZSet 窗口按 id 升序切片）；无 ORDER BY 时输出序依赖存储引擎默认序，
     * 分页'顺序稳定'只能靠巧合"。**勿删**（决策表 F-3）。
     *
     * <p>用途：{@code FollowService.loadUserList}（**事务外纯读**，见 F-3）。
     * 未选中的列留 null（本方法只服务列表视图，不需要手机号 / 口令散列）。
     *
     * @param ids 非空（调用方保证：空 {@code IN ()} 是非法 SQL）
     */
    List<User> findUsersByIds(@Param("ids") List<Long> ids);

    /**
     * 关注数（单列）。TV: {@code getFollowCountById} —— {@code select follow_count from users where id=?}，
     * 原注释："用户不存在按 0 处理（与计数语义一致）"。
     *
     * <p>写法说明：TV 用"无行 ⇒ 返回 0"的分支表达；此处用
     * {@code COALESCE((子查询), 0)} 把同一语义收进 SQL（与同仓 {@code findRoleById} 同款），
     * 避免"无行 ⇒ MyBatis 返回 null ⇒ 拆箱 NPE"。
     *
     * <p>用途：{@code FollowCache} 窗口读的**降级路径**取 total（本切片不建计数缓存，见 F-7）。
     */
    int getFollowCountById(@Param("userId") long userId);

    /** 粉丝数（单列）。TV: {@code getFollowerCountById}，口径同 {@link #getFollowCountById}。 */
    int getFollowerCountById(@Param("userId") long userId);

    /**
     * 幂等写入 {@code auto_bigv} 状态行（"存在即自动大V"）。
     *
     * <p>TV: {@code insertAutoBigV} —— {@code insert ignore into auto_bigv(user_id) values(?)}，
     * <b>返回 {@code affected == 1} 即"本次真升级"（edge 信号）</b>。
     *
     * <p>MyBatis 对 DML 的 {@code boolean} 返回即 {@code affected > 0}——对单行 DML 等价于
     * TV 的 {@code affected == 1}。**这个返回值是语义的一部分**（决定是否记状态迁移日志 /
     * 是否触发降级补推），不要为了"统一成 int"而改掉。
     *
     * @return {@code true} = 本次真写入（UPGRADED）；{@code false} = 行已存在（无 edge）
     */
    boolean insertAutoBigV(@Param("userId") long userId);

    /**
     * 幂等删除 {@code auto_bigv} 状态行。
     *
     * <p>TV: {@code deleteAutoBigV} —— {@code delete from auto_bigv where user_id=?}，
     * <b>返回 {@code affected == 1} 即"本次真降级"</b>（feed3-T28-B 补推的触发信号）。
     *
     * @return {@code true} = 本次真删到行（DOWNGRADED）；{@code false} = 本就不是自动大V
     */
    boolean deleteAutoBigV(@Param("userId") long userId);

    /**
     * 批量读**自动大V状态表**命中的作者子集（S9 补入）——读侧大V判定三路并集的**第②路**（滞回产物）。
     *
     * <p>TV: {@code findAutoBigVUserIdsIn} —— {@code SELECT user_id FROM auto_bigv WHERE user_id IN (?,…)}
     * （走主键 {@code PK(user_id)} 覆盖索引点查，无新索引）。
     *
     * <p>命中者 = "曾达线升为大V、掉粉后仍在带内未降级"的作者——**这就是滞回**：
     * 若只看第③路（粉丝数 ≥ 阈值），掉到带内的作者会被突然降级（大V内容从发件箱腿消失）。
     *
     * @param ids 待判定作者（调用方按批量尺寸分块保证非空）
     */
    List<Long> findAutoBigVUserIdsIn(@Param("ids") List<Long> ids);

    /**
     * 批量读**粉丝数 ≥ 阈值**的作者子集（S9 补入）——读侧大V判定三路并集的**第③路**（DB 真值兜底）。
     *
     * <p>TV: {@code findUserIdsByMinFollowerCount} ——
     * {@code SELECT id FROM users WHERE follower_count >= ? AND id IN (?,…)}
     *
     * <h2>★ 第③路不可省（TV 原注释的裁决，逐字保留）</h2>
     * 冷启动 / 历史数据尚未入状态表时，**已达标作者不得因"没有状态行"突然降级**：
     * 一个早就有 15000 粉的作者没有 {@code auto_bigv} 行，若只剩第①（名单）②（状态表）两路，
     * 他会被判成普通作者 → 其内容一夜间从"发件箱腿"落到"收件箱腿"，可见性错乱。
     * 第③路用 DB 真值兜住这个缺口。
     *
     * @param ids              待判定作者（调用方按批量尺寸分块保证非空）
     * @param minFollowerCount 阈值（升级线）
     */
    List<Long> findUserIdsByMinFollowerCount(@Param("ids") List<Long> ids,
                                             @Param("minFollowerCount") int minFollowerCount);
}
