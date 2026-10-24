package io.github.yjhhhaaa06.videoweb.coupon;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
 * 本类保留其**意图**（列表可读、抢券成功、重复领券幂等（`T64`：200 + 原码，不再 409）、过期 409、
 * 库存递减、my 三态），形式改为"Testcontainers + 真 HTTP + 真 DB"。
 *
 * <h2>契约形状（`T58` 起路径资源化；信封形状沿袭 TV，勿"顺手优化"）</h2>
 * <ul>
 *   <li>{@code POST /api/v1/coupons/{id}/claims} 的 {@code data} 是 {@code {couponCode}} 对象
 *       （`T59` 载荷面契约变更；`T59` 前是**纯字符串券码**）</li>
 *   <li>{@code GET /api/v1/coupons} / {@code GET /api/v1/users/me/coupons} 的 {@code data} 是数组；空集为 {@code []}，**不是 null**</li>
 *   <li>错误信封不含 {@code data} 键（{@code ApiResponse} 的 {@code NON_NULL} 约定）</li>
 * </ul>
 */
class CouponFlowTests extends AbstractCouponIntegrationTest {

    private static final String PHONE = "13800001001";
    private static final String USERNAME = "coupon-user";

    // ==================== 抢券：成功路径 ====================

    @Test
    @DisplayName("抢券成功：200 + data.couponCode 为 16 位券码；订单 +1 行、库存 -1（独立 oracle 复算）")
    void 抢券成功() {
        String token = registerAndGetToken(PHONE, USERNAME);
        long couponId = insertCoupon("新人券", 5, WINDOW_OPEN);

        ResponseEntity<String> resp = grab(couponId, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.code(resp)).isEqualTo("OK");
        // `T59` 载荷面契约变更：data 由纯字符串改为对象 {"couponCode": "…"}（§2.5 出参不得是裸 String）
        assertThat(Envelope.data(resp).path("couponCode").asString()).matches("[0-9A-Z]{16}");

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
        assertThat(Envelope.code(resp)).isEqualTo("COUPON_UNAVAILABLE");
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
        assertThat(Envelope.code(resp)).isEqualTo("COUPON_UNAVAILABLE");
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
        assertThat(Envelope.code(resp)).isEqualTo("COUPON_UNAVAILABLE");
        assertThat(countOrders(couponId, null)).isZero();
        assertThat(stockOf(couponId)).isEqualTo(998);
    }

    /**
     * ★ `T64` 契约变更（G8 例外：契约跟随）★
     *
     * <p><b>依据</b>：需求编号 `N4` §G·P0-3 / `T64` —— 重复领券由 **409** 改为
     * **200 + 原券码**（幂等重放）。变更点两处：① 状态码 409 → 200；
     * ② 不再出现 `SYSTEM_DUPLICATE_KEY` /「数据已存在」（`I-25` 已销账）。
     * 原因：券码只在首次响应给过一次，409 让"丢了 2xx 再重试"的客户端永远拿不回自己的券。
     *
     * <p>断言仍是可观察行为（HTTP 状态 + body + DB 终态）。第 2 次的 {@code couponCode} 必须
     * **逐字等于**第 1 次 —— 只断言"非空/形状对"抓不到"重放时另生了一个新码"。
     */
    @Test
    @DisplayName("T64：重复领券 200 + 原券码（幂等重放），库存只扣一次、订单仍 1 行")
    void 重复领券返回原券码() {
        String token = registerAndGetToken(PHONE, USERNAME);
        long couponId = insertCoupon("限量券", 5, WINDOW_OPEN);

        ResponseEntity<String> first = grab(couponId, token);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstCode = Envelope.data(first).path("couponCode").asString();
        assertThat(firstCode).matches("[0-9A-Z]{16}");

        ResponseEntity<String> second = grab(couponId, token);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.code(second)).isEqualTo("OK");
        assertThat(Envelope.data(second).path("couponCode").asString())
                .as("重放必须回原券码——另发一个码等于给同一人两张券")
                .isEqualTo(firstCode);

