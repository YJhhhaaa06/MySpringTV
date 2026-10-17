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
 * 信号②**关闭态**（{@code miss-multiplier = 1.0}）的契约测试（限流专项 T26；
 * 口径见 {@code architecture/tech/限流.md} §四）。
 *
 * <h2>钉住的语义</h2>
 * 1.0 = 关闭（沿用本仓"0/1 = 关闭"惯例）：冷读 miss **也不补扣**——只按 base 扣。
 * 本类补齐 {@code RateLimitMissPenaltyTests}（启用态）的另一半：
 * <ul>
 *   <li>把差额公式改成 **1.0 下非零**的形态（如 {@code base × multiplier}）⇒ 本类断言必红；</li>
 *   <li>⚠️ 仅删掉 {@code <= 1.0} 早退（公式仍 {@code base × (m−1)} = 0）**不会**红：零差额
 *       只省一次往返、桶值不变——那属优化而非可观察语义，本类对它无判别（启用态改坏由
 *       {@code RateLimitMissPenaltyTests} 抓）；</li>
 *   <li>把启用态改坏（不补扣）⇒ {@code RateLimitMissPenaltyTests} 必红——两个方向都有判别力。</li>
 * </ul>
 *
 * <h2>期望值推导（cap=100、rate=1/s、discount=0.3、base=1）</h2>
 * 冷读：门 100 − 1 = 99；无补扣 ⇒ 匿名桶 **99**（若误补扣会是 97）。
 * 判别面补强：断言"内容键已被回填"——证明 miss **确实发生过**，"没补扣"不是"没 miss"的假象。
 *
 * <h2>为什么单独一个类</h2>
 * 属性钉法不同（{@code =1.0} vs {@code =3.0}）——{@code @TestPropertySource} 是类级的
 * （T25 的"覆盖通道"教训：不猜优先级、显式钉）。
 */
@TestPropertySource(properties = {
        // 桶参数调小（同 RateLimitMissPenaltyTests，保证播种=cap 的截断性质成立）
        "video.rate-limit.server.capacity=100",
        "video.rate-limit.server.refill-per-sec=1",
        "video.rate-limit.user.authed.capacity=100",
        "video.rate-limit.user.authed.refill-per-sec=1",
        "video.rate-limit.user.anon.capacity=100",
        "video.rate-limit.user.anon.refill-per-sec=1",
        "video.rate-limit.cost.default-base=1",
        "video.rate-limit.cost.reject-discount=0.3",
        // ★ 本类的主体：信号②**关闭**（1.0）。显式 pin，不依赖 yaml 默认值。
        "video.rate-limit.cost.miss-multiplier=1.0",
        // ★ T27：任务自付费接线后，异步重建链会在执行处扣服务器桶——本类断言小桶绝对值，
        //   关掉本上下文的 MQ 消费者，防"夹具设值与断言之间"被异步扣减污染。
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class RateLimitMissPenaltyDisabledTests extends AbstractHttpIntegrationTest {

    private static final String ANON_KEY = "ratelimit:anon:127.0.0.1";
    private static final String SERVER_KEY = "ratelimit:server";
    private static final double SEED = 100;

    private static final long AUTHOR_ID = 990_263_001L;
    private static final long CONTENT_ID = 990_263_101L;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void 重建夹具与桶() {
        jdbcTemplate.update("DELETE FROM content_media WHERE content_id = ?", CONTENT_ID);
        jdbcTemplate.update("DELETE FROM content WHERE id = ?", CONTENT_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", AUTHOR_ID);
        jdbcTemplate.update("INSERT INTO users (id, username, hashed_password, phone) VALUES (?, ?, ?, ?)",
                AUTHOR_ID, "t26authorOff", "not-used", "13800002698");
        jdbcTemplate.update("INSERT INTO content (id, user_id, type, title, description) VALUES (?, ?, 1, ?, ?)",
                CONTENT_ID, AUTHOR_ID, "T26 关闭态标题", "T26 关闭态描述");
        jdbcTemplate.update("INSERT INTO content_media (content_id, url, type, sort) VALUES (?, ?, 1, 0)",
                CONTENT_ID, "t26/off.mp4");

        Set<String> keys = redis.keys("ratelimit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        redis.delete(List.of(CacheKeys.content(CONTENT_ID),
                CacheKeys.empty(CacheKeys.content(CONTENT_ID))));

        long now = System.currentTimeMillis();
        redis.opsForValue().set(ANON_KEY, SEED + ":" + now);
        redis.opsForValue().set(SERVER_KEY, SEED + ":" + now);
    }

    private double tokensOf(String key) {
        String raw = redis.opsForValue().get(key);
        assertThat(raw).as("桶键 %s 必须被写过", key).isNotNull();
        return Double.parseDouble(raw.substring(0, raw.indexOf(':')));
    }

    @Test
    @DisplayName("multiplier = 1.0（关闭）：冷读 miss 也不补扣（99 而非 97），且回源确实发生")
    void 关闭时_冷读也不补扣() throws InterruptedException {
        ResponseEntity<String> resp = get("/search/IdSearch?contentId=" + CONTENT_ID, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.code(resp)).isEqualTo(200);

        // 给"不该来的补扣"一个到达窗口：补扣在响应之后发生（finally）——若实现无视关闭开关，
        // 差额会落在这 300ms 内（正确实现下此后无任何写，桶值冻结）。
        Thread.sleep(300);

        assertThat(tokensOf(ANON_KEY))
                .as("关闭态：只扣 base ⇒ 100 − 1 = 99（若误补扣会是 97）")
                .isCloseTo(99, within(0.5));
        assertThat(tokensOf(SERVER_KEY))
                .as("关闭态：服务器桶同样只扣 base")
                .isCloseTo(99, within(0.5));
        // 判别面补强：内容键已被回填 ⇒ 这次确实是"冷读 miss"，不是"没发生回源"
        assertThat(redis.hasKey(CacheKeys.content(CONTENT_ID)))
                .as("内容键必须已被回源回填（证明 miss 发生过——否则本用例对'关闭'没有鉴别力）")
                .isTrue();
    }
}
