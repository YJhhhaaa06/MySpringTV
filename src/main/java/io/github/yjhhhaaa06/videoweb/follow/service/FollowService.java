package io.github.yjhhhaaa06.videoweb.follow.service;

import io.github.yjhhhaaa06.videoweb.common.exception.ConflictException;
import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.follow.cache.FollowCache;
import io.github.yjhhhaaa06.videoweb.follow.dao.FollowDao;
import io.github.yjhhhaaa06.videoweb.follow.event.FollowChangedEvent;
import io.github.yjhhhaaa06.videoweb.follow.model.vo.FollowUserVO;
import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import io.github.yjhhhaaa06.videoweb.user.model.entity.User;
import io.github.yjhhhaaa06.videoweb.user.service.AutoBigVStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关注关系服务（S4）。
 *
 * <p>迁移自 TV {@code com.itheima.follow.service.FollowService}（248 行，去掉了 feed 域的 3 个依赖）。
 *
 * <h2>交付的 4 个端点</h2>
 * <ul>
 *   <li>{@code POST /follow/add} → {@link #follow}</li>
 *   <li>{@code POST /follow/remove} → {@link #unfollow}</li>
 *   <li>{@code GET /follow/following} → {@link #getFollowingList}</li>
 *   <li>{@code GET /follow/followers} → {@link #getFollowerList}</li>
 * </ul>
 * 全部需登录（TV {@code AuthFilter} 的 {@code PROTECTED_PREFIXES} 含 {@code /follow}）——
 * 由 {@code FollowController} 的**类级** {@code @RequiresLogin} 声明。
 *
 * <h2>事务边界（《事务边界决策表》§二·E）</h2>
 * <ul>
 *   <li><b>F-1 / F-2</b>：{@link #follow} / {@link #unfollow} —— ✅ 保持单事务
 *       （关系行与双方计数必须原子；大V滞回判定同事务同连接，fail-atomic）。</li>
 *   <li><b>F-3</b>：{@link #loadUserList} —— ⚠️ **去掉事务**（纯读单条 SELECT，
 *       旧事务只是"取连接的手段"）。</li>
 *   <li><b>F-4</b>：提交后副作用（缓存双写 / 里程碑日志）—— 改为
 *       {@code @TransactionalEventListener(AFTER_COMMIT)}。</li>
 * </ul>
 * 事务注解**标在 public 入口**（{@code follow}/{@code unfollow}）——SOP 坑 12：
 * 同类自调用会绕过代理使事务静默失效。本类没有自调用路径（两个写方法都只被 Controller 调用）。
 */
@Slf4j
@Service
public class FollowService {

    private final FollowDao followDao;
    private final UserDao userDao;
    private final FollowCache followCache;
    private final AutoBigVStateService autoBigVState;
    private final ApplicationEventPublisher events;

    public FollowService(FollowDao followDao,
                         UserDao userDao,
                         FollowCache followCache,
                         AutoBigVStateService autoBigVState,
                         ApplicationEventPublisher events) {
        this.followDao = followDao;
        this.userDao = userDao;
        this.followCache = followCache;
        this.autoBigVState = autoBigVState;
        this.events = events;
    }

    // ========================================================================
    // 写：关注 / 取关（F-1 / F-2）
    // ========================================================================

    /**
     * 关注。
     *
     * <p>校验顺序与文案逐条保留 TV 口径（决定客户端拿到哪个 409）：
     * <ol>
     *   <li>关注自己 → {@code ConflictException("不能关注自己")}
     *       —— TV 把这条放在事务**外**，新实现放在 {@code @Transactional} 方法内；
     *       可观察行为不变（异常仍在任何写操作之前抛出）。</li>
     *   <li>已关注 → {@code ConflictException("已关注，不可重复操作")}</li>
     * </ol>
     *
     * <p>并发重复关注由唯一键 {@code follow.uk_user_follow} 兜底：撞键抛
     * {@code DuplicateKeyException} → 全局出口 409（同 C-1 / U-2 / L-1 的映射点）。
     *
     * <p>大V滞回判定（{@link AutoBigVStateService#evaluate}）在**计数之后、同一事务内**调用：
     * fail-atomic（判定输入是刚写入的 DB 真值；失败则整体回滚）。详见决策表 F-6。
     */
    @Transactional
    public void follow(long userId, long followedUserId) {
        if (userId == followedUserId) {
            throw new ConflictException("不能关注自己");
        }
        // 先检查是否已关注（TV 口径：先查后写，不用 INSERT IGNORE —— 后者会把 409 静默变成 200）
        if (followDao.isFollowing(userId, followedUserId)) {
            throw new ConflictException("已关注，不可重复操作");
        }
        followDao.insertFollow(userId, followedUserId);
        userDao.updateFollowCount(userId, 1);
        userDao.updateFollowerCount(followedUserId, 1);
        // feed3-T28-A：滞回判定紧接计数之后，判定输入是刚写入的 DB 真值；失败则整体回滚
        AutoBigVStateService.Transition transition = autoBigVState.evaluate(followedUserId);
        // 提交后副作用（缓存双写 + 提交后日志）交给 AFTER_COMMIT 监听器 —— 时序由框架保证
        events.publishEvent(FollowChangedEvent.followed(userId, followedUserId, transition));
    }

    /**
     * 取关。
     *
     * <p>与 {@link #follow} 同构的镜像：自我取关 → {@code "不能取关自己"}；
     * 未关注 → {@code "未关注，不可取消"}。
     *
     * <p>⚠️ 两处文案与内容侧/点赞侧**刻意不一致**（TV 原文如此，保留不动）：
     * 关注域用"不可取消"（而 S3 的点赞域是"无法取消"）。没有理由在这一步顺手统一
     * ——{@code msg} 虽不在冻结契约内，但"顺手统一"是无声的契约改动。
     */
    @Transactional
    public void unfollow(long userId, long followedUserId) {
        if (userId == followedUserId) {
            throw new ConflictException("不能取关自己");
        }
        if (!followDao.isFollowing(userId, followedUserId)) {
            throw new ConflictException("未关注，不可取消");
        }
        followDao.deleteFollow(userId, followedUserId);
        userDao.updateFollowCount(userId, -1);
        userDao.updateFollowerCount(followedUserId, -1);
        // 掉粉是滞回"降级"的唯一触发源
        AutoBigVStateService.Transition transition = autoBigVState.evaluate(followedUserId);
        events.publishEvent(FollowChangedEvent.unfollowed(userId, followedUserId, transition));
    }

    // ========================================================================
    // 读：分页列表（F-3）
    // ========================================================================

    /**
     * 关注列表（{@code userId} 关注了谁）**分页**。
     *
     * <p>{@code page} / {@code pageSize} 已由 Controller 归一后传入（T7 B2 / T11-A / T19 口径）：
     * 缺省（不传参）等价于 {@code page=1&pageSize=100}，响应**逐字节一致**——旧的"缺省返回全量数组"
     * 分支在 T11-A 已被删除，本切片沿用该契约。
     *
     * @param userId        要查看谁的关注列表
     * @param currentUserId 当前登录用户（决定条目的 {@code isFollowed} / {@code isSelf}）
     */
    public PageResult<FollowUserVO> getFollowingList(long userId, long currentUserId, int page, int pageSize) {
        long offset = (long) (page - 1) * pageSize;
        return buildPage(followCache.getFollowingWindow(userId, offset, pageSize), currentUserId, page, pageSize);
    }

    /** 粉丝列表（谁关注了 {@code userId}）**分页**：逻辑同 {@link #getFollowingList}，缓存入口换粉丝集。 */
    public PageResult<FollowUserVO> getFollowerList(long userId, long currentUserId, int page, int pageSize) {
        long offset = (long) (page - 1) * pageSize;
        return buildPage(followCache.getFollowerWindow(userId, offset, pageSize), currentUserId, page, pageSize);
    }

    /**
     * 分页信封组装：该页 ids 走统一装载逻辑（{@link #loadUserList}），
     * 总数取缓存窗口的 total（**与页内容同源**——同一趟 ZRANGE + ZCARD）。
     */
    private PageResult<FollowUserVO> buildPage(FollowCache.Window window,
                                               long currentUserId, int page, int pageSize) {
        List<FollowUserVO> users = loadUserList(window.ids(), currentUserId);
        int total = (int) Math.min(window.total(), Integer.MAX_VALUE);
        return new PageResult<>(users, total, page, pageSize);
    }

    /**
     * 该页 ids → 用户视图列表：**空 ids 直接返回空列表**（不打 DB、不触碰缓存）；
     * 非空则"DB 装载 → 事务外批量判关注态 → 组装"。
     *
     * <p>TV 的 T12 特意把**缓存读挪到 DB 装载之后、事务之外**（治池 U-14②：旧
     * {@code TransactionTemplate} 无传播语义，事务内缓存 miss 会再取新连接、叠加占用）。
     * 新实现照旧分两段——虽然 Spring 的 {@code @Transactional} 有传播语义、不会重复取连接，
     * 但保持"DB 装载"与"判关注态"分离仍是对的：两者是不同数据源，混在一起会让
     * "Redis 挂掉降级 DB" 的路径把两次 DB 查询捆进同一个事务快照。
     */
    private List<FollowUserVO> loadUserList(List<Long> ids, long currentUserId) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<User> users = userDao.findUsersByIds(ids);
        return buildUserViews(users, ids, currentUserId);
    }

    // ========================================================================
    // 对外查询 API（S6-B2a）：关注态的**唯一对外入口**
    // ========================================================================

    /**
     * 单个关注态：{@code userId} 是否关注了 {@code followedUserId}。
     *
     * <h2>★ 这组方法存在的唯一理由：把 {@code FollowCache} 收回成本域私有</h2>
     * 在 S6 之前，{@code content} 域的 {@code ContentStatusFiller} 与 {@code ProfileService}
     * **直接注入 {@code follow.cache.FollowCache}**——于是关注域的缓存实现变成了跨域 API：
     * 它的方法签名、降级语义、甚至"是否建计数缓存"都成了别的域要跟着走的东西。
     *
     * <p>这里把下游真正用到的 4 个查询提升为 {@code FollowService} 的公开方法，
     * 下游改为依赖**本 Service**（域的服务契约）而非其缓存实现。
     * 缓存要优化、要换实现、要加空标记（见《遗留台账》B2），下游都不必知道。
     *
     * <p><b>性能性质必须保住</b>：{@code ContentStatusFiller} 的类注释写明
     * "一次列表请求只打一次缓存/DB（批量而非逐条）"——100 条推荐页若逐条判关注态就是 100 次往返。
     * 故批量方法 {@link #batchIsFollowing} 是**原样透传**批量语义，不是循环调用单条版本。
     */
    public boolean isFollowing(long userId, long followedUserId) {
        return followCache.isFollowing(userId, followedUserId);
    }

    /**
     * 批量关注态：{@code userId} 是否关注了 {@code followedUserIds} 中的每一个。
     *
     * <p>一趟 pipeline {@code ZSCORE}（命中）或一次 {@code IN} 查询（降级）——见 {@link #isFollowing} 的说明。
     *
     * @return id → 是否已关注；缺失的 id 表示未知（调用方按 false 处理）
     */
    public Map<Long, Boolean> batchIsFollowing(long userId, List<Long> followedUserIds) {
        return followCache.batchIsFollowing(userId, followedUserIds);
    }

    /** 关注数（三态读缓存，miss 回源 {@code users.follow_count}，Redis 故障降级 DB）。 */
    public int getFollowCount(long userId) {
        return followCache.getFollowCount(userId);
    }

    /** 粉丝数（同 {@link #getFollowCount}）。 */
    public int getFollowerCount(long userId) {
        return followCache.getFollowerCount(userId);
    }

    /**
     * 全量关注集（升序）——feed 两路读与纯拉降级都要"我关注的全体"（S9 补入）。
     *
     * <p>与上面 4 个查询同族：把 feed 真正需要的能力提升为 {@code FollowService} 的公开方法，
     * 下游依赖**本 Service**（域的服务契约）而非 {@code FollowCache}（域实现细节）——
     * ArchUnit 规则 2 禁止 feed 触碰 {@code follow.cache}。
     *
     * @return 用户关注的全部博主 id（升序）；无关注 → 空列表
     */
    public List<Long> getFollowingIds(long userId) {
        return followCache.getFollowingIds(userId);
    }

    /**
     * 批量判关注态后按 DB 返回序组装用户视图。
     *
     * <p>判重口径与 TV 一致：仍按传入的**该页 ids** 批量查缓存（**不因 {@code users} 为空而跳过**）
     * ——若某页 ids 对应的用户全被删了，仍要如实走完缓存这趟（口径一致胜过"省一次调用"）。
     *
     * <p>缺人被静默跳过：{@code users} 里没有的 id 不会出现在结果中（TV 原样行为）。
     */
    private List<FollowUserVO> buildUserViews(List<User> users, List<Long> ids, long currentUserId) {
        Map<Long, Boolean> followedMap = followCache.batchIsFollowing(currentUserId, ids);
        Set<Long> followedSet = new HashSet<>();
        for (Map.Entry<Long, Boolean> entry : followedMap.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                followedSet.add(entry.getKey());
            }
        }

        List<FollowUserVO> result = new ArrayList<>(users.size());
        for (User u : users) {
            result.add(new FollowUserVO(
                    u.getId(),
                    u.getUsername(),
                    followedSet.contains(u.getId()),
                    u.getId() == currentUserId));
        }
        return result;
    }
}
