package io.github.yjhhhaaa06.videoweb.follow.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.cache.SingleFlight;
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
 * <h2>★ 部分装载：T11-C 口径**已由第三批 T4 / 账 B7 搬回**</h2>
 * S4 曾按 F-7 回到 T7 口径（"数据 key 存在 ⇒ 完整"）并整条去掉 {@code partial:} 标记与
 * 四处配套特判；T4 按工单把 T11-C 搬回来，判据是**可观察行为不变、只改装载量**
 * （TV 原话："唯一差别是装载量"）。收益：某博主 100 万粉丝时，**冷 key 的第 1 页与第 N 页
 * 的装载量都 = O(offset+count)，而不是 O(列表总量)**。
 *
 * <p>四处配套特判**必须同时存在**，缺一处就是静默错答案而不是性能退化：
 * <ol>
 *   <li><b>窗口读</b>：miss → 前缀装载 {@code [0, offset+count)}；部分态页越界 → 先补齐
 *       {@code [W, offset+count)}（{@link #partialWindow}）。DB 返回**不足** want 即"已到底"
 *       → 清 {@code partial} 转完整态；取满 → 仍是前缀。</li>
 *   <li><b>判定</b>（{@link #isFollowing} / {@link #batchIsFollowing}）在部分态下
 *       **未命中必须回落 DB**——前缀里查不到 ≠ 不是成员。</li>
 *   <li><b>全量读</b>（{@link #getFollowingIds}）遇部分态**必须先补齐**——feed 依赖
 *       "全量关注 ids"，返回前缀会**静默漏人**（数据正确性问题）。</li>
 *   <li><b>写路径</b>任一侧部分态 ⇒ **整体失效**（{@code ZREM} 会在前缀里留洞、{@code ZADD}
 *       会插入非前缀成员，两者都破坏 {@code ZRANGE offset} 语义）。</li>
 * </ol>
 * 另：部分态 / 降级态的 {@code total} 走**域级计数口径**（{@link #getFollowCount}），
 * 完整态仍是 {@code ZCARD}（与 T7 **逐字节一致**）——{@link Window#total()} 与
 * {@link Window#ids()} **必须同源**，否则会出现"页里有第 11 条但 total 说只有 10 条"这类
 * 自相矛盾的信封。
 *
 * <h2>★ 计数缓存：S4 去掉、S5 补回（决策表 G-8）</h2>
 * S4 曾去掉 {@code user:followCount} / {@code user:followerCount}（理由：去掉 {@code partial} 后
 * 它只剩"降级路径取 total"一个用途），并写明"补回位置 = S5 迁 {@code ProfileService} 时"。
 * <b>S5 兑现</b>：{@code /profile} 直接需要这两个计数（{@link #getFollowCount} /
 * {@link #getFollowerCount}），降级路径的 total 也改走它们（恢复 TV 口径）。
 * ★ T4 搬回 {@code partial} 后，它们**又多了一个用途**：部分态的 total（ZCARD 只是前缀大小）。
 * 写入方是 {@code FollowChangedListener}（提交后）的条件写 —— 见 {@link #applyCountWrite}。
 *
 * <h2>本切片不搬的读接口（无主代码）</h2>
 * {@code getFollowingIds}（全量关注 id，调用方全是 feed 域）S4 时**不搬**——按"不搬无主代码"
 * 的纪律留在 feed 切片；S9 交付 feed 后按同一纪律**回收**（见方法注释）。
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
    static final Duration EMPTY_MARKER_TTL = Duration.ofSeconds(CacheKeys.EMPTY_MARKER_TTL_SECONDS);

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

    /** 部分装载标记：{@code partial:{dataKey}}（T11-C / 账 B7）。委托 {@link CacheKeys}。 */
    static String partialKey(String dataKey) {
        return CacheKeys.partial(dataKey);
    }

    /**
     * 窗口装载的单飞 key：带**窗口指纹**（{@code key@offset+count}）。
     *
     * <p>为什么不能用裸数据 key：前缀装载的负载**与页位置相关**（第 1 页装 10 条、第 5 页装 50 条），
     * 若共用同一在飞条目，第 5 页的请求会**共享第 1 页的装载结果**（10 条）⇒ 越界补齐白做、
     * 甚至把"前缀"当成"这页"返回。空标记 / 全量读仍用裸数据 key（两者负载与页无关，不冲突）。
     */
    private static String windowFlightKey(String dataKey, long offset, int count) {
        return dataKey + "@" + offset + "+" + count;
    }

    private final FollowRedisOps ops;
    private final FollowDao followDao;
    private final UserDao userDao;
    private final FollowCacheProperties props;
    /** 单飞（B1）：同 key 并发 miss 只回源一次。 */
    private final SingleFlight singleFlight;

    public FollowCache(FollowRedisOps ops, FollowDao followDao, UserDao userDao,
                       FollowCacheProperties props, SingleFlight singleFlight) {
        this.ops = ops;
        this.followDao = followDao;
        this.userDao = userDao;
        this.props = props;
        this.singleFlight = singleFlight;
    }

    // ========================================================================
    // 写：由 FollowChangedListener 在**事务提交后**调用（AFTER_COMMIT，决策表 F-4）
    // ========================================================================

    /**
     * 缓存：关注。条件双写（{@code user:following:{关注者}} += 被关注者、
     * {@code user:follower:{被关注者}} += 关注者），任一侧未加载 / **部分装载** ⇒ 整体失效让读自愈。
     *
     * <p>失败时**失效两个数据 key + 两个空标记 + 两个 partial**（而不是只失效其一）：写失败意味着
     * 两侧都可能陈旧，只失效一边会让另一边继续给出错答案。失效本身也是 Redis 操作，Redis 真挂时
     * 会再次失败——一并吞掉并记日志（不能让缓存操作影响已提交的业务）。
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
                        partialKey(following), partialKey(follower),
                        userId, followedUserId, props.followTtl());
            } else {
                ops.applyUnfollowWrite(following, follower, emptyKey(following), emptyKey(follower),
                        partialKey(following), partialKey(follower),
                        userId, followedUserId, props.followTtl());
            }
        } catch (CacheUnavailableException e) {
            log.warn("关注关系缓存写失败，失效整套让读自愈: userId={}, followedUserId={}, follow={}",
                    userId, followedUserId, follow, e);
            invalidateQuietly(following, follower);
        }
        // ★ G-8：计数的条件写（与关系写同属"提交后"的缓存维护）
        applyCountWrite(CacheKeys.userFollowCount(userId), follow ? 1 : -1);
        applyCountWrite(CacheKeys.userFollowerCount(followedUserId), follow ? 1 : -1);
    }

    /**
     * 计数 key 的**条件写**（G-8）：key 存在才 {@code INCRBY}。
     *
     * <p>为什么条件：与 like/follow 的关系写同一个理由——防残缺缓存。计数 key 不存在
     * （从未被读过）时写一个 1，会让读路径把它当权威值，而真实值可能是 37。
     * 于是这里什么都不做，读路径下次 miss 回源 DB 并回填。
     *
     * <p>计数 key **没有空标记**：0 是合法值，"不存在"才表示未知——两者靠 GET 返回
     * {@code null} / {@code "0"} 区分。
     */
    private void applyCountWrite(String countKey, int delta) {
        try {
            ops.applyCountConditionalWrite(countKey, delta);
        } catch (CacheUnavailableException e) {
            log.warn("关注计数缓存写失败，失效 key 让读自愈: key={}", countKey, e);
            invalidateQuietly(countKey);
        }
    }

    private void invalidateQuietly(String... dataKeys) {
        String[] keys = dataKeyAndMarkers(dataKeys);
        try {
            ops.delete(keys);
        } catch (CacheUnavailableException e) {
            log.warn("缓存失效也失败（Redis 不可用），交由 TTL 自愈: keys={}", List.of(keys));
        }
    }

    // ========================================================================
    // 读：计数（G-8 补回 —— S4 的 F-7 去掉，F-7 同时写明"S5 迁 ProfileService 时补回"）
    // ========================================================================

    /**
     * 我的关注数（{@code user:followCount:{userId}}）。
     *
     * <pre>
     * key 命中   → 返回值并续期
     * miss       → 回源 {@code users.follow_count} 列 → 回填
     * Redis 失败 → 降级直读列（不影响结果）
     * </pre>
     *
     * <p>口径与 TV 的 T6 R-01 一致：DB 是最终真理，缓存只是加速器。
     * 0 是**合法值**，必须与"key 不存在"区分——靠 {@code GET} 返回 {@code null} 识别 miss。
     */
    public int getFollowCount(long userId) {
        return count(CacheKeys.userFollowCount(userId), () -> userDao.getFollowCountById(userId));
    }

    /** 我的粉丝数（{@code user:followerCount:{userId}}）。逻辑同 {@link #getFollowCount}。 */
    public int getFollowerCount(long userId) {
        return count(CacheKeys.userFollowerCount(userId), () -> userDao.getFollowerCountById(userId));
    }

    private int count(String countKey, java.util.function.IntSupplier dbLoader) {
        try {
            String cached = ops.getString(countKey);
            if (cached != null) {
                ops.expire(countKey, props.followTtl());
                return Integer.parseInt(cached);
            }
        } catch (CacheUnavailableException e) {
            log.warn("关注计数缓存读失败，降级 DB: key={}", countKey, e);
            // DB 失败必须上抛（不被此 catch 吞掉）：loader 在本 catch 块内执行，异常直接冒泡
            return dbLoader.getAsInt();
        } catch (NumberFormatException e) {
            log.warn("关注计数缓存值非法，按 miss 处理并清除: key={}", countKey);
            invalidateQuietly(countKey);
        }
        // miss：DB 是真理源，回填；单飞（B1）让同 key 并发只回源一次
        return singleFlight.get(countKey, () -> {
            int loaded = dbLoader.getAsInt();
            try {
                ops.setString(countKey, String.valueOf(loaded), props.followTtl());
            } catch (CacheUnavailableException e) {
                log.warn("关注计数回填失败（不影响本次读结果，下次读重试）: key={}", countKey, e);
            }
            return loaded;
        });
    }

    // ========================================================================
    // 读：关注 / 粉丝列表的「按序窗口」（分页载体，T11-C 前缀装载）
    // ========================================================================

    /**
     * 关注列表的升序窗口：返回该页 ids 与总数。
     *
     * <pre>
     * 空标记命中 → 空窗口（total = 0）
     * 数据 key 命中且**完整** → 一趟 pipeline：ZRANGE[offset, offset+count) + ZCARD（并续期）
     * 数据 key 命中但**部分态** → 页落在已知前缀内直接 ZRANGE（total 走计数口径）；越界先补齐
     * miss         → **前缀装载** DB [0, offset+count)（不是全量！）后按上面两态作答
     * Redis 失败   → 降级：DB 窗口查询 + DB 计数（**不装载、不写回**）
     * </pre>
     *
     * <p>★ 与 T7 的差别**只在装载量**（对外行为逐字节一致）：miss 时只装 {@code [0, offset+count)}，
     * 于是"第 1 页"与"第 100 页"的装载成本相差 100 倍，而 T7 都是 O(列表总量)。
     *
     * @param offset 0 基起始下标；越界 ⇒ 空窗口但 **total 保留**（契约：前端据此判末页）
     */
    public Window getFollowingWindow(long userId, long offset, int count) {
        return window(followingKey(userId), offset, count,
                (from, size) -> followDao.findFollowedUserIdsWindow(userId, from, size),
                () -> getFollowCount(userId));      // G-8：降级/部分态的 total 走计数缓存（恢复 TV 口径）
    }

    /**
     * 粉丝列表的升序窗口：逻辑同 {@link #getFollowingWindow}，换 key 与 loader
     * （窗口查询由 {@code idx_followed_user_user} 支撑同序、免 filesort）。
     */
    public Window getFollowerWindow(long userId, long offset, int count) {
        return window(followerKey(userId), offset, count,
                (from, size) -> followDao.findFollowerUserIdsWindow(userId, from, size),
                () -> getFollowerCount(userId));
    }

    /**
     * 全量关注 id（升序）——**S9 补入**（feed 两路读 / 纯拉都要"我关注的全体"）。
     *
     * <pre>
     * 空标记命中       → 空列表（已确认无关注）
     * 数据 key 命中完整 → ZRANGE 0 -1（升序）
     * 数据 key 命中部分 → ★ **先补齐**（本方法的契约是"返回全部"，返回前缀会静默漏人）
     * miss             → 回源 DB 全量 → 原子回填 → 作答
     * Redis 失败       → 降级：DB 全量直查（不装载、不写回）
     * </pre>
     *
     * <p>类注释原写"S4 不搬它（调用方全是 feed 域）"——S9 交付 feed 后它**有主了**，
     * 按"不搬无主代码"的纪律回收（与 {@code FollowDao.getFollowerUserIdsAfter} 同一处置）。
     *
     * <p>⚠️ 部分态补齐时返回的是 **DB 装载结果本身（权威答案）**，不是"写回后回读 Redis"：
     * 写回是 best-effort，一旦失败，回读拿到的仍是被标记为前缀的那部分——那正是本方法要杜绝的漏人。
     */
    public List<Long> getFollowingIds(long userId) {
        String dataKey = followingKey(userId);
        String emptyKey = emptyKey(dataKey);
        String partialKey = partialKey(dataKey);
        try {
            Set<String> existing = ops.existingOf(dataKey, emptyKey, partialKey);
            if (existing.contains(emptyKey)) {
                return List.of();
            }
            if (existing.contains(dataKey) && !existing.contains(partialKey)) {
                // 完整态：ZRANGE 0 -1（score 升序 = 成员 id 升序）
                return new ArrayList<>(ops.zWindow(dataKey, 0, -1, props.followTtl()).ids());
            }
        } catch (CacheUnavailableException e) {
            log.warn("全量关注集缓存读失败，降级 DB: key={}", dataKey, e);
            // DB 失败必须上抛（不被此 catch 吞掉）：loader 在本 catch 块内执行，异常直接冒泡
            return sortedAsc(followDao.findAllFollowedUserIds(userId));
        }
        // miss 与部分态走**同一个动作**（全量装载）：miss 是"装载"，部分态是"补齐"——loader 相同、
        // 写回相同（原子替换整个集合 + 清两枚标记），差别只在调用方语义
        List<Long> all = singleFlight.get(dataKey, () -> {
            List<Long> loaded = sortedAsc(followDao.findAllFollowedUserIds(userId));
            backfillFull(dataKey, emptyKey, partialKey, loaded);
            return loaded;
        });
        // 防御性拷贝：单飞让并发者共享同一 List，调用方（feed 等）若原地改动会互相影响（TV 同样 new ArrayList）
        return new ArrayList<>(all);
    }

    private Window window(String dataKey, long offset, int count,
                          WindowLoader dbWindowLoader, CountLoader dbCountLoader) {
        if (count <= 0 || offset < 0) {
            return new Window(List.of(), 0L);
        }
        String emptyKey = emptyKey(dataKey);
        String partialKey = partialKey(dataKey);
        try {
            Set<String> existing = ops.existingOf(dataKey, emptyKey, partialKey);
            if (existing.contains(emptyKey)) {
                return new Window(List.of(), 0L);          // 已确认"无关注/无粉丝"
            }
            if (existing.contains(dataKey)) {
                if (existing.contains(partialKey)) {
                    return partialWindow(dataKey, partialKey, offset, count, dbWindowLoader, dbCountLoader);
                }
                return readCompleteWindow(dataKey, offset, count, dbWindowLoader, dbCountLoader);
            }
        } catch (CacheUnavailableException e) {
            log.warn("关注列表缓存读失败，降级 DB: key={}", dataKey, e);
            // 降级不放量（T11-C 口径）：DB **窗口直查**、不装载不写回；total 走域级计数口径
            return degradeWindow(dataKey, offset, count, dbWindowLoader, dbCountLoader);
        }
        // miss：**前缀装载**（只取 [0, offset+count)）；单飞 key 带窗口指纹，防不同页串用装载结果
        int want = (int) Math.min(offset + count, Integer.MAX_VALUE);
        Boolean complete = singleFlight.get(windowFlightKey(dataKey, offset, count), () -> {
            List<Long> loaded = sortedAsc(dbWindowLoader.load(0, want));
            boolean reachedEnd = loaded.size() < want;      // DB 返回不足 want = 已到底
            writeWindowLoad(dataKey, partialKey, loaded, reachedEnd);
            return reachedEnd;
        });
        if (Boolean.TRUE.equals(complete)) {
            return readCompleteWindow(dataKey, offset, count, dbWindowLoader, dbCountLoader);
        }
        return partialWindow(dataKey, partialKey, offset, count, dbWindowLoader, dbCountLoader);
    }

    /**
     * 部分态窗口读（{@code W = ZCARD} 为已装前缀水位）：
     *
     * <ul>
     *   <li>请求窗口越过 W → **先补齐** DB {@code [W, offset+count)} 并 ZADD 追加（合并，不 DEL）；
     *       DB 返回不足 want 即到底 ⇒ 清 partial 转完整态；</li>
     *   <li>补齐后仍是部分态、或请求窗口本就落在前缀内 → 直接 ZRANGE 该页，
     *       total 取**域级计数口径**（不能用 ZCARD——那只是已知前缀大小）。</li>
     * </ul>
     *
     * <p>补齐的上界**不依赖 total**（只看 DB 是否取满），避免计数漂移影响装载量。
     */
    private Window partialWindow(String dataKey, String partialKey, long offset, int count,
                                 WindowLoader dbWindowLoader, CountLoader dbCountLoader) {
        try {
            long known = ops.zCard(dataKey);
            if (offset + count > known) {
                Boolean complete = singleFlight.get(windowFlightKey(dataKey, offset, count), () -> {
                    long from = ops.zCard(dataKey);         // 单飞内**重读水位**，避免拿陈旧 W 重复追加
                    long need = offset + count - from;
                    int want = (int) Math.min(Math.max(need, 0L), Integer.MAX_VALUE);
                    List<Long> more = (want <= 0) ? List.of() : sortedAsc(dbWindowLoader.load(from, want));
                    boolean reachedEnd = more.size() < want;  // want<=0 时不确定是否到底 → 保持部分态
                    writeWindowLoad(dataKey, partialKey, more, reachedEnd);
                    return reachedEnd;
                });
                if (Boolean.TRUE.equals(complete)) {
                    return readCompleteWindow(dataKey, offset, count, dbWindowLoader, dbCountLoader);
                }
            }
            return new Window(ops.zPage(dataKey, partialKey, offset, offset + count - 1, props.followTtl()),
                    dbCountLoader.count());
        } catch (CacheUnavailableException e) {
            log.warn("关注列表部分态窗口读失败，降级 DB 窗口: key={}", dataKey, e);
            return degradeWindow(dataKey, offset, count, dbWindowLoader, dbCountLoader);
        }
    }

    /**
     * 完整态的窗口读：一趟 pipeline 取该页 + ZCARD。Redis 失败 ⇒ 降级 DB 窗口（不装载不写回）。
     *
     * <p>这里再包一层 try 是因为**前缀装载之后**也会走到它（那时数据已写好，但 Redis 可能刚好又抖）——
     * 不让这最后一跳把"缓存失败必须降级"的纪律漏掉。
     */
    private Window readCompleteWindow(String dataKey, long offset, int count,
                                      WindowLoader dbWindowLoader, CountLoader dbCountLoader) {
        try {
            FollowRedisOps.WindowRead hit = ops.zWindow(dataKey, offset, offset + count - 1, props.followTtl());
            return new Window(hit.ids(), hit.total());
        } catch (CacheUnavailableException e) {
            log.warn("完整态窗口读失败，降级 DB 窗口: key={}", dataKey, e);
            return degradeWindow(dataKey, offset, count, dbWindowLoader, dbCountLoader);
        }
    }

    /**
     * **降级窗口读**（Redis 失败时）：DB 窗口直查 + 域级计数，**不装载、不写回**（T11-C 口径）。
     *
     * <p>经单飞与 TV 一致（{@code ZSetCache.getWindow} 的降级分支同样包 {@code singleFlight}）：
     * Redis 整体故障时，同一页的并发请求只打一次 DB——那恰是最需要削峰的时刻。
     * 单飞 key 复用窗口指纹（同一页的负载相同），不会与其它页串用。
     *
     * <p>三种"最后一跳"（探测入口失败 / 部分态页读失败 / 装载后回读失败）共用本方法，
     * 保证"缓存失败必须降级"的纪律在三处**没有一处被漏掉**。
     */
    private Window degradeWindow(String dataKey, long offset, int count,
                                 WindowLoader dbWindowLoader, CountLoader dbCountLoader) {
        List<Long> ids = singleFlight.get(windowFlightKey(dataKey, offset, count),
                () -> dbWindowLoader.load(offset, count));
        return new Window(ids, dbCountLoader.count());
    }

    // ========================================================================
    // 读：批量关注状态（列表条目的 isFollowed）
    // ========================================================================

    /**
     * 批量判定 {@code userId} 对 {@code followedUserIds} 的关注状态。
     *
     * <pre>
     * 空标记命中 → 全 false
     * 数据 key 命中完整 → 一趟 pipeline 做 N×ZSCORE（保持 TV「命令数与装载量双降」的性质）
     * 数据 key 命中部分 → ZSCORE **命中即可信 true**，**未命中并入 DB 批量兜底**（前缀里查不到 ≠ 不是成员）
     * miss         → 回源 DB 全量 → 原子回填 → 作答
     * Redis 失败   → 降级：按请求 id 批量 DB 作答（IN + &lt;foreach&gt;），不为少量 id 拉全量
     * </pre>
     *
     * <p>部分态下**不回填全量**：那会让装载量重新放大到 O(列表总量)，与本口径的目的相悖
     * （但**仍要按请求子集问 DB**，否则会漏答）。
     *
     * @return 完整映射（**含 DB 兜底结果，无缺失**）；入参为空 ⇒ 空映射
     */
    public Map<Long, Boolean> batchIsFollowing(long userId, List<Long> followedUserIds) {
        if (followedUserIds == null || followedUserIds.isEmpty()) {
            return Map.of();
        }
        String dataKey = followingKey(userId);
        String emptyKey = emptyKey(dataKey);
        String partialKey = partialKey(dataKey);
        try {
            Set<String> existing = ops.existingOf(dataKey, emptyKey, partialKey);
            if (existing.contains(emptyKey)) {
                return toResultMap(followedUserIds, Set.of());
            }
            if (existing.contains(dataKey)) {
                List<Boolean> hits = ops.zMembersPresent(dataKey, followedUserIds, props.followTtl());
                Map<Long, Boolean> result = new LinkedHashMap<>();
                List<Long> misses = new ArrayList<>();
                boolean partial = existing.contains(partialKey);
                for (int i = 0; i < followedUserIds.size(); i++) {
                    if (hits.get(i)) {
                        result.put(followedUserIds.get(i), true);
                    } else if (partial) {
                        misses.add(followedUserIds.get(i));   // 部分态：未命中不可信 → 回落 DB
                    } else {
                        result.put(followedUserIds.get(i), false);
                    }
                }
                if (!misses.isEmpty()) {
                    // DB 失败必须上抛（不被降级吞掉）：这里没有 catch，异常直接冒泡
                    Set<Long> answered = followDao.findFollowedIdsIn(userId, misses);
                    for (Long id : misses) {
                        result.put(id, answered.contains(id));
                    }
                }
                return result;
            }
        } catch (CacheUnavailableException e) {
            log.warn("批量关注状态缓存读失败，降级 DB: key={}", dataKey, e);
            return toResultMap(followedUserIds, followDao.findFollowedIdsIn(userId, followedUserIds));
        }
        // miss：单飞（B1）——同一关注集的并发批量读只回源一次（负载是全量，与请求 id 子集无关）
        List<Long> all = singleFlight.get(dataKey, () -> {
            List<Long> loaded = sortedAsc(followDao.findAllFollowedUserIds(userId));
            backfillFull(dataKey, emptyKey, partialKey, loaded);
            return loaded;
        });
        return toResultMap(followedUserIds, new HashSet<>(all));
    }

    /**
     * 单条判定 {@code userId} 是否关注了 {@code followedUserId}。
     *
     * <pre>
     * 空标记命中 → false
     * 数据 key 命中完整 → 一趟 ZSCORE
     * 数据 key 命中部分 → ZSCORE 命中即 true；**未命中回落 DB 单行判定**（前缀里查不到 ≠ 不是成员）
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
        String partialKey = partialKey(dataKey);
        try {
            Set<String> existing = ops.existingOf(dataKey, emptyKey, partialKey);
            if (existing.contains(emptyKey)) {
                return false;
            }
            if (existing.contains(dataKey)) {
                List<Boolean> hits = ops.zMembersPresent(dataKey, List.of(followedUserId), props.followTtl());
                if (!hits.isEmpty() && hits.getFirst()) {
                    return true;                            // 命中即可信（部分态下同样可信）
                }
                if (existing.contains(partialKey)) {
                    // DB 失败必须上抛（不被降级吞掉）：本行没有 catch，异常直接冒泡
                    return followDao.isFollowing(userId, followedUserId);
                }
                return false;
            }
        } catch (CacheUnavailableException e) {
            log.warn("关注状态缓存读失败，降级 DB: key={}", dataKey, e);
            return followDao.isFollowing(userId, followedUserId);
        }
        // miss：单飞（B1）——同一关注集的并发单条判定只回源一次
        List<Long> all = singleFlight.get(dataKey, () -> {
            List<Long> loaded = sortedAsc(followDao.findAllFollowedUserIds(userId));
            backfillFull(dataKey, emptyKey, partialKey, loaded);
            return loaded;
        });
        return all.contains(followedUserId);
    }

    // ========================================================================
    // 内部：回填 / 装载写回 / 结果映射
    // ========================================================================

    /**
     * **全量**回填（best-effort：失败不影响本次读结果）：原子替换整个集合 + 清空标记 + 清 partial。
     *
     * <p>空集**不建数据 key**（Redis 里空 ZSet 不存在），改由 {@code markEmpty} 写空标记
     * （它同时清 partial）——这是防穿透的落点。
     */
    private void backfillFull(String dataKey, String emptyKey, String partialKey, List<Long> members) {
        try {
            if (members.isEmpty()) {
                ops.markEmpty(dataKey, emptyKey, partialKey, EMPTY_MARKER_TTL);
            } else {
                ops.backfillZSet(dataKey, emptyKey, partialKey, members, props.followTtl());
            }
        } catch (CacheUnavailableException e) {
            log.warn("关注列表回填失败（不影响本次读结果，下次读重试）: key={}", dataKey);
        }
    }

    /**
     * 窗口装载/补齐结果的写回（{@code ZADD 合并}，不 DEL——不能丢掉已装的更多成员）。
     *
     * <p>"DB 到底 且 本次一无所获"（{@code more} 为空且 {@code complete}）时：
     * 数据 key 不存在（首次装载就确认真空）⇒ 写空标记；存在（前缀补齐到尾部）⇒ 只清 partial。
     * 这两种情况都由 {@code markEmpty} 一个调用覆盖（它的守卫是"数据 key 不存在才写空标记"）。
     */
    private void writeWindowLoad(String dataKey, String partialKey, List<Long> members, boolean complete) {
        try {
            if (members.isEmpty() && complete) {
                ops.markEmpty(dataKey, emptyKey(dataKey), partialKey, EMPTY_MARKER_TTL);
            } else {
                ops.appendWindow(dataKey, partialKey, members, complete, props.followTtl());
            }
        } catch (CacheUnavailableException e) {
            log.warn("关注列表窗口装载写回失败（读自愈）: key={}", dataKey);
        }
    }

    /**
     * 统一升序（对外顺序契约 = 按 id 升序）。
     *
     * <p>为什么需要显式排序：两条**全量** loader 的 SQL **都没有 {@code ORDER BY}**（TV 原样），
     * 顺序由缓存侧 ZSet 的 {@code score = 成员 id} 归一。若回填前不排序，"miss 那次应答"
     * 与"后续命中缓存那次应答"可能顺序不同——分页"页间不重不漏"就会随缓存状态漂移。
     * （窗口 loader 自己有 {@code ORDER BY}，这里的排序只是防御性归一。）
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

    /**
     * 失效集 = **三件套**：数据 key + 空标记 + 部分装载标记（T11-C / 账 B7）。
     *
     * <p>三者必须一起删：数据 key 失效后残留的空标记会让读"假空"；残留的 partial 会让
     * **新装载的完整集合被误读为前缀**（total 失真、触发多余补齐）。TV 的
     * {@code invalidateKeysQuietly} 正是这个口径。
     */
    private static String[] dataKeyAndMarkers(String... dataKeys) {
        String[] keys = new String[dataKeys.length * 3];
        for (int i = 0; i < dataKeys.length; i++) {
            keys[3 * i] = dataKeys[i];
            keys[3 * i + 1] = emptyKey(dataKeys[i]);
            keys[3 * i + 2] = partialKey(dataKeys[i]);
        }
        return keys;
    }

    /**
     * 回源"该用户的窗口成员"（前缀装载与降级共用）。
     *
     * <p>返**不足 count 行即表示 DB 已到底**——这是"清 partial 转完整态"的判据
     * （上界不依赖 total，避免计数漂移影响装载量）。
     */
    @FunctionalInterface
    private interface WindowLoader {
        List<Long> load(long offset, int count);
    }

    /** 回源"该用户的总数"（部分态 / 降级态的 total；本域走计数缓存 {@code user:followCount}）。 */
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
     * <p>注意 total 的**口径随状态而异**：完整态 = {@code ZCARD}（与 ids 同一趟往返）；
     * 部分态 / 降级态 = 域级计数（成员集只是前缀，ZCARD 会低估）。
     */
    public record Window(List<Long> ids, long total) {
    }
}
