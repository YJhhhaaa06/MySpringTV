package io.github.yjhhhaaa06.videoweb.feed.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.RedisOps;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * feed 缓存的 **Redis 命令层** —— 只负责"把命令发出去"，不含任何缓存语义
 * （三态判断 / 回源 / 回填 / 降级都在 {@link FeedCache}）。
 *
 * <h2>与 {@code FollowRedisOps} / {@code LikeRedisOps} 同构</h2>
 * 归档：通用协议（{@code guarded} / {@code pipeline} / {@code existingKeys} / {@code delete} /
 * {@code expire} / {@code eval}）已在 S5 收敛到基类 {@link RedisOps}，本类只放 **feed 域内命令**：
 * <ul>
 *   <li>ZSet 窗口读（收件箱全量升序 + 滑动续期）；</li>
 *   <li>大V发件箱的**一趟 pipeline 批量探测**（EXISTS 空标记 / EXISTS 数据 / ZREVRANGE / EXPIRE）；</li>
 *   <li>大V发件箱的一趟 pipeline 回填（ZADD×N + EXPIRE）；</li>
 *   <li>重建锁（{@code SET NX EX} + Lua CAS 释放）；</li>
 *   <li>空标记（Lua，只在数据 key 不存在时写——与 {@code FollowRedisOps.MARK_EMPTY_LUA} 同脚本，
 *       ⚠️ 已知的小重复：两域各自持有，因为 {@code markEmpty} 是"域内约定"而非纯协议；</li>
 * </ul>
 *
 * <h2>★ 为什么是抽象基类的子类（不是 bean）</h2>
 * 同 {@link RedisOps} 类注释：基类若标 {@code @Component}，按类型注入会歧义。本类各自 {@code @Component}。
 */
@Component
public class FeedRedisOps extends RedisOps {

    /**
     * 释放锁：CAS（值等于自己的 token 才删）——避免误删他人已获得的锁。TV 原脚本。
     * KEYS: {@code [lockKey]}；ARGV: {@code [token]}。
     */
    private static final String RELEASE_LOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    /**
     * 写空标记（原子）：只在**数据 key 不存在**时才写，避免覆盖并发写入的真数据。
     * KEYS: {@code [dataKey, emptyKey]}；ARGV: {@code [emptyTtlSeconds]}。
     * 与 {@code FollowRedisOps.MARK_EMPTY_LUA} 逐字相同。
     */
    private static final String MARK_EMPTY_LUA =
            "if redis.call('EXISTS', KEYS[1]) == 0 then "
            + "  redis.call('SETEX', KEYS[2], ARGV[1], '1') "
            + "  return 1 "
            + "end "
            + "return 0";

    /**
     * 原子回填整个 ZSet（含 TTL）并清掉空标记：{@code DEL + ZADD score=成员 ×N + EXPIRE + DEL empty}。
     * KEYS: {@code [dataKey, emptyKey]}；ARGV: {@code [ttlSeconds, member...]}（成员 id 即 score）。
     *
     * <p>为什么用 Lua 原子替换而不是"逐个 ZADD"：并发读者要么看到"key 不存在"（→ 自己也回源 DB，
     * 答案正确），要么看到**完整**集合；逐 ZADD 会让读者命中一个**部分填充**的 ZSet，
     * 把"有内容"误判成"没有"。与 {@code FollowRedisOps.BACKFILL_ZSET_LUA} 逐字相同（同一理由）。
     * 空集**不走本脚本**（Redis 里空 ZSet 不存在），改由 {@link #markEmpty} 写空标记。
     */
    private static final String BACKFILL_ZSET_LUA =
            "redis.call('DEL', KEYS[1]) "
            + "for i = 2, #ARGV do redis.call('ZADD', KEYS[1], ARGV[i], ARGV[i]) end "
            + "redis.call('EXPIRE', KEYS[1], ARGV[1]) "
            + "redis.call('DEL', KEYS[2]) "
            + "return 1";

    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            new DefaultRedisScript<>(RELEASE_LOCK_LUA, Long.class);
    private static final DefaultRedisScript<Long> MARK_EMPTY_SCRIPT =
            new DefaultRedisScript<>(MARK_EMPTY_LUA, Long.class);
    private static final DefaultRedisScript<Long> BACKFILL_ZSET_SCRIPT =
            new DefaultRedisScript<>(BACKFILL_ZSET_LUA, Long.class);

    public FeedRedisOps(StringRedisTemplate redis) {
        super(redis);
    }

    // ==================== 读 ====================

    /**
     * 一趟 pipeline 判断两个 key 是否存在，返回**存在**的那些 key（定长两参，委托基类 varargs）。
     * 口径同 {@code FollowRedisOps.existingOf}：定长签名让桩与断言无歧义。
     */
    public Set<String> existingOf(String key1, String key2) {
        return existingKeys(key1, key2);
    }

    /**
     * 全量升序读 ZSet + **滑动续期**（一趟 pipeline）。
     *
     * <p>为什么全量（{@code ZRANGE 0 -1}）而非窗口：① 收件箱/发件箱的窗口量级有限
     * （重建裁剪到 C、fanout 只追增）；② 读侧最终只可见 M 条，但**归并去重排序需要看得更全**
     * （收件箱腿 ∪ 大V腿）。方向一致性：升序返回；收件箱腿由 {@link FeedCache} 做一次内存反序
     * 得到内容倒序（miss 与降级路径的 loader 也是升序 ⇒ 三条路径同向）。
     */
    public List<Long> zRangeAll(String key, Duration ttl) {
        List<Object> raw = pipeline(ops -> {
            ops.opsForZSet().range(key, 0, -1);
            ops.expire(key, ttl);
        });
        return toLongList(raw.isEmpty() ? null : raw.getFirst());
    }

