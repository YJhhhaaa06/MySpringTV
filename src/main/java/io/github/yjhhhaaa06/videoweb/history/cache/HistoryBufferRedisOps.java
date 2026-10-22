package io.github.yjhhhaaa06.videoweb.history.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.RedisCircuitBreaker;
import io.github.yjhhhaaa06.videoweb.common.cache.RedisOps;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * 播放记录**上报缓冲的 Redis 命令层**（`R-19` / `T51-1`）——只负责"把命令 / 脚本发出去"，
 * 不含业务语义（闲置阈值 / 落库 / 失败分流在 {@code HistoryFlushService}）。
 *
 * <p>口径唯一事实源 = {@code architecture/domain/history/history.md} §12.2–12.3。
 *
 * <h2>两个 key</h2>
 * <ul>
 *   <li>{@code history:report:{userId}}（HASH，field = contentId，value = {@code "progress:ts"}）；</li>
 *   <li>{@code history:dirty}（ZSET，member = userId，score = 最后上报 ts）。</li>
 * </ul>
 *
 * <h2>★ 为什么写必须是**一条 Lua**</h2>
 * {@code HSET} 与 {@code ZADD} 若拆成两次往返，中间断连会留下"数据在 hash 里、标记不在 dirty 里"的
 * **孤儿**：它永远不会被轮询捡到，只能等 TTL 静默消失。反向（dirty 有、hash 空）是良性的——轮询空跑一次。
 *
 * <h2>★ 确认（落库后删除）为什么是"条件删"而不是"弹出"</h2>
 * 见 {@link #removeIfUnchanged}。
 *
 * <p>本层不 catch：Redis 失败统一由 {@link RedisOps#guarded} 归一为
 * {@code CacheUnavailableException}（熔断即在那里生效）。
 */
@Component
public class HistoryBufferRedisOps extends RedisOps {

    /**
     * 原子写入：`HSET field "progress:ts"` + `EXPIRE` + `ZADD dirty ts userId`。
     *
     * <p>KEYS = [bufferKey, dirtyKey]；ARGV = [userId, contentId, progress, tsMillis, ttlSeconds]。
     * ★ {@code ZADD} 的 score 与 hash 里的 ts **同源**（都由调用方传同一个 {@code tsMillis}）——
     * 用两个时钟会让"刚上报"被判成"已闲置"。
     */
    private static final String WRITE_LUA = """
            redis.call('HSET', KEYS[1], ARGV[2], ARGV[3] .. ':' .. ARGV[4])
            redis.call('EXPIRE', KEYS[1], ARGV[5])
            redis.call('ZADD', KEYS[2], ARGV[4], ARGV[1])
            return 1
            """;

    /**
     * 条件删除（落库成功后）：只删"值仍是落库时读到的那个"的 field。
     *
     * <p>KEYS = [bufferKey, dirtyKey]；ARGV = [userId, field1, value1, field2, value2, …]。
     *
     * <p>★ <b>为什么不是"原子弹出"</b>：弹出后到写库之间崩溃 ⇒ 数据已经没了，"失败放回"根本执行不到。
     * 条件删把语义变成**至少一次 + 幂等**：重复写的 ODKU 结果完全相同（`update_time` 取自缓冲而非
     * `CURRENT_TIMESTAMP`）⇒ 崩溃只会重写、不会丢。它**同时**解决了"放回覆盖新值"——
     * 期间有新上报 ⇒ 值已变 ⇒ 不删，下一轮写新值。
     *
     * <p>hash 为空才 {@code DEL} + {@code ZREM}（仍有新 field ⇒ 保留脏标记，等下一轮）。
     */
    private static final String ACK_LUA = """
            local removed = 0
            for i = 2, #ARGV, 2 do
              if redis.call('HGET', KEYS[1], ARGV[i]) == ARGV[i + 1] then
                removed = removed + redis.call('HDEL', KEYS[1], ARGV[i])
              end
            end
            if redis.call('HLEN', KEYS[1]) == 0 then
              redis.call('DEL', KEYS[1])
              redis.call('ZREM', KEYS[2], ARGV[1])
            end
            return removed
            """;

    private static final DefaultRedisScript<Long> WRITE_SCRIPT =
            new DefaultRedisScript<>(WRITE_LUA, Long.class);
    private static final DefaultRedisScript<Long> ACK_SCRIPT =
            new DefaultRedisScript<>(ACK_LUA, Long.class);

    public HistoryBufferRedisOps(StringRedisTemplate redis, RedisCircuitBreaker breaker) {
        super(redis, breaker);
    }

    /** 缓冲里的一条：进度（秒）+ 服务端接收时刻（毫秒）。 */
    public record Entry(int progress, long receiveTs) {

        /** 与 {@link #ofRaw} 互逆：写进 hash 的原始字符串形态（条件删要比对**原始串**，不是对象）。 */
        public String raw() {
            return progress + ":" + receiveTs;
        }

        /**
         * 解析 hash 里的原始串；格式不认识 ⇒ {@link Optional#empty()}（**不抛**）。
         *
         * <p>★ 解析失败按"跳过"而不是"报错"：一条坏值不该让整批落库卡死（同
         * {@code FeedDeliveryBuffer} 对不可序列化载荷的处置——重放必然同样失败，只能放弃）。
         */
        public static Optional<Entry> ofRaw(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            int sep = raw.indexOf(':');
            if (sep <= 0 || sep == raw.length() - 1) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Entry(
                        Integer.parseInt(raw.substring(0, sep)),
                        Long.parseLong(raw.substring(sep + 1))));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }

    /**
     * 原子写入一条上报（`HSET` + `EXPIRE` + `ZADD` 一条 Lua）。
     *
     * @param receiveTs 服务端接收时刻（毫秒）；**同时**用作 hash 值的时间戳与 dirty 的 score
     * @param ttl       该用户缓冲的存活时长（每次写都续期：活跃用户不会中途过期）
     */
    public void write(long userId, long contentId, int progress, long receiveTs, Duration ttl) {
        eval(WRITE_SCRIPT, List.of(CacheKeys.historyReport(userId), CacheKeys.HISTORY_DIRTY),
                String.valueOf(userId),
                String.valueOf(contentId),
                String.valueOf(progress),
                String.valueOf(receiveTs),
                String.valueOf(ttl.toSeconds()));
    }

    /**
     * 读一个用户的**全部**脏进度（原始串，保序）。
     *
     * <p>返回原始串而不是 {@link Entry}：条件删要比对"落库时读到的那个字符串"，若先解析再序列化回去，
     * 任何格式归一（如前导零）都会让比对失效 ⇒ 该删的删不掉（重复落库，虽幂等但白跑）。
     */
    public Map<Long, String> readRaw(long userId) {
        Map<Object, Object> entries = guarded(() -> redis.opsForHash().entries(CacheKeys.historyReport(userId)));
        Map<Long, String> result = new LinkedHashMap<>(entries.size());
        for (Map.Entry<Object, Object> e : entries.entrySet()) {
            try {
                result.put(Long.parseLong(String.valueOf(e.getKey())), String.valueOf(e.getValue()));
            } catch (NumberFormatException ignored) {
                // field 不是 contentId（脏键 / 人工写入）⇒ 跳过，不阻断整批
            }
        }
        return result;
    }

    /** 读单个 (userId, contentId) 的缓冲值（`/position` 用；未命中 / 格式坏 ⇒ empty）。 */
    public Optional<Entry> readOne(long userId, long contentId) {
        Object raw = guarded(() -> redis.opsForHash().get(
                CacheKeys.historyReport(userId), String.valueOf(contentId)));
        return raw == null ? Optional.empty() : Entry.ofRaw(String.valueOf(raw));
    }

    /**
     * 落库成功后**条件删**（见 {@link #ACK_LUA}）。
     *
     * @param written 落库时读到的原始 (contentId → raw) 快照
     * @return 实际删掉的 field 数（诊断 / 测试断言用）
     */
    public long removeIfUnchanged(long userId, Map<Long, String> written) {
        if (written.isEmpty()) {
            return 0L;
        }
        Object[] args = new Object[1 + written.size() * 2];
        args[0] = String.valueOf(userId);
        int i = 1;
        for (Map.Entry<Long, String> e : written.entrySet()) {
            args[i++] = String.valueOf(e.getKey());
            args[i++] = e.getValue();
        }
        Long removed = eval(ACK_SCRIPT, List.of(CacheKeys.historyReport(userId), CacheKeys.HISTORY_DIRTY), args);
        return removed == null ? 0L : removed;
    }

    /**
     * 取"最后一次上报早于 {@code cutoffMillis}"的用户（按 score 升序，最久的先出）。
     *
     * <p>一条 {@code ZRANGEBYSCORE -inf cutoff LIMIT 0 n} 同时回答"谁脏"与"谁够闲"——
     * 这是选 ZSET 而不是"扫 hash 判 mtime"的全部理由。
     */
    public List<Long> idleUserIds(long cutoffMillis, int limit) {
        var members = guarded(() -> redis.opsForZSet().rangeByScore(
                CacheKeys.HISTORY_DIRTY, Double.NEGATIVE_INFINITY, cutoffMillis, 0, limit));
        List<Long> result = new ArrayList<>();
        if (members != null) {
            for (String member : members) {
                try {
                    result.add(Long.parseLong(member));
                } catch (NumberFormatException ignored) {
                    // 非数字成员（人工写入 / 串了别的 key）⇒ 跳过
                }
            }
        }
        return result;
    }

    /** 把用户从脏集合里摘掉（仅在缓冲已空时被 {@link #removeIfUnchanged} 调用；也可供运维用）。 */
    public void removeDirty(long userId) {
        guarded(() -> redis.opsForZSet().remove(CacheKeys.HISTORY_DIRTY, String.valueOf(userId)));
    }

    /**
     * 读 Redis 的 `maxmemory-policy`（启动自检用；**不参与熔断**——它是诊断，不是业务路径）。
     *
     * <p>★ 返回 {@link Optional#empty()} 的三种合法情形：命令被托管 Redis 禁用、连接失败、
     * 返回体不是 `Properties`。自检**绝不因此阻断启动**——它只是把"缓冲可能被淘汰"这条
     * 部署前提变成一条可观测的 WARN。
     */
    public Optional<String> maxmemoryPolicy() {
        try {
            Properties props = redis.execute((RedisCallback<Properties>) (RedisConnection connection) ->
                    connection.serverCommands().getConfig("maxmemory-policy"));
            if (props == null) {
                return Optional.empty();
            }
            String value = props.getProperty("maxmemory-policy");
            return value == null ? Optional.empty() : Optional.of(value);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
