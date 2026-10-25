package io.github.yjhhhaaa06.videoweb.common.idempotency;

import io.github.yjhhhaaa06.videoweb.common.security.JwtAuthFilter;
import io.github.yjhhhaaa06.videoweb.common.web.RateLimitFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **链序常量的守护**（写接口幂等专项 `T66`）。
 *
 * <p>为什么需要一条"只比常量"的用例：{@code IdempotencyFilter} 的三条收益（429 不留 PENDING、
 * 不复用 token 解析、早于 multipart 解析）全部来自"它排在 {@code RateLimitFilter} 之内侧"这一个
 * 位置事实。运行期用例**抓不到**它——把本类挪到限流外侧，`comment` 的 6 条用例照样全绿
 * （测试环境的限流桶被调到 10^6，永远不拒）。故必须有一处把它变成可复算的断言。
 *
 * <p>同理，{@code RATE_LIMIT_ORDER > AUTH_FILTER_ORDER} 也是"先鉴权后扣费"的位置事实
 * （限流的契约用例里只有一条间接证明）。这里把两条关系一起钉住。
 *
 * <p>★ 这两个常量都定义成"被依赖者 + 10" ⇒ 本用例是**真断言**而不是同义反复：
 * 若有人把 {@code IDEMPOTENCY_ORDER} 改成裸数字（或把 {@code @Order} 直接写死），
 * 相对关系就可能倒挂，而该类是 {@code OncePerRequestFilter}、没有任何别的运行期用例会读这个值。
 */
class IdempotencyChainOrderTests {

    @Test
    @DisplayName("链序常量：鉴权(0) < 限流(+10) < 幂等(+10)——位置倒挂 ⇒ 429 会留下 PENDING、multipart 会先落盘")
    void 链序常量必须严格递增() {
        assertThat(RateLimitFilter.RATE_LIMIT_ORDER)
                .as("限流必须在鉴权之内侧（先鉴权后扣费）")
                .isGreaterThan(JwtAuthFilter.AUTH_FILTER_ORDER);
        assertThat(IdempotencyFilter.IDEMPOTENCY_ORDER)
                .as("幂等必须在限流与鉴权之内侧")
                .isGreaterThan(RateLimitFilter.RATE_LIMIT_ORDER);
    }
}
