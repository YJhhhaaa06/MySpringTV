package io.github.yjhhhaaa06.videoweb.follow.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 关注缓存的 **Redis 协议层**——只负责"把命令发出去"，不含任何缓存语义
 * （三态判断 / 回源 / 回填 / 降级都在 {@link FollowCache}）。
 *
 * <h2>为什么单独一层</h2>
 * ① 决策⑤ 要求的是"**显式封装层**"（不用 {@code @Cacheable}），本类与 {@link FollowCache}
 * 一起构成该层：上层管语义、下层管协议；
 * ② 所有 Redis 异常在此**归一化为 {@link CacheUnavailableException}**，
 * 使"Redis 失败 vs DB 失败"在类型上可区分；
 * ③ 降级路径因此可以**确定性地被测试**：把本类换成会抛异常的 spy 即可，无需真的停掉 Redis。
 *
 * <h2>与 like 的关系（照抄形态，不抽公共层）</h2>
 * 与 {@code LikeRedisOps} 同构（同一套 {@code guarded} 归一化、同一套 Lua 原子写法），
 * 差别只有数据结构：like 用 {@code Set}（无序），follow 用 **{@code ZSet}**（{@code score = 成员 id} ⇒ 有序）。
 * 按《事务边界决策表》F-7：本切片是第 2 个使用方（like + follow），
 * 按 rule of three 等 **S5** 出现第 3 个使用方再抽 {@code common/cache} 的框架层。
 * 唯一例外是异常的公共类型（已上移到 {@code common.cache}）。
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
public class FollowRedisOps {

    // ==================== Lua 脚本 ====================

    /**
     * 关注：**条件双写**（原子）。KEYS: {@code [followingKey, followerKey, emptyFollowing, emptyFollower]}；
     * ARGV: {@code [userId, followedUserId, ttlSeconds]}。
     *
     * <p>"已加载"= 数据 key 存在 **或** 空标记存在。"无关注"这一事实由空标记承载
     * （收在这里有真实用途：关注时要把空标记转成真实成员，靠它才能安全地做增量写）。
     *
     * <p>两条原因导致不值得增量写 → **整体失效**（DEL 四件套）让读自愈回填：
     * ① 任一侧冷 key（未加载）；② ⚠️ 不能只 DEL 数据 key——残留的空标记会让读"假空"。
     */
    private static final String FOLLOW_WRITE_LUA =
            "local followingReady = redis.call('EXISTS', KEYS[1]) "
            + "local followingEmpty = redis.call('EXISTS', KEYS[3]) "
            + "local followerReady = redis.call('EXISTS', KEYS[2]) "
            + "local followerEmpty = redis.call('EXISTS', KEYS[4]) "
            + "if (followingReady == 1 or followingEmpty == 1) "
            + "   and (followerReady == 1 or followerEmpty == 1) then "
            + "  if followingEmpty == 1 then redis.call('DEL', KEYS[3]) end "
            + "  if followerEmpty == 1 then redis.call('DEL', KEYS[4]) end "
            + "  redis.call('ZADD', KEYS[1], ARGV[2], ARGV[2]) "
            + "  redis.call('ZADD', KEYS[2], ARGV[1], ARGV[1]) "
            + "  redis.call('EXPIRE', KEYS[1], ARGV[3]) "
            + "  redis.call('EXPIRE', KEYS[2], ARGV[3]) "
            + "  return 1 "
            + "end "
            + "redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4]) "
            + "return 0";

    /**
     * 取关：条件双写（原子）。KEYS / ARGV 同 {@link #FOLLOW_WRITE_LUA}。
     *
     * <p>与关注**不对称**（TV 原样口径）：只有"两侧都**已加载真实集合**"才做 {@code ZREM}。
     * 任一侧是空标记 ⇒ 说明该侧"确认无关系"，与"我要取关"矛盾 ⇒ 缓存不可信，
     * 整体失效让读对齐 DB（最安全）。
     */
    private static final String UNFOLLOW_WRITE_LUA =
            "local followingReady = redis.call('EXISTS', KEYS[1]) "
            + "local followingEmpty = redis.call('EXISTS', KEYS[3]) "
            + "local followerReady = redis.call('EXISTS', KEYS[2]) "
            + "local followerEmpty = redis.call('EXISTS', KEYS[4]) "
            + "if followingReady == 1 and followerReady == 1 "
            + "   and followingEmpty == 0 and followerEmpty == 0 then "
            + "  redis.call('ZREM', KEYS[1], ARGV[2]) "
            + "  redis.call('ZREM', KEYS[2], ARGV[1]) "
            + "  redis.call('EXPIRE', KEYS[1], ARGV[3]) "
            + "  redis.call('EXPIRE', KEYS[2], ARGV[3]) "
            + "  return 1 "
            + "end "
            + "redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4]) "
            + "return 0";

