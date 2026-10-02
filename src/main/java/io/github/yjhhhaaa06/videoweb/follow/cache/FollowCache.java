package io.github.yjhhhaaa06.videoweb.follow.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.config.FollowCacheProperties;
import io.github.yjhhhaaa06.videoweb.follow.dao.FollowDao;
import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关注关系缓存（决策⑤「显式封装层」，**不用** {@code @Cacheable}）。
 *
 * <h2>骨架（与 TV {@code follow.service.FollowCache} 同构）</h2>
 * <ol>
 *   <li><b>DB 是唯一真理源</b>：读 miss → 回源 DB → 回填；写先落 DB、**提交后**才碰缓存
 *       （提交时机由 {@code FollowChangedListener} 的 {@code AFTER_COMMIT} 保证，见决策表 F-4）。</li>
 *   <li><b>两个 ZSet key 族</b>（用户维度）：
 *       <pre>
 *       user:following:{userId}   ZSet&lt;followedUserId&gt;，score = 成员 id  ⇒ 升序即"按 id 升序"
 *       user:follower:{userId}    ZSet&lt;userId&gt;，        score = 成员 id
 *       </pre>
 *       为什么必须是 **ZSet**（而不是 like 那种 Set）：分页要"按 id 升序取第 N 页"，ZSet 的
 *       {@code ZRANGE by rank} 天然给出该页成员，且**成本与列表总量弱相关**。
 *       用 Set 就得全量拉回内存再切片——那正是 TV 的 T7 A1 特意改掉的东西。</li>
 *   <li><b>空标记</b>：{@code empty:{dataKey}}（短 TTL 60s），承载"已确认无关注/无粉丝"。
 *       它不只是防穿透——**写路径也依赖它**：关注时要知道"这一侧是'已加载的空'而不是'没加载'"，
 *       才能安全地把空标记转成真实成员（见 {@link FollowRedisOps} 的条件写脚本）。</li>
 *   <li><b>失败降级不抛</b>：Redis 出问题 → 读直查 DB、写失效 key 让读自愈；
 *       <b>Redis 挂掉不会让关注接口 500</b>（TV NEEDS 4.2 / H5）。</li>
 * </ol>
 *
 * <h2>★ 两种失败必须区分（决策表 F-5）</h2>
 * <table>
 *   <caption>失败分类与处置</caption>
 *   <tr><th>失败</th><th>处置</th><th>理由</th></tr>
 *   <tr><td>Redis 失败（{@link CacheUnavailableException}）</td><td>降级直查 DB，不影响结果</td>
 *       <td>缓存只是加速器</td></tr>
 *   <tr><td>DB 失败（{@code DataAccessException}）</td><td><b>原样上抛</b>（→ 500）</td>
 *       <td>"无答案可答"。降级成空值会把"读不到"伪装成"没有"，是更坏的语义</td></tr>
 * </table>
 * 实现上靠**只 catch 自己的异常类型**来保证：{@link CacheUnavailableException} 只从
 * {@link FollowRedisOps} 冒出，DB 异常不经过它。
 *
 * <h2>相对 TV 的有意精简（逐条记录在决策表 F-7，此处只列要点）</h2>
 * <b>保留</b>：条件双写防残缺缓存（Lua 原子）、失败降级、TTL 与命中续期、有序窗口读、
 * 批量状态（一趟 pipeline）、三态读、空标记。
 * <b>去掉</b>：**单飞**（并发 miss 各打一次 DB，正确性不变）、**{@code partial} 标记**
 * （T11-C 的前缀窗口装载 ⇒ 本切片回到"key 存在即完整"的 T7 口径；影响与补回位置见 F-7）、
 * **计数缓存**（{@code user:followCount}/{@code user:followerCount} —— 去掉 partial 后它在本切片
 * 只剩"降级路径取 total"这一个用途，直查 DB 一列即可）、打点、熔断、TV 的通用
 * {@code CacheAside}/{@code ZSetCache} 框架层。
 *
 * <h2>本切片不搬的读接口（无主代码）</h2>
 * {@code getFollowingIds}（全量关注 id，调用方全是 feed 域）、单条 {@code isFollowing}
 * （调用方是 S5 的 {@code ContentStatusFiller}/{@code ProfileService}）——
 * 按"不搬无主代码"的纪律留在各自切片；{@code batchIsFollowing} 则**搬**（{@code FollowService} 在用）。
 */
@Slf4j
@Component
public class FollowCache {

