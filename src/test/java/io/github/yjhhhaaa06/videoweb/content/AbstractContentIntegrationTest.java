package io.github.yjhhhaaa06.videoweb.content;

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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * content 切片（S5）测试的共用夹具：造内容/媒体行、查独立 oracle（DB + Redis 两套）、发请求。
 *
 * <h2>★ 为什么必须用 JDBC 直接造数据（而不是调接口上传）</h2>
 * 老 project 的 pytest 靠 {@code POST /api/upload/video} 造内容，因为那边 upload 是同一个进程。
 * 本仓的 **upload 域尚未迁移**（决策表 §二·G 的范围裁定：{@code addVideo}/{@code addPost} 随
 * upload 批次）⇒ 内容与媒体行只能直接插库。
 * 这不是取巧：S5 交付的是**读路径**，它消费的是"库里已有的内容"——直接造夹具反而让
 * 被测边界更干净（不把 upload 的行为混进来）。
 *
 * <h2>★ 每个用例前必须清 Redis</h2>
 * 测试 Redis 是 {@code support/Containers} 的**进程级单例**（整个测试 JVM 一个容器、端口恒定），
 * 所以缓存 key 会**跨测试类残留**。不清就会出现"上一条用例的缓存让下一条用例命中"这种
 * 极难定位的耦合。故 {@link #resetState()} 在删表之外还清空 Redis（同 S3/S4 的纪律）。
 *
 * <h2>两套独立 oracle</h2>
 * ① <b>MySQL</b>：直查 {@code content}/{@code content_media} 的行与列——
 * 不信任接口回显（《测试策略》§4 纪律 2）；
 * ② <b>Redis</b>：直读 {@link StringRedisTemplate} 的键与值——验证"缓存"这一侧。
 *
 * <h2>请求形态</h2>
 * 写操作是 {@code POST} + query 参数（TV 的 {@code /content/update?contentId=X&title=…} 就是
 * query 形态），故 {@link #postQuery}；读操作是 {@code GET}。
 *
 * <h2>★ 为什么 {@code public}（而不是像 S3/S4 那样包级）</h2>
 * S5 的 comment 读路径测试（{@code comment/CommentReadTests}）需要**同一套夹具**——
 * 评论必须挂在内容上（{@code comment.content_id}），故它也要造内容与媒体。
 * 与其复制一份，不如把这个夹具开放给同一切片的 comment 测试继承。
 * 名称保留 {@code Content} 前缀是因为它造的确实是内容侧的夹具（评论是它的附加部分）。
 */
public abstract class AbstractContentIntegrationTest extends AbstractHttpIntegrationTest {

    protected static final String PASSWORD = "abc123456";

    /** 与 {@code CacheKeys} 一致（刻意写死字面量——key 形状本身是契约）。 */
    protected static final String CONTENT_KEY_PREFIX = "content:";
    protected static final String CONTENT_INDEX_PREFIX = "content:index:";
    protected static final String EMPTY_PREFIX = "empty:";

    /** 域级分页常量（T19）：search / profile 上限 100、信封 100。 */
    protected static final int PAGE_SIZE_MAX = 100;

    protected static final int CONTENT_TYPE_VIDEO = 1;
    protected static final int CONTENT_TYPE_IMAGE = 2;
    protected static final int MEDIA_TYPE_VIDEO = 1;
    protected static final int MEDIA_TYPE_IMAGE = 2;
    protected static final int MEDIA_TYPE_COVER = 3;

    @Autowired
    protected StringRedisTemplate redis;

    @BeforeEach
    void resetState() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        // content / content_media / comment / *_like 都没有外键，删除顺序不影响正确性。
        jdbcTemplate.update("DELETE FROM content_media");
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

    /** 当前 Redis 里的全部键。用于断言"缓存**没有**被写入"（回滚 / 降级场景）。 */
    protected Set<String> redisKeys() {
        Set<String> keys = redis.keys("*");
        return keys == null ? Set.of() : keys;
    }

    /** {@code content:{id}} 的原始 JSON（null = 该 key 不存在，即未缓存）。 */
    protected String cachedContentJson(long contentId) {
        return redis.opsForValue().get(CONTENT_KEY_PREFIX + contentId);
    }

    // ==================== 造数据（JDBC 直插） ====================

    protected long insertContent(long authorId, int type, String title, String description, int categoryId) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description, category_id) VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setInt(2, type);
            ps.setString(3, title);
            ps.setString(4, description);
            ps.setInt(5, categoryId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

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

    /** 造一条**可构建**的视频内容（视频行 + 封面行）。 */
    protected long insertVideoContent(long authorId, String title) {
        long contentId = insertContent(authorId, CONTENT_TYPE_VIDEO, title, title + " 的简介", 1);
        insertMedia(contentId, "/upload/video/" + contentId + ".mp4", MEDIA_TYPE_VIDEO, 1);
        insertMedia(contentId, "/upload/cover/" + contentId + ".png", MEDIA_TYPE_COVER, 1);
        return contentId;
    }

    /** 造一条**可构建**的图文内容（2 张图 + 封面）。 */
    protected long insertPostContent(long authorId, String title) {
        long contentId = insertContent(authorId, CONTENT_TYPE_IMAGE, title, title + " 的简介", 2);
        insertMedia(contentId, "/upload/image/" + contentId + "_1.jpg", MEDIA_TYPE_IMAGE, 1);
        insertMedia(contentId, "/upload/image/" + contentId + "_2.jpg", MEDIA_TYPE_IMAGE, 2);
        insertMedia(contentId, "/upload/cover/" + contentId + ".png", MEDIA_TYPE_COVER, 1);
        return contentId;
    }

    /** 造一条**媒体损坏**的内容：type=1（视频）但没有视频行 ⇒ 对外不可用（详情 404 / 列表跳过）。 */
    protected long insertBrokenVideoContent(long authorId, String title) {
        long contentId = insertContent(authorId, CONTENT_TYPE_VIDEO, title, title, 1);
        insertMedia(contentId, "/upload/cover/" + contentId + ".png", MEDIA_TYPE_COVER, 1);
        return contentId;
    }

    /** 2 参便捷形态（密码沿用本域的 {@link #PASSWORD}）。实现已下沉到 {@code AbstractHttpIntegrationTest}。 */
    protected String registerAndGetToken(String phone, String username) {
        return registerAndGetToken(phone, username, PASSWORD);
    }

    // ==================== DB 侧 oracle（不信任接口回显） ====================

    protected String contentTitleColumn(long contentId) {
        return jdbcTemplate.queryForObject(
                "SELECT title FROM content WHERE id = ?", String.class, contentId);
    }

    protected String contentDescriptionColumn(long contentId) {
        return jdbcTemplate.queryForObject(
                "SELECT description FROM content WHERE id = ?", String.class, contentId);
    }

    protected int contentCommentEnabledColumn(long contentId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT comment_enabled FROM content WHERE id = ?", Integer.class, contentId);
        return value == null ? -1 : value;
    }

    protected int contentIsDeletedColumn(long contentId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM content WHERE id = ?", Integer.class, contentId);
        return value == null ? -1 : value;
    }

    protected int countContentRows() {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content", Integer.class);
        return n == null ? 0 : n;
    }

    protected int countMediaRows(long contentId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_media WHERE content_id = ?", Integer.class, contentId);
        return n == null ? 0 : n;
    }

    protected int countLikeRows(long contentId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_like WHERE content_id = ?", Integer.class, contentId);
        return n == null ? 0 : n;
    }

    /** 把若干内容的 {@code create_time} 钉到同一秒（T15 tie-breaker 用例用）。 */
    protected void pinCreateTime(String timestamp, long... contentIds) {
        for (long contentId : contentIds) {
            jdbcTemplate.update("UPDATE content SET create_time = ? WHERE id = ?", timestamp, contentId);
        }
    }

    // ==================== HTTP ====================

    /** POST + query 参数（TV 的 {@code /content/xxx?param=…} 形态）。{@code token == null} = 匿名。 */
    protected ResponseEntity<String> postQuery(String path, MultiValueMap<String, String> query, String token) {
        return send(spec -> {
            RestClient.RequestBodySpec request = spec.uri(uriBuilder -> {
                var builder = uriBuilder.path(path);
                if (query != null) {
                    builder.queryParams(query);
                }
                return builder.build();
            }).contentType(MediaType.APPLICATION_JSON);
            if (token != null) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return request;
        });
    }

    /**
     * POST + {@code x-www-form-urlencoded}（TV 的 **like 域**写操作形态：
     * {@code POST /like/comment/add} 收 form 而不是 JSON）。
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

    /** 可变参数构造 query（{@code query("contentId", "1", "enabled", "0")}）。 */
    protected static MultiValueMap<String, String> query(String... keyValues) {
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.add(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    protected static MultiValueMap<String, String> noQuery() {
        return new LinkedMultiValueMap<>();
    }

    /**
     * GET + 结构化 query 参数。
     *
     * <p>★ 为什么需要它（而不是拼 {@code "?keyword=%20%20"}）：{@code RestClient.uri(String)}
     * 会把字符串当 URI 模板，其中的 {@code %} 会被**再编码一次**（{@code %20} → {@code %2520}），
     * 于是"空白关键词"到服务端变成了字面量 {@code "%20%20"}——测试会**假绿/假红**而不报错。
     * 用 {@code UriBuilder.queryParams} 让编码只发生一次。
     */
    protected ResponseEntity<String> getQuery(String path, MultiValueMap<String, String> query, String token) {
        return sendGet(spec -> {
            RestClient.RequestHeadersSpec<?> request = spec.uri(uriBuilder -> {
                var builder = uriBuilder.path(path);
                builder.queryParams(query);
                return builder.build();
            });
            if (token != null) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return request;
        });
    }

    /** 造 {@code content_like} 行（准备"已点赞"前置状态；走 like 端点也行，但这里直插更简洁）。 */
    protected void insertContentLike(long userId, long contentId) {
        jdbcTemplate.update("INSERT INTO content_like (user_id, content_id) VALUES (?, ?)",
                userId, contentId);
    }

    // ==================== 评论夹具（S5 的 comment 读路径测试共用） ====================

    /**
     * 造一条评论行，返回 {@code comment_id}。
     *
     * <p>⚠️ <b>不</b>联动 {@code content.comment_count} / {@code comment.reply_count}：
     * 读路径的断言只关心"评论树长什么样"，计数列的口径由 S2 的 {@code CommentCounterTests} 负责
     * （那里的夹具是自洽的）。这里若也同步计数，会让"计数由写路径维护"这一点被双重覆盖，
     * 反而掩盖写路径的问题。
     */
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
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, parentId);
            }
            if (replyToUserId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, replyToUserId);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("comment 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    /** 主楼（{@code parent_id = NULL}）。 */
    protected long insertMainComment(long contentId, long userId, String text) {
        return insertComment(contentId, userId, text, null, null);
    }

    protected void softDeleteComment(long commentId) {
        jdbcTemplate.update("UPDATE comment SET is_deleted = 1 WHERE comment_id = ?", commentId);
    }

    /** 直改主楼 {@code reply_count}（读路径的 total 来源；写路径的维护由 S2 的测试负责）。 */
    protected void setReplyCount(long rootId, int value) {
        jdbcTemplate.update("UPDATE comment SET reply_count = ? WHERE comment_id = ?", value, rootId);
    }

    protected int commentRows(long contentId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE content_id = ?", Integer.class, contentId);
        return n == null ? 0 : n;
    }

    /**
     * 直改 {@code content.comment_enabled}。
     *
     * <p>⚠️ <b>用它会绕过缓存失效</b>：内容 key 里缓存了 {@code commentEnabled}，
     * 只有走真实端点（{@code POST /content/commentEnabled}，提交后发事件失效）才会刷新。
     * 故断言"开关生效"的用例**必须走端点**；本方法只适合"断言缓存陈旧窗口本身"这类用例。
     * （实测踩过一次：用它关闭后，再直改开启，读到的仍是缓存里的"关闭"。）
     */
    protected void setCommentEnabled(long contentId, int enabled) {
        jdbcTemplate.update("UPDATE content SET comment_enabled = ? WHERE id = ?", enabled, contentId);
    }

    protected void insertCommentLike(long userId, long commentId) {
        jdbcTemplate.update("INSERT INTO comment_like (user_id, comment_id) VALUES (?, ?)",
                userId, commentId);
    }

    /** 评论树的两键组 / count 的 key（刻意写死字面量——key 形状本身是契约）。 */
    protected static String rootsKey(long contentId) {
        return "content:comments:" + contentId + ":roots";
    }

    protected static String repliesKey(long contentId) {
        return "content:comments:" + contentId + ":replies";
    }

    protected static String rootCountKey(long contentId) {
        return "content:comments:" + contentId + ":count";
    }

    /** 内容是否仍在**类型分区索引**里（遍历全部 {@code content:index:*} 的 LIST）。 */
    protected boolean indexContains(long contentId) {
        Set<String> keys = redis.keys(CONTENT_INDEX_PREFIX + "*");
        if (keys == null) {
            return false;
        }
        for (String key : keys) {
            java.util.List<String> values = redis.opsForList().range(key, 0, -1);
            if (values != null && values.contains(String.valueOf(contentId))) {
                return true;
            }
        }
        return false;
    }

    /** 该内容的媒体行（{@code type} / {@code sort} 的拼接，用于断言 sort 连续）。 */
    protected java.util.List<String> mediaTypeSorts(long contentId, int type) {
        return jdbcTemplate.queryForList(
                "SELECT CONCAT(type, ':', sort) FROM content_media WHERE content_id = ? AND type = ? ORDER BY sort",
                String.class, contentId, type);
    }
}
