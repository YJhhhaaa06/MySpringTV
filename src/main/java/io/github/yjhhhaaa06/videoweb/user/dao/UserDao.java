package io.github.yjhhhaaa06.videoweb.user.dao;

import io.github.yjhhhaaa06.videoweb.user.model.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
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
     * <p><b>T34 补防负守卫</b>（TV 原 SQL 无守卫）。{@code users.follow_count} 是
     * {@code int unsigned DEFAULT 0}，计数为 0 时再 −1 会因 UNSIGNED 下溢报 1690 → 500。
     * 守卫**不能**用 {@code follow_count + delta >= 0}（unsigned 列上自身下溢），须
     * {@code CAST(follow_count AS SIGNED) + delta >= 0}。语义 = block：拦住 ⇒ 影响 0 行、
     * 不抛错、不回滚 ⇒ 取关照常成功（计数停在 0）。
     *
     * @return 受影响行数（守卫拦住时为 0）
     */
    int updateFollowCount(@Param("userId") long userId, @Param("delta") int delta);

    /** 粉丝数增减。TV: {@code updateFollowerCount}。口径与 {@link #updateFollowCount} 完全一致（含同样的防负守卫）。 */
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

    // ========================================================================
    // 以下 4 个方法由投币专项 T37（硬币供给端）补入：
    // users 的 coin_deci / last_sign_date 两列由 coin 域维护，但方法**进所在表的 DAO**
    // （先例 = 上面的 updateFollowCount / updateFollowerCount）。设计见
    // architecture/domain/coin/coin.md §四（单位约定）与 §五.2（签到幂等）。
    // ========================================================================

    /**
     * 硬币余额增减（**十分位整数**：{@code deltaDeci} 的单位是 0.1 枚）。
     *
     * <p>{@code update users set coin_deci = coin_deci + #{deltaDeci} where id = #{userId}}。
     * 投币写路径（T38）用它给**作者**加收益（{@code + amount}，恰好等于 {@code amount} 个十分位）；
     * 投票者的扣款走 {@link #deductCoinDeci}（带 {@code >=} 守卫的条件写）；
     * 签到不走它（签到是一条带幂等判据的原子条件写，见 {@link #signIn}）。
     *
     * <p>⚠️ <b>本方法没有"余额够不够"的守卫</b>：扣款必须走
     * {@link #deductCoinDeci}（{@code where coin_deci >= ?} 的条件写，设计 §五.1），
     * 不能用"先 {@link #findCoinDeci} 再减"。
     * 本方法只表达"加减多少"，不承担并发正确性。
     *
     * @return 受影响行数（0 = 用户不存在）
     */
    int updateCoinDeci(@Param("userId") long userId, @Param("deltaDeci") int deltaDeci);

    /**
     * **条件扣款**：余额够才扣（投币写路径 T38 的"余额"那一台原子条件写）。
     *
     * <p>SQL: {@code update users set coin_deci = coin_deci - #{costDeci}
     * where id = #{userId} and coin_deci >= #{costDeci}}。
     *
     * <p>★ <b>这是"余额够不够"的唯一判据</b>：返回 0 行 ⇒ 不足 ⇒ 400「硬币不足」。
     * <b>禁止</b>写成"先 {@link #findCoinDeci} 再 {@link #updateCoinDeci}"——
     * 那个读写窗口在并发下会让两个请求都读到足够的余额、都扣成功 ⇒ 扣成负数。
     *
     * <p>★ 它是本仓**唯一**一处带 {@code >=} 守卫的余额写。余额列是**有符号** {@code int}
     * （不是 unsigned）：语义是"够不够"，靠守卫拦住，而不是靠类型溢出报错
     * （若给 unsigned 会逼出 {@code CAST} 技巧，见设计 §十 #6）。
     *
     * <p>⚠️ 与 {@link #updateCoinDeci} 的分工：那个表达"加减多少"（作者收益 +N），
     * 这个表达"够才减"（投票者扣款）。不要把作者侧也换成这个方法——作者的收益没有"够不够"问题。
     *
     * @param costDeci 本次要扣的十分位数（{@code count × DECI_PER_COIN}）
     * @return 影响行数：1 = 扣款成功；0 = 余额不足（或用户不存在）
     */
    int deductCoinDeci(@Param("userId") long userId, @Param("costDeci") int costDeci);

    /**
     * 签到：**一条原子条件 UPDATE**，同时完成"加币 + 记日期 + 幂等判定"（设计 §五.2）。
     *
     * <pre>
     * update users set coin_deci = coin_deci + #{deciPerCoin}, last_sign_date = #{today}
     *  where id = #{userId} and (last_sign_date is null or last_sign_date &lt; #{today})
     * </pre>
     *
     * <p>★ <b>幂等与并发安全全部由这条 {@code WHERE} 守卫承担</b>：
     * 同一天第二次调用（无论串行还是并发双击）都影响 0 行——不写库、不抛错。
     * <b>绝不能</b>改成"先查 {@code last_sign_date} 再决定加不加"：那个读写窗口会让并发双击
     * 各拿到一次 {@code null}（或同一个昨天）⇒ 两次到账。反向验证口径：
     * 删掉守卫括号里的任一分支 ⇒ {@code CoinSignInTests} 的并发用例会读到 {@code coin_deci == 20}。
     *
     * <p>{@code deciPerCoin} 由调用方传常量（{@code CoinService.DECI_PER_COIN}），
     * <b>不在 SQL 里写 {@code 10}</b>：单位约定只允许有一处声明（列名带 {@code deci} +
     * 常量 + 只在 VO 出口换算，见设计 §四）。
     *
     * @param today       站内"今天"（{@code SiteTime.today()}，上海自然日）
     * @param deciPerCoin 一枚硬币的十分位数（恒为 {@code 10}）
     * @return <b>影响行数</b>：{@code 1} = 本次到账；{@code 0} = 今日已签到（或用户不存在）
     */
    int signIn(@Param("userId") long userId,
               @Param("today") LocalDate today,
               @Param("deciPerCoin") int deciPerCoin);

    /**
     * 硬币余额（单列窄查询，**十分位整数**）。
     *
     * <p>刻意返回包装类型 {@link Integer}：无行 ⇒ {@code null}。写成 {@code int} 会在
     * MyBatis 返回 null 时拆箱 NPE，而"用户不存在"是**可能发生**的读结果
     * （{@code UserService#getProfile} 在查到用户之后才调它；{@code CoinService#signIn}
     * 没有存在性前置，靠调用侧把 null 兜成 0）。
     *
     * <p>写法选择：直接 {@code select coin_deci ...}，**不用** {@code COALESCE((子查询), 0)}
     * （同文件的 {@code getFollowCountById} 用的是后者）。理由：那条的口径是"用户不存在按 0 处理"，
     * 而余额的 0 是**真实值**——用 COALESCE 会把"查无此人"伪装成"余额为零"，把数据异常吞掉。
     *
     * <p>⚠️ 调用方读到 null 时先确认前提：{@code UserService#getProfile} 在
     * {@code findByIdForProfile} 非 null 之后才调它（用户必然存在）。
     */
    Integer findCoinDeci(@Param("userId") long userId);

    /**
     * 最近签到日（单列窄查询，{@code date} 列 ⇒ {@link LocalDate}）。
     *
     * <p>无行 / 从未签到（列 {@code NULL}）⇒ {@code null}。两种 null 在本仓不可区分，
     * 且都不影响语义：{@code null} 一律视为"今天还没签"（签到守卫
     * {@code last_sign_date is null or last_sign_date < today} 同样把它们视为可签）。
     *
     * <p>用途：{@code /user/me} 的 {@code signedToday}（是否有值 == 今天）。
     * 这里**只读一列**，不扩 {@code findByIdForProfile} 的列集——资料端点没必要为了一个布尔
     * 把签到日暴露成 VO 字段。
     */
    LocalDate findLastSignDate(@Param("userId") long userId);
}