    /**
     * 原子回填整个 ZSet（含 TTL），并清掉空标记。
     * KEYS: {@code [dataKey, emptyKey]}；ARGV: {@code [ttlSeconds, member...]}（成员即 score）。
     *
     * <p>空集**不走本脚本**（Redis 里空 ZSet 不存在），改由 {@link #markEmpty} 写空标记。
     */
    private static final String BACKFILL_ZSET_LUA =
            "redis.call('DEL', KEYS[1]) "
            + "for i = 2, #ARGV do redis.call('ZADD', KEYS[1], ARGV[i], ARGV[i]) end "
            + "redis.call('EXPIRE', KEYS[1], ARGV[1]) "
            + "redis.call('DEL', KEYS[2]) "
            + "return 1";

    /**
     * 写空标记（原子）：只在**数据 key 不存在**时才写，避免覆盖并发写入的真数据。
     * KEYS: {@code [dataKey, emptyKey]}；ARGV: {@code [emptyTtlSeconds]}。
     *
     * <p>对照 TV {@code CacheAside.markEmpty}：它接受了一个"exists 检查 → setex 之间的毫秒间隙内
     * 并发写入时，空标记可能覆盖其上"的残余竞态（靠 60s 过期 / 下次业务写自愈）。
     * 这里用 Lua 把两步行成一步，**竞态消失、行为不变**（不是行为改动）。
     */
    private static final String MARK_EMPTY_LUA =
            "if redis.call('EXISTS', KEYS[1]) == 0 then "
            + "  redis.call('SETEX', KEYS[2], ARGV[1], '1') "
            + "  return 1 "
            + "end "
            + "return 0";

    private static final DefaultRedisScript<Long> FOLLOW_WRITE_SCRIPT =
            new DefaultRedisScript<>(FOLLOW_WRITE_LUA, Long.class);
    private static final DefaultRedisScript<Long> UNFOLLOW_WRITE_SCRIPT =
            new DefaultRedisScript<>(UNFOLLOW_WRITE_LUA, Long.class);
    private static final DefaultRedisScript<Long> BACKFILL_ZSET_SCRIPT =
            new DefaultRedisScript<>(BACKFILL_ZSET_LUA, Long.class);
    private static final DefaultRedisScript<Long> MARK_EMPTY_SCRIPT =
            new DefaultRedisScript<>(MARK_EMPTY_LUA, Long.class);

    private final StringRedisTemplate redis;

    public FollowRedisOps(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ==================== 读 ====================

    /**
     * 一趟 pipeline 判断两个 key 是否存在，返回**存在**的那些 key。
     *
     * <p>三态读的判定需要同时知道"数据 key 在不在"与"空标记在不在"，两次独立往返会把
     * 判定成本翻倍；pipeline 保住"一趟往返"的性质（TV 的 {@code probeZSet} 同款）。
     *
     * <p>刻意用**两个固定参数**而不是 {@code String...}：调用面只有这一种形态，
     * 定长签名让桩与断言无歧义（varargs 的匹配语义容易写出"看着对、其实没匹配上"的桩）。
     */
    @SuppressWarnings("unchecked")
    public Set<String> existingOf(String key1, String key2) {
        String[] keys = {key1, key2};
        return guarded(() -> {
            List<Object> raw = redis.executePipelined(new SessionCallback<Object>() {
                @Override
                public <K, V> Object execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> typed = (RedisOperations<String, String>) operations;
                    for (String key : keys) {
                        typed.hasKey(key);
                    }
                    return null;
                }
            });
            Set<String> existing = new LinkedHashSet<>();
            for (int i = 0; i < keys.length; i++) {
                if (i < raw.size() && Boolean.TRUE.equals(raw.get(i))) {
                    existing.add(keys[i]);
                }
            }
            return existing;
        });
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
    @SuppressWarnings("unchecked")
    public WindowRead zWindow(String key, long offset, long stop, Duration ttl) {
        return guarded(() -> {
            List<Object> raw = redis.executePipelined(new SessionCallback<Object>() {
                @Override
                public <K, V> Object execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> typed = (RedisOperations<String, String>) operations;
                    typed.opsForZSet().range(key, offset, stop);
                    typed.opsForZSet().zCard(key);
                    typed.expire(key, ttl);
                    return null;
                }
            });
            List<Long> ids = toLongList(raw.get(0));
            Long total = raw.size() > 1 && raw.get(1) instanceof Number n ? n.longValue() : 0L;
            return new WindowRead(ids, total);
        });
    }