    /**
     * 空标记的短 TTL，60 秒。口径照搬 TV {@code CacheKeys.EMPTY_MARKER_TTL_SECONDS}
     * （原注释："NEEDS 4.4 约定约 30s~5min，取 60s"）。
     *
     * <p>刻意比数据 key 的 TTL（30 分钟）**短得多**：空标记是"高置信度但短时效"的结论，
     * 让它尽快过期、回到"未知"再回源一次 DB，比长期断言"什么都没有"更安全。
     * 命中读**不续期**空标记（TV 同口径）。
     */
    static final Duration EMPTY_MARKER_TTL = Duration.ofSeconds(60);

    /** 我关注了谁：{@code user:following:{userId}}（ZSet，score = followedUserId）。委托 {@link CacheKeys}。 */
    static String followingKey(long userId) {
        return CacheKeys.userFollowing(userId);
    }

    /** 谁关注了我：{@code user:follower:{userId}}（ZSet，score = userId）。委托 {@link CacheKeys}。 */
    static String followerKey(long userId) {
        return CacheKeys.userFollower(userId);
    }

    /** 空标记：{@code empty:{dataKey}}。委托 {@link CacheKeys}。 */
    static String emptyKey(String dataKey) {
        return CacheKeys.empty(dataKey);
    }

    private final FollowRedisOps ops;
    private final FollowDao followDao;
    private final UserDao userDao;
    private final FollowCacheProperties props;

    public FollowCache(FollowRedisOps ops, FollowDao followDao, UserDao userDao, FollowCacheProperties props) {
        this.ops = ops;
        this.followDao = followDao;
        this.userDao = userDao;
        this.props = props;
    }

    // ========================================================================
    // 写：由 FollowChangedListener 在**事务提交后**调用（AFTER_COMMIT，决策表 F-4）
    // ========================================================================

    /**
     * 缓存：关注。条件双写（{@code user:following:{关注者}} += 被关注者、
     * {@code user:follower:{被关注者}} += 关注者），任一侧未加载 ⇒ 整体失效让读自愈。
     *
     * <p>失败时**失效两个数据 key + 两个空标记**（而不是只失效其一）：写失败意味着两侧都可能陈旧，
     * 只失效一边会让另一边继续给出错答案。失效本身也是 Redis 操作，Redis 真挂时会再次失败——
     * 一并吞掉并记日志（不能让缓存操作影响已提交的业务）。
     */
    public void cacheFollow(long userId, long followedUserId) {
        applyWrite(userId, followedUserId, true);
    }

    /** 缓存：取关。口径同 {@link #cacheFollow}（{@code ZREM} 两方向）。 */
    public void cacheUnfollow(long userId, long followedUserId) {
        applyWrite(userId, followedUserId, false);
    }

    private void applyWrite(long userId, long followedUserId, boolean follow) {
        String following = followingKey(userId);
        String follower = followerKey(followedUserId);
        try {
            if (follow) {
                ops.applyFollowWrite(following, follower, emptyKey(following), emptyKey(follower),
                        userId, followedUserId, props.followTtl());
            } else {
                ops.applyUnfollowWrite(following, follower, emptyKey(following), emptyKey(follower),
                        userId, followedUserId, props.followTtl());
            }
        } catch (CacheUnavailableException e) {
            log.warn("关注关系缓存写失败，失效四件套让读自愈: userId={}, followedUserId={}, follow={}",
                    userId, followedUserId, follow, e);
            invalidateQuietly(following, follower);
        }
    }

    private void invalidateQuietly(String... dataKeys) {
        String[] keys = dataKeyAndEmptyMarker(dataKeys);
        try {
            ops.delete(keys);
        } catch (CacheUnavailableException e) {
            log.warn("缓存失效也失败（Redis 不可用），交由 TTL 自愈: keys={}", List.of(keys));
        }
    }

    // ========================================================================
    // 读：关注 / 粉丝列表的「按序窗口」（分页载体）
    // ========================================================================

    /**
     * 关注列表的升序窗口：返回该页 ids 与总数。
     *
     * <pre>
     * 空标记命中 → 空窗口（total = 0）
     * 数据 key 命中 → 一趟 pipeline：ZRANGE[offset, offset+count) + ZCARD（并续期）
     * miss         → 回源 DB 全量 → 原子回填 → 内存切片
     * Redis 失败   → 降级：DB 窗口查询 + DB 计数（**不装载、不写回**）
     * </pre>
     *
     * @param offset 0 基起始下标；越界 ⇒ 空窗口但 **total 保留**（契约：前端据此判末页）
     */
    public Window getFollowingWindow(long userId, long offset, int count) {
        return window(followingKey(userId), offset, count,
                () -> followDao.findAllFollowedUserIds(userId),
                () -> followDao.findFollowedUserIdsWindow(userId, offset, count),
                () -> userDao.getFollowCountById(userId));
    }

