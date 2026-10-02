package io.github.yjhhhaaa06.videoweb.coupon;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切片 S1 验收：coupon 模块功能闭环（列表 → 抢券 → 我的券）。
 *
 * <p>断言的都是**可观察行为**（HTTP 状态 + 信封形状 + DB 终态），不断言内部实现
 * （决策⑦ 防"测试迎合"）。关键计数一律走**独立 oracle**——直接 {@code SELECT COUNT(*) / 直接查列}，
 * 不信任接口回显（测试策略 §4 纪律 2）。
 *
 * <h2>与 TV pytest 的对应（语义翻译，非机械复制）</h2>
 * 旧体系里有 4 处 coupon 用例（{@code test_smoke.S-15/S-16}、{@code test_boundary.E-09/E-09b}、
 * {@code test_consistency.C-10}、{@code test_coupon_my_and_comment_status.TestCouponMy}）。
 * 本类保留其**意图**（列表可读、抢券成功、重复 409、过期 409、库存递减、my 三态），
 * 形式改为"Testcontainers + 真 HTTP + 真 DB"。
 *
 * <h2>契约形状（沿袭 TV，勿"顺手优化"）</h2>
 * <ul>
 *   <li>{@code /coupon/grab} 的 {@code data} 是**纯字符串券码**，不是对象</li>
 *   <li>{@code /coupon/list} / {@code /coupon/my} 的 {@code data} 是数组；空集为 {@code []}，**不是 null**</li>
 *   <li>错误信封不含 {@code data} 键（{@code ApiResponse} 的 {@code NON_NULL} 约定）</li>
 * </ul>
 */
class CouponFlowTests extends AbstractCouponIntegrationTest {

    private static final String PHONE = "13800001001";
    private static final String USERNAME = "coupon-user";

    // ==================== 抢券：成功路径 ====================

    @Test
    @DisplayName("抢券成功：200 + 16 位券码；订单 +1 行、库存 -1（独立 oracle 复算）")
    void 抢券成功() {
        String token = registerAndGetToken(PHONE, USERNAME);
        long couponId = insertCoupon("新人券", 5, WINDOW_OPEN);

        ResponseEntity<String> resp = grab(couponId, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.code(resp)).isEqualTo(200);
        // data 是纯字符串券码（不是对象）——形状契约
        assertThat(Envelope.data(resp).asString()).matches("[0-9A-Z]{16}");

        // 独立 oracle：不读接口回显，直接查库
        assertThat(countOrders(couponId, firstUserId())).isEqualTo(1L);
        assertThat(stockOf(couponId)).isEqualTo(4);
    }

    // ==================== 抢券：失败路径（409） ====================

