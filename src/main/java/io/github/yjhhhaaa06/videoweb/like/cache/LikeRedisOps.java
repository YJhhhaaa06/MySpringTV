package io.github.yjhhhaaa06.videoweb.like.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 点赞缓存的 **Redis 协议层**——只负责"把命令发出去"，不含任何缓存语义
 * （三态判断 / 回源 / 回填 / 降级都在 {@link LikeCache}）。
 *
 * <h2>为什么单独一层</h2>
 * ① 决策⑤ 要求的是"**显式封装层**"（不用 {@code @Cacheable}），本类与 {@link LikeCache}
 * 一起构成该层：上层管语义、下层管协议；
 * ② 所有 Redis 异常在此**归一化为 {@link CacheUnavailableException}**，
 * 使"Redis 失败 vs DB 失败"在类型上可区分（见 {@link CacheUnavailableException} 的说明）；
 * ③ 降级路径因此可以**确定性地被测试**：把本类换成会抛异常的 spy 即可，无需真的停掉 Redis。
 *
 * <h2>与 TV {@code cache} 包的关系（有意不搬，见决策表 L-7）</h2>
 * TV 的 {@code RedisAccess}(90) + {@code CacheAside}(624) + {@code SetCache}(397) + {@code CacheKeys}(296)
 * 共 2,528 行/12 文件，但它**不能开箱即用**：依赖自研连接池 {@code MyRedisPool}、
 * 自研熔断 {@code RedisCircuitBreaker}（已判给 Resilience4j）、{@code AppConfig} 覆盖链、
 * Jackson 2 包名、{@code @Component}/{@code @InjectConstructor}、{@code LogUtil}、{@code TransactionTemplate}。
 * 搬它等于先做一次基建重构 ⇒ 按决策⑤ 做精简版，去掉的机制与影响**逐条记录在 L-7**。
 *
 * <h2>条件写为什么用 Lua</h2>
 * "只在 key 已存在时才 INCR/SADD"是**防残缺缓存**的关键（冷 key 不创建半套数据，
 * 否则会被读命中而给出错的答案）。若写成"先 EXISTS 探一下、再写"，两步之间可能被并发
 * 失效命令插入（TV 三期 N7 记录过这个窗口：并发 DEL 后 INCR 会以 1 重建计数 key，
 * 该错误值会存活到 TTL 到期）。Lua 脚本在 Redis 服务端原子执行，窗口消失。
 */
@Component
public class LikeRedisOps {

    /**
     * 点赞条件写（原子，一趟往返）：set 存在才 SADD、count 存在才 INCR。
     * KEYS: {@code [setKey, countKey]}；ARGV: {@code [memberId]}。
     *
     * <p>对照 TV {@code LikeCacheService.LIKE_CONDITIONAL_SCRIPT}：**少了 {@code DEL KEYS[3]}**
     * ——那一行是清"空标记"（{@code empty:...}），而精简版有意不做空标记（见 L-7 的影响记录）。
     */
    private static final String LIKE_LUA =
            "if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('SADD', KEYS[1], ARGV[1]) end "
            + "if redis.call('EXISTS', KEYS[2]) == 1 then redis.call('INCR', KEYS[2]) end "
            + "return 1";

    /**
     * 取消点赞条件写（原子）：set 存在才 SREM、count 存在才 DECR。
     * KEYS: {@code [setKey, countKey]}；ARGV: {@code [memberId]}。
     */
    private static final String UNLIKE_LUA =
            "if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('SREM', KEYS[1], ARGV[1]) end "
            + "if redis.call('EXISTS', KEYS[2]) == 1 then redis.call('DECR', KEYS[2]) end "
            + "return 1";

    /**
     * 回填整个用户维度 set（原子替换）。
     * KEYS: {@code [setKey]}；ARGV: {@code [ttlSeconds, member...]}。
     *
     * <p>用<b>原子替换</b>（DEL 后 SADD）而非 TV 的"SADD-union"：union 会让并发读者在回填过程中
     * 命中一个**部分填充**的 set，从而把"已点赞"误判成 false；DEL+SADD+EXPIRE 在脚本内一次执行完，
     * 读者要么看到"key 不存在"（→ 自己也回源 DB，答案正确），要么看到完整集合。
     *
     * <p>空集时**不创建 key**（Redis 里空集合不存在）——这正是"有意不做空标记"的后果：
     * 从未点赞的用户每次都回源 DB。影响已记录在 L-7。
     */
    private static final String BACKFILL_SET_LUA =
            "redis.call('DEL', KEYS[1]) "
            + "for i = 2, #ARGV do redis.call('SADD', KEYS[1], ARGV[i]) end "
            + "if #ARGV > 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
            + "return 1";

