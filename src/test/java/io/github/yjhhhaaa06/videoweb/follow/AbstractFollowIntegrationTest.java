package io.github.yjhhhaaa06.videoweb.follow;

import io.github.yjhhhaaa06.videoweb.follow.service.FollowService;
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

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * follow 切片（S4）测试的共用夹具：造用户、查独立 oracle（DB + Redis 两套）、发带 token 的请求。
 *
 * <h2>★ 每个用例前必须清 Redis</h2>
 * 测试 Redis 是 {@code support/Containers} 的**进程级单例**（整个测试 JVM 一个容器、端口恒定），
 * 所以缓存 key 会**跨测试类残留**。不清就会出现"上一条用例的缓存让下一条用例命中"这种
 * 极难定位的耦合。故 {@link #resetState()} 在删表之外还清空 Redis（同 S3 的纪律）。
 *
 * <h2>两套独立 oracle</h2>
 * ① <b>MySQL</b>：直查 {@code follow} 行数与 {@code users.follow_count}/{@code follower_count}
 * **列**（不信任接口回显；《测试策略》§4 纪律 2）；
 * ② <b>Redis</b>：直读 ZSet 成员与空标记——验证"缓存"这一侧。
 *
 * <h2>请求形态</h2>
 * 写操作沿袭 TV：{@code POST} + {@code x-www-form-urlencoded}（旧 pytest 就是 form 提交的），
 * 故 {@link #postForm} 而非基类的 JSON {@code post}。
 */
abstract class AbstractFollowIntegrationTest extends AbstractHttpIntegrationTest {

    protected static final String PASSWORD = "abc123456";

    /** 与 {@code FollowCache} 的 key 工厂一致（刻意在测试里写死字面量——key 形状本身是契约）。 */
    protected static final String FOLLOWING_KEY = "user:following:";
    protected static final String FOLLOWER_KEY = "user:follower:";
    protected static final String EMPTY_PREFIX = "empty:";

    /** 域级分页常量（T19：200 → 100）。测试里写死，防止实现侧悄悄改动。 */
    protected static final int PAGE_SIZE_MAX = 100;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected FollowService followService;

    @BeforeEach
    void resetState() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        // follow / auto_bigv 都没有外键，删除顺序不影响正确性。
        jdbcTemplate.update("DELETE FROM follow");
        jdbcTemplate.update("DELETE FROM auto_bigv");
        clearRedis();
    }

    // ==================== Redis（缓存侧 oracle） ====================

    protected void clearRedis() {
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /** 当前 Redis 里的全部键。用于断言"缓存**没有**被写入"（回滚 / 冷 key 场景）。 */
    protected Set<String> redisKeys() {
        Set<String> keys = redis.keys("*");
        return keys == null ? Set.of() : keys;
    }

    protected boolean followingSetContains(long userId, long followedUserId) {
        return redis.opsForZSet().score(FOLLOWING_KEY + userId, String.valueOf(followedUserId)) != null;
    }

    protected boolean followerSetContains(long userId, long followerId) {
        return redis.opsForZSet().score(FOLLOWER_KEY + userId, String.valueOf(followerId)) != null;
    }

    protected Long zCard(String key) {
        return redis.opsForZSet().zCard(key);
    }

    protected boolean hasKey(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    /** 当前 Redis 里以某前缀开头的键（用于断言"这类 key 一个都没被创建"）。 */
    protected Set<String> keysWithPrefix(String prefix) {
        return redisKeys().stream().filter(k -> k.startsWith(prefix)).collect(Collectors.toSet());
    }

    /** key 的剩余 TTL（秒）。用于验证"写入时带了 TTL"（TTL=-1 表示永不过期 ⇒ 是缺陷）。 */
    protected Long ttlSecondsOf(String key) {
        return redis.getExpire(key, TimeUnit.SECONDS);
    }

    // ==================== 造数据 ====================

    /**
     * 直接插 {@code follow} 行（夹具用；走 API 会顺带触发缓存写，构造"已有关注"初态时不需要）。
     *
     * <p>⚠️ <b>必须同步维护两侧计数列</b>——这是 S2 踩过一次的坑（SOP §二 坑 13）：
     * 计数列（{@code users.follow_count}/{@code follower_count}）与业务行是**冗余关系**，
     * 夹具只插业务行会让"降级路径取 total"读到 0、而成员集里却有 1 条，
     * 于是断言失败指向夹具的不自洽、而不是被测逻辑。故这里一并 +1。
     */
    protected void insertFollowRow(long userId, long followedUserId) {
        jdbcTemplate.update("INSERT INTO follow (user_id, followed_user_id) VALUES (?, ?)",
                userId, followedUserId);
        jdbcTemplate.update("UPDATE users SET follow_count = follow_count + 1 WHERE id = ?", userId);
        jdbcTemplate.update("UPDATE users SET follower_count = follower_count + 1 WHERE id = ?", followedUserId);
    }

    /** 注册用户并回填其 id 与 token（S4 几乎每个用例都要两个以上用户）。 */
    protected TestUser register(String phone, String username) {
        String token = registerAndGetToken(phone, username, PASSWORD);
        return new TestUser(userIdOf(phone), token);
    }

    /** 一个已注册用户的 (id, token)。 */
    protected record TestUser(long id, String token) {
    }

    // ==================== DB 侧 oracle（不信任接口回显） ====================

    protected long countFollowRows(long userId, long followedUserId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM follow WHERE user_id = ? AND followed_user_id = ?",
                Long.class, userId, followedUserId);
        return n == null ? 0 : n;
    }

    protected long countFollowRows() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM follow", Long.class);
        return n == null ? 0 : n;
    }

    /** 直接查 {@code users.follow_count} 列。 */
    protected int followCountColumn(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT follow_count FROM users WHERE id = ?", Integer.class, userId);
        if (n == null) {
            throw new AssertionError("users 行应存在: " + userId);
        }
        return n;
    }

    /** 直接查 {@code users.follower_count} 列。 */
    protected int followerCountColumn(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT follower_count FROM users WHERE id = ?", Integer.class, userId);
        if (n == null) {
            throw new AssertionError("users 行应存在: " + userId);
        }
        return n;
    }

    /** {@code auto_bigv} 是否存在该行（"存在即自动大V"）。 */
    protected boolean isAutoBigV(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auto_bigv WHERE user_id = ?", Long.class, userId);
        return n != null && n > 0;
    }

    // ==================== HTTP ====================

    /** POST + {@code x-www-form-urlencoded}（TV 的写操作形态）。 */
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

    protected ResponseEntity<String> follow(long followedUserId, String token) {
        return postForm("/follow/add", form("followedUserId", followedUserId), token);
    }

    protected ResponseEntity<String> unfollow(long followedUserId, String token) {
        return postForm("/follow/remove", form("followedUserId", followedUserId), token);
    }

    protected ResponseEntity<String> getFollowing(long userId, String token) {
        return get("/follow/following?userId=" + userId, token);
    }

    protected ResponseEntity<String> getFollowers(long userId, String token) {
        return get("/follow/followers?userId=" + userId, token);
    }

    protected static MultiValueMap<String, String> form(String name, long value) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(name, String.valueOf(value));
        return form;
    }
}