    /**
     * 粉丝列表的升序窗口：逻辑同 {@link #getFollowingWindow}，换 key 与 loader
     * （窗口查询由 {@code idx_followed_user_user} 支撑同序、免 filesort）。
     */
    public Window getFollowerWindow(long userId, long offset, int count) {
        return window(followerKey(userId), offset, count,
                () -> followDao.findAllFollowerUserIds(userId),
                () -> followDao.findFollowerUserIdsWindow(userId, offset, count),
                () -> userDao.getFollowerCountById(userId));
    }

    private Window window(String dataKey, long offset, int count,
                          FullLoader fullLoader, WindowLoader dbWindowLoader, CountLoader dbCountLoader) {
        if (count <= 0 || offset < 0) {
            return new Window(List.of(), 0L);
        }
        String emptyKey = emptyKey(dataKey);
        try {
            Set<String> existing = ops.existingOf(dataKey, emptyKey);
            if (existing.contains(emptyKey)) {
                return new Window(List.of(), 0L);          // 已确认"无关注/无粉丝"
            }
            if (existing.contains(dataKey)) {
                FollowRedisOps.WindowRead hit = ops.zWindow(dataKey, offset, offset + count - 1, props.followTtl());
                return new Window(hit.ids(), hit.total());
            }
        } catch (CacheUnavailableException e) {
            log.warn("关注列表缓存读失败，降级 DB: key={}", dataKey, e);
            // DB 失败必须上抛（不被此 catch 吞掉）：loader 在本 catch 块内执行，异常直接冒泡
            return new Window(dbWindowLoader.load(), dbCountLoader.count());
        }
        // miss：DB 是真理源，顺带回填（回填失败不影响本次结果）
        List<Long> all = sortedAsc(fullLoader.load());
        backfillQuietly(dataKey, emptyKey, all);
        return new Window(slice(all, offset, count), (long) all.size());
    }

    // ========================================================================
    // 读：批量关注状态（列表条目的 isFollowed）
    // ========================================================================

    /**
     * 批量判定 {@code userId} 对 {@code followedUserIds} 的关注状态。
     *
     * <pre>
     * 空标记命中 → 全 false
     * 数据 key 命中 → 一趟 pipeline 做 N×ZSCORE（保持 TV「命令数与装载量双降」的性质）
     * miss         → 回源 DB 全量 → 原子回填 → 作答
     * Redis 失败   → 降级：按请求 id 批量 DB 作答（IN + &lt;foreach&gt;），不为少量 id 拉全量
     * </pre>
     *
     * @return 完整映射（**含 DB 兜底结果，无缺失**）；入参为空 ⇒ 空映射
     */
    public Map<Long, Boolean> batchIsFollowing(long userId, List<Long> followedUserIds) {
        if (followedUserIds == null || followedUserIds.isEmpty()) {
            return Map.of();
        }
        String dataKey = followingKey(userId);
        String emptyKey = emptyKey(dataKey);
        try {
            Set<String> existing = ops.existingOf(dataKey, emptyKey);
            if (existing.contains(emptyKey)) {
                return toResultMap(followedUserIds, Set.of());
            }
            if (existing.contains(dataKey)) {
                List<Boolean> hits = ops.zMembersPresent(dataKey, followedUserIds, props.followTtl());
                Map<Long, Boolean> result = new LinkedHashMap<>();
                for (int i = 0; i < followedUserIds.size(); i++) {
                    result.put(followedUserIds.get(i), hits.get(i));
                }
                return result;
            }
        } catch (CacheUnavailableException e) {
            log.warn("批量关注状态缓存读失败，降级 DB: key={}", dataKey, e);
            return toResultMap(followedUserIds, followDao.findFollowedIdsIn(userId, followedUserIds));
        }
        List<Long> all = sortedAsc(followDao.findAllFollowedUserIds(userId));
        backfillQuietly(dataKey, emptyKey, all);
        return toResultMap(followedUserIds, new HashSet<>(all));
    }

