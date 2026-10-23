package io.github.yjhhhaaa06.videoweb.coin;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 投币专项 T37 验收：签到（供给端）+ 余额读出。
 *
 * <h2>★ 每条用例的期望值都来自业务口径，不是"照抄实现"</h2>
 * 断言全部落在**可观察行为**上：HTTP 状态 + 信封 + {@code users} 两列的终态
 * （独立 oracle，见 {@link AbstractCoinIntegrationTest}）。
 * 期望值的推导链：
 * <ul>
 *   <li>首次签到 = {@code +1} 枚 ⇒ {@code coin_deci: 0 → 10}，{@code last_sign_date: NULL → 今天}；</li>
 *   <li>同一自然日再签 = 幂等 ⇒ 200「今日已签到」，**两列都不动**；</li>
 *   <li>昨天签过 ⇒ 今天可以再签 ⇒ {@code 10 → 20}，日期推进；</li>
 *   <li>并发双击 ⇒ 只有一条条件 {@code UPDATE} 命中 ⇒ 只 {@code +10}、只推进一次；</li>
 *   <li>{@code GET /api/v1/users/me} ⇒ {@code coin = deci / 10}（一位小数）、{@code signedToday} 与列复算一致。</li>
 * </ul>
 *
 * <h2>反向验证（改哪一行必红）</h2>
 * <ul>
 *   <li>删 {@code UserMapper.xml#signIn} 的 {@code (last_sign_date is null or ...)} 守卫
 *       ⇒ {@link #并发双击只到账一次且只推进一次签到日()} 会读到 {@code coin_deci == 20}、
 *       且两个响应都 {@code credited == true}；{@link #同日重复签到不加币也不改签到日()} 会读到 20。</li>
 *   <li>把 {@code signIn} 改回"先查后写" ⇒ 并发用例偶发/稳定红（取决于交错），串行用例仍绿
 *       —— 这正是该用例必须存在的理由。</li>
 *   <li>去掉 {@code +10}（只写日期）⇒ 首次签到用例红；改成 {@code +20} ⇒ 首次与昨天两条都红。</li>
 *   <li>{@code toCoinAmount} 丢 {@code setScale(1)}（或改用 {@code double}）⇒ balance 的
 *       {@code scale == 1} 断言红（{@code 10 → 1} 只剩整数 scale 0）。</li>
 * </ul>
 *
 * <p>⚠️ 未登录用例对 {@code @RequiresLogin} 是**假绿**（401 由 {@code @CurrentUserId} 解析器兜出）——
 * 机制级判别力在 {@code common/security/SecurityContractTests}。
 */
class CoinSignInTests extends AbstractCoinIntegrationTest {

    /**
     * 「上海自然日」的**独立 oracle**：本类刻意**不调用**实现里的 {@code SiteTime} 来产生期望值。
     *
     * <p>若与实现共用同一个方法，时区写错（例如把 {@code Asia/Shanghai} 改成 {@code UTC}）时
     * 实现与断言会**一起漂移**、用例仍然全绿 —— 那就成了"抄实现"。
     * 这里直接写时区字面量：口径来自 `architecture/domain/coin/coin-需求.md` §二（自然日按上海算）。
     */
    private static LocalDate shanghaiToday() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai"));
    }

    // ==================== 1. 首次签到 ====================

    @Test
    @DisplayName("首次签到：200 +「签到成功」+ credited=true + 余额 1.0（scale 恰为 1）+ DB 两列落地")
    void 首次签到到账一枚() {
        TestUser user = registerUser("13900003701", "coinsign_first");
        assertThat(coinDeciOf(user.id())).as("新用户 0 币（需求 §二：注册不送币）").isZero();
        assertThat(lastSignDateOf(user.id())).as("新用户从未签到").isNull();

        ResponseEntity<String> resp = signIn(user.token());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.msg(resp)).as("文案冻结：「签到成功」").isEqualTo("签到成功");
        JsonNode data = Envelope.data(resp);
        assertThat(data.path("credited").isBoolean())
                .as("★ 先断言字段在：asBoolean() 对缺失节点也返回 false ⇒ "
                        + "只断 false 的话「字段被删」会假绿")
                .isTrue();
        assertThat(data.path("credited").asBoolean()).as("本次真的到账").isTrue();
        assertThat(data.path("balance").decimalValue().scale())
                .as("余额的一位小数是契约：10 分位必须序列化成 1.0；丢 setScale 会变成 1")
                .isEqualTo(1);
        assertThat(data.path("balance").decimalValue()).isEqualByComparingTo("1.0");

        // 独立 oracle：不读接口回显，直查列终态
        assertThat(coinDeciOf(user.id())).as("签到 +1 枚 = +10 十分位").isEqualTo(10);
        assertThat(lastSignDateOf(user.id()))
                .as("签到日 = 上海自然日（独立按 Asia/Shanghai 复算，不调实现里的 SiteTime），"
                        + "不是 JVM 默认时区的今天")
                .isEqualTo(shanghaiToday());
        assertThat(countCoinRows()).as("签到不产生投币记录行").isZero();
    }

    // ==================== 2. 同日重复（幂等） ====================

    @Test
    @DisplayName("同日重复签到：200 +「今日已签到」+ credited=false + 两列都不变（幂等）")
    void 同日重复签到不加币也不改签到日() {
        TestUser user = registerUser("13900003702", "coinsign_repeat");
        assertThat(signIn(user.token()).getStatusCode().value()).isEqualTo(200);

        int coinBefore = coinDeciOf(user.id());
        LocalDate dateBefore = lastSignDateOf(user.id());
        assertThat(coinBefore).isEqualTo(10);

        ResponseEntity<String> second = signIn(user.token());

        assertThat(second.getStatusCode().value())
                .as("重复签到是合法状态，不是 409/400 —— 否则前端无法把按钮置灰")
                .isEqualTo(200);
        assertThat(Envelope.msg(second)).as("文案冻结：「今日已签到」").isEqualTo("今日已签到");
        JsonNode data = Envelope.data(second);
        assertThat(data.path("credited").isBoolean())
                .as("★ false 断言必须配「字段在」：缺失节点 asBoolean() 也是 false")
                .isTrue();
        assertThat(data.path("credited").asBoolean())
                .as("本次没到账 —— 与首次签到靠这个布尔区分，而不是靠状态码")
                .isFalse();
        assertThat(data.path("balance").decimalValue().scale()).isEqualTo(1);
        assertThat(data.path("balance").decimalValue()).isEqualByComparingTo("1.0");

        // 独立 oracle：两列**逐值**不变（不是"还是 10"这种弱断言）
        assertThat(coinDeciOf(user.id())).isEqualTo(coinBefore);
        assertThat(lastSignDateOf(user.id())).isEqualTo(dateBefore);
    }

    // ==================== 3. 昨天签过 ⇒ 今天可以再签 ====================

    @Test
    @DisplayName("昨天签过：今天再签 credited=true、余额 2.0、coin_deci 20、签到日推进到今天")
    void 昨天签过今天可以再签() {
        TestUser user = registerUser("13900003703", "coinsign_yesterday");
        setCoinDeci(user.id(), 10);
        setLastSignDate(user.id(), shanghaiToday().minusDays(1).toString());

        ResponseEntity<String> resp = signIn(user.token());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.msg(resp)).isEqualTo("签到成功");
        JsonNode data = Envelope.data(resp);
        assertThat(data.path("credited").isBoolean()).as("credited 字段必须在").isTrue();
        assertThat(data.path("credited").asBoolean()).isTrue();
        assertThat(data.path("balance").decimalValue().scale()).isEqualTo(1);
        assertThat(data.path("balance").decimalValue()).isEqualByComparingTo("2.0");

        assertThat(coinDeciOf(user.id())).as("10 + 10 = 20 分位").isEqualTo(20);
        assertThat(lastSignDateOf(user.id())).isEqualTo(shanghaiToday());
    }

    // ==================== 4. ★ 并发双击 ====================

    @Test
    @DisplayName("★并发双击：两线程同点签到 ⇒ coin_deci 恰为 10、签到日只推进一次、credited 恰好一个 true")
    void 并发双击只到账一次且只推进一次签到日() throws Exception {
        TestUser user = registerUser("13900003704", "coinsign_concurrent");

        // 与 coupon/CouponConcurrencyTests 同款：全部线程先卡在 startGate，主线程放行后一起冲，
        // 制造对**同一行**的真实锁竞争（不是"提交后各跑各的"）。
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>(threads);
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    try {
                        return signIn(user.token());
                    } finally {
                        finished.countDown();
                    }
                }));
            }
            startGate.countDown();

            assertThat(finished.await(30, TimeUnit.SECONDS))
                    .as("并发请求在 30 秒内未全部结束——疑似行锁等待（InnoDB 默认 50s）")
                    .isTrue();

            List<ResponseEntity<String>> responses = new ArrayList<>(threads);
            for (Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get());
            }

            assertThat(responses).allSatisfy(resp ->
                    assertThat(resp.getStatusCode().value())
                            .as("两条都应 200（失败不该是 409/500）")
                            .isEqualTo(200));
            long creditedCount = responses.stream()
                    .filter(resp -> Envelope.data(resp).path("credited").asBoolean())
                    .count();
            assertThat(creditedCount)
                    .as("★判别力：去掉 WHERE 守卫后两次 UPDATE 都会命中 ⇒ 这里会是 2，且 coin_deci == 20")
                    .isEqualTo(1);

            // 独立 oracle：DB 终态只反映一次到账
            assertThat(coinDeciOf(user.id()))
                    .as("一条原子条件 UPDATE 只能命中一次：10，不是 20")
                    .isEqualTo(10);
            assertThat(lastSignDateOf(user.id())).isEqualTo(shanghaiToday());
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 5. GET /api/v1/users/me 的余额与已签标记 ====================

    @Test
    @DisplayName("GET /api/v1/users/me：签到后 coin=1.0（scale 1）、signedToday=true；直改签到日为昨天 ⇒ signedToday=false 且余额不变")
    void 资料端点回带余额与今日已签标记() {
        TestUser user = registerUser("13900003705", "coinsign_profile");
        assertThat(signIn(user.token()).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> me = get("/api/v1/users/me", user.token());
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        JsonNode data = Envelope.data(me);
        assertThat(data.path("coin").decimalValue().scale())
                .as("整数枚也必须是一位小数（1.0），前端不用自己补 .0")
                .isEqualTo(1);
        assertThat(data.path("coin").decimalValue()).isEqualByComparingTo("1.0");
        assertThat(data.path("signedToday").isBoolean())
                .as("signedToday 字段必须在（缺失时 asBoolean() 也是 false）").isTrue();
        assertThat(data.path("signedToday").asBoolean())
                .as("今天签过 ⇒ 签到按钮进页就置灰")
                .isTrue();

        // 绕过端点把签到日改到昨天：signedToday 必须跟着列复算，余额列不受影响
        LocalDate yesterday = shanghaiToday().minusDays(1);
        setLastSignDate(user.id(), yesterday.toString());

        ResponseEntity<String> again = get("/api/v1/users/me", user.token());
        JsonNode againData = Envelope.data(again);
        assertThat(againData.path("signedToday").isBoolean())
                .as("★ false 断言必须配「字段在」：缺失节点 asBoolean() 也是 false").isTrue();
        assertThat(againData.path("signedToday").asBoolean())
                .as("signedToday 是「last_sign_date == 上海今日」的直接复算，不是缓存的布尔")
                .isFalse();
        assertThat(againData.path("coin").decimalValue()).isEqualByComparingTo("1.0");
        assertThat(againData.path("coin").decimalValue().scale()).isEqualTo(1);
        assertThat(coinDeciOf(user.id())).as("改签到日不该动余额列").isEqualTo(10);
    }

    // ==================== 6. 未登录 ====================

    @Test
    @DisplayName("未登录签到：401 + 统一错误信封（本条对 @RequiresLogin 是假绿，判别力在 SecurityContractTests）")
    void 未登录签到被拒() {
        ResponseEntity<String> resp = postForm("/coin/signIn", noQuery(), null);

        // ⚠️ 这条 401 可能来自 @CurrentUserId 的参数解析器，而不是 @RequiresLogin ——
        //    机制是否生效只能由 SecurityContractTests 直接对 RequestMappingLookup 求值来证明
        //    （AGENTS.md「测试纪律」④）。
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(Envelope.code(resp)).isEqualTo("AUTH_UNAUTHORIZED");
        assertThat(Envelope.hasDataField(resp)).as("错误信封不含 data 键").isFalse();
    }

    // ==================== 夹具 ====================

    /** 注册 / {@code (id, token)} 夹具已下沉到 {@link AbstractCoinIntegrationTest}（T38 起两组用例共用）。 */

    /** 签到端点：{@code POST + form}、无入参（照 {@code /like/*} 的写路径形态）。 */
    private ResponseEntity<String> signIn(String token) {
        return postForm("/coin/signIn", noQuery(), token);
    }
}