    @Test
    @DisplayName("库存为 0：409，且不产生订单行、库存不为负")
    void 库存为零() {
        String token = registerAndGetToken(PHONE, USERNAME);
        long couponId = insertCoupon("已抢光", 0, WINDOW_OPEN);

        ResponseEntity<String> resp = grab(couponId, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(Envelope.code(resp)).isEqualTo(409);
        assertThat(countOrders(couponId, null)).isZero();
        assertThat(stockOf(couponId)).isZero();          // 关键：不得为负
    }

    @Test
    @DisplayName("活动未开始：409（时间窗判定在 SQL 内），库存不动")
    void 活动未开始() {
        String token = registerAndGetToken(PHONE, USERNAME);
        long couponId = insertCoupon("未开始", 5, WINDOW_NOT_STARTED);

        ResponseEntity<String> resp = grab(couponId, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(Envelope.code(resp)).isEqualTo(409);
        assertThat(countOrders(couponId, null)).isZero();
        assertThat(stockOf(couponId)).isEqualTo(5);      // 未扣
    }

    @Test
    @DisplayName("活动已结束：409，库存不动（TV pytest E-09b 的翻译）")
    void 活动已结束() {
        String token = registerAndGetToken(PHONE, USERNAME);
        long couponId = insertCoupon("已过期", 998, WINDOW_ENDED);

        ResponseEntity<String> resp = grab(couponId, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(Envelope.code(resp)).isEqualTo(409);
        assertThat(countOrders(couponId, null)).isZero();
        assertThat(stockOf(couponId)).isEqualTo(998);
    }

    /**
     * ★ 本切片最核心的事务边界测试（决策表 C-1）★
     *
     * <p>旧实现里"扣库存"与"插订单"在同一事务内，插订单撞唯一键时整笔回滚 ⇒ **库存只扣一次**。
     * 新实现靠 {@code @Transactional} + {@code DuplicateKeyException}（RuntimeException，默认触发回滚）
     * 维系同一语义。
     *
     * <p>若有人把 {@code @Transactional} 去掉（或把 {@code insertOrder} 移出事务），
     * 第二次抢券会先扣掉库存再报冲突，此处 {@code stock == 4} 的断言会变成 3 ⇒ 测试失败。
     * <b>这就是本用例存在的意义</b>：把一个"不报错、只慢慢少库存"的隐性退化钉住。
     */
    @Test
    @DisplayName("C-1：重复抢券 409，且库存只扣一次（★证明失败路径真的回滚了）")
    void 重复抢券库存只扣一次() {
        String token = registerAndGetToken(PHONE, USERNAME);
        long couponId = insertCoupon("限量券", 5, WINDOW_OPEN);

        assertThat(grab(couponId, token).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = grab(couponId, token);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(Envelope.code(second)).isEqualTo(409);

        // 独立 oracle 复算：订单恰 1 行；库存恰减 1（不是 2）
        assertThat(countOrders(couponId, firstUserId())).isEqualTo(1L);
        assertThat(stockOf(couponId))
                .as("重复抢券必须整笔回滚——刚扣掉的库存要被还原，否则库存会被慢慢扣光")
                .isEqualTo(4);
    }

    // ==================== 抢券：鉴权与参数 ====================

    @Test
    @DisplayName("未登录抢券：401（TV AuthFilter PROTECTED_EXACT 含 /coupon/grab）")
    void 未登录抢券() {
        long couponId = insertCoupon("新人券", 5, WINDOW_OPEN);

        ResponseEntity<String> resp = post("/coupon/grab", Map.of("couponId", couponId), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(Envelope.code(resp)).isEqualTo(401);
        assertThat(Envelope.hasDataField(resp)).as("错误信封不含 data 键").isFalse();
        assertThat(countOrders(couponId, null)).isZero();
    }

    @Test
    @DisplayName("缺 couponId / 非正数：400 参数错误（声明式校验，取代 TV 的「静默变 0 再报 409」）")
    void 参数非法() {
        String token = registerAndGetToken(PHONE, USERNAME);
        long couponId = insertCoupon("新人券", 5, WINDOW_OPEN);

        assertThat(post("/coupon/grab", Map.of(), token).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/coupon/grab", Map.of("couponId", 0), token).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/coupon/grab", Map.of("couponId", -1), token).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // 非法参数不得产生任何副作用
        assertThat(countOrders(couponId, null)).isZero();
        assertThat(stockOf(couponId)).isEqualTo(5);
    }

    // ==================== 可用券列表（公开端点） ====================

    @Test
    @DisplayName("可用券列表：无需 token；空库返回 data == []（不是 null）")
    void 可用券列表为空() {
        ResponseEntity<String> resp = get("/coupon/list", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.code(resp)).isEqualTo(200);
        assertThat(Envelope.data(resp).isArray()).isTrue();
        assertThat(Envelope.data(resp).size()).isZero();
    }

    @Test
    @DisplayName("可用券列表：形状沿袭 TV（6 字段）；按 begin_time 升序；已结束的券不出现")
    void 可用券列表形状与排序() {
        // 先插入"晚开始"的，再插入"早开始"的——若实现没排序，返回顺序会是插入顺序
        long laterId = insertCoupon("晚开始券", 10, WINDOW_NOT_STARTED);
        long earlierId = insertCoupon("早开始券", 20, WINDOW_OPEN);
        insertCoupon("已结束券", 30, WINDOW_ENDED);

        ResponseEntity<String> resp = get("/coupon/list", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.data(resp).isArray()).isTrue();
        assertThat(Envelope.data(resp).size())
                .as("end_time > NOW() 的券才出现：已结束的那张必须被 WHERE 过滤掉；"
                        + "注意未开始的券**应当出现**（口径是 end_time > NOW()，不是'可抢'）")
                .isEqualTo(2);

        // 排序：begin_time ASC ⇒ 早开始的在前（尽管它是后插入的）
        assertThat(Envelope.data(resp).get(0).path("id").asLong()).isEqualTo(earlierId);
        assertThat(Envelope.data(resp).get(1).path("id").asLong()).isEqualTo(laterId);

        // 字段形状契约（TV rowToMap 的 6 个键）
        assertThat(Envelope.data(resp).get(0).has("id")).isTrue();
        assertThat(Envelope.data(resp).get(0).has("title")).isTrue();
        assertThat(Envelope.data(resp).get(0).has("stock")).isTrue();
        assertThat(Envelope.data(resp).get(0).has("beginTime")).isTrue();
        assertThat(Envelope.data(resp).get(0).has("endTime")).isTrue();
        assertThat(Envelope.data(resp).get(0).has("createTime")).isTrue();
        assertThat(Envelope.data(resp).get(0).path("title").asString()).isEqualTo("早开始券");
        assertThat(Envelope.data(resp).get(0).path("stock").asInt()).isEqualTo(20);
    }

    // ==================== 我的优惠券 ====================

    @Test
    @DisplayName("我的券：未登录 401（TV AuthFilter PROTECTED_EXACT 含 /coupon/my）")
    void 我的券未登录() {
        ResponseEntity<String> resp = get("/coupon/my", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(Envelope.code(resp)).isEqualTo(401);
    }

    @Test
    @DisplayName("我的券：未抢券返回 data == []（TV pytest test_coupon_my_empty_list 的翻译）")
    void 我的券为空() {
        String token = registerAndGetToken(PHONE, USERNAME);

        ResponseEntity<String> resp = get("/coupon/my", token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.data(resp).isArray()).isTrue();
        assertThat(Envelope.data(resp).size()).isZero();
    }

    @Test
    @DisplayName("我的券：抢券后恰 1 条，含 couponId / 非空 couponCode / title（TV test_coupon_my_after_grab）")
    void 我的券抢券后() {
        String token = registerAndGetToken(PHONE, USERNAME);
        long couponId = insertCoupon("我的专属券", 5, WINDOW_OPEN);
        assertThat(grab(couponId, token).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> resp = get("/coupon/my", token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.data(resp).size()).isEqualTo(1);
        assertThat(Envelope.data(resp).get(0).path("couponId").asLong()).isEqualTo(couponId);
        assertThat(Envelope.data(resp).get(0).path("couponCode").asString()).isNotBlank();
        assertThat(Envelope.data(resp).get(0).path("title").asString()).isEqualTo("我的专属券");
        assertThat(Envelope.data(resp).get(0).path("status").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("我的券：只能看到自己的券（C-3 的 userId 过滤口径）")
    void 我的券按用户隔离() {
        long couponId = insertCoupon("共享券池", 5, WINDOW_OPEN);

        String tokenA = registerAndGetToken("13800002001", "coupon-a");
        String tokenB = registerAndGetToken("13800002002", "coupon-b");
        assertThat(grab(couponId, tokenA).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(Envelope.data(get("/coupon/my", tokenA)).size()).isEqualTo(1);
        assertThat(Envelope.data(get("/coupon/my", tokenB)).size())
                .as("B 没抢过券，不应看到 A 的订单")
                .isZero();
    }

    // ==================== helpers ====================

    /** 取"当前唯一的那个用户"的 id——仅用于单用户用例（多用户用例见 {@link #我的券按用户隔离}）。 */
    private Long firstUserId() {
        Long id = jdbcTemplate.queryForObject("SELECT MIN(id) FROM users", Long.class);
        assertThat(id).as("用例应先注册用户").isNotNull();
        return id;
    }
}