    /**
     * 大V发件箱**一趟 pipeline 批量探测**（每个作者 4 条命令）：
     * {@code EXISTS empty:} / {@code EXISTS key} / {@code ZREVRANGE 0 N-1} / {@code EXPIRE key ttl}。
     *
     * <p>为什么必须批量：读的往返次数要与"关注的大V作者数"**解耦**（TV 的用户拍板：大V腿不封顶、
     * 以 pipeline 吸收往返）。滑动续期**无条件入列**（key 不存在返回 0、无副作用）；空标记**不续期**。
     *
     * @param authorIds  大V作者集合（非空）
     * @param windowSize N（每作者窗口大小，&gt; 0——{@code ZREVRANGE 0 N-1}，N≤0 会退化成读全量，故调用方先守卫）
     * @return 与入参**同序**的探测结果
     */
    public List<OutboxProbe> probeOutbox(List<Long> authorIds, int windowSize, Duration ttl) {
        List<Object> raw = pipeline(ops -> {
            for (Long authorId : authorIds) {
                String key = CacheKeys.feedOutbox(authorId);
                ops.hasKey(CacheKeys.empty(key));
                ops.hasKey(key);
                ops.opsForZSet().reverseRange(key, 0, windowSize - 1L);
                ops.expire(key, ttl);
            }
        });
        List<OutboxProbe> result = new ArrayList<>(authorIds.size());
        for (int i = 0; i < authorIds.size(); i++) {
            int base = i * 4;
            boolean empty = Boolean.TRUE.equals(raw.get(base));
            boolean exists = Boolean.TRUE.equals(raw.get(base + 1));
            List<Long> ids = toLongList(raw.get(base + 2));
            result.add(new OutboxProbe(authorIds.get(i), empty, exists, ids));
        }
        return result;
    }

    // ==================== 写 ====================

    /**
     * 大V发件箱**一趟 pipeline 回填**：逐作者 {@code ZADD score=contentId}（非空）+ {@code EXPIRE}。
     *
     * <p>空作者**不走本方法**（Redis 里空 ZSet 不存在），改由 {@link #markEmpty} 写空标记。
     *
     * @param authorIds       作者集合（回填的驱动顺序）
     * @param idsByAuthor     作者 → 该次回源的成员（降序；空/缺失的作者被跳过）
     */
    public void backfillOutbox(List<Long> authorIds, Map<Long, List<Long>> idsByAuthor, Duration ttl) {
        pipeline(ops -> {
            for (Long authorId : authorIds) {
                List<Long> ids = idsByAuthor.get(authorId);
                if (ids == null || ids.isEmpty()) {
                    continue;
                }
                String key = CacheKeys.feedOutbox(authorId);
                for (Long id : ids) {
                    ops.opsForZSet().add(key, String.valueOf(id), id.doubleValue());   // score = contentId
                }
                ops.expire(key, ttl);
            }
        });
    }

    /** 写空标记（原子，见 {@link #MARK_EMPTY_LUA}）。{@code emptyTtl} 是**短 TTL**，与数据 key 不同。 */
    public void markEmpty(String dataKey, String emptyKey, Duration emptyTtl) {
        eval(MARK_EMPTY_SCRIPT, List.of(dataKey, emptyKey), String.valueOf(emptyTtl.toSeconds()));
    }

    /**
     * 原子回填整个 ZSet（{@code DEL + ZADD×N + EXPIRE}）并清空标记（见 {@link #BACKFILL_ZSET_LUA}）。
     * {@code members} 必须**非空**（空集走 {@link #markEmpty}）。
     */
    public void backfillZSet(String dataKey, String emptyKey, List<Long> members, Duration ttl) {
        List<Object> args = new ArrayList<>(members.size() + 1);
        args.add(String.valueOf(ttl.toSeconds()));
        for (Long m : members) {
            args.add(String.valueOf(m));
        }
        eval(BACKFILL_ZSET_SCRIPT, List.of(dataKey, emptyKey), args.toArray(new Object[0]));
    }

    // ==================== 锁 ====================

    /**
     * 取锁（{@code SET key token NX EX ttl}）。
     *
     * @return {@code true} = 本次真正获得锁（可执行重建）；{@code false} = 已被他人持有
     */
    public boolean tryLock(String key, String token, Duration ttl) {
        Boolean acquired = guarded(() -> redis.opsForValue().setIfAbsent(key, token, ttl));
        return Boolean.TRUE.equals(acquired);
    }

    /** 释放锁（Lua CAS，只删自己的 token，见 {@link #RELEASE_LOCK_LUA}）。 */
    public void releaseLock(String key, String token) {
        eval(RELEASE_LOCK_SCRIPT, List.of(key), token);
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

    /**
     * 大V发件箱单作者探测结果：{@code empty=true} 命中空标记；{@code exists=true} 命中数据 key；
     * 其余为 miss（需回源）；{@code ids} 为 {@code ZREVRANGE}（已按 score 降序）结果。
     */
    public record OutboxProbe(long authorId, boolean empty, boolean exists, List<Long> ids) {
    }
}
