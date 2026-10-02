package io.github.yjhhhaaa06.videoweb.user.service;

import io.github.yjhhhaaa06.videoweb.common.config.BigVProperties;
import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 自动大V状态维护（**写侧判定单点**，TV feed3-T28-A）：把"谁是大V"从**无状态读时求值**升级为
 * **带滞回**的判定——升级线 {@code 粉丝数 >= threshold}、降级线 {@code 粉丝数 < ratio × threshold}；
 * 两线之间（带内）**状态不翻转**。
 *
 * <h2>为什么在 user 域</h2>
 * TV 的原注释（即规格，逐条保留）：本类被 {@code follow.service.FollowService} 在**关注 / 取关事务内**
 * 调用。若把它放进 feed 域，会新增 {@code follow → feed} 依赖（{@code feed → follow} 已存在）而
 * **成包环**；状态表本身也是"用户属性"（{@code users.follower_count} 的派生状态），
 * 落 user 域与 {@link UserDao} 同层最自然。
 *
 * <h2>状态表语义 = 「存在即自动大V」（{@code auto_bigv}，PK {@code user_id}）</h2>
 * 升 = {@code INSERT IGNORE}、降 = {@code DELETE}，**两者的 affected rows 即 edge 信号**
 * （"本次是否真发生状态迁移"）——刻意**不用**"比较 before/after 计数"（并发下两个取关可能都判"跨越"
 * ⇒ 重复触发）。此语义落在 {@link UserDao#insertAutoBigV} / {@link UserDao#deleteAutoBigV} 的
 * {@code boolean} 返回值上（{@code affected > 0}），**不是**在 Java 里比计数。
 *
 * <h2>为什么"同一事务"这件事没有变（迁移要点）</h2>
 * TV 靠 {@code Connection conn} 手工穿透表达"与 {@code updateFollowerCount} 同一个连接"；
 * 新实现删掉 {@code conn} 参数——Spring 的 {@code @Transactional} 把事务绑定在**线程**上，
 * 只要本方法在调用方事务内被调用，所有 DAO 调用就自动落在同一事务 / 同一连接上。
 * 因此**本方法刻意不标 {@code @Transactional}**：
 * <ul>
 *   <li>标了会因 {@code REQUIRED} 传播"恰好也并进"已有事务——**行为无害，但会掩盖
 *       "它必须被事务内调用"这一约束**；</li>
 *   <li>更坏的是：一旦将来有人在事务**外**调它，标了注解会让它**自带**一个事务，
 *       于是"计数已变但状态没跟上"的中间态就重新变得可能。不标 ⇒ 这种调用在数据层
 *       就失去原子性保障，问题暴露在评审而不是线上。</li>
 * </ul>
 *
 * <h2>fail-atomic（原样保留）</h2>
 * 判定输入是**刚写入的 DB 真值**（{@code users.follower_count}）；本方法失败 ⇒
 * 调用方的整个关注 / 取关事务回滚（不给"计数已变但状态没跟上"的中间态）。
 * TV 在此处把 {@code SQLException} 包成 {@code ServerException} 并记 SEVERE + 堆栈；
 * 新实现让 {@code DataAccessException} 原样上抛，由 {@code GlobalExceptionHandler} 的
 * 数据访问分支统一记堆栈 + 转 500（"包装点即源头"收敛到唯一出口）。
 *
 * <h2>与 TV 的两处差异（有意，见《事务边界决策表》§二·E 盘点 B / F-6）</h2>
 * <ol>
 *   <li><b>取值载体</b>：{@code FeedBigVConfig}（236 行热更快照）→ {@link BigVProperties}
 *       （{@code video.bigv.*}）。本类只用得到 threshold 与 downgradeRatio，名单属读侧。</li>
 *   <li><b>配置非法</b>：TV 在取值时抛（首次关注请求 500），新实现改为绑定期失败。
 *       配置正确时行为完全一致。</li>
 * </ol>
 */
@Slf4j
@Service
public class AutoBigVStateService {

    private final UserDao userDao;
    private final BigVProperties bigVProperties;

    public AutoBigVStateService(UserDao userDao, BigVProperties bigVProperties) {
        this.userDao = userDao;
        this.bigVProperties = bigVProperties;
    }

    /** 一次评估的状态迁移结果（{@link #NONE} = 状态未变）。 */
    public enum Transition {
        /** 状态未变（带内 / 已在表中 / 本就不在表中）。 */
        NONE,
        /** 本次真升级（首次入表）——无提交后动作（TV 里 UPGRADED 不触发补推）。 */
        UPGRADED,
        /** 本次真降级（本次删到行）——TV 里这是 feed3-T28-B 降级补推的触发信号（本批不搬，见 F-4 裁剪表）。 */
        DOWNGRADED
    }

    /**
     * 评估并维护 {@code userId}（**被关注者**，即粉丝数变化者）的自动大V状态。
     *
     * <p><b>必须在调用方事务内调用</b>，且在 {@code updateFollowerCount(±1)} **之后**——这两条是
     * 滞回判定正确性的前提：① fail-atomic；② 那条 UPDATE 已持有该 users 行的排它锁，
     * 两笔并发关注 / 取关在该行上**串行** ⇒ 后面读到的计数与状态表操作都基于已串行化的最新值
     * ⇒ "并发两次取关只有先到者 affected == 1"由行锁保证（无需额外 CAS / 版本号）。
     *
     * @param userId 被关注者（粉丝数变化者），**非操作者**
     * @return 状态迁移结果（供调用方在**提交后**记录 / 触发补推）
     */
    public Transition evaluate(long userId) {
        int threshold = bigVProperties.threshold();
        double downgradeLine = bigVProperties.downgradeRatio() * threshold;

        int followerCount = userDao.getFollowerCountById(userId);
        if (followerCount >= threshold) {
            // 幂等：已在表中 ⇒ affected = 0 ⇒ 无 edge
            return userDao.insertAutoBigV(userId) ? Transition.UPGRADED : Transition.NONE;
        }
        if (followerCount < downgradeLine) {
            // 幂等：本就不在表中 ⇒ affected = 0 ⇒ 无 edge
            return userDao.deleteAutoBigV(userId) ? Transition.DOWNGRADED : Transition.NONE;
        }
        // 带内：滞回生效，不翻转（已升者不因掉到带内而降级）
        return Transition.NONE;
    }
}
