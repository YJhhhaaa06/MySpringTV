package io.github.yjhhhaaa06.videoweb.common.cache;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 缓存层的 **Redis 协议层基类** —— 只负责"把命令发出去"与"把异常归一化"，
 * **不含任何缓存语义**（三态判断 / 回源 / 回填 / 降级 / 条件写都在各自的 {@code *Cache} 里）。
 *
 * <h2>为什么这一层要抽（S5，rule of three 落地；决策表 G-6）</h2>
 * S3 的 {@code LikeRedisOps} 与 S4 的 {@code FollowRedisOps} 里有**逐字重复**的一段：
 * {@code guarded} / {@code guardedVoid} 的异常归一化、{@code keyExists} / {@code getString} /
 * {@code setString} / {@code delete} / {@code expire}，以及 {@code StringRedisTemplate} 的持有与构造。
 * S3/S4 当时刻意各写一份（第 2 个使用方，F-7 预定"等 S5 的第 3 个使用方再抽"）。
 * S5 带来 {@code ContentCache} 与 {@code CommentCache} 两个新使用方 ⇒ 本类落地，
 * 并把 like/follow 一并收敛过来。
 *
 * <h2>★ 这不是"顺手重写"，语义零变化</h2>
 * 本类只搬**协议**；like/follow 的**语义**（三态读 / Lua 条件写 / 降级 / TTL 续期 / 条件写判定）
 * 一字不改。收敛后它们仍各自 {@code extends RedisOps}，只保留域内命令（Set/ZSet/List/Hash 与 Lua）。
 * 既有测试是这次收敛的回归网。
 *
 * <h2>异常归一化是"两种失败可区分"的实现基础（G-4 / L-5 / F-5）</h2>
 * <table>
 *   <caption>失败分类与处置</caption>
 *   <tr><th>失败</th><th>处置</th><th>理由</th></tr>
 *   <tr><td>Redis 失败（{@link CacheUnavailableException}）</td><td>降级直查 DB，不影响结果</td>
 *       <td>缓存只是加速器</td></tr>
 *   <tr><td>DB 失败（{@code DataAccessException}）</td><td><b>原样上抛</b>（→ 500）</td>
 *       <td>"无答案可答"。降级成空值会把"读不到"伪装成"没有"，是更坏的语义</td></tr>
 * </table>
 * 实现上靠**只 catch 自己的异常类型**来保证：{@link CacheUnavailableException} 只从本层冒出，
 * DB 异常不经过这里。上层 {@code *Cache} 只 catch 这一个类型。
 *
 * <h2>为什么是**抽象基类**而不是 {@code @Component}</h2>
 * 它不是任何一个可独立使用的 bean：它只是"把命令发出去"的公共骨架，语义由子类定义
 * （like 的 Set 条件写、follow 的 ZSet 条件写、content 的索引 LIST、comment 的两键组）。
 * 若把它也标成 {@code @Component}，按类型注入 {@code RedisOps} 会立刻变成
 * <b>NoUniqueBeanDefinitionException</b>（实测：{@code expected single matching bean but found 3:
 * redisOps, followRedisOps, likeRedisOps}）——这是一个"看起来能跑、启动即失败"的坑，
 * 故这里用**抽象类**从源头消除（子类各自 {@code @Component}，注入点写具体类型）。
 */
public abstract class RedisOps {

    /** 子类命令层直接用（如 {@code redis.opsForSet()}）；子类不再各自声明字段与构造。 */
    protected final StringRedisTemplate redis;

    public RedisOps(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ========================================================================
    // 读
    // ========================================================================

    public boolean keyExists(String key) {
        return guarded(() -> Boolean.TRUE.equals(redis.hasKey(key)));
    }

    /**
     * 一趟 pipeline 判断多个 key 是否存在，返回**存在**的那些 key（顺序为入参顺序）。
     *
     * <p>三态读的判定需要同时知道"数据 key 在不在"与"空标记在不在"；两次独立往返会把判定成本翻倍。
     * 本方法保住"一趟往返"的性质（TV 的 {@code probeZSet} 同款）。
     *
     * <p>返回 {@code Set} 让调用方按 {@code contains(key)} 判定，避免"两个布尔值谁是谁"的下标错误。
     */
    public Set<String> existingKeys(String... keys) {
        List<Object> raw = pipeline(ops -> {
            for (String key : keys) {
                ops.hasKey(key);
            }
        });
        Set<String> existing = new LinkedHashSet<>();
        for (int i = 0; i < keys.length; i++) {
            if (i < raw.size() && Boolean.TRUE.equals(raw.get(i))) {
                existing.add(keys[i]);
            }
        }
        return existing;
    }

    /** 读字符串。**key 不存在返回 {@code null}**（{@code "0"} 是合法值，必须与"不存在"区分开）。 */
    public String getString(String key) {
        return guarded(() -> redis.opsForValue().get(key));
    }

    /**
     * 一趟 pipeline 执行调用方给定的命令序列，返回**与入队顺序一致**的结果列表。
     *
     * <p>给域内命令层用（如"窗口读 + 读总数 + 续期"必须一趟往返；"N 个成员判定"同理）。
     * 命令的**读写意图一致**才可入同一 pipeline——读命令的结果按入队序返回，
     * 但写命令的返回值语义由 Redis 决定（如 {@code INCR} 返回新值）。
     */
    @SuppressWarnings("unchecked")
    public List<Object> pipeline(Consumer<RedisOperations<String, String>> commands) {
        return guarded(() -> redis.executePipelined(new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                commands.accept((RedisOperations<String, String>) operations);
                return null;
            }
        }));
    }

    // ========================================================================
    // 写
    // ========================================================================

    public void setString(String key, String value, Duration ttl) {
        guardedVoid(() -> redis.opsForValue().set(key, value, ttl));
    }

    /** 续期（滑动过期：读命中即续，热 key 不会被 TTL 淘汰）。 */
    public void expire(String key, Duration ttl) {
        guardedVoid(() -> redis.expire(key, ttl));
    }

    /** 删除若干 key（失败路径的"失效让读自愈"，以及业务显式失效）。 */
    public void delete(String... keys) {
        guardedVoid(() -> redis.delete(List.of(keys)));
    }

    // ========================================================================
    // 脚本 / 遍历
    // ========================================================================

    /** 执行 Lua 脚本（原子）。KEYS 与 ARGV 分开传，脚本内用 {@code KEYS[n]} / {@code ARGV[n]} 取。 */
    public <T> T eval(RedisScript<T> script, List<String> keys, Object... args) {
        return guarded(() -> redis.execute(script, keys, args));
    }

    /**
     * SCAN 遍历匹配模式的 key（**不用 KEYS** —— 那是 O(N) 主线程阻塞，TV 的 T8/H13 修过）。
     *
     * @param matchPattern glob 模式（如 {@code content:index:*}）
     */
    public List<String> scanKeys(String matchPattern) {
        return guarded(() -> {
            Set<String> keys = new LinkedHashSet<>();
            try (Cursor<String> cursor = redis.scan(
                    ScanOptions.scanOptions().match(matchPattern).count(100).build())) {
                cursor.forEachRemaining(keys::add);
            }
            return new ArrayList<>(keys);
        });
    }

    // ========================================================================
    // 异常归一化
    // ========================================================================

    /** 把任意 Redis 层异常归一化为 {@link CacheUnavailableException}（子类命令层复用）。 */
    protected <T> T guarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (CacheUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CacheUnavailableException("Redis 访问失败: " + e.getMessage(), e);
        }
    }

    protected void guardedVoid(Runnable action) {
        guarded(() -> {
            action.run();
            return null;
        });
    }
}