    /**
     * 一趟 pipeline 判定多个成员是否在 ZSet 内，返回与入参**同序**的布尔列表。
     *
     * <p>用 {@code ZSCORE}（而非 {@code ZRANK}）：只需"在不在"，且 {@code ZSCORE} 不依赖偏移语义。
     */
    @SuppressWarnings("unchecked")
    public List<Boolean> zMembersPresent(String key, List<Long> members, Duration ttl) {
        return guarded(() -> {
            List<Object> raw = redis.executePipelined(new SessionCallback<Object>() {
                @Override
                public <K, V> Object execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> typed = (RedisOperations<String, String>) operations;
                    for (Long member : members) {
                        typed.opsForZSet().score(key, String.valueOf(member));
                    }
                    typed.expire(key, ttl);
                    return null;
                }
            });
            List<Boolean> result = new ArrayList<>(members.size());
            for (int i = 0; i < members.size(); i++) {
                // ZSCORE 未命中返回 null（而不是 false）
                result.add(i < raw.size() && raw.get(i) != null);
            }
            return result;
        });
    }

    // ==================== 写 ====================

    /** 关注：条件双写（原子，见 {@link #FOLLOW_WRITE_LUA}）。不做增量即整体失效（脚本内完成）。 */
    public void applyFollowWrite(String followingKey, String followerKey,
                                 String emptyFollowingKey, String emptyFollowerKey,
                                 long userId, long followedUserId, Duration ttl) {
        evalConditional(FOLLOW_WRITE_SCRIPT, followingKey, followerKey, emptyFollowingKey, emptyFollowerKey,
                String.valueOf(userId), String.valueOf(followedUserId), String.valueOf(ttl.toSeconds()));
    }

    /** 取关：条件双写（原子，见 {@link #UNFOLLOW_WRITE_LUA}）。 */
    public void applyUnfollowWrite(String followingKey, String followerKey,
                                   String emptyFollowingKey, String emptyFollowerKey,
                                   long userId, long followedUserId, Duration ttl) {
        evalConditional(UNFOLLOW_WRITE_SCRIPT, followingKey, followerKey, emptyFollowingKey, emptyFollowerKey,
                String.valueOf(userId), String.valueOf(followedUserId), String.valueOf(ttl.toSeconds()));
    }

    /** 原子回填 ZSet（{@code DEL + ZADD×N + EXPIRE}）并清空标记。{@code members} 必须**非空**。 */
    public void backfillZSet(String dataKey, String emptyKey, List<Long> members, Duration ttl) {
        List<String> args = new ArrayList<>(members.size() + 1);
        args.add(String.valueOf(ttl.toSeconds()));
        for (Long m : members) {
            args.add(String.valueOf(m));
        }
        guardedVoid(() -> redis.execute(BACKFILL_ZSET_SCRIPT, List.of(dataKey, emptyKey),
                args.toArray(new Object[0])));
    }

    /** 写空标记（原子，见 {@link #MARK_EMPTY_LUA}）。{@code emptyTtl} 是**短 TTL**，与数据 key 的 TTL 不同。 */
    public void markEmpty(String dataKey, String emptyKey, Duration emptyTtl) {
        guardedVoid(() -> redis.execute(MARK_EMPTY_SCRIPT, List.of(dataKey, emptyKey),
                String.valueOf(emptyTtl.toSeconds())));
    }

    /** 删除若干 key（失败路径的"失效让读自愈"）。 */
    public void delete(String... keys) {
        guardedVoid(() -> redis.delete(List.of(keys)));
    }

    // ==================== 异常归一化 ====================

    private void evalConditional(DefaultRedisScript<Long> script, String k1, String k2, String k3, String k4,
                                 String... args) {
        guardedVoid(() -> redis.execute(script, List.of(k1, k2, k3, k4), (Object[]) args));
    }

    /** 把任意 Redis 层异常归一化为 {@link CacheUnavailableException}。 */
    private <T> T guarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            throw new CacheUnavailableException("Redis 访问失败: " + e.getMessage(), e);
        }
    }

    private void guardedVoid(Runnable action) {
        guarded(() -> {
            action.run();
            return null;
        });
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
