package io.github.yjhhhaaa06.videoweb.coin.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.RedisCircuitBreaker;
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
 * 投币缓存的 **Redis 命令层** —— 只负责"把命令发出去"，不含任何缓存语义
 * （三态判断 / 回源 / 回填 / 降级都在 {@link CoinCache}）。
 *
 * <p>分工沿袭 {@code LikeRedisOps}（S5 的基类收敛）：连接、异常归一化、{@code exists} /
 * {@code getString} / {@code setString} / {@code delete} / pipeline / Lua 执行都在
 * {@link RedisOps}；本类只留**投币域**的命令（HASH 读写、两台条件写、原子回填、空标记）。
 *
 * <h2>★ 两台条件写的形态**不同且都必须精确**（设计 §六）</h2>
 * <table>
 *   <caption>写什么、为什么</caption>
 *   <tr><th>key</th><th>脚本</th><th>为什么这样写</th></tr>
 *   <tr><td>{@code user:coinAmount}</td><td>{@code HSET} 写**新总量**</td>
 *       <td>事件带出了"该用户对该内容投完之后的累计枚数" ⇒ 精确回放、幂等；
 *           不能像 like 那样 {@code INCR}（like 的一条关系行恒等于 +1，投币不是）</td></tr>
 *   <tr><td>{@code content:coinCount}</td><td>{@code INCRBY delta}</td>
 *       <td>投币不可取消 ⇒ 本计数单调只增，增量写是精确的</td></tr>
 * </table>
 *
 * <h2>为什么条件写用 Lua</h2>
 * "只在 key 已存在时才写"是**防残缺缓存**的关键（冷 key 不创建半套数据，否则会被读命中并
 * 给出错的答案）。若写成"先 EXISTS 探一下、再写"，两步之间可能被并发失效命令插入
 * （{@code LikeRedisOps} 类注释记录过同一个窗口：并发 {@code DEL} 后 {@code INCR} 会以 1
 * 重建计数 key，错误值存活到 TTL 到期）。Lua 在 Redis 服务端原子执行，窗口消失。
 *
 * <h2>空标记</h2>
 * {@code empty:user:coinAmount:{userId}} 承载"已确认该用户一枚币都没投过"（短 TTL 60s）。
 * 条件写会先 {@code DEL} 它：本方法刚刚断言"他投了这一条"，与"他一条都没投过"自相矛盾，
 * 不清就是读路径优先命中空标记 ⇒ 60 秒假否定。
 *
 * <p>⚠️ {@code content:coinCount} **刻意无空标记**（{@code 0} 是合法值，见
 * {@code CacheKeys#contentCoinCount}），故 count 侧没有空标记命令。
 */
@Component
public class CoinRedisOps extends RedisOps {

    /**
     * 用户投币枚数 HASH 的条件写（原子，一趟往返）：**先清空标记，key 存在才 HSET**。
     * KEYS: {@code [hashKey, emptyKey]}；ARGV: {@code [contentId, newAmount]}。
     *
     * <p>★ 写的是**新总量**（{@code newAmount} = 该用户对该内容投完之后的累计枚数），
     * **不是** {@code HINCRBY}：事件里的总量是事务内读到的真相，直接覆盖即"精确回放"，
     * 重复投递同一事件也不会漂移（幂等）；而 {@code HINCRBY} 在重复投递时会多算一次
     * （设计 §六 ①）。
     *
     * <p>★ {@code if EXISTS} 守卫是**冷 key 不建半套**的落点：key 未加载时只清空标记、
     * 不创建 HASH —— 否则读路径会命中一个"只含刚投这一条"的残缺集合，把"他投过哪些"
     * 答成"只有这一条"。去掉守卫 ⇒ {@code CoinCacheTests#冷key投币不创建半套缓存} 必红。
     *
     * <p>清空标记与"key 存在才写"必须在**同一段脚本**里：分开两步的话，中间可能出现
     * "空标记已清、HASH 仍不存在"，读者会走 miss 回源（答案仍对，但多打一次 DB）；
     * 更糟的是并发 {@code DEL} 后 {@code HSET} 会以残缺内容重建 key（同 like 的三期 N7 窗口）。
     */
    private static final String COIN_AMOUNT_LUA =
            "redis.call('DEL', KEYS[2]) "
            + "if redis.call('EXISTS', KEYS[1]) == 1 then "
            + "  redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]) "
            + "end "
            + "return 1";

    /**
     * 内容投币计数的条件写（原子）：key 存在才 INCRBY。
     * KEYS: {@code [countKey]}；ARGV: {@code [delta]}。
     *
     * <p>投币**不可取消** ⇒ 计数单调只增 ⇒ 增量写是精确的（不像 favorite 那样要整体失效）。
     * 冷 key（未加载）时**不创建**：创建了就是"1"这种"看起来对、其实漏了历史"的残缺值。
     * 去掉 {@code exists} 守卫 ⇒ {@code CoinCacheTests#冷key投币不创建半套缓存} 必红。
     */
    private static final String COIN_COUNT_LUA =
            "if redis.call('EXISTS', KEYS[1]) == 1 then "
            + "  redis.call('INCRBY', KEYS[1], ARGV[1]) "
            + "end "
            + "return 1";

    /**
     * 回填整个用户维度 HASH（原子替换），并**清掉空标记**。
     * KEYS: {@code [hashKey, emptyKey]}；ARGV: {@code [ttlSeconds, field1, value1, field2, value2, ...]}。
     *
     * <p>用**原子替换**（{@code DEL} 后逐对 {@code HSET}）而非"HINCRBY 逐条叠加"：
     * 并发读者不会命中一个**部分填充**的 HASH（那会把"投过"读成"没投过"）；
     * 读者要么看到 key 不存在（自己也回源 DB，答案正确），要么看到完整集合。
     *
     * <p>★ 必须 {@code DEL} 空标记：读路径**先看空标记**，若它残留，刚回填的真数据会被它
     * 挡在 60 秒假否定后面（同一处置见 {@code LikeRedisOps#BACKFILL_SET_LUA}）。
     *
     * <p>空 Map **不走本脚本**（Redis 里空 HASH 不存在），改由
     * {@link #markEmptyIfAbsent} 写空标记 —— 防穿透的落点。
     */
    private static final String BACKFILL_AMOUNT_LUA =
            "redis.call('DEL', KEYS[1]) "
            + "for i = 2, #ARGV, 2 do "
            + "  redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1]) "
            + "end "
            + "if #ARGV > 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
            + "redis.call('DEL', KEYS[2]) "
            + "return 1";

    /**
     * 写空标记（原子）：只在**数据 key 不存在**时才写，避免覆盖并发回填的真数据。
     * KEYS: {@code [dataKey, emptyKey]}；ARGV: {@code [emptyTtlSeconds]}。
     *
     * <p>守卫的用途：并发回填已经写出真 HASH 时，不要把"无投币"的旧结论盖上去
     * （否则读路径 60s 假否定）。与 {@code LikeRedisOps#MARK_EMPTY_LUA} 逐字相同
     * —— {@code markEmpty} 是各域的**既定小重复**（域内约定，不做公共抽象）。
     */
    private static final String MARK_EMPTY_LUA =
            "if redis.call('EXISTS', KEYS[1]) == 0 then "
            + "  redis.call('SETEX', KEYS[2], ARGV[1], '1') "
            + "  return 1 "
            + "end "
            + "return 0";

    private static final DefaultRedisScript<Long> COIN_AMOUNT_SCRIPT =
            new DefaultRedisScript<>(COIN_AMOUNT_LUA, Long.class);
    private static final DefaultRedisScript<Long> COIN_COUNT_SCRIPT =
            new DefaultRedisScript<>(COIN_COUNT_LUA, Long.class);
    private static final DefaultRedisScript<Long> BACKFILL_AMOUNT_SCRIPT =
            new DefaultRedisScript<>(BACKFILL_AMOUNT_LUA, Long.class);
    private static final DefaultRedisScript<Long> MARK_EMPTY_SCRIPT =
            new DefaultRedisScript<>(MARK_EMPTY_LUA, Long.class);

    public CoinRedisOps(StringRedisTemplate redis, RedisCircuitBreaker breaker) {
        super(redis, breaker);
    }

    // ==================== 读 ====================

    /**
     * 一趟 pipeline 判断数据 key 与空标记是否存在，返回**存在**的那些 key。
     *
     * <p>三态读必须同时知道两者；两次独立往返会把判定成本翻倍。委托基类
     * {@link RedisOps#existingKeys(String...)}（协议层已收敛）。保留**定长两参**签名，
     * 让桩与断言无歧义（口径同 {@code LikeRedisOps#existingOf}）。
     */
    public Set<String> existingOf(String hashKey, String emptyKey) {
        return existingKeys(hashKey, emptyKey);
    }

    /**
     * 读 HASH 里某内容的枚数。
     *
     * <p>字节语义：{@code null} = **field 不存在**（该用户没给这条内容投过币，或值被别的
     * 失效路径清了）；调用方把它答成 {@code 0}。不要与"整个 key 不存在"混淆 ——
     * 后者在调用方已经先由 {@code existingOf} 判定过。
     */
    public String hget(String hashKey, long contentId) {
        return guarded(() -> (String) redis.opsForHash().get(hashKey, String.valueOf(contentId)));
    }

    /**
     * 一趟批量读多个 String key（计数回填 / 批量读用）。
     *
     * <p>返回与入参**同序**的列表，缺失项为 {@code null}。{@code multiGet} 在 Jedis 下是
     * 一条 {@code MGET}，不会退化成 N 次往返。
     */
    public List<String> mget(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<String> values = guarded(() -> redis.opsForValue().multiGet(keys));
        return values == null ? List.of() : values;
    }

    // ==================== 写 ====================

    /** 条件写用户投币 HASH（写新总量，见 {@link #COIN_AMOUNT_LUA}）。 */
    public void applyCoinAmountConditionalWrite(String hashKey, String emptyKey,
                                                long contentId, int newAmount) {
        eval(COIN_AMOUNT_SCRIPT, List.of(hashKey, emptyKey),
                String.valueOf(contentId), String.valueOf(newAmount));
    }

    /** 条件累加内容投币计数（单调只增，见 {@link #COIN_COUNT_LUA}）。 */
    public void applyCoinCountConditionalIncr(String countKey, int delta) {
        eval(COIN_COUNT_SCRIPT, List.of(countKey), String.valueOf(delta));
    }

    /**
     * 原子回填用户投币 HASH（含 TTL，并清空标记）。
     *
     * <p>{@code amounts} 必须**非空**（空集走 {@link #markEmptyIfAbsent}）；
     * 入参按插入顺序逐对写入（{@link Map} 的迭代序由调用方保证，字段内容与顺序无关）。
     */
    public void backfillCoinAmountHash(String hashKey, String emptyKey,
                                       Map<Long, Integer> amounts, Duration ttl) {
        List<String> args = new ArrayList<>(amounts.size() * 2 + 1);
        args.add(String.valueOf(ttl.toSeconds()));
        for (Map.Entry<Long, Integer> e : amounts.entrySet()) {
            args.add(String.valueOf(e.getKey()));
            args.add(String.valueOf(e.getValue()));
        }
        eval(BACKFILL_AMOUNT_SCRIPT, List.of(hashKey, emptyKey), args.toArray(new Object[0]));
    }

    /**
     * 写空标记（原子，见 {@link #MARK_EMPTY_LUA}）：只在数据 key 不存在时才写。
     * {@code emptyTtl} 是**短 TTL**（口径见 {@code CacheKeys.EMPTY_MARKER_TTL_SECONDS}），
     * 与数据 key 的 TTL 不同。
     */
    public void markEmptyIfAbsent(String dataKey, String emptyKey, Duration emptyTtl) {
        eval(MARK_EMPTY_SCRIPT, List.of(dataKey, emptyKey), String.valueOf(emptyTtl.toSeconds()));
    }

    /**
     * 失效单个 key（内容删除时的 {@code content:coinCount}，以及写失败后的自愈）。
     *
     * <p>命令层只发 {@code DEL}；"失败吞掉还是上抛"是缓存语义，由 {@link CoinCache} 决定
     * —— 内容删除路径必须吞（缓存故障不能让已提交的删除表现成失败）。
     */
    public void invalidate(String key) {
        delete(key);
    }
}
