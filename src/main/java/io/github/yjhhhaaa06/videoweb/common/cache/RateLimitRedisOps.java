package io.github.yjhhhaaa06.videoweb.common.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 限流桶的 **Redis 命令层**（限流专项 T24）——只负责"把脚本发出去"，
 * 不含接线语义（覆盖范围 / 429 出口 / fail-open 都在过滤器，T25）。
 * 口径的唯一事实源 = {@code architecture/tech/限流.md}；文中后续任务编号的锚点：
 * {@code T25} → §三（接线），{@code T26} → §四（权重），{@code T27} → §四（任务自付费），
 * {@code T28} → §五（可观测）。
 *
 * <h2>结构：两个记账主体、两类桶</h2>
 * 用户桶（per-key：登录 {@code ratelimit:user:{id}} / 匿名 {@code ratelimit:anon:{ip}}，
 * 见 {@link CacheKeys}）与服务器桶（全局单桶 {@code ratelimit:server}）。
 * 请求侧**一次 Lua**（{@code KEYS = [userKey, serverKey]}）覆盖"惰性补充 → 询问费 → 闸门 → 放行扣减"；
 * 任务侧单独一次 Lua（{@code KEYS = [serverKey]}，见 {@link #deductTaskCost}）。
 * ★ T26 追加**门后差额**一次 Lua（{@code KEYS = [userKey, serverKey]}，见 {@link #deductDelta}，
 * 信号②：仅对"门放行过"的请求、在请求收尾时补扣 {@code base × (multiplier − 1)}——
 * miss 是请求运行期事实、门时取不到，只能事后结算）。
 *
 * <h2>★ 非对称扣减（一次 Lua 内的两阶段写法）</h2>
 * 用户桶**无条件扣询问费**（被拒也扣——否则被拒的请求是免费探路，刷子可以零成本试探）；
 * 服务器桶**只在放行时扣**（被拒时"一个字节都不写回"——只读了它的 refill 值用于判定，不落盘）。
 * 两段天然分离 ⇒ 不需要跨 key 的原子判定。
 *
 * <h2>桶的状态编码与 TTL</h2>
 * 每桶一个 key，值 = 紧凑字符串 {@code "tokens:lastRefillMs"}（如 {@code "12.5:1712345678901"}）。
 * 选紧凑字符串而非 HASH：GET/SET 各一次、脚本内解析一次 split 即可；
 * ⚠️ 也**不用 INCRBYFLOAT 一类命令**：惰性补充要先 {@code min(cap, …)} 截断再扣，
 * 这是"读-改-写"，非单命令可表达（必须 Lua）。
 * <p>TTL = {@code ceil(capacity / refillPerSec)}（把空桶补满所需时间），**写即刷新**：
 * 用户桶每次访问都写（放行 / 被拒都刷新）；服务器桶只在放行与任务扣减时写
 * （被拒时它一个字节都不写、也就无从刷新——其淘汰按自证性质无损）。
 * ★ 对用户桶，"每访必刷"是必须的：TTL 自证性质以"淘汰 ⟺ 空闲 ≥ TTL"为前提，不刷新则活跃桶也会到期。
 * ★ 自证边界的精确表述（T24 的复核结论，见 {@code architecture/tech/限流.md}；登记为 {@code I-18}）：对 {@code tokens ≥ 0} 的桶，
 * 淘汰 ⟺ 空闲 ≥ 满桶时间 ⇒ 按惰性公式它本来就该是满桶 ⇒ **淘汰无损**；
 * 对 {@code tokens < 0}（欠账态）的桶，淘汰会把**剩余欠账豁免**（本仓首版参数恰整除，差额 = |t|）
 * ——这是语义选择而非缺陷："沉默满桶时间后重新做人"；持续活跃的刷子不受影响（访问使 TTL 恒被刷新）。
 *
 * <h2>★ 惰性补充的两条纪律</h2>
 * ① 任何一次写（请求侧、任务侧）都必须"先补到 now、再改、再写回并更新 lastRefillMs"——
 * 否则近满桶时这笔改动会被后续 refill 悄悄吸收（扣了等于没扣）；
 * ② 补充用 {@code redis.call('TIME')} 取时钟（与 TTL 淘汰同一时钟源；Redis 5+ 脚本内 TIME 合法）。
 *
 * <h2>返回值承载：紧凑字符串（T24 的选定：行为确定 / 可调试 / 报错可定位）</h2>
 * Lua 返回 {@code "1:0"} / {@code "0:5"}（{@code allowed:retryAfterSeconds}）。
 * 不用 {@code RedisScript<List>}：字符串行为完全确定、可在 redis-cli 直接观察、解析失败可精确报错。
 *
 * <h2>失败边界（本层不 catch）</h2>
 * Redis 不可达 ⇒ 基类 {@code guarded} 归一为 {@link CacheUnavailableException}（fail-open 归 T25）；
 * ★ 返回值解析失败 ⇒ {@link RateLimitScriptException}——**故意不归一的第二种失败**：
 * 解析发生在 {@code eval} 返回之后（guarded 之外），T28 才能把"Redis 挂了（WARN）"与
 * "脚本 / 解析异常（ERROR）"在日志上分档。
 *
 * <h2>参数为什么由调用方传入</h2>
 * 本类不注入 {@code RateLimitProperties}：保持"纯命令层"（与既有六个 {@code *RedisOps} 子类同形），
 * 桶参数 / 成本 / 折扣由 T25 的过滤器从配置取值后传入——测试也可以用小数值直接驱动、不依赖全局配置。
 */
@Component
public class RateLimitRedisOps extends RedisOps {

    /**
     * 请求侧脚本（一次 Lua 覆盖全流程）。KEYS: {@code [userKey, serverKey]}；
     * ARGV: {@code [userCap, userRate, userTtl, serverCap, serverRate, serverTtl, cost, rejectDiscount]}。
     *
     * <p>返回 {@code "allowed:retrySeconds"}——被拒时 retry = 两桶"补到可过闸"所需时间的**较大者**，
     * 向上取整；已够的一侧计 0；放行为 {@code "1:0"}。
     * ★ 两桶的补足目标都是 {@code cost}——⚠️ 用户桶**不是** {@code cost × (1 − discount)}：
     * 下一次进入闸门前还会**再扣一次询问费** {@code cost × discount}，届时桶存量需 {@code ≥ cost}
     * 才过得了闸；取尾款会把 Retry-After 系统性偏小 {@code cost × discount / rate}。
     * （"放行前扣的尾款"与"等待补足的目标"是两个量，别混——见类注释的非对称扣减。）
     */
    private static final String ACQUIRE_LUA = """
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)

            local function refill(key, cap, rate)
              local raw = redis.call('GET', key)
              if not raw then
                return cap
              end
              local p = string.find(raw, ':')
              local tokens = tonumber(string.sub(raw, 1, p - 1))
              local last = tonumber(string.sub(raw, p + 1))
              return math.min(cap, tokens + (now - last) * rate / 1000)
            end

            local uCap = tonumber(ARGV[1])
            local uRate = tonumber(ARGV[2])
            local sCap = tonumber(ARGV[4])
            local sRate = tonumber(ARGV[5])
            local cost = tonumber(ARGV[7])
            local discount = tonumber(ARGV[8])
            local tail = cost * (1 - discount)

            local uTokens = refill(KEYS[1], uCap, uRate) - cost * discount
            local sTokens = refill(KEYS[2], sCap, sRate)

            local allowed = (uTokens >= tail) and (sTokens >= cost)

            local retry = 0
            if allowed then
              uTokens = uTokens - tail
              sTokens = sTokens - cost
              redis.call('SET', KEYS[2], tostring(sTokens) .. ':' .. tostring(now))
              redis.call('EXPIRE', KEYS[2], ARGV[6])
            else
              local uw = 0
              if uTokens < cost then uw = (cost - uTokens) / uRate end
              local sw = 0
              if sTokens < cost then sw = (cost - sTokens) / sRate end
              if sw > uw then uw = sw end
              retry = math.ceil(uw)
            end

            redis.call('SET', KEYS[1], tostring(uTokens) .. ':' .. tostring(now))
            redis.call('EXPIRE', KEYS[1], ARGV[3])

            if allowed then
              return '1:0'
            end
            return '0:' .. tostring(retry)
            """;

    /**
     * 任务侧脚本（无条件扣服务器桶，可为负）。KEYS: {@code [serverKey]}；
     * ARGV: {@code [serverCap, serverRate, serverTtl, taskCost]}。
     *
     * <p>与请求侧同样"先补后扣"（类注释纪律①）：不补齐的话，近满桶时这笔任务成本
     * 会被下一次请求的 refill 悄悄吸收。不碰任何用户桶。
     */
    private static final String DEDUCT_TASK_LUA = """
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)

            local cap = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local raw = redis.call('GET', KEYS[1])
            local tokens
            if raw then
              local p = string.find(raw, ':')
              tokens = tonumber(string.sub(raw, 1, p - 1))
              local last = tonumber(string.sub(raw, p + 1))
              tokens = math.min(cap, tokens + (now - last) * rate / 1000)
            else
              tokens = cap
            end

            tokens = tokens - tonumber(ARGV[4])

            redis.call('SET', KEYS[1], tostring(tokens) .. ':' .. tostring(now))
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """;

    /**
     * 门后差额脚本（信号②；T26）。KEYS: {@code [userKey, serverKey]}；
     * ARGV: {@code [userCap, userRate, userTtl, serverCap, serverRate, serverTtl, delta]}。
     *
     * <p>与任务侧同样"先补后扣"（类注释纪律①）：不补齐的话，近满桶时这笔差额会被下一次
     * 请求的 refill 悄悄吸收。两桶**无条件**扣、可为负（D-6 的欠账态）、不设折扣
     * （请求已经放行，差额是全款的一部分）——这是**事后结算**，不做任何判定。
     */
    private static final String DEDUCT_DELTA_LUA = """
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)

            local function refill(key, cap, rate)
              local raw = redis.call('GET', key)
              if not raw then
                return cap
              end
              local p = string.find(raw, ':')
              local tokens = tonumber(string.sub(raw, 1, p - 1))
              local last = tonumber(string.sub(raw, p + 1))
              return math.min(cap, tokens + (now - last) * rate / 1000)
            end

            local delta = tonumber(ARGV[7])
            local uTokens = refill(KEYS[1], tonumber(ARGV[1]), tonumber(ARGV[2])) - delta
            local sTokens = refill(KEYS[2], tonumber(ARGV[4]), tonumber(ARGV[5])) - delta

            redis.call('SET', KEYS[1], tostring(uTokens) .. ':' .. tostring(now))
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            redis.call('SET', KEYS[2], tostring(sTokens) .. ':' .. tostring(now))
            redis.call('EXPIRE', KEYS[2], ARGV[6])
            return 1
            """;

    private static final DefaultRedisScript<String> ACQUIRE_SCRIPT =
            new DefaultRedisScript<>(ACQUIRE_LUA, String.class);
    private static final DefaultRedisScript<Long> DEDUCT_TASK_SCRIPT =
            new DefaultRedisScript<>(DEDUCT_TASK_LUA, Long.class);
    private static final DefaultRedisScript<Long> DEDUCT_DELTA_SCRIPT =
            new DefaultRedisScript<>(DEDUCT_DELTA_LUA, Long.class);

    public RateLimitRedisOps(StringRedisTemplate redis, RedisCircuitBreaker breaker) {
        super(redis, breaker);
    }

    /**
     * 请求侧过闸：一次 Lua 完成"惰性补充 + 询问费 + 闸门（两桶 AND）+ 放行扣减"。
     *
     * @param userKey        用户桶 key（{@link CacheKeys#rateLimitUser(long)} /
     *                       {@link CacheKeys#rateLimitAnon(String)}）
     * @param serverKey      服务器桶 key（{@link CacheKeys#rateLimitServer()}）
     * @param userBucket     用户桶参数（容量 / 补充速率；TTL 由此派生）
     * @param serverBucket   服务器桶参数
     * @param cost           本次请求折算的令牌数（信号① × 信号② 由调用方算好传入；
     *                       权重表接线归 T26，见 {@code architecture/tech/限流.md} §四）
     * @param rejectDiscount 被拒时仍扣的"询问费"比例（配置**绑定期**校验 ∈ [0,1]；默认 0.3 落在
     *                       开区间，由绑定测试兜住）
     * @return 放行与否 + Retry-After 秒（放行时为 0）
     */
    public Decision tryAcquire(String userKey, String serverKey,
                               BucketSpec userBucket, BucketSpec serverBucket,
                               double cost, double rejectDiscount) {
        String raw = eval(ACQUIRE_SCRIPT, List.of(userKey, serverKey),
                String.valueOf(userBucket.capacity()),
                String.valueOf(userBucket.refillPerSec()),
                String.valueOf(userBucket.ttlSeconds()),
                String.valueOf(serverBucket.capacity()),
                String.valueOf(serverBucket.refillPerSec()),
                String.valueOf(serverBucket.ttlSeconds()),
                String.valueOf(cost),
                String.valueOf(rejectDiscount));
        return parseDecision(raw);
    }

    /**
     * 任务侧扣减：无条件扣服务器桶（可为负），不碰任何用户桶。
     *
     * <p>为什么无条件、为什么只扣服务器桶：任务成本**不属于任何用户**（去抖把多人的触发
     * 合并成一次执行），且任务跑在 MQ 消费线程上、与请求线程不是同一执行上下文。
     * <p>⚠️ "扣减失败不得让任务失败"（fail-open）是调用方（T27 的 feed 重建落点）的职责——
     * 本层照常抛 {@link CacheUnavailableException}。
     */
    public void deductTaskCost(String serverKey, BucketSpec serverBucket, int taskCost) {
        eval(DEDUCT_TASK_SCRIPT, List.of(serverKey),
                String.valueOf(serverBucket.capacity()),
                String.valueOf(serverBucket.refillPerSec()),
                String.valueOf(serverBucket.ttlSeconds()),
                String.valueOf(taskCost));
    }

    /**
     * 门后差额补扣（信号②，T26）：对**门放行过**的请求，按 {@code base × (multiplier − 1)}
     * 无条件扣**两桶**（可为负、不设折扣——请求已放行，差额是全款的一部分；这次调用不做任何判定）。
     *
     * <p>为什么两桶都扣：成本在门时是两桶联合判定的输入（见 {@link #tryAcquire}），
     * 请求结束时的**真实成本**同样属于两桶——服务器桶代表"实例整体还能接多少活"，
     * 缓存回源确实多消耗了它。
     *
     * <p>⚠️ 本方法由请求**收尾**处调用（响应已在途 / 已写出）——Redis 不可达抛
     * {@link CacheUnavailableException}、脚本/解析异常抛 {@link RateLimitScriptException}，
     * "补扣失败不得让已完成的响应变失败"（fail-open + 兜底）是**调用方**的职责。
     */
    public void deductDelta(String userKey, String serverKey,
                            BucketSpec userBucket, BucketSpec serverBucket, double delta) {
        eval(DEDUCT_DELTA_SCRIPT, List.of(userKey, serverKey),
                String.valueOf(userBucket.capacity()),
                String.valueOf(userBucket.refillPerSec()),
                String.valueOf(userBucket.ttlSeconds()),
                String.valueOf(serverBucket.capacity()),
                String.valueOf(serverBucket.refillPerSec()),
                String.valueOf(serverBucket.ttlSeconds()),
                String.valueOf(delta));
    }

    /**
     * 解析脚本返回值（{@code "allowed:retrySeconds"}）。
     *
     * <p>★ 解析刻意放在 {@code eval}（含 {@code guarded} 的异常归一）**之外**：
     * 失败抛 {@link RateLimitScriptException} 而非被归一成 {@link CacheUnavailableException}
     * ——否则"限流器自身缺陷"会被 fail-open 伪装成"Redis 挂了"（T28 日志分档的依据）。
     *
     * <p>不做宽松容错：返回值由本类自己的脚本产生，格式漂移 = 代码缺陷，应显式爆出（ERROR）。
     */
    private static Decision parseDecision(String raw) {
        if (raw == null || raw.length() < 3 || raw.charAt(1) != ':'
                || (raw.charAt(0) != '0' && raw.charAt(0) != '1')) {
            throw new RateLimitScriptException(
                    "限流脚本返回值无法解析: [" + raw + "]（期望 \"<0|1>:<retrySeconds>\"）");
        }
        try {
            return new Decision(raw.charAt(0) == '1', Long.parseLong(raw.substring(2)));
        } catch (NumberFormatException e) {
            throw new RateLimitScriptException("限流脚本返回值无法解析: [" + raw + "]", e);
        }
    }

    /**
     * 单个桶的参数规格（容量 / 补充速率）。TTL 由此派生，调用方不需要自己算。
     *
     * @param capacity     容量（上界；{@code min(cap, …)} 的截断点）
     * @param refillPerSec 每秒补充速率
     */
    public record BucketSpec(int capacity, int refillPerSec) {

        public BucketSpec {
            if (capacity <= 0 || refillPerSec <= 0) {
                throw new IllegalArgumentException(
                        "桶参数必须为正：capacity=" + capacity + ", refillPerSec=" + refillPerSec);
            }
        }

        /**
         * {@code TTL = ceil(capacity / refillPerSec)} = 把空桶补满所需时间（秒，向上取整）。
         *
         * <p>向上取整是为了"淘汰后重建为满桶"与"惰性公式算出满桶"对齐（自证性质，
         * 见类注释与 {@code architecture/tech/限流.md}）。
         */
        public long ttlSeconds() {
            return ((long) capacity + refillPerSec - 1) / refillPerSec;
        }
    }

    /**
     * 过闸结果。
     *
     * @param allowed          是否放行
     * @param retryAfterSeconds 被拒时的 {@code Retry-After}（秒，向上取整；放行时为 0）
     */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }
}
