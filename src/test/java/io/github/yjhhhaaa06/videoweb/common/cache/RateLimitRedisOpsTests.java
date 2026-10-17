package io.github.yjhhhaaa06.videoweb.common.cache;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * {@code RateLimitRedisOps}（限流桶的 Redis 命令层）的**契约测试**（限流专项 T24；
 * 口径见 {@code architecture/tech/限流.md}）。
 *
 * <h2>两侧独立 oracle（防迎合实现）</h2>
 * 桶状态的断言全部**直读 Redis 键**（键名与值格式**硬编码字面量**——键形状是冻结契约，
 * 另见 {@link #桶键命名冻结()}），不拿脚本返回值自证；并在 Java 侧按惰性公式**独立复算**。
 * ★ 复算的技巧：用**脚本自己写回的 {@code lastRefillMs}**（= 脚本内的 now）作为时间参照反推补充量
 * ⇒ 复算与脚本共用同一个"时间差"，**无时钟近似**，容差给 1e-6 即可（远严于"删掉补充差 4 个令牌"这类注入的判别需求）。
 * ⚠️ 该技巧的**代价**：复算对"now 推导本身出错"（单位 / 截断类偏差）无判别力（两边同错 ⇒ 自洽）。
 * 对冲 = {@link #老桶按公式复算()} 里一条**真实时钟锚定**断言（Java 时钟距离 ⇒ 补充量区间）。
 *
 * <h2>期望值的推导口径（不是抄实现）</h2>
 * 全部由三个参数按设计语义推出：容量 {@code cap}、速率 {@code rate}、成本 {@code cost} 与折扣 {@code discount}
 * （如"新桶 = 满桶 ⇒ 扣全款后 = cap − cost"）。改数值只改上面的常量，**改语义才改断言**。
 *
 * <h2>★ 反向验证指向（清单归宿 = {@code architecture/tech/限流.md} §六）</h2>
 * <ul>
 *   <li>删掉脚本里的惰性补充（{@code refill}）⇒ {@link #老桶按公式复算}、{@link #很久没请求的桶补满封顶} 必红；</li>
 *   <li>删掉脚本里的 {@code EXPIRE} ⇒ {@link #两桶TTL按各自参数派生且访问续期} 必红；</li>
 *   <li>把"被拒只扣询问费"改错（免扣 / 全款）⇒ {@link #用户桶不够_被拒_只扣询问费_服务器桶一个字节都不写} 必红。</li>
 * </ul>
 * 文中 {@code T25} / {@code T28} 等后续任务编号的锚点见 {@code architecture/tech/限流.md} 对应章节。
 */
@TestPropertySource(properties = {
        // ★ T27-C4（审查修正）：任务自付费接线后，异步重建链会在执行处扣服务器桶（ratelimit:server），
        //   而本类断言该键的精确绝对值——关掉本上下文的 MQ 消费者，防"夹具设值与断言之间"
        //   被异步扣减污染（与限流.md §四③ 的桶断言测试类开关规则对齐，本类是其覆盖面之一）。
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class RateLimitRedisOpsTests extends AbstractIntegrationTest {

    /**
     * 桶参数（刻意取小值便于手算推导；与 {@code application.yaml} 的生产值**无关**——
     * 命令层的参数由调用方传入，这正是"命令层不注入配置"带来的可测性）。
     * <p>派生量：userTTL = ceil(10/2) = 5s；serverTTL = ceil(20/10) = 2s。
     */
    private static final RateLimitRedisOps.BucketSpec USER_BUCKET = new RateLimitRedisOps.BucketSpec(10, 2);
    private static final RateLimitRedisOps.BucketSpec SERVER_BUCKET = new RateLimitRedisOps.BucketSpec(20, 10);

    /** 成本折算：cost = 3、折扣 0.3 ⇒ 询问费 = 0.9、尾款 = 2.1（判定线）。 */
    private static final double COST = 3.0;
    private static final double DISCOUNT = 0.3;
    private static final double FEE = COST * DISCOUNT;

    /** 键名**硬编码字面量**（防 {@code CacheKeys} 改名导致测试静默失联，见 CacheKeys 类注释）。 */
    private static final String USER_KEY = "ratelimit:user:990001";
    private static final String SERVER_KEY = "ratelimit:server";

    @Autowired
    private RateLimitRedisOps ops;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clearRedis() {
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ==================== 读写辅助（独立 oracle 的"直读"侧） ====================

    private String rawOf(String key) {
        return redis.opsForValue().get(key);
    }

    private double tokensOf(String key) {
        String raw = rawOf(key);
        return Double.parseDouble(raw.substring(0, raw.indexOf(':')));
    }

    private long lastOf(String key) {
        String raw = rawOf(key);
        return Long.parseLong(raw.substring(raw.indexOf(':') + 1));
    }

    /** 直写一个桶（夹具）。与实现共用 {@code "tokens:lastRefillMs"} 格式是**故意**的（格式本身就是契约）。 */
    private void putBucket(String key, String tokens, long lastRefillMs) {
        redis.opsForValue().set(key, tokens + ":" + lastRefillMs);
    }

    private RateLimitRedisOps.Decision acquire() {
        return ops.tryAcquire(USER_KEY, SERVER_KEY, USER_BUCKET, SERVER_BUCKET, COST, DISCOUNT);
    }

    private static RedisCircuitBreaker freshBreaker() {
        return new RedisCircuitBreaker(CircuitBreaker.ofDefaults("t24-script-boundary"));
    }

    /**
     * 测试替身：把 {@code execute}（脚本执行入口）替换为给定的原始结果——
     * {@code RuntimeException} 则抛出（模拟 Redis 不可达），其余原样返回（模拟脚本返回坏值）。
     * <p>为什么不用 Mockito：{@code execute} 是 varargs 签名，匹配器语义随 Mockito 版本漂移；
     * 本用例只关心"外部给什么、本层怎么反应"，替身子类最直白。
     */
    private static final class StubRedis extends StringRedisTemplate {

        private final Object result;

        private StubRedis(Object result) {
            this.result = result;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            if (result instanceof RuntimeException e) {
                throw e;
            }
            return (T) result;
        }
    }

    // ==================== 放行路径 ====================

    @Test
    @DisplayName("新桶首次访问 = 满桶起步：扣全款（询问费 + 尾款 = cost），两桶同时扣")
    void 新桶首次访问() {
        RateLimitRedisOps.Decision decision = acquire();

        assertThat(decision.allowed()).as("冷启动 = 满桶，首次访问必然放行").isTrue();
        assertThat(decision.retryAfterSeconds()).as("放行时 Retry-After 无意义，恒 0").isZero();
        // 期望推导：满桶 cap − 全款 cost（冷启动无补充量 ⇒ 不受毫秒级时延影响）
        assertThat(tokensOf(USER_KEY)).as("用户桶 = 10 − 3").isCloseTo(USER_BUCKET.capacity() - COST, within(1e-6));
        assertThat(tokensOf(SERVER_KEY)).as("服务器桶 = 20 − 3").isCloseTo(SERVER_BUCKET.capacity() - COST, within(1e-6));
    }

    @Test
    @DisplayName("★ 独立 oracle：老桶按惰性公式复算（两桶都补；用读回的 lastRefillMs 反推、无时钟近似）")
    void 老桶按公式复算() {
        long t0 = System.currentTimeMillis();
        // 用户桶：5 个令牌、2 秒前（半补：5 + 2s×2/s = 9）；服务器桶：0 个令牌、5 秒前（应补满、被封顶）
        putBucket(USER_KEY, "5", t0 - 2000);
        putBucket(SERVER_KEY, "0", t0 - 5000);

        RateLimitRedisOps.Decision decision = acquire();

        // ★ 复算：用**脚本写回的 last** 反推 elapsed ⇒ 与脚本共用同一时间差，无近似
        long t1 = lastOf(USER_KEY);
        long s1 = lastOf(SERVER_KEY);
        double expectedUser = Math.min(USER_BUCKET.capacity(),
                5 + (t1 - (t0 - 2000)) * USER_BUCKET.refillPerSec() / 1000.0) - COST;
        double expectedServer = Math.min(SERVER_BUCKET.capacity(),
                0 + (s1 - (t0 - 5000)) * SERVER_BUCKET.refillPerSec() / 1000.0) - COST;

        assertThat(decision.allowed()).as("补充后 9 ≥ 2.1 且 20 ≥ 3 ⇒ 放行").isTrue();
        assertThat(tokensOf(USER_KEY)).as("用户桶实际值 vs Java 侧独立复算").isCloseTo(expectedUser, within(1e-6));
        assertThat(tokensOf(SERVER_KEY)).as("服务器桶实际值 vs Java 侧独立复算").isCloseTo(expectedServer, within(1e-6));
        assertThat(tokensOf(USER_KEY)).as("确实补充过：未补充则 = 5 − 3 = 2（远小于此）").isGreaterThan(5.0);
        // ★ 真实时钟锚定（对冲"复算对 now 推导本身无判别力"——两边同错则自洽）：
        //   夹具距离 ≈ 2000ms（Java 时钟，与 Redis 时钟同机、偏差毫秒级）× 2/s ⇒ 补充 ≈ 4 ⇒ ≈ 5 + 4 − 3 = 6
        assertThat(tokensOf(USER_KEY)).as("按真实时钟复核补充量：≈ 6（±0.3 覆盖毫秒级抖动）")
                .isCloseTo(6.0, within(0.3));
    }

    @Test
    @DisplayName("很久没请求的桶补满封顶（min 截断）：空桶放 100 秒后 = 满桶，而不是 200 个令牌")
    void 很久没请求的桶补满封顶() {
        long t0 = System.currentTimeMillis();
        putBucket(USER_KEY, "0", t0 - 100_000);
        putBucket(SERVER_KEY, "0", t0 - 100_000);

        RateLimitRedisOps.Decision decision = acquire();

        assertThat(decision.allowed()).as("两侧都补满 ⇒ 放行").isTrue();
        // 0 + 100s × 2/s = 200 个"应补"，但封顶在 cap ⇒ 最终 = cap − cost（精确值：min 截断后不受时延影响）
        assertThat(tokensOf(USER_KEY)).as("用户桶封顶 = 10 − 3").isCloseTo(USER_BUCKET.capacity() - COST, within(1e-6));
        assertThat(tokensOf(SERVER_KEY)).as("服务器桶封顶 = 20 − 3").isCloseTo(SERVER_BUCKET.capacity() - COST, within(1e-6));
    }

    // ==================== 拒绝路径（非对称扣减） ====================

    @Test
    @DisplayName("★ 用户桶不够 ⇒ 被拒：只扣询问费；服务器桶**一个字节都不写**（连键都不创建）")
    void 用户桶不够_被拒_只扣询问费_服务器桶一个字节都不写() {
        long t0 = System.currentTimeMillis();
        // ⚠️ 200ms 的夹具距离：让"复算式"断言对补充量有**稳定的非零判别面**（删补充注入 ⇒ 必红），
        //    又不改变语义（补 0.4 后仍远买不起尾款）。
        putBucket(USER_KEY, "0.2", t0 - 200);

        RateLimitRedisOps.Decision decision = acquire();

        assertThat(decision.allowed()).as("付不起尾款 ⇒ 拒绝").isFalse();
        // 期望推导：0.2 + 0.2s×2/s − 询问费 0.9 ⇒ ≈ −0.3 —— 询问费无条件扣（被拒不是免费探路）
        long t1 = lastOf(USER_KEY);
        double expected = Math.min(USER_BUCKET.capacity(),
                0.2 + (t1 - (t0 - 200)) * USER_BUCKET.refillPerSec() / 1000.0) - FEE;
        assertThat(tokensOf(USER_KEY)).as("只扣了询问费 0.9（不扣尾款）").isCloseTo(expected, within(1e-6));
        assertThat(tokensOf(USER_KEY)).as("被拒 ⇒ 欠账（负令牌）").isNegative();

        // ★ "服务器桶完全不动"的最硬证据：本用例从未写过服务器桶 ⇒ 它必须**仍然不存在**
        assertThat(rawOf(SERVER_KEY)).as("被拒时服务器桶一个字节都不写（连键都不创建）").isNull();

        // Retry-After 推导：补足目标 = cost（下次进入前还要再扣询问费）⇒ (3 − (−0.3)) / 2 = 1.65s ⇒ ceil = 2；
        // 服务器桶不缺 ⇒ 取大者 2
        assertThat(decision.retryAfterSeconds()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 服务器桶不够 ⇒ 被拒：用户桶仍只扣询问费；服务器桶**逐字原样保留**（从未写入）")
    void 服务器桶不够_被拒_服务器桶逐字原样保留() {
        long t0 = System.currentTimeMillis();
        // ⚠️ 夹具的时间戳"距离"会经惰性补充变成令牌——必须按公式过一遍再取数：
        //    半秒前 × 1/s ≈ 补 0.5 个，仍够不着 cost=3（第一版曾取 t0−12345，12 秒把 1 个令牌的桶补满、反致放行）。
        long s0 = t0 - 500;
        RateLimitRedisOps.BucketSpec slowServer = new RateLimitRedisOps.BucketSpec(20, 1);
        // ⚠️ 用户桶夹具 = cap：任何补充都会被 min 截断吸收 ⇒ 本用例对"惰性补充"**天然无判别面**
        //    （那是 老桶 / 封顶 用例的职责；本用例的判别对象 = 服务器侧"原样保留"与询问费）。
        putBucket(USER_KEY, "10", t0);
        putBucket(SERVER_KEY, "0", s0);

        RateLimitRedisOps.Decision decision =
                ops.tryAcquire(USER_KEY, SERVER_KEY, USER_BUCKET, slowServer, COST, DISCOUNT);

        assertThat(decision.allowed()).as("补充后 ≈0.5 < cost ⇒ 拒绝（两桶 AND 的服务器侧）").isFalse();

        long t1 = lastOf(USER_KEY);
        double expectedUser = Math.min(USER_BUCKET.capacity(),
                10 + (t1 - t0) * USER_BUCKET.refillPerSec() / 1000.0) - FEE;
        assertThat(tokensOf(USER_KEY)).as("用户桶够尾款也照扣询问费（被拒不是免费探路）").isCloseTo(expectedUser, within(1e-6));

        // ★ 服务器桶"完全不动"的逐字证据：值与 lastRefillMs 保持夹具原样（SET 从未发生）
        assertThat(rawOf(SERVER_KEY)).as("服务器桶逐字原样（从未被写）").isEqualTo("0:" + s0);

        // Retry-After 推导：服务器桶需 (3 − ≈0.5) / 1 ≈ 2.5s ⇒ ceil = 3；用户桶不缺 ⇒ 3
        assertThat(decision.retryAfterSeconds()).as("由服务器桶缺口 / 速率决定").isEqualTo(3);
    }

    @Test
    @DisplayName("负令牌（欠账）可存可读可继续扣 —— 欠账累积是设计语义（被拒的询问费不返还）")
    void 负令牌欠账累积() {
        long t0 = System.currentTimeMillis();
        // ⚠️ 200ms 距离 ⇒ 补充 ≈ 0.4（也让本用例的 Retry-After 落在非整数缺口上、可判别 ceil）
        putBucket(USER_KEY, "-5", t0 - 200);
        putBucket(SERVER_KEY, "20", t0);

        RateLimitRedisOps.Decision decision = acquire();

        assertThat(decision.allowed()).as("欠账态 ⇒ 拒绝").isFalse();
        long t1 = lastOf(USER_KEY);
        double expected = Math.min(USER_BUCKET.capacity(),
                -5 + (t1 - (t0 - 200)) * USER_BUCKET.refillPerSec() / 1000.0) - FEE;
        assertThat(tokensOf(USER_KEY)).as("−5 + 补充 − 0.9 ≈ −5.5（负值存储往返完好）").isCloseTo(expected, within(1e-6));

        // Retry-After 推导：补足目标 = cost ⇒ (3 − (−5.5)) / 2 = 4.25s ⇒ ceil = 5
        // （非整数缺口：floor 实现会得 4 ⇒ 红；若退化成"补到尾款"也会得 4 ⇒ 红——本条同时钉住那处审查修正）
        assertThat(decision.retryAfterSeconds()).isEqualTo(5);
    }

    @Test
    @DisplayName("★ 两桶同时不够 ⇒ Retry-After 取两桶补足时间的**较大者**（用户桶侧主导）")
    void 两桶同时不足_RetryAfter取较大者() {
        long t0 = System.currentTimeMillis();
        putBucket(USER_KEY, "0", t0 - 200);   // 用户桶：0 + 0.4 − 0.9 ≈ −0.5 ⇒ 需 (3 − (−0.5)) / 2 = 1.75s
        putBucket(SERVER_KEY, "0", t0 - 200); // 服务器桶：≈ 0.2s×10 = 2（< cost 3，仍不足）⇒ 需 (3 − 2) / 10 = 0.1s

        RateLimitRedisOps.Decision decision = acquire();

        assertThat(decision.allowed()).as("两桶都不足 ⇒ 拒绝").isFalse();
        // 取较大者 ⇒ ceil(1.75) = 2（若实现误写成取 min，会得 ceil(0.1) = 1 ⇒ 必红）
        assertThat(decision.retryAfterSeconds()).as("max(2, 1) = 2 —— 不是 min").isEqualTo(2);
    }

    // ==================== 逐桶判定（T28：可观测面 scope 的真值来源） ====================

    @Test
    @DisplayName("★ 逐桶判定：放行 ⇒ 两侧都过（Decision 的逐桶标记不是恒真/恒假的摆设）")
    void 逐桶判定_放行时两侧都过() {
        RateLimitRedisOps.Decision decision = acquire();

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.userBucketPassed()).as("用户桶够尾款 ⇒ 通过").isTrue();
        assertThat(decision.serverBucketPassed()).as("服务器桶够 cost ⇒ 通过").isTrue();
    }

    @Test
    @DisplayName("★ 逐桶判定：拒绝时能分辨「谁挡的」——用户桶挡 / 服务器桶挡 / 两边都不够")
    void 逐桶判定_拒绝时谁挡的可分辨() {
        long t0 = System.currentTimeMillis();

        // ① 用户桶挡（服务器桶够）：0.5 − 0.9 < 尾款 2.1 ⇒ user 不过；服务器桶是新桶（满）⇒ 过
        putBucket(USER_KEY, "0.5", t0);
        RateLimitRedisOps.Decision userBlocked = acquire();
        assertThat(userBlocked.allowed()).isFalse();
        assertThat(userBlocked.userBucketPassed()).as("用户桶是瓶颈（scope=user 的那条 rejected）").isFalse();
        assertThat(userBlocked.serverBucketPassed()).as("服务器桶没挡（scope=server 那条应记 allowed）").isTrue();

        // ② 服务器桶挡（用户桶够）：满用户桶 ⇒ 过；服务器桶 0.5 < cost 3 ⇒ 不过
        clearRedis();
        putBucket(USER_KEY, "10", t0);
        putBucket(SERVER_KEY, "0.5", t0);
        RateLimitRedisOps.Decision serverBlocked = acquire();
        assertThat(serverBlocked.allowed()).isFalse();
        assertThat(serverBlocked.userBucketPassed()).as("用户桶没挡（scope=user 那条应记 allowed）").isTrue();
        assertThat(serverBlocked.serverBucketPassed())
                .as("★ 服务器桶是瓶颈 —— 若逐桶判定被实现成「拒绝时两侧都记 rejected」，本断言必红")
                .isFalse();

        // ③ 两桶都不够：allowed 是 AND，但逐桶标记必须**各自**为 false（不能塌成一个"挡了"）
        clearRedis();
        putBucket(USER_KEY, "0", t0);
        putBucket(SERVER_KEY, "0", t0);
        RateLimitRedisOps.Decision bothBlocked = acquire();
        assertThat(bothBlocked.allowed()).isFalse();
        assertThat(bothBlocked.userBucketPassed()).isFalse();
        assertThat(bothBlocked.serverBucketPassed()).isFalse();
    }

    // ==================== TTL（自证性质的实现前提） ====================

    @Test
    @DisplayName("两桶 TTL 按各自参数派生（ceil(cap/rate)）且每次访问续期 —— 被拒也续（访问即刷新）")
    void 两桶TTL按各自参数派生且访问续期() {
        acquire();

        assertThat(redis.getExpire(USER_KEY, TimeUnit.MILLISECONDS))
                .as("用户桶 TTL = ceil(10/2) = 5s（刚写回，允许几十 ms 流逝）")
                .isGreaterThan(3000L).isLessThanOrEqualTo(5000L);
        assertThat(redis.getExpire(SERVER_KEY, TimeUnit.MILLISECONDS))
                .as("服务器桶 TTL = ceil(20/10) = 2s —— 两桶参数独立")
                .isGreaterThan(1000L).isLessThanOrEqualTo(2000L);

        // 手动把 TTL 压到 1s，再访问一次 ⇒ 必须回到 ≈5s（续期的判别点：不刷新则停留 1s 走向过期）
        redis.expire(USER_KEY, Duration.ofSeconds(1));
        acquire();
        assertThat(redis.getExpire(USER_KEY, TimeUnit.MILLISECONDS))
                .as("访问续期：TTL 回到 5s 档")
                .isGreaterThan(3000L);

        // ★ 被拒路径也必须刷新 TTL（若实现只在放行分支 EXPIRE，活跃的被拒桶会走向过期、被静默淘汰）
        putBucket(USER_KEY, "0.1", System.currentTimeMillis());
        RateLimitRedisOps.Decision rejected = acquire();
        assertThat(rejected.allowed()).isFalse();
        assertThat(redis.getExpire(USER_KEY, TimeUnit.MILLISECONDS))
                .as("被拒也刷新 TTL")
                .isGreaterThan(3000L);
    }

    @Test
    @DisplayName("TTL 派生：向上取整（cap 不能被 rate 整除时）；非法桶参数响亮失败")
    void TTL派生向上取整() {
        assertThat(new RateLimitRedisOps.BucketSpec(10, 3).ttlSeconds()).as("ceil(10/3) = 4").isEqualTo(4);
        assertThat(new RateLimitRedisOps.BucketSpec(20, 10).ttlSeconds()).as("整除时无取整余量").isEqualTo(2);
        assertThatThrownBy(() -> new RateLimitRedisOps.BucketSpec(0, 1))
                .as("容量 ≤ 0 响亮失败（不静默）").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitRedisOps.BucketSpec(10, 0))
                .as("速率为 0 会在脚本里除零 ⇒ 也必须响亮失败").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("桶键命名是冻结契约：CacheKeys 的三个工厂与测试硬编码字面量逐字一致（防改名静默失联）")
    void 桶键命名冻结() {
        // ⚠️ 命令层不产键（键由调用方传入）⇒ 仅靠测试里的硬编码字面量发现不了 CacheKeys 改名；
        //    本用例把"产出者 ↔ 冻结字面量"接上（CacheKeys 一改名立即红）。
        assertThat(CacheKeys.rateLimitUser(990001)).as("登录桶键形").isEqualTo(USER_KEY);
        assertThat(CacheKeys.rateLimitAnon("127.0.0.1")).as("匿名桶键形").isEqualTo("ratelimit:anon:127.0.0.1");
        assertThat(CacheKeys.rateLimitServer()).as("服务器桶键").isEqualTo(SERVER_KEY);
    }

    // ==================== 任务侧（第三个记账主体） ====================

    @Test
    @DisplayName("任务侧扣减：只扣服务器桶、不碰任何用户桶；先补后扣；可为负")
    void 任务侧扣减() {
        long t0 = System.currentTimeMillis();
        putBucket(USER_KEY, "10", t0 - 100_000); // 用户桶放远处：本用例不应被碰（连 last 都不许变）

        // a) 冷启动：服务器桶 = 满桶起步，扣 taskCost = 10
        ops.deductTaskCost(SERVER_KEY, SERVER_BUCKET, 10);
        assertThat(tokensOf(SERVER_KEY)).as("20 − 10").isCloseTo(SERVER_BUCKET.capacity() - 10.0, within(1e-6));

        // b) 用户桶分毫未动：值与 last 逐字保留（证明任务脚本从不读写用户桶）
        assertThat(rawOf(USER_KEY)).as("用户桶的值与 lastRefillMs 原样（从未被本方法触碰）")
                .isEqualTo("10:" + (t0 - 100_000));

        // c) 先补后扣：服务器桶 0 个令牌、1 秒前 ⇒ 补 10 后扣 10 ≈ 0（若不补则 −10，差一个数量级）
        long s0 = System.currentTimeMillis();
        putBucket(SERVER_KEY, "0", s0 - 1000);
        ops.deductTaskCost(SERVER_KEY, SERVER_BUCKET, 10);
        long s1 = lastOf(SERVER_KEY);
        double expected = Math.min(SERVER_BUCKET.capacity(),
                0 + (s1 - (s0 - 1000)) * SERVER_BUCKET.refillPerSec() / 1000.0) - 10;
        assertThat(tokensOf(SERVER_KEY)).as("先补后扣：≈ 0，而非 −10").isCloseTo(expected, within(1e-6));
        assertThat(tokensOf(SERVER_KEY)).as("确实补过（未补则恰好 = −10）").isGreaterThan(-1.0);

        // d) 可为负（欠账）：−5 再扣 10 ≈ −15
        long s2 = System.currentTimeMillis();
        putBucket(SERVER_KEY, "-5", s2);
        ops.deductTaskCost(SERVER_KEY, SERVER_BUCKET, 10);
        long s3 = lastOf(SERVER_KEY);
        double expectedNegative = Math.min(SERVER_BUCKET.capacity(),
                -5 + (s3 - s2) * SERVER_BUCKET.refillPerSec() / 1000.0) - 10;
        assertThat(tokensOf(SERVER_KEY)).as("−5 − 10 ≈ −15（无条件、可为负）").isCloseTo(expectedNegative, within(1e-6));
    }

    // ==================== 门后差额补扣（T26 信号②） ====================

    @Test
    @DisplayName("差额补扣：两桶同扣 + 先补后扣（独立 oracle 复算）+ 写即刷新 TTL")
    void 差额补扣_两桶同扣_先补后扣() {
        long t0 = System.currentTimeMillis();
        // 用户桶：1 个令牌、2 秒前（补 2×2 = 4 → 5）；服务器桶：0 个令牌、5 秒前（补 50 → 封顶 20）
        putBucket(USER_KEY, "1", t0 - 2000);
        putBucket(SERVER_KEY, "0", t0 - 5000);

        ops.deductDelta(USER_KEY, SERVER_KEY, USER_BUCKET, SERVER_BUCKET, 2.5);

        // 独立 oracle：以脚本写回的 lastRefillMs 反推补充量（同 T24/T25 手法，与脚本共用同一时间差、无时钟近似）
        double expectedUser = Math.min(USER_BUCKET.capacity(),
                1 + (lastOf(USER_KEY) - (t0 - 2000)) * USER_BUCKET.refillPerSec() / 1000.0) - 2.5;
        double expectedServer = Math.min(SERVER_BUCKET.capacity(),
                0 + (lastOf(SERVER_KEY) - (t0 - 5000)) * SERVER_BUCKET.refillPerSec() / 1000.0) - 2.5;
        assertThat(tokensOf(USER_KEY)).as("用户桶 = 补后 − 差额").isCloseTo(expectedUser, within(1e-6));
        assertThat(tokensOf(SERVER_KEY)).as("服务器桶同扣（两桶共享同一个差额）").isCloseTo(expectedServer, within(1e-6));
        assertThat(tokensOf(USER_KEY)).as("先补后扣的判别面：不补则恰好 = 1 − 2.5 = −1.5").isGreaterThan(0.0);

        // 写即刷新（两桶都被写 ⇒ 都刷新 TTL）：派生 TTL = ceil(cap/rate) ⇒ 断言落在 (0, TTL] 窗口
        assertThat(redis.getExpire(USER_KEY, TimeUnit.SECONDS))
                .as("用户桶 TTL 被刷新（ceil(10/2) = 5s 窗口内）").isBetween(1L, USER_BUCKET.ttlSeconds());
        assertThat(redis.getExpire(SERVER_KEY, TimeUnit.SECONDS))
                .as("服务器桶 TTL 被刷新（ceil(20/10) = 2s 窗口内）").isBetween(1L, SERVER_BUCKET.ttlSeconds());
    }

    @Test
    @DisplayName("差额补扣：可为负（欠账态，D-6）——不足也照扣，这是**事后结算**、不再判定")
    void 差额补扣_可为负() {
        long t0 = System.currentTimeMillis();
        putBucket(USER_KEY, "1", t0);
        putBucket(SERVER_KEY, "1", t0);

        ops.deductDelta(USER_KEY, SERVER_KEY, USER_BUCKET, SERVER_BUCKET, 3.0);

        double expectedUser = Math.min(USER_BUCKET.capacity(),
                1 + (lastOf(USER_KEY) - t0) * USER_BUCKET.refillPerSec() / 1000.0) - 3.0;
        double expectedServer = Math.min(SERVER_BUCKET.capacity(),
                1 + (lastOf(SERVER_KEY) - t0) * SERVER_BUCKET.refillPerSec() / 1000.0) - 3.0;
        assertThat(tokensOf(USER_KEY)).as("≈ −2（可为负）").isCloseTo(expectedUser, within(1e-6));
        assertThat(tokensOf(SERVER_KEY)).as("服务器桶同样可为负").isCloseTo(expectedServer, within(1e-6));
    }

    // ==================== 失败边界（本层不吞） ====================

    @Test
    @DisplayName("★ Redis 不可达 ⇒ 抛 CacheUnavailableException（命令层不 catch；fail-open 归 T25 过滤器）")
    void Redis不可达_上抛CacheUnavailableException() {
        RateLimitRedisOps mocked = new RateLimitRedisOps(
                new StubRedis(new RuntimeException("模拟 Redis 不可达")), freshBreaker());

        assertThatThrownBy(() -> mocked.tryAcquire(USER_KEY, SERVER_KEY, USER_BUCKET, SERVER_BUCKET, COST, DISCOUNT))
                .isInstanceOf(CacheUnavailableException.class);
        assertThatThrownBy(() -> mocked.deductTaskCost(SERVER_KEY, SERVER_BUCKET, 10))
                .as("任务侧同一条 guarded 路径，同样上抛不吞").isInstanceOf(CacheUnavailableException.class);
        assertThatThrownBy(() -> mocked.deductDelta(USER_KEY, SERVER_KEY, USER_BUCKET, SERVER_BUCKET, 2.0))
                .as("门后差额（T26）同一条 guarded 路径，同样上抛不吞").isInstanceOf(CacheUnavailableException.class);
    }

    @Test
    @DisplayName("★ 脚本返回值无法解析 ⇒ 抛 RateLimitScriptException（**不**归一成 CacheUnavailableException——T28 分档依据）")
    void 脚本返回无法解析_抛脚本异常() {
        // 形态一：首字符非法（"garbage"）
        RateLimitRedisOps badChar = new RateLimitRedisOps(new StubRedis("garbage"), freshBreaker());
        assertThatThrownBy(() -> badChar.tryAcquire(USER_KEY, SERVER_KEY, USER_BUCKET, SERVER_BUCKET, COST, DISCOUNT))
                .as("首字符非法 ⇒ 解析失败，且**不**是 CacheUnavailableException")
                .isInstanceOf(RateLimitScriptException.class)
                .isNotInstanceOf(CacheUnavailableException.class);

        // 形态二：长度不足（"1:"）
        RateLimitRedisOps tooShort = new RateLimitRedisOps(new StubRedis("1:"), freshBreaker());
        assertThatThrownBy(() -> tooShort.tryAcquire(USER_KEY, SERVER_KEY, USER_BUCKET, SERVER_BUCKET, COST, DISCOUNT))
                .as("长度不足 ⇒ 解析失败")
                .isInstanceOf(RateLimitScriptException.class);

        // 形态三：null 返回值
        RateLimitRedisOps nullReturn = new RateLimitRedisOps(new StubRedis(null), freshBreaker());
        assertThatThrownBy(() -> nullReturn.tryAcquire(USER_KEY, SERVER_KEY, USER_BUCKET, SERVER_BUCKET, COST, DISCOUNT))
                .as("null ⇒ 解析失败")
                .isInstanceOf(RateLimitScriptException.class);

        // 形态四：retry 部分非数字（进入 NumberFormatException 分支）
        RateLimitRedisOps badRetry = new RateLimitRedisOps(new StubRedis("1:abc"), freshBreaker());
        assertThatThrownBy(() -> badRetry.tryAcquire(USER_KEY, SERVER_KEY, USER_BUCKET, SERVER_BUCKET, COST, DISCOUNT))
                .as("retry 非数字 ⇒ 解析失败")
                .isInstanceOf(RateLimitScriptException.class);
    }

    @Test
    @DisplayName("★ 逐桶判定段缺失/非法 ⇒ 抛脚本异常（差一位不得被静默当成放行）")
    void 逐桶判定段非法_抛脚本异常() {
        // 三段（T24 旧形态残留 / 脚本只改了一半）⇒ 解析失败，不是"保守放行"
        RateLimitRedisOps oldShape = new RateLimitRedisOps(new StubRedis("1:0:1"), freshBreaker());
        assertThatThrownBy(() -> oldShape.tryAcquire(USER_KEY, SERVER_KEY, USER_BUCKET, SERVER_BUCKET, COST, DISCOUNT))
                .as("旧的三段形态必须响亮失败（否则 scope 统计会静默读到缺省值）")
                .isInstanceOf(RateLimitScriptException.class);

        // 逐桶段取值不是 0/1
        RateLimitRedisOps badFlag = new RateLimitRedisOps(new StubRedis("1:0:1:x"), freshBreaker());
        assertThatThrownBy(() -> badFlag.tryAcquire(USER_KEY, SERVER_KEY, USER_BUCKET, SERVER_BUCKET, COST, DISCOUNT))
                .isInstanceOf(RateLimitScriptException.class);

        // 放行段与逐桶段矛盾（allowed=1 但 user 段=0）不属格式错，但格式校验只管形态：
        // 这里钉的是"多一段/少一段"的边界，语义矛盾由脚本保证（allowed 由两段推出）。
        RateLimitRedisOps extra = new RateLimitRedisOps(new StubRedis("1:0:1:1:9"), freshBreaker());
        assertThatThrownBy(() -> extra.tryAcquire(USER_KEY, SERVER_KEY, USER_BUCKET, SERVER_BUCKET, COST, DISCOUNT))
                .as("多一段同样是格式漂移")
                .isInstanceOf(RateLimitScriptException.class);
    }
}