    /**
     * 单条判定 {@code userId} 是否关注了 {@code followedUserId}。
     *
     * <pre>
     * 空标记命中 → false
     * 数据 key 命中 → 一趟 ZSCORE
     * miss         → 回源 DB 全量 → 原子回填 → 作答
     * Redis 失败   → 降级：单条 DB 判定（不为 1 个 id 拉全量）
     * </pre>
     *
     * <p><b>S5 补入</b>（S4 时它没有调用方——F-7 明确记："单条 {@code isFollowing}
     * 的调用方是 S5 的 {@code ContentStatusFiller}/{@code ProfileService}"）。
     * 现由详情页的 {@code isFollowed} 使用。
     */
    public boolean isFollowing(long userId, long followedUserId) {
        String dataKey = followingKey(userId);
        String emptyKey = emptyKey(dataKey);
        try {
            Set<String> existing = ops.existingOf(dataKey, emptyKey);
            if (existing.contains(emptyKey)) {
                return false;
            }
            if (existing.contains(dataKey)) {
                List<Boolean> hits = ops.zMembersPresent(dataKey, List.of(followedUserId), props.followTtl());
                return !hits.isEmpty() && hits.getFirst();
            }
        } catch (CacheUnavailableException e) {
            log.warn("关注状态缓存读失败，降级 DB: key={}", dataKey, e);
            // DB 失败必须上抛（不被此 catch 吞掉）：loader 在本 catch 块内执行，异常直接冒泡
            return followDao.isFollowing(userId, followedUserId);
        }
        List<Long> all = sortedAsc(followDao.findAllFollowedUserIds(userId));
        backfillQuietly(dataKey, emptyKey, all);
        return all.contains(followedUserId);
    }

    // ========================================================================
    // 内部：回填 / 切片 / 结果映射
    // ========================================================================

    /**
     * 回填（best-effort：失败不影响本次读结果）。
     *
     * <p>空集**不建数据 key**（Redis 里空 ZSet 不存在），改写空标记——这是防穿透的落点；
     * 非空集走原子替换（{@code DEL + ZADD×N + EXPIRE}），保证并发读者不会看到半套成员。
     */
    private void backfillQuietly(String dataKey, String emptyKey, List<Long> members) {
        try {
            if (members.isEmpty()) {
                ops.markEmpty(dataKey, emptyKey, EMPTY_MARKER_TTL);
            } else {
                ops.backfillZSet(dataKey, emptyKey, members, props.followTtl());
            }
        } catch (CacheUnavailableException e) {
            log.warn("关注列表回填失败（不影响本次读结果，下次读重试）: key={}", dataKey);
        }
    }

    /** 内存切片（miss 回填后的本次作答；越界 ⇒ 空列表）。 */
    private static List<Long> slice(List<Long> all, long offset, int count) {
        if (offset >= all.size()) {
            return List.of();
        }
        int from = (int) offset;
        int to = (int) Math.min((long) from + count, all.size());
        return new ArrayList<>(all.subList(from, to));
    }

    /**
     * 统一升序（对外顺序契约 = 按 id 升序）。
     *
     * <p>为什么需要显式排序：两条全量 loader 的 SQL **都没有 {@code ORDER BY}**（TV 原样），
     * 顺序由缓存侧 ZSet 的 {@code score = 成员 id} 归一。若回填前不排序，"miss 那次应答"
     * 与"后续命中缓存那次应答"可能顺序不同——分页"页间不重不漏"就会随缓存状态漂移。
     */
    private static List<Long> sortedAsc(List<Long> ids) {
        List<Long> copy = new ArrayList<>(ids);
        copy.sort(Long::compareTo);
        return copy;
    }

    private static Map<Long, Boolean> toResultMap(List<Long> targetIds, Set<Long> followedIds) {
        Map<Long, Boolean> result = new LinkedHashMap<>();
        for (Long id : targetIds) {
            result.put(id, followedIds.contains(id));
        }
        return result;
    }

    private static String[] dataKeyAndEmptyMarker(String... dataKeys) {
        String[] keys = new String[dataKeys.length * 2];
        for (int i = 0; i < dataKeys.length; i++) {
            keys[2 * i] = dataKeys[i];
            keys[2 * i + 1] = emptyKey(dataKeys[i]);
        }
        return keys;
    }

    /** 回源"该用户的全量成员"（回填用）。 */
    @FunctionalInterface
    private interface FullLoader {
        List<Long> load();
    }

    /** 回源"该用户的窗口成员"（降级用，避免为了一页拉全量）。 */
    @FunctionalInterface
    private interface WindowLoader {
        List<Long> load();
    }

    /** 回源"该用户的总数"（降级用；本切片直读 {@code users.follow_count} 列）。 */
    @FunctionalInterface
    private interface CountLoader {
        int count();
    }

    /**
     * 一次窗口读的对外产物：升序成员 + 总数。
     *
     * <p>与 TV 的 {@code ZSetCache.Window} 同形（{@code getIds()} / {@code getTotal()}）。
     * 两者**必须同源**——{@code total} 不得来自另一次快照计数（否则会出现"页里有第 11 条，
     * 但 total 说只有 10 条"这类自相矛盾的信封）。
     */
    public record Window(List<Long> ids, long total) {
    }
}
