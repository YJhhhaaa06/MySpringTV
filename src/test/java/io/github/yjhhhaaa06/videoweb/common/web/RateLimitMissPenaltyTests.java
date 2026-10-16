package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 缓存 miss 差额补扣（信号②）的 **HTTP 级契约测试**（限流专项 T26；口径的唯一事实源 =
 * {@code architecture/tech/限流.md} §四）。
 *
 * <h2>两阶段语义（本类要钉住的东西）</h2>
 * miss 是**请求运行期事实**（门时取不到）⇒ 门按 {@code base} 扣、请求收尾按差额
 * {@code base × (multiplier − 1)} 补扣。本类用同一端点两次请求证两侧：
 * <ol>
 *   <li><b>冷读（miss）</b>：详情接口首访必然回源 ⇒ 总扣 {@code 1 + 2}（multiplier = 3）；</li>
 *   <li><b>热读（命中）</b>：同端点二访全命中 ⇒ 只扣 {@code 1}（**不补扣**）。</li>
 * </ol>
 *
 * <h2>期望值的推导口径（不抄实现；参数 cap=100、rate=1/s、discount=0.3、base=1、multiplier=3）</h2>
 * <pre>
 * 播种两桶 = cap=100（= cap ⇒ 补充被 min 截断，精确可推）
 * 冷读：门 100 − 1 = 99 → 收尾补扣 99 − 2 = 97
 * 热读：门 97 − 1 = 96（无补扣）——若补扣错发 ⇒ 94，若门没扣 ⇒ 97，两向都被断言排除
 * </pre>
 * ⚠️ 补扣发生在**响应之后**（过滤器 finally）⇒ 断言一律**轮询等待**（{@link #awaitTokensBelow}），
 * 不做"响应到手就该扣完"的时序假设（真实 HTTP 下响应先于 finally 到达是合法时序）。
 * 容差 0.5：rate=1/s ⇒ 200ms 级调度抖动 ≤ 0.2；而判据间距是 2（补/不补），远大于容差。
 *
 * <h2>★ 反向验证指向</h2>
 * 把 {@code CacheMissSignal.mark()} 接成常量 false、或删掉过滤器收尾的 {@code settleMissPenalty}
 * 调用 ⇒ 冷读断言必红（97 → 99）；把热读路径也补扣（标记没被 consume 清零）⇒ 热读断言必红。
 * "multiplier = 1.0 = 关闭"的语义见 {@code RateLimitMissPenaltyDisabledTests}。
 *
 * <h2>与 T25 契约测试的拆分理由</h2>
 * 参数钉法不同（本类 pin {@code miss-multiplier=3.0}、T25 类 pin {@code =1.0} 隔离信号②），
 * 且验证面不同（本类 = 收尾结算；T25 类 = 闸门判定）。
 */
@TestPropertySource(properties = {
        // 桶参数调小（覆盖基类 1M 基线）：cap=100、rate=1/s——播种=cap ⇒ 补充被 min 截断 ⇒ 断言精确。
        "video.rate-limit.server.capacity=100",
        "video.rate-limit.server.refill-per-sec=1",
        "video.rate-limit.user.authed.capacity=100",
        "video.rate-limit.user.authed.refill-per-sec=1",
        "video.rate-limit.user.anon.capacity=100",
        "video.rate-limit.user.anon.refill-per-sec=1",
        "video.rate-limit.cost.default-base=1",
        "video.rate-limit.cost.reject-discount=0.3",
        // ★ 本类的主体：信号②启用（3.0）。显式 pin，不依赖 yaml 默认值。
        "video.rate-limit.cost.miss-multiplier=3.0"
})
class RateLimitMissPenaltyTests extends AbstractHttpIntegrationTest {

    /** 键名字面量硬编码（防 {@code CacheKeys} 改名导致测试静默失联，同 T24/T25 手法）。 */
    private static final String ANON_KEY = "ratelimit:anon:127.0.0.1";
    private static final String SERVER_KEY = "ratelimit:server";

    /** 播种值 = 桶容量（cap=100）：补充被 {@code min} 截断 ⇒ 扣减结果精确可推。 */
    private static final double SEED = 100;

    /** 夹具 id（远高于自增水位；每测重建，不依赖全局种子库）。 */
    private static final long AUTHOR_ID = 990_262_001L;
    private static final long CONTENT_ID = 990_262_101L;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void 重建夹具与桶() {
        // 内容夹具（findContent 走 JOIN users ⇒ 作者行必须在）
        jdbcTemplate.update("DELETE FROM content_media WHERE content_id = ?", CONTENT_ID);
        jdbcTemplate.update("DELETE FROM content WHERE id = ?", CONTENT_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", AUTHOR_ID);
        jdbcTemplate.update("INSERT INTO users (id, username, hashed_password, phone) VALUES (?, ?, ?, ?)",
                AUTHOR_ID, "t26author", "not-used", "13800002699");
        jdbcTemplate.update("INSERT INTO content (id, user_id, type, title, description) VALUES (?, ?, 1, ?, ?)",
                CONTENT_ID, AUTHOR_ID, "T26 标题", "T26 描述");
        jdbcTemplate.update("INSERT INTO content_media (content_id, url, type, sort) VALUES (?, ?, 1, 0)",
                CONTENT_ID, "t26/test.mp4");

        // 桶与内容缓存键清理（进程级共享容器 ⇒ 每测自清）
        Set<String> keys = redis.keys("ratelimit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        redis.delete(List.of(CacheKeys.content(CONTENT_ID),
                CacheKeys.empty(CacheKeys.content(CONTENT_ID))));
    }

    private String 详情() {
        return "/search/IdSearch?contentId=" + CONTENT_ID;
    }

    private void seedBuckets() {
        long now = System.currentTimeMillis();
        redis.opsForValue().set(ANON_KEY, SEED + ":" + now);
        redis.opsForValue().set(SERVER_KEY, SEED + ":" + now);
    }

    private double tokensOf(String key) {
        String raw = redis.opsForValue().get(key);
        assertThat(raw).as("桶键 %s 必须被写过（否则 oracle 读不到账）", key).isNotNull();
        return Double.parseDouble(raw.substring(0, raw.indexOf(':')));
    }

    /**
     * 轮询等待桶值降到阈值以下（补扣在响应之后发生——不做时序假设）。
     * 等待超时则返回当前值，由调用方的断言暴露"补扣没来"。
     */
    private void awaitTokensBelow(String key, double threshold) {
        long deadline = System.currentTimeMillis() + 3000;
        while (tokensOf(key) >= threshold && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Test
    @DisplayName("★ 冷读 miss ⇒ 总价 ×3（门 1 + 收尾差额 2）；热读命中 ⇒ 只扣 base（不补扣）")
    void 冷读乘3_热读只扣base() {
        seedBuckets();

        // ---- 第一访：冷读（内容键已被清）⇒ 必回源 ⇒ 收尾补扣 ----
        ResponseEntity<String> first = get(详情(), null);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.code(first)).isEqualTo(200);

        awaitTokensBelow(ANON_KEY, 98.0);   // 97 附近（补扣到达）才可能低于 98；若补扣没来会停在 99
        assertThat(tokensOf(ANON_KEY))
                .as("冷读总价 = 1 + 2 = 3 ⇒ 匿名桶 100 − 3 ≈ 97（不补扣会是 99）")
                .isCloseTo(97, within(0.5));
        awaitTokensBelow(SERVER_KEY, 98.0);
        assertThat(tokensOf(SERVER_KEY))
                .as("服务器桶同扣差额（两桶同扣）")
                .isCloseTo(97, within(0.5));

        // ---- 第二访：热读（内容键已回填）⇒ 全命中 ⇒ 不补扣 ----
        ResponseEntity<String> second = get(详情(), null);
        assertThat(second.getStatusCode().value()).isEqualTo(200);

        awaitTokensBelow(ANON_KEY, 96.8);   // 96 附近（门扣到达）才可能低于 96.8
        assertThat(tokensOf(ANON_KEY))
                .as("热读只扣 base ⇒ 97 − 1 ≈ 96（若补扣错发会是 94；若门没扣会停在 97）")
                .isCloseTo(96, within(0.5));
        awaitTokensBelow(SERVER_KEY, 96.8);
        assertThat(tokensOf(SERVER_KEY))
                .as("热读服务器桶同样只扣 base")
                .isCloseTo(96, within(0.5));
    }
}