        // 独立 oracle：不读接口回显，直接查库
        assertThat(countOrders(couponId, firstUserId())).isEqualTo(1L);
        assertThat(stockOf(couponId))
                .as("可判定的重复路径不得扣库存：库存只能因首次领取减 1（不是 3）")
                .isEqualTo(4);
    }

    /**
     * ★ `T64` 的优先级用例 ★
     *
     * <p>口径（`T64` 任务书）：**回查在前** —— 已领过 ⇒ 200 + 原码，**与库存 / 时间窗无关**；
     * 只有**未领过**的人才可能因库存 0 / 未开始 / 已结束拿到 409。
     *
     * <p>为什么必须有：写反成"先判可用性、再查订单"的实现，在上面的顺序两连用例里**照样全绿**
     * （券还在进行中、库存也够）。只有"领过之后券变得不可领"才能把那个顺序打红。
     * 这里先用 `stock=0`、再用 `end_time` 提前，把两条判据各踩一次。
     *
     * <p>同时带**对照**：同一张券下"另一个没领过的用户"必须仍是 409 ——
     * 否则"一律 200"这种退化实现也能过。
     */
    @Test
    @DisplayName("T64：领过之后券变不可领（库存 0 / 已结束）⇒ 仍 200 且同码；未领过者仍 409")
    void 领过之后券不可领仍返回原券码() {
        String token = registerAndGetToken(PHONE, USERNAME);
        long couponId = insertCoupon("限量券", 5, WINDOW_OPEN);
        String originalCode = Envelope.data(grab(couponId, token)).path("couponCode").asString();
        assertThat(countOrders(couponId, firstUserId())).as("前置：首次领取必须成功").isEqualTo(1L);

        // ① 券改成"抢光"
        jdbcTemplate.update("UPDATE coupon SET stock = 0 WHERE id = ?", couponId);
        ResponseEntity<String> afterSoldOut = grab(couponId, token);
        assertThat(afterSoldOut.getStatusCode())
                .as("已领过的人重放，不该因为库存归零变 409")
                .isEqualTo(HttpStatus.OK);
        assertThat(Envelope.data(afterSoldOut).path("couponCode").asString()).isEqualTo(originalCode);

        // ② 再把活动改成"已结束"
        jdbcTemplate.update("UPDATE coupon SET end_time = NOW() - INTERVAL 1 HOUR WHERE id = ?", couponId);
        ResponseEntity<String> afterEnded = grab(couponId, token);
        assertThat(afterEnded.getStatusCode())
                .as("已领过的人重放，不该因为活动结束变 409")
                .isEqualTo(HttpStatus.OK);
        assertThat(Envelope.data(afterEnded).path("couponCode").asString()).isEqualTo(originalCode);

        // 对照：没领过的用户在同一张（已结束 + 抢光）的券上仍是真失败
        String otherToken = registerAndGetToken("13800001002", "coupon-other");
        ResponseEntity<String> other = grab(couponId, otherToken);
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(Envelope.code(other)).isEqualTo("COUPON_UNAVAILABLE");

        // 两条重放路径都不得产生副作用
        assertThat(countOrders(couponId, null)).as("订单仍只有首次那 1 行").isEqualTo(1L);
        assertThat(stockOf(couponId)).as("重放不得再扣库存（也不得为负）").isZero();
    }

    // ==================== 抢券：鉴权与参数 ====================

    @Test
    @DisplayName("未登录领券：401（TV AuthFilter PROTECTED_EXACT 含原 /coupon/grab，新路径 /api/v1/coupons/{id}/claims 同口径）")
    void 未登录抢券() {
        long couponId = insertCoupon("新人券", 5, WINDOW_OPEN);

        ResponseEntity<String> resp = post("/api/v1/coupons/" + couponId + "/claims", null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(Envelope.code(resp)).isEqualTo("AUTH_UNAUTHORIZED");
        assertThat(Envelope.hasDataField(resp)).as("错误信封不含 data 键").isFalse();
        assertThat(countOrders(couponId, null)).isZero();
    }

    /**
     * `T58` 契约变更 + 依据《接口契约.md》§2.3/§2.4：{@code couponId} 进路径
     * （{@code POST /api/v1/coupons/{id}/claims}），body 载荷（{@code GrabCouponRequest}）退役——
     * 旧「缺 couponId / 非正数 → 400」档随之失效（少路径段 = 无路由匹配 404；
     * 声明式校验挂在 body 上，路径参数没有这层），可判定档收敛为：
     * 非数字 id → 400（参数绑定失败）；不存在的数字 id → 409（deductStock 匹配不到行）。
     */
    @Test
    @DisplayName("非数字 id：400 参数错误；不存在的 id：409 COUPON_UNAVAILABLE + 零副作用（`T58` 契约变更）")
    void 参数非法() {
        String token = registerAndGetToken(PHONE, USERNAME);
        long couponId = insertCoupon("新人券", 5, WINDOW_OPEN);

        assertThat(post("/api/v1/coupons/abc/claims", null, token).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/v1/coupons/0/claims", null, token).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(Envelope.code(post("/api/v1/coupons/0/claims", null, token)))
                .isEqualTo("COUPON_UNAVAILABLE");
        // 非法参数不得产生任何副作用
        assertThat(countOrders(couponId, null)).isZero();
        assertThat(stockOf(couponId)).isEqualTo(5);
    }

    // ==================== 可用券列表（公开端点） ====================

    @Test
    @DisplayName("可用券列表：无需 token；空库返回 data == []（不是 null）")
    void 可用券列表为空() {
        ResponseEntity<String> resp = get("/api/v1/coupons", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.code(resp)).isEqualTo("OK");
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

        ResponseEntity<String> resp = get("/api/v1/coupons", null);

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
    @DisplayName("我的券：未登录 401（TV AuthFilter PROTECTED_EXACT 含原 /coupon/my，新路径 /api/v1/users/me/coupons 同口径）")
    void 我的券未登录() {
        ResponseEntity<String> resp = get("/api/v1/users/me/coupons", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(Envelope.code(resp)).isEqualTo("AUTH_UNAUTHORIZED");
    }

    @Test
    @DisplayName("我的券：未抢券返回 data == []（TV pytest test_coupon_my_empty_list 的翻译）")
    void 我的券为空() {
        String token = registerAndGetToken(PHONE, USERNAME);

        ResponseEntity<String> resp = get("/api/v1/users/me/coupons", token);

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

        ResponseEntity<String> resp = get("/api/v1/users/me/coupons", token);

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

        assertThat(Envelope.data(get("/api/v1/users/me/coupons", tokenA)).size()).isEqualTo(1);
        assertThat(Envelope.data(get("/api/v1/users/me/coupons", tokenB)).size())
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
