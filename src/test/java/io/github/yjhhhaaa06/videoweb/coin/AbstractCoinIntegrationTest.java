package io.github.yjhhhaaa06.videoweb.coin;

import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 投币专项测试的共用夹具（T37 起）：清库 + **独立 oracle**（直读 {@code users} 的两列）。
 *
 * <h2>为什么 oracle 必须直读 DB 列</h2>
 * 签到回执里已经有 {@code balance} / {@code credited}，但那是**被测实现的回声**：
 * 若 {@code signIn} 的累加写错（比如 +20、或没写 {@code last_sign_date}），
 * 读回余额的同一套代码会把错误值原样回显，断言仍然"通过"。
 * 故本类的帮助方法一律 {@code SELECT coin_deci, last_sign_date FROM users WHERE id = ?}，
 * 用**列终态**复算（{@code AGENTS.md}「测试纪律」②）。
 *
 * <h2>清表与 Redis</h2>
 * 父类每个用例前 {@code DELETE FROM users}（自增 id 不重置）；本类再清
 * {@code content_coin} / {@code content}（签到暂时不碰它们，但 T38 会用到同一套夹具）。
 * Redis 是**进程级单例容器**（见 {@code support/Containers}），键会跨测试类残留 ⇒ 必须清，
 * 否则"上一条用例的缓存命中下一条"这种耦合极难定位（同 {@code AbstractContentIntegrationTest}）。
 */
public abstract class AbstractCoinIntegrationTest extends AbstractHttpIntegrationTest {

    /** 与 {@code AbstractContentIntegrationTest} 同款密码（注册夹具用）。 */
    protected static final String PASSWORD = "abc123456";

    @Autowired
    protected StringRedisTemplate redis;

    @BeforeEach
    void resetState() {
        // 父类的 DELETE FROM users 先跑（JUnit 5 父类 @BeforeEach 在前）。
        // content_coin 无外键，删除顺序不影响正确性；先把引用内容的行删掉再删内容，
        // 保持"按依赖倒序"这一读起来不会出错的习惯。
        jdbcTemplate.update("DELETE FROM content_coin");
        jdbcTemplate.update("DELETE FROM content");
        clearRedis();
    }

    // ==================== Redis ====================

    protected void clearRedis() {
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ==================== HTTP 形态辅助 ====================

    /** 空 form / query（签到端点无入参；T38 的投币端点会带参数）。 */
    protected static MultiValueMap<String, String> noQuery() {
        return new LinkedMultiValueMap<>();
    }

    /**
     * POST + {@code x-www-form-urlencoded}（本仓写路径的既有形态，同 {@code /like/*}）。
     *
     * <p>实现与 {@code AbstractContentIntegrationTest#postForm} 逐字同款：那个方法目前挂在
     * 内容切片夹具上，没有下沉到 {@code AbstractHttpIntegrationTest}；本域不继承内容夹具
     * （不需要造内容/媒体的能力），故先在这里保留一份。T38 会继续用它。
     */
    protected ResponseEntity<String> postForm(String path, MultiValueMap<String, String> form, String token) {
        return send(spec -> {
            RestClient.RequestBodySpec request = spec.uri(path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED);
            if (token != null) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return request.body(form);
        });
    }

    // ==================== DB 侧 oracle（不信任接口回显） ====================

    /** {@code users.coin_deci}（十分位整数）。用户不存在即断言失败——oracle 读不到就没有意义。 */
    protected int coinDeciOf(long userId) {
        Integer deci = jdbcTemplate.queryForObject(
                "SELECT coin_deci FROM users WHERE id = ?", Integer.class, userId);
        assertThat(deci).as("oracle 必须能读到 userId=%s 的 coin_deci", userId).isNotNull();
        return deci;
    }

    /**
     * {@code users.last_sign_date}（站内自然日；从未签到 / 用户不存在 ⇒ {@code null}）。
     *
     * <p>用 {@code java.sql.Date} 接再转 {@link LocalDate}：MySQL 的 {@code date} 列在 JDBC 侧
     * 就是 {@code java.sql.Date}，比依赖驱动的 {@code getObject(..., LocalDate.class)} 更稳。
     */
    protected LocalDate lastSignDateOf(long userId) {
        Date date = jdbcTemplate.queryForObject(
                "SELECT last_sign_date FROM users WHERE id = ?", Date.class, userId);
        return date == null ? null : date.toLocalDate();
    }

    /** 直改余额（造"昨天已签过、已有 N 枚"等前置状态；绕过端点，**不得**用于断言写路径本身）。 */
    protected void setCoinDeci(long userId, int deci) {
        jdbcTemplate.update("UPDATE users SET coin_deci = ? WHERE id = ?", deci, userId);
    }

    /** 直改签到日（{@code yyyy-MM-dd} 字符串，MySQL 会按列类型转 date）。 */
    protected void setLastSignDate(long userId, String date) {
        jdbcTemplate.update("UPDATE users SET last_sign_date = ? WHERE id = ?", date, userId);
    }

    /** {@code content_coin} 的总行数（T38 起用；本条签到用例用来断言"零副作用"不落行）。 */
    protected int countCoinRows() {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content_coin", Integer.class);
        return n == null ? 0 : n;
    }
}
