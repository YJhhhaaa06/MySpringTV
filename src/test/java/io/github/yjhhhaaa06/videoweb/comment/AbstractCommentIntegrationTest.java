package io.github.yjhhhaaa06.videoweb.comment;

import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * comment 切片（S2）测试的共用夹具：造 content/comment、查独立 oracle、发带 token 的请求。
 *
 * <h2>为什么造数据不经过 HTTP</h2>
 * S2 是**写路径**切片：`content` 与既有评论树都是**前置条件**而非被测对象
 * （`content` 的创建属 upload/admin 切片，评论的读取属 S5）。故夹直接用 JDBC 造 seed，
 * 让每条用例只钉住"本次写操作改变了什么"。
 *
 * <p>同理，读回一律走**独立 oracle**（直接 `SELECT`），不经任何接口回显——
 * 这既是《测试策略》§4 纪律 2 的要求，也是本切片的**结构性必需**：
 * {@code /comment/show} 尚未迁移（CM-3），没有接口可以回显。
 *
 * <h2>与旧 pytest 的对照</h2>
 * 旧体系里写路径的行为规格集中在 {@code test_comment_delete.py}（删除规则）、
 * {@code test_comment_enabled.py}（开关门禁）、{@code test_boundary.E-03}（缺 message → 400）、
 * {@code test_smoke.S-12} 与 {@code test_consistency.C-09}（发评论与计数）。
 * 本切片的两个测试类即这些用例的**语义翻译**（意图保留、形式随架构重写）。
 */
abstract class AbstractCommentIntegrationTest extends AbstractHttpIntegrationTest {

    protected static final String PASSWORD = "abc123456";

    @BeforeEach
    void resetCommentState() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        // comment.content_id / content.user_id 均无外键（见 V1 baseline），故删除顺序不敏感，
        // 仍按"先子后父"写以便将来加外键时不返工。
        jdbcTemplate.update("DELETE FROM comment");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ==================== 造数据 ====================

    /**
     * 插入一条内容（{@code content} 表），返回自增 id。
     *
     * @param commentEnabled 评论区开关；{@code false} 用于验证发评论被拒（409）
     */
    protected long insertContent(long authorId, boolean commentEnabled) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description, comment_enabled) "
                            + "VALUES (?, 1, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setString(2, "S2 测试内容 " + authorId);
            ps.setString(3, "S2 测试描述");
            ps.setBoolean(4, commentEnabled);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    /** 插入一条评论（绕过接口，用于准备"楼中楼已存在"等前置状态），返回自增 id。 */
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

    protected String registerAndGetToken(String phone, String username) {
        ResponseEntity<String> resp = post("/user/register",
                Map.of("phone", phone, "username", username, "password", PASSWORD), null);
        String token = Envelope.str(resp, "token");
        assertThat(token).as("注册必须返回 token，否则后续用例无法进行：%s", resp.getBody()).isNotBlank();
        return token;
    }

    protected long userIdOf(String phone) {
        Long id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
        assertThat(id).as("手机号 %s 应已注册", phone).isNotNull();
        return id;
    }

    protected void setCommentEnabled(long contentId, boolean enabled) {
        jdbcTemplate.update("UPDATE content SET comment_enabled = ? WHERE id = ?", enabled, contentId);
    }

    protected void softDeleteContent(long contentId) {
        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", contentId);
    }

    // ==================== 独立 oracle（不信任接口回显） ====================

    protected int commentCountOf(long contentId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT comment_count FROM content WHERE id = ?", Integer.class, contentId);
        assertThat(n).as("content %s 应存在", contentId).isNotNull();
        return n;
    }

    protected int replyCountOf(long commentId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT reply_count FROM comment WHERE comment_id = ?", Integer.class, commentId);
        assertThat(n).as("comment %s 应存在", commentId).isNotNull();
        return n;
    }

    protected boolean isCommentDeleted(long commentId) {
        Integer d = jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM comment WHERE comment_id = ?", Integer.class, commentId);
        assertThat(d).as("comment %s 应存在", commentId).isNotNull();
        return d == 1;
    }

    /** 该内容下**未软删**的评论数。 */
    protected long countLiveComments(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE content_id = ? AND is_deleted = 0", Long.class, contentId);
        return n == null ? 0 : n;
    }

    /** 该内容下**全部**评论行数（含已软删）——用于断言"软删而非物理删"。 */
    protected long countAllComments(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE content_id = ?", Long.class, contentId);
        return n == null ? 0 : n;
    }

    /** 按正文定位评论 id（写路径没有读接口，夹具层用它把"刚发的评论"接上）。 */
    protected long findCommentId(long contentId, String text) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT comment_id FROM comment WHERE content_id = ? AND content = ?", Long.class, contentId, text);
        assertThat(id).as("内容 %s 下应存在正文为 %s 的评论", contentId, text).isNotNull();
        return id;
    }

    /** 直接查列：{@code parent_id}（可为 null）。 */
    protected Long parentIdOf(long commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT parent_id FROM comment WHERE comment_id = ?", Long.class, commentId);
    }

    /** 直接查列：{@code reply_to_user_id}（可为 null）。 */
    protected Long replyToUserIdOf(long commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT reply_to_user_id FROM comment WHERE comment_id = ?", Long.class, commentId);
    }

    // ==================== HTTP 便捷方法 ====================

    /** POST {@code /comment/add}。{@code parentId} 为 null 表示发主楼评论。 */
    protected ResponseEntity<String> addComment(long contentId, String message, Long parentId, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contentId", contentId);
        body.put("message", message);
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        return post("/comment/add", body, token);
    }

    /** POST {@code /comment/delete}（query 参数，沿袭 TV）。 */
    protected ResponseEntity<String> deleteComment(Object commentId, String token) {
        String path = commentId == null ? "/comment/delete" : "/comment/delete?commentId=" + commentId;
        return post(path, null, token);
    }
}
