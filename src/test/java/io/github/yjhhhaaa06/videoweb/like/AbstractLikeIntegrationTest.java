package io.github.yjhhhaaa06.videoweb.like;

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

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * like 切片（S3）测试的共用夹具：造 content/comment、查独立 oracle、读 Redis 键、发带 token 的请求。
 *
 * <h2>★ 每个用例前必须清 Redis</h2>
 * 测试 Redis 是 {@code support/Containers} 的**进程级单例**（整个测试 JVM 一个容器、端口恒定），
 * 所以缓存 key 会**跨测试类残留**。不清就会出现"上一条用例的缓存让下一条用例命中"这种
 * 极难定位的耦合。故 {@link #resetState()} 在删表之外还清空 Redis。
 *
 * <h2>两套独立 oracle</h2>
 * ① <b>MySQL</b>：直查 {@code content_like}/{@code comment_like} 行数与计数字列——
 * 验证"DB 真理源"这一侧；
 * ② <b>Redis</b>：直读 {@link StringRedisTemplate} 的键与值——验证"缓存"这一侧。
 * 两侧都**不信任接口回显**（《测试策略》§4 纪律 2）。S3 是首次同时需要两侧 oracle 的切片。
 *
 * <h2>请求形态</h2>
 * 写操作是 {@code POST} + {@code x-www-form-urlencoded}（沿袭 TV，旧 pytest 就是 form 提交的），
 * 故 {@link #postForm} 而非基类的 JSON {@code post}。
 */
abstract class AbstractLikeIntegrationTest extends AbstractHttpIntegrationTest {

    protected static final String PASSWORD = "abc123456";

    /** 与 {@code LikeCache} 的 key 工厂一致（刻意在测试里写死字面量——key 形状本身是契约）。 */
    protected static final String CONTENT_LIKE_COUNT_KEY = "content:likeCount:";
    protected static final String COMMENT_LIKE_COUNT_KEY = "comment:likeCount:";
    protected static final String USER_LIKE_SET_KEY = "user:likeSet:";
    protected static final String USER_COMMENT_LIKE_SET_KEY = "user:commentLikeSet:";

    /** 空标记前缀（第三批 T4 / 账 B2 补回后 like 域也有负缓存）。 */
    protected static final String EMPTY_PREFIX = "empty:";

    @Autowired
    protected StringRedisTemplate redis;

    @BeforeEach
    void resetState() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        jdbcTemplate.update("DELETE FROM content_like");
        jdbcTemplate.update("DELETE FROM comment_like");
        jdbcTemplate.update("DELETE FROM comment");
        jdbcTemplate.update("DELETE FROM content");
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

    protected String contentLikeCountValue(long contentId) {
        return redis.opsForValue().get(CONTENT_LIKE_COUNT_KEY + contentId);
    }

    protected String commentLikeCountValue(long commentId) {
        return redis.opsForValue().get(COMMENT_LIKE_COUNT_KEY + commentId);
    }

    protected boolean userLikeSetContains(long userId, long contentId) {
        return Boolean.TRUE.equals(
                redis.opsForSet().isMember(USER_LIKE_SET_KEY + userId, String.valueOf(contentId)));
    }

    protected boolean userCommentLikeSetContains(long userId, long commentId) {
        return Boolean.TRUE.equals(
                redis.opsForSet().isMember(USER_COMMENT_LIKE_SET_KEY + userId, String.valueOf(commentId)));
    }

    protected boolean hasKey(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    /** key 的剩余 TTL（秒）。用于验证"空标记是**短** TTL"（TTL=-1 表示永不过期 ⇒ 是缺陷）。 */
    protected Long ttlSecondsOf(String key) {
        return redis.getExpire(key, TimeUnit.SECONDS);
    }

    // ==================== 造数据 ====================

    protected long insertContent(long authorId) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description) VALUES (?, 1, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setString(2, "S3 测试内容 " + authorId);
            ps.setString(3, "S3 测试描述");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    protected long insertComment(long contentId, long userId, String text, Long parentId, Long replyToUserId) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO comment (content_id, user_id, content, parent_id, reply_to_user_id) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    new String[]{"comment_id"});
            ps.setLong(1, contentId);
            ps.setLong(2, userId);
            ps.setString(3, text);
            if (parentId == null) {
                ps.setNull(4, Types.BIGINT);
            } else {
                ps.setLong(4, parentId);
            }
            if (replyToUserId == null) {
                ps.setNull(5, Types.BIGINT);
            } else {
                ps.setLong(5, replyToUserId);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("comment 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    protected void softDeleteComment(long commentId) {
        jdbcTemplate.update("UPDATE comment SET is_deleted = 1 WHERE comment_id = ?", commentId);
    }

    /** 2 参便捷形态（密码沿用本域的 {@link #PASSWORD}）。实现已下沉到 {@code AbstractHttpIntegrationTest}。 */
    protected String registerAndGetToken(String phone, String username) {
        return registerAndGetToken(phone, username, PASSWORD);
    }

    // ==================== DB 侧 oracle（不信任接口回显） ====================

    /** {@code content_like} 里该内容的点赞**行数**（与 {@code content.like_count} 列互为独立复算）。 */
    protected long countContentLikeRows(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_like WHERE content_id = ?", Long.class, contentId);
        return n == null ? 0 : n;
    }

    protected long countContentLikeRows(long contentId, long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_like WHERE content_id = ? AND user_id = ?",
                Long.class, contentId, userId);
        return n == null ? 0 : n;
    }

    protected long countCommentLikeRows(long commentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comment_like WHERE comment_id = ?", Long.class, commentId);
        return n == null ? 0 : n;
    }

    /** 直接查 {@code content.like_count} 列。 */
    protected int contentLikeCountColumn(long contentId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT like_count FROM content WHERE id = ?", Integer.class, contentId);
        assertThat(n).as("content %s 应存在", contentId).isNotNull();
        return n;
    }

    /** 直接查 {@code comment.like_count} 列。 */
    protected int commentLikeCountColumn(long commentId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT like_count FROM comment WHERE comment_id = ?", Integer.class, commentId);
        assertThat(n).as("comment %s 应存在", commentId).isNotNull();
        return n;
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

    protected ResponseEntity<String> likeContent(long contentId, String token) {
        return postForm("/like/content/add", form("contentId", contentId), token);
    }

    protected ResponseEntity<String> unlikeContent(long contentId, String token) {
        return postForm("/like/content/remove", form("contentId", contentId), token);
    }

    protected ResponseEntity<String> likeComment(long commentId, String token) {
        return postForm("/like/comment/add", form("commentId", commentId), token);
    }

    protected ResponseEntity<String> unlikeComment(long commentId, String token) {
        return postForm("/like/comment/remove", form("commentId", commentId), token);
    }

    protected static MultiValueMap<String, String> form(String name, long value) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(name, String.valueOf(value));
        return form;
    }
}
