package io.github.yjhhhaaa06.videoweb.follow.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

/**
 * 关注关系数据访问。
 *
 * <p>迁移自 TV {@code com.itheima.follow.dao.FollowDao}——《迁移参照系》§2.1 的机械改动：
 * <ul>
 *   <li>删掉首参 {@code Connection conn}（连接由 Spring 事务绑定）</li>
 *   <li>删掉 {@code throws SQLException}（{@code DataAccessException} 接管）</li>
 *   <li>{@code ?} + {@code setXxx(n, v)} 改为 {@code #{name}} 命名参数</li>
 *   <li>{@code ResultSet} 手工遍历 → MyBatis 单列结果集（{@code resultType="long"} / {@code "boolean"}）</li>
 *   <li>{@code StringBuilder} 拼 {@code IN (?,?)} → {@code <foreach>}（《参照系》§2.1 的"白赚"项）</li>
 *   <li>{@code @Component} → {@code @Mapper}</li>
 * </ul>
 * <b>SQL 文本、表名、列名、条件、顺序（含 {@code ORDER BY}）——原样保留</b>
 * （逐条 TV 对照注释见 {@code FollowMapper.xml}）。
 *
 * <h2>只搬本切片端点调用链上真正用得着的 8 条</h2>
 * TV 的 {@code FollowDao} 共 9 个方法，**未搬**：
 * <ul>
 *   <li>{@code getFollowerUserIdsAfter}（游标 keyset 迭代）—— 唯一调用方是
 *       {@code feed.service.FeedInboxWriter}（feed 域，本批明确不做）⇒ **无主代码，不搬**。</li>
 * </ul>
 * 见《事务边界决策表》§二·E 盘点 B / F-7 末段。
 *
 * <h2>⚠️ 关于 {@link #findAllFollowedUserIds} 与 {@link #findAllFollowerUserIds} 为什么在</h2>
 * 关注方向的**全量** loader 一直在用；粉丝方向的全量 loader 在 TV 里被 **T11-C**（前缀窗口装载）
 * 判为"已无主代码"并在缓存层删除，DAO 方法 {@code getFollowerUserIds} 成了零调用遗留。
 * 本切片**回到 T11-C 之前的口径**（数据 key 存在 ⇒ 完整），于是它又被需要了——
 * 两个全量方法都是**缓存 miss 时的回填 loader**。取舍与影响逐条记在 F-7。
 */
@Mapper
public interface FollowDao {

    // ==================== 读：批量 / 全量 / 窗口 ====================

    /**
     * 批量查"userId 关注了 {@code followedUserIds} 中的哪些人"。
     *
     * <p>TV: {@code getFollowedIds} —— {@code SELECT followed_user_id FROM follow
     * WHERE user_id = ? AND followed_user_id IN (?,?,...)}
     *
     * <p>用途：{@code FollowCache} 判关注态的**降级批量作答**（Redis 挂掉时按请求 id 作答，
     * 不为少量 id 拉用户全量关注史）。TV 在 DAO 内对空列表返回空集，新实现把该守卫放在
     * {@code FollowCache}（语义层）——{@code <foreach>} 不接受空集合，调用方保证非空。
     *
     * @param followedUserIds 非空（调用方保证）
     */
    Set<Long> findFollowedIdsIn(@Param("userId") long userId,
                                @Param("ids") List<Long> followedUserIds);

    /**
     * 用户关注的**全部**博主 id（缓存 miss 时的回填 loader）。
     *
     * <p>TV: {@code getAllFollowedUserIds} —— {@code SELECT followed_user_id FROM follow WHERE user_id = ?}
     *
     * <p>⚠️ TV 这条**没有 {@code ORDER BY}**，顺序由缓存侧 ZSet 的 {@code score = 成员 id} 归一为升序。
     * 新实现保留原 SQL（不加 {@code ORDER BY}），并在缓存层回填前显式升序排序——
     * 保证"数据 key 存在 ⇒ 内容与顺序都确定"（对外顺序契约见 F-3）。
     */
    List<Long> findAllFollowedUserIds(@Param("userId") long userId);

    /**
     * 用户的**全部**粉丝 id（缓存 miss 时的回填 loader）。
     *
     * <p>TV: {@code getFollowerUserIds} —— {@code SELECT user_id FROM follow WHERE followed_user_id = ?}
     */
    List<Long> findAllFollowerUserIds(@Param("followedUserId") long followedUserId);

    /**
     * 关注方向**窗口**查询：按 {@code followed_user_id} 升序取 {@code [offset, offset+count)}。
     *
     * <p>TV: {@code getFollowedUserIdsInWindow}。原注释说明它"走既有
     * {@code uk_user_follow(user_id, followed_user_id)}——等值列 + 有序第二列，无需 filesort；
     * 与缓存侧 ZSet 的 score=成员 id 升序口径同源"。**索引使用方式属被保真项，勿改 SQL。**
     *
     * <p>用途：① 缓存 miss 的回填 loader；② **降级路径**（Redis 挂掉时直接 DB 窗口作答）。
     */
    List<Long> findFollowedUserIdsWindow(@Param("userId") long userId,
                                         @Param("offset") long offset,
                                         @Param("count") int count);

    /**
     * 粉丝方向**窗口**查询：按 {@code user_id} 升序取 {@code [offset, offset+count)}。
     *
     * <p>TV: {@code getFollowerUserIdsInWindow}。原注释说明它"依赖
     * {@code idx_followed_user_user(followed_user_id, user_id)}：等值列 + 有序第二列同序，
     * 避免用 {@code idx_followed_user_id} 时的 filesort"。
     */
    List<Long> findFollowerUserIdsWindow(@Param("followedUserId") long followedUserId,
                                         @Param("offset") long offset,
                                         @Param("count") int count);

    /**
     * 是否已关注。
     *
     * <p>TV: {@code isFollowing} —— {@code SELECT COUNT(*) FROM follow WHERE user_id = ? AND followed_user_id = ?}
     * 并把 {@code COUNT > 0} 手写为布尔。新实现沿用同仓写法（{@code ContentDao.isContentExist} 的
     * {@code COUNT(*) > 0} → {@code EXISTS}）：行集等价，COUNT 无"返回 NULL"的聚合歧义。
     */
    boolean isFollowing(@Param("userId") long userId, @Param("followedUserId") long followedUserId);

    // ==================== 写 ====================

    /**
     * 建立关注关系。
     *
     * <p>TV: {@code addFollow} —— {@code INSERT INTO follow (user_id, followed_user_id) VALUES (?, ?)}
     *
     * <p>⚠️ **不做 {@code INSERT IGNORE}**：TV 的幂等口径是"事务内先 {@code isFollowing} 查、查到抛 409"，
     * 并发竞态由唯一键 {@code uk_user_follow} 兜底（撞键 → {@code DuplicateKeyException} → 全局出口 409）。
     * 改成 {@code IGNORE} 会让 409 静默变成 200，**是契约改动**。
     *
     * @return 受影响行数（正常恒为 1）
     */
    int insertFollow(@Param("userId") long userId, @Param("followedUserId") long followedUserId);

    /**
     * 解除关注关系。
     *
     * <p>TV: {@code deleteFollow} —— {@code DELETE FROM follow WHERE user_id = ? AND followed_user_id = ?}
     *
     * @return 受影响行数
     */
    int deleteFollow(@Param("userId") long userId, @Param("followedUserId") long followedUserId);
}
