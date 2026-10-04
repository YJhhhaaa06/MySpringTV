package io.github.yjhhhaaa06.videoweb.follow.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.RedisCircuitBreaker;
import io.github.yjhhhaaa06.videoweb.common.cache.RedisOps;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 关注缓存的 **Redis 命令层** —— 只负责"把命令发出去"，不含任何缓存语义
 * （三态判断 / 回源 / 回填 / 降级都在 {@link FollowCache}）。
 *
 * <h2>S5 起的分工（决策表 G-6：rule of three 收敛）</h2>
 * S4 时本类与 {@code LikeRedisOps} 各持一份逐字重复的协议代码（{@code guarded}/{@code guardedVoid}、
 * {@code delete}/{@code expire}/{@code getString}/{@code setString}/{@code keyExists}）。
 * S4 的 F-7 预定"等 S5 的第 3 个使用方再抽"，S5 兑现：通用协议上移到
 * {@link RedisOps} **基类**，本类只留**域内**命令——ZSet（{@code score = 成员 id} ⇒ 有序）、
 * 四次 Lua 条件写 / 回填 / 空标记。
 *
 * <p>收敛是**纯搬迁**：命令语义、Lua 脚本、TTL 口径一字未改。
 *
 * <h2>★ 部分装载（T11-C / 账 B7，第三批 T4 补回）</h2>
 * 窗口读不再"全量装载 + 内存切片"，而是**前缀装载**：miss 只取 {@code [0, offset+count)}，
 * 部分态只补 {@code [W, offset+count)}（{@code W = ZCARD}）。集合不变量从
 * "数据 key 存在 ⇒ 完整"放宽为"**无 {@code partial:} 标记 ⇒ 完整**"。
 * 本类为此提供四个命令：{@link #existingOf(String, String, String)}（三态探测，含 partial）、
 * {@link #zCard(String)}（前缀水位）、{@link #zPage}（部分态的页读 + 双续期）、
 * {@link #appendWindow}（ZADD **合并** + 标记切换）。写路径的两条 Lua 也各增两个 partial key，
 * 任一侧处于部分态即**整体失效**（前缀里 ZREM 会留洞、ZADD 会插入非前缀成员，都破坏偏移语义）。
 *
 * <h2>为什么用 Lua 而不是 Pipeline / MULTI</h2>
 * <ul>
 *   <li><b>条件写</b>："只在 key 已加载时才 ZADD/ZREM"是**防残缺缓存**的关键。若写成
 *       "先 EXISTS 探一下、再写"，两步之间可能被并发失效命令插入（TV 三期 N7 记录过这个窗口）。
 *       Lua 在服务端原子执行，窗口消失——本类把 TV 的"probe + MULTI + 失败双 DEL"三条往返
 *       **收成一条 Lua**：读状态、决定、写、失效都在脚本内完成，判定与落地之间没有缝隙。</li>
 *   <li><b>回填</b>：{@code DEL + ZADD×N + EXPIRE} 一次执行完。用"逐个 ZADD"会让并发读者
 *       命中一个**部分填充**的集合，从而把"已关注"误判成未关注；原子替换后读者要么看到
 *       "key 不存在"（→ 自己也回源 DB，答案正确），要么看到完整集合。</li>
 * </ul>
 *
 * <p>对照 TV {@code FollowCache}：它用 Jedis 原生 {@code Pipeline}/{@code Transaction}（{@code MULTI}），
 * 那是自研连接池时代的写法；Spring Data Redis 下同类保证由 Lua 提供，且少一次往返。
 */
@Component
public class FollowRedisOps extends RedisOps {

    // ==================== Lua 脚本 ====================

    /**
     * 关注：**条件双写**（原子）。KEYS:
     * {@code [followingKey, followerKey, emptyFollowing, emptyFollower, partialFollowing, partialFollower]}；
     * ARGV: {@code [userId, followedUserId, ttlSeconds]}。
     *
     * <p>"已加载"= 数据 key 存在 **或** 空标记存在。"无关注"这一事实由空标记承载
     * （收在这里有真实用途：关注时要把空标记转成真实成员，靠它才能安全地做增量写）。
     *
     * <p>三种原因导致不值得增量写 → **整体失效**（DEL 六件套）让读自愈回填：
     * ① 任一侧冷 key（未加载）；② ⚠️ 不能只 DEL 数据 key——残留的空标记会让读"假空"；
     * ③ ★ **任一侧处于部分装载态**（T11-C / 账 B7）：集合只是前缀，往前缀里插一个非前缀成员
     * 会破坏 {@code ZRANGE offset} 的偏移语义 ⇒ 只剩"整体失效让读按需重装"这一条安全路径。
     */
    private static final String FOLLOW_WRITE_LUA =
            "local followingReady = redis.call('EXISTS', KEYS[1]) "
            + "local followingEmpty = redis.call('EXISTS', KEYS[3]) "
            + "local followingPartial = redis.call('EXISTS', KEYS[5]) "
            + "local followerReady = redis.call('EXISTS', KEYS[2]) "
            + "local followerEmpty = redis.call('EXISTS', KEYS[4]) "
            + "local followerPartial = redis.call('EXISTS', KEYS[6]) "
            + "if (followingReady == 1 or followingEmpty == 1) and followingPartial == 0 "
            + "   and (followerReady == 1 or followerEmpty == 1) and followerPartial == 0 then "
            + "  if followingEmpty == 1 then redis.call('DEL', KEYS[3]) end "
            + "  if followerEmpty == 1 then redis.call('DEL', KEYS[4]) end "
            + "  redis.call('ZADD', KEYS[1], ARGV[2], ARGV[2]) "
            + "  redis.call('ZADD', KEYS[2], ARGV[1], ARGV[1]) "
            + "  redis.call('EXPIRE', KEYS[1], ARGV[3]) "
            + "  redis.call('EXPIRE', KEYS[2], ARGV[3]) "
            + "  return 1 "
            + "end "
            + "redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5], KEYS[6]) "
            + "return 0";

    /**
     * 取关：条件双写（原子）。KEYS / ARGV 同 {@link #FOLLOW_WRITE_LUA}。
     *
     * <p>与关注**不对称**（TV 原样口径）：只有"两侧都**已加载真实集合**"才做 {@code ZREM}。
     * 任一侧是空标记 ⇒ 说明该侧"确认无关系"，与"我要取关"矛盾 ⇒ 缓存不可信，整体失效让读对齐 DB。
     * ★ 任一侧**部分装载态**同样整体失效（T11-C / 账 B7）：取关会在前缀里留一个"洞"
     * （被删的成员若在前缀内，{@code ZRANGE offset} 之后的偏移全部错位）。
     */
    private static final String UNFOLLOW_WRITE_LUA =
            "local followingReady = redis.call('EXISTS', KEYS[1]) "
            + "local followingEmpty = redis.call('EXISTS', KEYS[3]) "
            + "local followingPartial = redis.call('EXISTS', KEYS[5]) "
            + "local followerReady = redis.call('EXISTS', KEYS[2]) "
            + "local followerEmpty = redis.call('EXISTS', KEYS[4]) "
            + "local followerPartial = redis.call('EXISTS', KEYS[6]) "
            + "if followingReady == 1 and followerReady == 1 "
            + "   and followingEmpty == 0 and followerEmpty == 0 "
            + "   and followingPartial == 0 and followerPartial == 0 then "
            + "  redis.call('ZREM', KEYS[1], ARGV[2]) "
            + "  redis.call('ZREM', KEYS[2], ARGV[1]) "
            + "  redis.call('EXPIRE', KEYS[1], ARGV[3]) "
            + "  redis.call('EXPIRE', KEYS[2], ARGV[3]) "
            + "  return 1 "
            + "end "
            + "redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5], KEYS[6]) "
            + "return 0";

    /**
     * 原子回填**整个** ZSet（含 TTL），并清掉空标记与部分装载标记：
     * {@code DEL + ZADD score=成员 ×N + EXPIRE + DEL empty + DEL partial}。
     * KEYS: {@code [dataKey, emptyKey, partialKey]}；ARGV: {@code [ttlSeconds, member...]}（成员即 score）。
     *
     * <p>用于"全量读 miss"与"部分态补齐"——两者都是"本次拿到了全集"，故用**原子替换**
     * （读者要么看到 key 不存在、要么看到完整集合，不会命中半套）。
     * 空集**不走本脚本**（Redis 里空 ZSet 不存在），改由 {@link #markEmpty} 写空标记。
     */
    private static final String BACKFILL_ZSET_LUA =
            "redis.call('DEL', KEYS[1]) "
            + "for i = 2, #ARGV do redis.call('ZADD', KEYS[1], ARGV[i], ARGV[i]) end "
            + "redis.call('EXPIRE', KEYS[1], ARGV[1]) "
            + "redis.call('DEL', KEYS[2]) "
            + "redis.call('DEL', KEYS[3]) "
            + "return 1";

    /**
     * 窗口装载/补齐结果的写回（原子，T11-C / 账 B7）：
     * {@code ZADD 合并×N（不 DEL，漏掉并发写）+ EXPIRE + [complete ? DEL partial : SETEX partial]}。
     * KEYS: {@code [dataKey, partialKey]}；ARGV: {@code [ttlSeconds, completeFlag(0|1), member...]}。
     *
     * <p>为什么这里**合并而不是替换**：本方法与 {@link #BACKFILL_ZSET_LUA} 的差别正是"我们手上的
     * 是不是全集"。窗口装载只拿到**前缀**，替换会把此前已装的更多成员丢掉；合并天然是"取并集"，
     * 而并集的每一份来源都是同一份 DB 真相 ⇒ 结果单调不减、无中间态可被误读。
     *
     * <p>⚠️ **标记在最后一步切**：先落成员再改标记。若顺序反了（先 DEL partial 再 ZADD），
     * 并发读者会看到"完整态 + 更短的集合"，{@code ZCARD} 报出的 total 就偏小。
     *
     * <p>⚠️ {@code completeFlag=0} 且数据 key 不存在时**不写 partial**（防御性）：
     * 留下"只有 partial 没有数据"的悬空标记没有意义（读路径按 miss 处理、下次装载还会清它），
     * 只会让 key 空间多一个噪声。TV 无此守卫，此处收紧且**不改变可观察行为**。
     */
    private static final String APPEND_WINDOW_LUA =
            "for i = 3, #ARGV do redis.call('ZADD', KEYS[1], ARGV[i], ARGV[i]) end "
            + "if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
            + "if ARGV[2] == '1' then "
            + "  redis.call('DEL', KEYS[2]) "
            + "elseif redis.call('EXISTS', KEYS[1]) == 1 then "
            + "  redis.call('SETEX', KEYS[2], ARGV[1], '1') "
            + "end "
            + "return 1";

    /**
     * 写空标记（原子）：只在**数据 key 不存在**时才写，避免覆盖并发写入的真数据；
     * **无论走哪个分支都清掉部分装载标记**（已确认无数据的集合不该再被当成"前缀"）。
     * KEYS: {@code [dataKey, emptyKey, partialKey]}；ARGV: {@code [emptyTtlSeconds]}。
     *
     * <p>对照 TV {@code CacheAside.markEmpty}：它接受了一个"exists 检查 → setex 之间的毫秒间隙内
     * 并发写入时，空标记可能覆盖其上"的残余竞态（靠 60s 过期 / 下次业务写自愈）。
     * 这里用 Lua 把两步行成一步，**竞态消失、行为不变**（不是行为改动）。
     * TV 是在 {@code writeWindowLoad} 里分两步做（markEmpty + clearPartial），合并后语义相同。
     */
    private static final String MARK_EMPTY_LUA =
            "if redis.call('EXISTS', KEYS[1]) == 0 then "
            + "  redis.call('SETEX', KEYS[2], ARGV[1], '1') "
            + "end "
            + "redis.call('DEL', KEYS[3]) "
            + "return 1";

    /**
     * 计数 key 的条件写（S5 / G-8 补回）：**只在计数 key 已加载时**才 INCRBY。
     * KEYS: {@code [countKey]}；ARGV: {@code [delta]}。
     *
     * <p>为什么条件：与 like 的条件写同一个理由（防残缺缓存）。计数 key 不存在 ⇒ 说明
     * "这个计数从未被读过/装载过"，此刻写一个"1"会让读路径把它当成权威值，
     * 而真实值可能是 37。故 key 不存在时**什么都不做**（读路径会回源 DB 并回填）。
     */
    private static final String COUNT_CONDITIONAL_LUA =
            "if redis.call('EXISTS', KEYS[1]) == 1 then "
            + "  redis.call('INCRBY', KEYS[1], ARGV[1]) "
            + "  return 1 "
            + "end "
            + "return 0";

    private static final DefaultRedisScript<Long> FOLLOW_WRITE_SCRIPT =
            new DefaultRedisScript<>(FOLLOW_WRITE_LUA, Long.class);
    private static final DefaultRedisScript<Long> UNFOLLOW_WRITE_SCRIPT =
            new DefaultRedisScript<>(UNFOLLOW_WRITE_LUA, Long.class);
    private static final DefaultRedisScript<Long> BACKFILL_ZSET_SCRIPT =
            new DefaultRedisScript<>(BACKFILL_ZSET_LUA, Long.class);
    private static final DefaultRedisScript<Long> APPEND_WINDOW_SCRIPT =
            new DefaultRedisScript<>(APPEND_WINDOW_LUA, Long.class);
    private static final DefaultRedisScript<Long> MARK_EMPTY_SCRIPT =
            new DefaultRedisScript<>(MARK_EMPTY_LUA, Long.class);
    private static final DefaultRedisScript<Long> COUNT_CONDITIONAL_SCRIPT =
            new DefaultRedisScript<>(COUNT_CONDITIONAL_LUA, Long.class);

    public FollowRedisOps(StringRedisTemplate redis, RedisCircuitBreaker breaker) {
        super(redis, breaker);
    }

    // ==================== 读 ====================

    /**
     * 一趟 pipeline 判断**三个** key 是否存在（数据 / 空标记 / 部分装载标记），
     * 返回**存在**的那些 key（顺序为入参顺序）。
     * 委托基类的 {@link RedisOps#existingKeys(String...)}（协议层已收敛）。
     *
     * <p>三态读要同时知道三者；分三次独立往返会把判定成本翻三倍。
     * 保留**定长三参**签名（而非直接用 varargs）：调用面只有这一种形态，
     * 定长签名让桩与断言无歧义（varargs 的匹配语义容易写出"看着对、其实没匹配上"的桩）。
     *
     * <p>★ 第三参（partial）是 T11-C / 账 B7 补回的：没有它就无法区分"命中完整集合"与
     * "命中前缀"——后者下 {@code ZSCORE} 未命中**不可信**、{@code total} 也不能用 ZCARD。
     */
    public Set<String> existingOf(String key1, String key2, String key3) {
        return existingKeys(key1, key2, key3);
    }

    /**
     * 已知成员数（{@code ZCARD}）。**只在部分态下有意义**（那时它 = 已装前缀水位 W）；
     * 完整态的 total 走 {@link #zWindow}（与成员同源同一趟往返）。
     */
    public long zCard(String key) {
        Long card = guarded(() -> redis.opsForZSet().zCard(key));
        return card == null ? 0L : card;
    }

    /**
     * **部分态**的窗口读：只取该页成员（**不取 ZCARD**——那只是已知前缀 W，会低估 total），
     * 并顺带续期数据 key 与部分装载标记（标记掉队会让"完整集被当成前缀"，反之亦然）。
     */
    public List<Long> zPage(String key, String partialKey, long offset, long stop, Duration ttl) {
        List<Object> raw = pipeline(ops -> {
            ops.opsForZSet().range(key, offset, stop);
            ops.expire(key, ttl);
            ops.expire(partialKey, ttl);
        });
        return toLongList(raw.get(0));
    }

    /**
     * 命中路径的**窗口读**：一趟 pipeline 取 {@code [offset, stop]} 的升序成员、总数 {@code ZCARD}，
     * 并**滑动续期**。
     *
     * <p>为什么必须一趟：TV 的 T7 A1 目标就是"分页成本与列表总量弱相关"——{@code ZRANGE} 只取该页
     * 成员、{@code ZCARD} 只取总数，两者合起来一次往返。退化成多次独立调用会让"每次翻页 N 个往返"
     * 的性质悄悄回来。
     *
     * @return 升序成员（可能为空——越界页）与总数
     */
    public WindowRead zWindow(String key, long offset, long stop, Duration ttl) {
        List<Object> raw = pipeline(ops -> {
            ops.opsForZSet().range(key, offset, stop);
            ops.opsForZSet().zCard(key);
            ops.expire(key, ttl);
        });
        List<Long> ids = toLongList(raw.get(0));
        Long total = raw.size() > 1 && raw.get(1) instanceof Number n ? n.longValue() : 0L;
        return new WindowRead(ids, total);
    }

    /**
     * 一趟 pipeline 判定多个成员是否在 ZSet 内，返回与入参**同序**的布尔列表。
     *
     * <p>用 {@code ZSCORE}（而非 {@code ZRANK}）：只需"在不在"，且 {@code ZSCORE} 不依赖偏移语义。
     */
    public List<Boolean> zMembersPresent(String key, List<Long> members, Duration ttl) {
        List<Object> raw = pipeline(ops -> {
            for (Long member : members) {
                ops.opsForZSet().score(key, String.valueOf(member));
            }
            ops.expire(key, ttl);
        });
        List<Boolean> result = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            // ZSCORE 未命中返回 null（而不是 false）
            result.add(i < raw.size() && raw.get(i) != null);
        }
        return result;
    }

    // ==================== 写 ====================

    /** 关注：条件双写（原子，见 {@link #FOLLOW_WRITE_LUA}）。不做增量即整体失效（脚本内完成）。 */
    public void applyFollowWrite(String followingKey, String followerKey,
                                 String emptyFollowingKey, String emptyFollowerKey,
                                 String partialFollowingKey, String partialFollowerKey,
                                 long userId, long followedUserId, Duration ttl) {
        eval(FOLLOW_WRITE_SCRIPT,
                List.of(followingKey, followerKey, emptyFollowingKey, emptyFollowerKey,
                        partialFollowingKey, partialFollowerKey),
                String.valueOf(userId), String.valueOf(followedUserId), String.valueOf(ttl.toSeconds()));
    }

    /** 取关：条件双写（原子，见 {@link #UNFOLLOW_WRITE_LUA}）。 */
    public void applyUnfollowWrite(String followingKey, String followerKey,
                                   String emptyFollowingKey, String emptyFollowerKey,
                                   String partialFollowingKey, String partialFollowerKey,
                                   long userId, long followedUserId, Duration ttl) {
        eval(UNFOLLOW_WRITE_SCRIPT,
                List.of(followingKey, followerKey, emptyFollowingKey, emptyFollowerKey,
                        partialFollowingKey, partialFollowerKey),
                String.valueOf(userId), String.valueOf(followedUserId), String.valueOf(ttl.toSeconds()));
    }

    /**
     * 原子回填**整个** ZSet（{@code DEL + ZADD×N + EXPIRE}）并清空标记与部分装载标记。
     * {@code members} 必须**非空**（空集走 {@link #markEmpty}）。
     */
    public void backfillZSet(String dataKey, String emptyKey, String partialKey,
                             List<Long> members, Duration ttl) {
        List<String> args = new ArrayList<>(members.size() + 1);
        args.add(String.valueOf(ttl.toSeconds()));
        for (Long m : members) {
            args.add(String.valueOf(m));
        }
        eval(BACKFILL_ZSET_SCRIPT, List.of(dataKey, emptyKey, partialKey), args.toArray(new Object[0]));
    }

    /**
     * 窗口装载/补齐结果的写回（原子，见 {@link #APPEND_WINDOW_LUA}）。
     * {@code members} 可为空（"补齐时 DB 到底且没有新成员"）。
     *
     * @param complete {@code true} = 本次已确认拿到全集 ⇒ 清 partial；{@code false} = 仍是前缀 ⇒ 打 partial
     */
    public void appendWindow(String dataKey, String partialKey, List<Long> members,
                             boolean complete, Duration ttl) {
        List<String> args = new ArrayList<>(members.size() + 2);
        args.add(String.valueOf(ttl.toSeconds()));
        args.add(complete ? "1" : "0");
        for (Long m : members) {
            args.add(String.valueOf(m));
        }
        eval(APPEND_WINDOW_SCRIPT, List.of(dataKey, partialKey), args.toArray(new Object[0]));
    }

    /**
     * 写空标记（原子，见 {@link #MARK_EMPTY_LUA}）：数据 key 不存在才写短 TTL 的空标记，
     * 且**总是清掉部分装载标记**。{@code emptyTtl} 是**短 TTL**，与数据 key 的 TTL 不同。
     */
    public void markEmpty(String dataKey, String emptyKey, String partialKey, Duration emptyTtl) {
        eval(MARK_EMPTY_SCRIPT, List.of(dataKey, emptyKey, partialKey),
                String.valueOf(emptyTtl.toSeconds()));
    }

    /**
     * 计数条件写（G-8）：key 存在才 {@code INCRBY}。不存在 ⇒ 什么都不做（读路径回源 DB）。
     *
     * @return 是否实际执行了自增
     */
    public boolean applyCountConditionalWrite(String countKey, int delta) {
        Long applied = eval(COUNT_CONDITIONAL_SCRIPT, List.of(countKey), String.valueOf(delta));
        return applied != null && applied == 1L;
    }

    private static List<Long> toLongList(Object raw) {
        List<Long> ids = new ArrayList<>();
        if (raw instanceof Iterable<?> it) {
            for (Object o : it) {
                if (o != null) {
                    ids.add(Long.valueOf(String.valueOf(o)));
                }
            }
        }
        return ids;
    }

    /** 一次窗口读的协议层产物：升序成员 + 总数（总数与成员**同源**，来自同一趟往返）。 */
    public record WindowRead(List<Long> ids, long total) {
    }
}