    private static final DefaultRedisScript<Long> LIKE_SCRIPT = new DefaultRedisScript<>(LIKE_LUA, Long.class);
    private static final DefaultRedisScript<Long> UNLIKE_SCRIPT = new DefaultRedisScript<>(UNLIKE_LUA, Long.class);
    private static final DefaultRedisScript<Long> BACKFILL_SET_SCRIPT =
            new DefaultRedisScript<>(BACKFILL_SET_LUA, Long.class);

    private final StringRedisTemplate redis;

    public LikeRedisOps(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ==================== 读 ====================

    public boolean keyExists(String key) {
        return guarded(() -> Boolean.TRUE.equals(redis.hasKey(key)));
    }

    public boolean setIsMember(String key, long member) {
        return guarded(() -> Boolean.TRUE.equals(redis.opsForSet().isMember(key, String.valueOf(member))));
    }

    /**
     * 一趟 pipeline 判定多个成员是否在集合内，返回与入参**同序**的布尔列表。
     *
     * <p>为什么必须 pipeline：TV 三期 T4 把成员 key 从内容维度反转为用户维度，
     * 目标就是"命令数与装载量双降"。若这里退化成 N 次独立 SISMEMBER，页面上 200 条评论
     * 就是 200 个往返，那个性质会被悄悄破坏。
     */
    @SuppressWarnings("unchecked")
    public List<Boolean> setIsMemberBatch(String key, List<Long> members) {
        return guarded(() -> {
            List<Object> raw = redis.executePipelined(new SessionCallback<Object>() {
                @Override
                public <K, V> Object execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> typed = (RedisOperations<String, String>) operations;
                    for (Long member : members) {
                        typed.opsForSet().isMember(key, String.valueOf(member));
                    }
                    return null;
                }
            });
            return raw.stream().map(o -> Boolean.TRUE.equals(o)).toList();
        });
    }

    /** 读计数。**key 不存在返回 null**（0 是合法值，必须与"不存在"区分开）。 */
    public String getString(String key) {
        return guarded(() -> redis.opsForValue().get(key));
    }

    // ==================== 写 ====================

    public void setString(String key, String value, Duration ttl) {
        guardedVoid(() -> redis.opsForValue().set(key, value, ttl));
    }

    /** 续期（滑动过期：命中即续，热 key 不会被 TTL 淘汰）。 */
    public void expire(String key, Duration ttl) {
        guardedVoid(() -> redis.expire(key, ttl));
    }

    /** 删除若干 key（失败路径的"失效让读自愈"）。 */
    public void delete(String... keys) {
        guardedVoid(() -> redis.delete(List.of(keys)));
    }

    /** 条件写：点赞。{@code memberId} 是 contentId 或 commentId（成员写在用户维度的 set 里）。 */
    public void applyLikeConditionalWrite(String setKey, String countKey, long memberId) {
        evalConditional(LIKE_SCRIPT, setKey, countKey, memberId);
    }

    /** 条件写：取消点赞。 */
    public void applyUnlikeConditionalWrite(String setKey, String countKey, long memberId) {
        evalConditional(UNLIKE_SCRIPT, setKey, countKey, memberId);
    }

    /** 原子回填用户维度 set（含 TTL）。空集不创建 key。 */
    public void backfillSet(String setKey, Set<Long> members, Duration ttl) {
        List<String> args = new ArrayList<>(members.size() + 1);
        args.add(String.valueOf(ttl.toSeconds()));
        for (Long m : members) {
            args.add(String.valueOf(m));
        }
        guardedVoid(() -> redis.execute(BACKFILL_SET_SCRIPT, List.of(setKey), args.toArray(new String[0])));
    }

    // ==================== 异常归一化 ====================

    private void evalConditional(DefaultRedisScript<Long> script, String setKey, String countKey, long memberId) {
        guardedVoid(() -> redis.execute(script, List.of(setKey, countKey), String.valueOf(memberId)));
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
}
