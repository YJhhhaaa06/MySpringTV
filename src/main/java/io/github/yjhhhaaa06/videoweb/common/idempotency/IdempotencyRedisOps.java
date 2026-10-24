package io.github.yjhhhaaa06.videoweb.common.idempotency;

import io.github.yjhhhaaa06.videoweb.common.cache.RedisCircuitBreaker;
import io.github.yjhhhaaa06.videoweb.common.cache.RedisOps;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 幂等状态机的 **Redis 命令层**（写接口幂等专项 `T66`）——只负责"把脚本发出去 + 解析返回值"，
 * 语义（占位 / 重放 / 409 / 降级）在 {@link IdempotencyStore} 与 {@link IdempotencyFilter}。
 * 口径的唯一事实源 = {@code architecture/tech/idempotency/写接口幂等.md} §五 / §十。
 *
 * <h2>存储结构：HASH（不是 JSON 字符串）</h2>
 * <pre>{@code
 * KEY   idem:{slug}:{uid}:{clientKey}
 * FIELD state   PENDING | DONE | FAILED
 * FIELD owner   随机 token（CAS 用）
 * FIELD fp      SHA-256(请求体)，仅 JSON 端点、仅 DONE（multipart 无指纹 ⇒ 空）
 * FIELD code    首次响应的 HTTP status
 * FIELD body    首次响应的 body（<= snapshot-bytes）
 * FIELD ctype   Content-Type
 * TTL   占位 = 注册表的 pendingTtlSeconds；DONE = ttl.done-seconds；FAILED = ttl.failed-seconds
 * }</pre>
 * 选 HASH 的理由：{@code COMPLETE} 要写 4 个字段、{@code RELEASE} 只改 {@code state}；
 * 字符串形态下两者都得先读再整体写回。
 *
 * <h2>★ 三个脚本，禁"无条件写"</h2>
 * <table>
 *   <caption>脚本语义</caption>
 *   <tr><th>脚本</th><th>条件</th><th>返回</th></tr>
 *   <tr><td>{@code ACQUIRE}</td><td>不存在 <b>或</b> {@code state=FAILED}</td>
 *       <td>{@code ACQUIRED} / {@code PENDING}（别人的）/ {@code DONE + 快照}</td></tr>
 *   <tr><td>{@code COMPLETE}</td><td>{@code state=PENDING && owner==ARGV.owner}</td><td>1 / 0</td></tr>
 *   <tr><td>{@code RELEASE}</td><td>同上</td><td>1 / 0</td></tr>
 * </table>
 * <p>★ {@code owner} CAS 是<b>防串扰的唯一手段</b>：A 超时（占位 TTL 已过）→ B 占位 → A 的
 * {@code finally} 若无条件写 {@code FAILED}，会打掉 B 的占位 ⇒ C 又能占位 ⇒ <b>三重执行</b>。
 * 同一手法与 {@code FeedRedisOps#releaseLock} 同源。
 * <p>★ {@code ACQUIRE} 必须区分"我占到了"与"别人占着"——两者落库结果相同但语义相反
 * （前者进 chain、后者 409），故脚本返回 {@code ACQUIRED} / {@code PENDING} 两个不同的串。
 *
 * <h2>返回值承载：紧凑字符串 + 单元分隔符</h2>
 * 不用 {@code RedisScript<List>}（泛型擦除 + 元素类型不确定），也不拼 {@code :}
 * （JSON body 里满是冒号）：{@code DONE} 分支返回
 * {@code "DONE\u001Ffp\u001Fcode\u001Fctype\u001Fbody"}，<b>body 恒为最后一段</b>
 * （按前 4 个分隔符切，余下整体就是 body）。状态串只接受脚本自己产出的值，
 * 其余一律 {@link IdempotencyScriptException}（"差一位就静默当成未命中"是这机制最危险的失败形态）。
 *
 * <h2>失败边界（本层不 catch）</h2>
 * Redis 不可达 ⇒ 基类 {@code guarded} 归一为 {@code CacheUnavailableException}；
 * ★ 返回值解析失败 / 缺字段（{@code DONE} 却没有 code/body/ctype）⇒ {@link IdempotencyScriptException}
 * ——<b>故意不归一的第二种失败</b>，让过滤器把"Redis 挂了（WARN）"与"幂等器自身缺陷（ERROR 持栈）"分档。
 *
 * <h2>为什么参数由调用方传入</h2>
 * 与既有 {@code *RedisOps} 子类同形：本类不注入 {@link IdempotencyProperties}，TTL 由 Store 传入
 * （端点级 PENDING TTL 来自注册表、DONE/FAILED 来自配置）——测试可以用小数值直接驱动。
 */
@Component
public class IdempotencyRedisOps extends RedisOps {

    /** 状态串与快照字段间的分隔符（Redis 协议二进制安全，JSON 里不会出现裸 0x1F）。 */
    private static final char SEP = 31;

    /**
     * 占位脚本。KEYS: {@code [key]}；ARGV: {@code [owner, pendingTtlSeconds]}。
     *
     * <p>不存在 <b>或</b> {@code FAILED} ⇒ 覆盖成 {@code PENDING}（TTL = pending）；
     * 其余状态<b>一个字节都不写</b>：
     * <ul>
     *   <li>{@code DONE} ⇒ 连带把快照带出去（省一次 HGETALL，也避免"读到 DONE 后 key 刚好过期"的裂窗）；</li>
     *   <li>{@code PENDING} ⇒ 只报状态（别人的占位，调用方短轮询 / 409）；</li>
     *   <li>其它值 / {@code DONE} 缺字段 ⇒ {@code CORRUPT}（解析即抛，ERROR）。</li>
     * </ul>
     * ★ {@code FAILED} 只是墓碑：命中它的行为完全等同"无记录"（重新占位执行），不承载分支。
     */
    private static final String ACQUIRE_LUA = """
            local state = redis.call('HGET', KEYS[1], 'state')
            if (not state) or state == 'FAILED' then
              redis.call('DEL', KEYS[1])
              redis.call('HSET', KEYS[1], 'state', 'PENDING', 'owner', ARGV[1])
              redis.call('EXPIRE', KEYS[1], ARGV[2])
              return 'ACQUIRED'
            end
            if state == 'PENDING' then
              return 'PENDING'
            end
            if state == 'DONE' then
              local fp = redis.call('HGET', KEYS[1], 'fp')
              local code = redis.call('HGET', KEYS[1], 'code')
              local ctype = redis.call('HGET', KEYS[1], 'ctype')
              local body = redis.call('HGET', KEYS[1], 'body')
              if (not code) or (not ctype) or (not body) then
                return 'CORRUPT'
              end
              local sep = string.char(31)
              return 'DONE' .. sep .. (fp or '') .. sep .. code .. sep .. ctype .. sep .. body
            end
            return 'CORRUPT'
            """;

    /**
     * 固化脚本。KEYS: {@code [key]}；ARGV: {@code [owner, code, ctype, fp, body, doneTtlSeconds]}。
     *
     * <p>只在 {@code state=PENDING && owner==本请求} 时生效（★ 禁无条件写）。TTL 同时改成
     * {@code ttl.done-seconds}——{@code DONE} 的存活时间与占位时间不是同一个量。
     */
    private static final String COMPLETE_LUA = """
            if redis.call('HGET', KEYS[1], 'state') ~= 'PENDING' then
              return 0
            end
            if redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1] then
              return 0
            end
            redis.call('HSET', KEYS[1], 'state', 'DONE', 'code', ARGV[2], 'ctype', ARGV[3],
                       'fp', ARGV[4], 'body', ARGV[5])
            redis.call('EXPIRE', KEYS[1], ARGV[6])
            return 1
            """;

    /**
     * 墓碑脚本。KEYS: {@code [key]}；ARGV: {@code [owner, failedTtlSeconds]}。
     *
     * <p>同样 CAS（★ 见类注释）：若已经写成 {@code DONE}（成功路径刚刚收尾），本脚本必然返回 0 ——
     * 这正是"一次客户端断连不会把写好的 DONE 覆盖成 FAILED"的实现。
     */
    private static final String RELEASE_LUA = """
            if redis.call('HGET', KEYS[1], 'state') ~= 'PENDING' then
              return 0
            end
            if redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1] then
              return 0
            end
            redis.call('HSET', KEYS[1], 'state', 'FAILED')
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
            """;

    private static final DefaultRedisScript<String> ACQUIRE_SCRIPT =
            new DefaultRedisScript<>(ACQUIRE_LUA, String.class);
    private static final DefaultRedisScript<Long> COMPLETE_SCRIPT =
            new DefaultRedisScript<>(COMPLETE_LUA, Long.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>(RELEASE_LUA, Long.class);

    public IdempotencyRedisOps(StringRedisTemplate redis, RedisCircuitBreaker breaker) {
        super(redis, breaker);
    }

    /**
     * 占位或读快照（原子）。
     *
     * @param key                {@code idem:{slug}:{uid}:{clientKey}}
     * @param owner              本请求的随机 token（CAS 凭据）
     * @param pendingTtlSeconds  端点级占位 TTL（来自注册表）
     * @return {@code ACQUIRED}（占到了，可进 chain）/ {@code PENDING}（别人占着）/ {@code DONE + 快照}
     * @throws io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException Redis 不可达 / 熔断
     * @throws IdempotencyScriptException 返回值无法解析 / {@code DONE} 缺字段
     */
    public Acquired acquire(String key, String owner, int pendingTtlSeconds) {
        String raw = eval(ACQUIRE_SCRIPT, List.of(key), owner, String.valueOf(pendingTtlSeconds));
        return parseAcquire(raw);
    }

    /**
     * 固化首次响应（CAS）。
     *
     * @return {@code true} = 本次 CAS 生效（已写 {@code DONE + 快照}）；
     *         {@code false} = 占位已不属于本请求（被接管 / 已过期 / 已是 DONE）——<b>不是错误</b>
     * @throws IdempotencyScriptException 返回值不是 0/1
     */
    public boolean complete(String key, String owner, int status, String contentType,
                            String fingerprint, String body, int doneTtlSeconds) {
        Long raw = eval(COMPLETE_SCRIPT, List.of(key), owner, String.valueOf(status), contentType,
                fingerprint == null ? "" : fingerprint, body, String.valueOf(doneTtlSeconds));
        return parseFlag(raw, "COMPLETE");
    }

    /**
     * 写墓碑（CAS）。
     *
     * @return {@code true} = 本请求的占位已改成 {@code FAILED}；
     *         {@code false} = 占位已不属于本请求（★ 成功路径上"已写 DONE"就落在这里）
     * @throws IdempotencyScriptException 返回值不是 0/1
     */
    public boolean release(String key, String owner, int failedTtlSeconds) {
        Long raw = eval(RELEASE_SCRIPT, List.of(key), owner, String.valueOf(failedTtlSeconds));
        return parseFlag(raw, "RELEASE");
    }

    /**
     * 解析 {@code ACQUIRE} 的三种合法返回。
     *
     * <p>★ 不做宽松容错：返回值由本类自己的脚本产生，格式漂移 = 代码缺陷，必须显式爆出（ERROR）。
     * 特别注意不要"认不出来就当未命中"——那会把幂等保护静默关掉。
     */
    private static Acquired parseAcquire(String raw) {
        if (raw == null) {
            throw new IdempotencyScriptException(
                    "幂等脚本返回值无法解析: [null]（期望 ACQUIRED / PENDING / DONE…）");
        }
        if ("ACQUIRED".equals(raw)) {
            return new Acquired(State.ACQUIRED, null);
        }
        if ("PENDING".equals(raw)) {
            return new Acquired(State.PENDING, null);
        }
        if (raw.startsWith("DONE" + SEP)) {
            return new Acquired(State.DONE, parseSnapshot(raw));
        }
        // 含脚本显式返回的 CORRUPT（DONE 缺字段）与任何未知状态值
        throw new IdempotencyScriptException(
                "幂等脚本返回值无法解析: [" + abbreviate(raw) + "]（期望 ACQUIRED / PENDING / DONE…）");
    }

    /**
     * 解析 {@code "DONE\u001Ffp\u001Fcode\u001Fctype\u001Fbody"}。
     *
     * <p>按<b>前 4 个</b>分隔符切，第 5 段起原样并入 body —— body 是 JSON，虽然 JSON 本身不能含裸
     * {@code 0x1F}，但不依赖这一点更安全（将来若快照换成别的格式，这段不用重写）。
     * 同时做**自洽校验**：状态码必须是 3 位数、Content-Type 非空 —— 缺一个就说明记录被写坏。
     */
    private static Snapshot parseSnapshot(String raw) {
        String rest = raw.substring("DONE".length() + 1);
        String[] head = new String[3];
        int from = 0;
        for (int i = 0; i < head.length; i++) {
            int cut = rest.indexOf(SEP, from);
            if (cut < 0) {
                throw new IdempotencyScriptException(
                        "幂等脚本返回值无法解析: DONE 快照缺字段（期望 DONE·fp·code·ctype·body）");
            }
            head[i] = rest.substring(from, cut);
            from = cut + 1;
        }
        String body = rest.substring(from);
        int status;
        try {
            status = Integer.parseInt(head[1]);
        } catch (NumberFormatException e) {
            throw new IdempotencyScriptException(
                    "幂等脚本返回值无法解析: DONE 快照的 code 不是整数 [" + abbreviate(head[1]) + "]", e);
        }
        if (status < 100 || status > 599 || head[2].isEmpty()) {
            throw new IdempotencyScriptException(
                    "幂等脚本返回值自相矛盾: DONE 快照 code=" + status
                            + " ctype=[" + abbreviate(head[2]) + "]（必须是 3 位状态码 + 非空 Content-Type）");
        }
        return new Snapshot(head[0].isEmpty() ? null : head[0], status, head[2], body);
    }

    /** {@code COMPLETE} / {@code RELEASE} 的 1/0 返回值：null 与任何非 0/1 都按解析失败处理。 */
    private static boolean parseFlag(Long raw, String script) {
        if (raw == null) {
            throw new IdempotencyScriptException("幂等脚本 " + script + " 返回 null（期望 1 / 0）");
        }
        return switch (raw.intValue()) {
            case 1 -> true;
            case 0 -> false;
            default -> throw new IdempotencyScriptException(
                    "幂等脚本 " + script + " 返回值无法解析: [" + raw + "]（期望 1 / 0）");
        };
    }

    /** 日志/异常里出现的返回值可能含客户端 body：只留前 64 个字符。 */
    private static String abbreviate(String raw) {
        if (raw == null) {
            return "null";
        }
        String flat = raw.replace(SEP, '|').replace('\n', ' ');
        return flat.length() <= 64 ? flat : flat.substring(0, 64) + "…";
    }

    /** {@code ACQUIRE} 的结果类别（★ "我占到了"与"别人占着"必须分开）。 */
    public enum State {
        /** 本请求刚写下 {@code PENDING}（或从 {@code FAILED} 墓碑接管）⇒ 进 chain 执行。 */
        ACQUIRED,
        /** 同一个 key 的上一次仍在处理 ⇒ 短轮询 / 409。 */
        PENDING,
        /** 已有固化结果 ⇒ 重放。 */
        DONE
    }

    /**
     * 首次响应快照（重放所需的<b>最小集</b>）。
     *
     * @param fingerprint {@code SHA-256(请求体)}；{@code null} = 无指纹（multipart / 请求体超限 / handler 未读 body）
     * @param status      首次响应的 HTTP 状态（只会是 2xx——非 2xx 从不进快照）
     * @param contentType 首次响应的 Content-Type（原样回放）
     * @param body        首次响应的 body（UTF-8 解码后的字符串，<= {@code limits.snapshot-bytes}）
     */
    public record Snapshot(String fingerprint, int status, String contentType, String body) {

        /** 按 UTF-8 取回字节（写回响应时用）。 */
        public byte[] bodyBytes() {
            return body.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * {@code ACQUIRE} 的完整结果。
     *
     * @param state    结果类别
     * @param snapshot 仅 {@link State#DONE} 非 {@code null}
     */
    public record Acquired(State state, Snapshot snapshot) {
    }
}
