package io.github.yjhhhaaa06.videoweb.coin;

import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
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
 * {@code content_coin} / {@code content_media} / {@code content}（签到不碰它们，
 * T38 的投币写路径与"零副作用"断言要用同一套夹具）。
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
        // content_coin / content_media / content 都没有外键，删除顺序不影响正确性；
        // 先删引用内容的两张子表再删内容，保持"按依赖倒序"这一读起来不会出错的习惯。
        jdbcTemplate.update("DELETE FROM content_coin");
        jdbcTemplate.update("DELETE FROM content_media");
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

    // ==================== 造数据（JDBC 直插，T38） ====================

    /**
     * 造一条内容，返回自增 {@code content_id}。
     *
     * <p>只给 {@code user_id / type / title / description / category_id} 五列
     * （抄 {@code AbstractContentIntegrationTest#insertContent}）：{@code coin_count} 等计数列
     * 走表默认值。投币是**写路径**，不需要媒体行 —— 媒体只影响"内容可否构建 / 详情可见"，
     * 而 coin 的门禁判据是 {@code content.type} 与 {@code is_deleted}（{@code CoinService#add} 第 2~4 级）。
     *
     * @param authorId 作者 id
     * @param type     {@code 1} 视频 / {@code 2} 图文
     */
    protected long insertContent(long authorId, int type, String title) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description, category_id) VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setInt(2, type);
            ps.setString(3, title);
            ps.setString(4, title + " 的简介");
            ps.setInt(5, type == 1 ? 1 : 2);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    /**
     * 造一条媒体行，返回 {@code content_media.id}（T40 从
     * {@code content.AbstractContentIntegrationTest#insertMedia} 逐字搬来）。
     *
     * <p>⚠️ 上面的 {@link #insertContent} **只插 content 行、没有媒体行** —— 写路径（T38）
     * 不需要它，但**读路径**（详情 / 列表）会把这种记录当"媒体损坏"直接跳过 / 404
     * （{@code ContentCache#buildContentMedia} 的口径）。故 T40 的读面用例必须用下面两个
     * "可构建"夹具。
     */
    protected long insertMedia(long contentId, String url, int type, int sort) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content_media (content_id, url, type, sort) VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, contentId);
            ps.setString(2, url);
            ps.setInt(3, type);
            ps.setInt(4, sort);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content_media 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    /** 造一条**可构建**的视频内容（视频行 + 封面行）——读面夹具，逐字同内容切片基类。 */
    protected long insertVideoContent(long authorId, String title) {
        long contentId = insertContent(authorId, 1, title);
        insertMedia(contentId, "/upload/video/" + contentId + ".mp4", 1, 1);
        insertMedia(contentId, "/upload/cover/" + contentId + ".png", 3, 1);
        return contentId;
    }

    /** 造一条**可构建**的图文内容（2 张图 + 封面）——读面夹具，逐字同内容切片基类。 */
    protected long insertPostContent(long authorId, String title) {
        long contentId = insertContent(authorId, 2, title);
        insertMedia(contentId, "/upload/image/" + contentId + "_1.jpg", 2, 1);
        insertMedia(contentId, "/upload/image/" + contentId + "_2.jpg", 2, 2);
        insertMedia(contentId, "/upload/cover/" + contentId + ".png", 3, 1);
        return contentId;
    }

    // ==================== 用户夹具（T38 从 CoinSignInTests 下沉） ====================

    /**
     * 测试用户（id + token）。
     *
     * <p>下沉到基类是因为签到与投币两组用例都要"注册一个用户并拿到 id/token"；
     * 两份私有 record + 两份注册方法只会漂移（比如一边漏了 {@code userIdOf} 的断言）。
     */
    protected record TestUser(long id, String token) {
    }

    /**
     * 注册一个用户并取回 {@code (id, token)}。
     *
     * <p>id 由手机号反查（不信任注册接口回显 id）：注册响应的契约只有 token，
     * 用 {@code SELECT id FROM users WHERE phone = ?} 作独立 oracle（同父类 {@code userIdOf}）。
     */
    protected TestUser registerUser(String phone, String username) {
        String token = registerAndGetToken(phone, username, PASSWORD);
        return new TestUser(userIdOf(phone), token);
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

    /** {@code content_coin} 的总行数（T38 起用；签到用例用来断言"零副作用"不落行）。 */
    protected int countCoinRows() {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content_coin", Integer.class);
        return n == null ? 0 : n;
    }

    // ==================== 投币 oracle（T38） ====================

    /** {@code content.coin_count}（累计被投币数）。 */
    protected int coinCountOf(long contentId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT coin_count FROM content WHERE id = ?", Integer.class, contentId);
        assertThat(n).as("oracle 必须能读到 contentId=%s 的 coin_count", contentId).isNotNull();
        return n;
    }

    /**
     * 我在该内容上投了几枚（{@code content_coin.amount}）。
     *
     * <p>无行 ⇒ {@code null} —— "没投过"与"投了 0 枚"必须分得开：后者在本域不存在
     * （拒单即回滚，不落 0 行），所以 {@code null} 是唯一合法的"没投过"表示。
     * 用 {@code queryForList} 而不是 {@code queryForObject}：后者无行会抛
     * {@code EmptyResultDataAccessException}，把断言失败变成长堆栈。
     */
    protected Integer amountOf(long userId, long contentId) {
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT amount FROM content_coin WHERE user_id = ? AND content_id = ?",
                Integer.class, userId, contentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 直造 {@code content_coin} 的前置态（绕过端点）。
     *
     * <p>先删后插而不是 {@code ON DUPLICATE KEY UPDATE}：语义最直白，也不依赖 MySQL 版本的
     * {@code VALUES()} 弃用口径。⚠️ 只用于"已满 / 已投 1 枚"这类**前置状态**，
     * 不得用它制造断言目标（写路径的终态必须由真实 HTTP 请求产生）。
     */
    protected void setAmount(long userId, long contentId, int amount) {
        jdbcTemplate.update("DELETE FROM content_coin WHERE user_id = ? AND content_id = ?",
                userId, contentId);
        jdbcTemplate.update("INSERT INTO content_coin (user_id, content_id, amount) VALUES (?, ?, ?)",
                userId, contentId, amount);
    }

    /** 直改 {@code content.coin_count}（配 {@link #setAmount} 造自洽的前置态）。 */
    protected void setCoinCount(long contentId, int n) {
        jdbcTemplate.update("UPDATE content SET coin_count = ? WHERE id = ?", n, contentId);
    }

    /**
     * 作者余额（十分位）—— 语义化别名，读的就是 {@link #coinDeciOf} 那一列。
     *
     * <p>单独取名只为让断言读起来是"作者收到了多少"，而不是"某个用户的余额"；
     * 实现**不复制 SQL**（否则两处口径能漂移）。
     */
    protected int authorCoinDeciOf(long authorId) {
        return coinDeciOf(authorId);
    }
}
