package io.github.yjhhhaaa06.videoweb.coupon;

import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.client.RestClient;

import java.sql.PreparedStatement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * coupon 切片（S1）测试的共用夹具：造券、查独立 oracle、发带 token 的请求。
 *
 * <p>抽出来是为了让功能闭环测试（{@link CouponFlowTests}）与并发测试
 * （{@link CouponConcurrencyTests}）共用同一套夹具语义，
 * 而不是各写一份"造券"逻辑——两份夹具一旦漂移，测试就失去可比性。
 *
 * <h2>时间窗约定（重要）</h2>
 * 所有时间窗都用**数据库时钟**（{@code NOW()}）在 SQL 里算，不用 JVM 的 {@code LocalDateTime}。
 * 原因：Testcontainers 的 MySQL 容器默认 UTC，而 JVM 是 Asia/Shanghai，
 * 用 {@code Timestamp} 绑定会引入偏移，表现为"活动未开始却抢到了"这类**偶发**失败
 * （不报错、时好时坏，是最难查的一类）。{@code WINDOW_*} 常量即为此准备。
 */
abstract class AbstractCouponIntegrationTest extends AbstractHttpIntegrationTest {

    protected static final String PASSWORD = "abc123456";

    /** 活动进行中（已开始、未结束）。 */
    protected static final String WINDOW_OPEN = "NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 1 DAY";

    /** 活动尚未开始（`begin_time` 在未来）——注意它仍会出现在 /coupon/list 里。 */
    protected static final String WINDOW_NOT_STARTED = "NOW() + INTERVAL 1 DAY, NOW() + INTERVAL 2 DAY";

    /** 活动已结束。 */
    protected static final String WINDOW_ENDED = "NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 1 DAY";

    @BeforeEach
    void resetCouponState() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        // 先删子表再删父表：coupon_order 对 coupon 有 FK（ON DELETE CASCADE），
        // 显式删除子表更清晰，也保证不留残留行。
        jdbcTemplate.update("DELETE FROM coupon_order");
        jdbcTemplate.update("DELETE FROM coupon");
    }

    // ==================== 造数据 ====================

    /**
     * 插入一张券，返回自增 id。
     *
     * <p>{@code window} 是直接内联进 SQL 的**时间窗表达式**（见类注释）。
     * 该片段是测试自己的常量（无外部输入），无注入口风险。
     */
    protected long insertCoupon(String title, int stock, String window) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        String sql = "INSERT INTO coupon (title, stock, begin_time, end_time) VALUES (?, ?, " + window + ")";
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, title);
            ps.setInt(2, stock);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("coupon 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    /** 注册用户并返回 token（断言注册必须成功——否则后续用例的失败原因会指错方向）。 */
    protected String registerAndGetToken(String phone, String username) {
        ResponseEntity<String> resp = post("/user/register",
                Map.of("phone", phone, "username", username, "password", PASSWORD), null);
        String token = Envelope.str(resp, "token");
        assertThat(token).as("注册必须返回 token，否则后续用例无法进行：%s", resp.getBody()).isNotBlank();
        return token;
    }

    // ==================== 独立 oracle（不信任接口回显） ====================

    /** 直接查列取库存（独立 oracle，见测试策略 §4 纪律 2）。 */
    protected int stockOf(long couponId) {
        Integer stock = jdbcTemplate.queryForObject(
                "SELECT stock FROM coupon WHERE id = ?", Integer.class, couponId);
        assertThat(stock).as("coupon %s 应存在", couponId).isNotNull();
        return stock;
    }

    /** 订单行数。{@code userId} 为 null 时只按券统计（用于"完全没产生订单"的断言）。 */
    protected long countOrders(long couponId, Long userId) {
        Long n = userId == null
                ? jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM coupon_order WHERE coupon_id = ?", Long.class, couponId)
                : jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM coupon_order WHERE coupon_id = ? AND user_id = ?",
                        Long.class, couponId, userId);
        return n == null ? 0 : n;
    }

    // ==================== HTTP ====================

    protected ResponseEntity<String> grab(long couponId, String token) {
        return post("/coupon/grab", Map.of("couponId", couponId), token);
    }

    protected ResponseEntity<String> post(String path, Object body, String token) {
        return send(spec -> {
            RestClient.RequestBodySpec request = spec.uri(path).contentType(MediaType.APPLICATION_JSON);
            if (token != null) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return request.body(body);
        });
    }

    protected ResponseEntity<String> get(String path, String token) {
        return sendGet(spec -> {
            RestClient.RequestHeadersSpec<?> request = spec.uri(path);
            if (token != null) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return request;
        });
    }
}
