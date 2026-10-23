package io.github.yjhhhaaa06.videoweb.favorite;

import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收藏三期 <b>T17</b> 的**隔离契约**：稍后再看**不污染收藏**（不共表的价值所在）。
 *
 * <h2>为什么单列一类（这是本方案的核心卖点，必须被钉死）</h2>
 * 稍后再看与收藏**不共表**（用户 2026-10-14 拍板）—— 目的就是让
 * {@code /api/v1/contents/{id}/favorite}（{@code isFavorited}）、{@code /api/v1/contents/{id}/favorite-count}、夹列表、公开夹
 * **四条读语义天然看不到稍后再看**。本类把"看不到"变成一条**可观察断言**：
 * add 稍后再看后，收藏状态仍 {@code false}、收藏数不变。
 *
 * <h2>★ 反向注入（必红）</h2>
 * 让"稍后再看写路径同时写 {@code favorite_item}"（例如在 {@code WatchLaterService.add} 里
 * 加一句 {@code itemDao.insertItem(...)}）⇒ 本类必红（{@code isFavorited} 变 true、count 变 1、
 * oracle 的 {@code favorite_item} 行数 > 0）。这正是 T17 验收③要求的注入点。
 *
 * <h2>独立性</h2>
 * 两处计数都用**独立 oracle** 直查 DB（{@code favorite_item} 行数、{@code content.favorite_count} 列），
 * 不信接口回显 —— 接口回显只作同一事实的第二确认。
 */
class WatchLaterIsolationTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    @BeforeEach
    void 清空本域相关表() {
        jdbcTemplate.update("DELETE FROM watch_later");
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content_media");
        jdbcTemplate.update("DELETE FROM content");
    }

    @Test
    @DisplayName("★加入稍后再看不改变收藏状态：/api/v1/contents/{id}/favorite 仍 false+[]、oracle favorite_item 0 行")
    void 加入稍后再看不改变收藏状态() {
        TestUser me = register("13800006001", "隔离用户");
        long video = insertVideoContent(me.id, "隔离视频");

        // 基线：未收藏
        assertThat(status(me.token, video).path("isFavorited").asBoolean()).isFalse();
        assertThat(status(me.token, video).path("folders").size()).isZero();

        assertThat(add(me.token, video).getStatusCode().value()).isEqualTo(200);

        // ★ 稍后再看写成功后，收藏状态**一字不变**
        JsonNode after = status(me.token, video);
        assertThat(after.path("isFavorited").asBoolean())
                .as("★ 稍后再看不进收藏集（独立表 ⇒ 天然看不到）").isFalse();
        assertThat(after.path("folders").size()).as("收在哪些夹为空").isZero();
        assertThat(oracleFavoriteItemRows(me.id, video))
                .as("★ 独立 oracle：favorite_item 里 0 行（稍后再看写路径不许碰它）").isZero();
        assertThat(oracleFavoriteCountColumn(video))
                .as("★ 独立 oracle：content.favorite_count 仍为 0").isZero();
    }

    @Test
    @DisplayName("★加入稍后再看不改变收藏数：/api/v1/contents/{id}/favorite-count 不变；oracle 列与行都不动")
    void 加入稍后再看不改变收藏数() {
        TestUser me = register("13800006002", "隔离计数用户");
        long video = insertVideoContent(me.id, "计数隔离视频");

        assertThat(count(me.token, video)).as("基线：无人收藏 ⇒ 0").isEqualTo(0L);
        add(me.token, video);
        assertThat(count(me.token, video))
                .as("★ 收藏数不因'加入稍后再看'变化（favorite-需求.md §六：两者独立）").isEqualTo(0L);
        assertThat(oracleFavoriteCountColumn(video)).isZero();
    }

    @Test
    @DisplayName("★互不干扰：先收藏再加稍后再看，收藏数仍是 1（不是 2）")
    void 收藏与稍后再看互不干扰() {
        TestUser me = register("13800006003", "互扰用户");
        long video = insertVideoContent(me.id, "互扰视频");

        // 先真正收藏（走真实写路径 ⇒ 维护 favorite_count 列）
        assertThat(postForm("/api/v1/favorites", "contentId", String.valueOf(video), me.token).getStatusCode().value())
                .isEqualTo(200);
        assertThat(count(me.token, video)).as("收藏后 = 1").isEqualTo(1L);
        assertThat(status(me.token, video).path("isFavorited").asBoolean()).isTrue();

        // 再加稍后再看：收藏侧一切不变
        add(me.token, video);
        assertThat(count(me.token, video))
                .as("★ 稍后再看不给收藏数 +1（仍是 1）").isEqualTo(1L);
        assertThat(oracleFavoriteItemRows(me.id, video)).as("favorite_item 仍是 1 行").isEqualTo(1L);
        assertThat(oracleWatchLaterRows(me.id, video)).as("稍后再看 1 行").isEqualTo(1L);
    }

    @Test
    @DisplayName("★红线：除清单外无状态面 —— 不存在'是否在稍后再看'的读端点；详情互动态不含 isWatchLater")
    void 没有状态面() {
        TestUser me = register("13800006004", "无状态面用户");
        long video = insertVideoContent(me.id, "红线视频");

        // `T58` 契约变更（接口契约.md §2.3）：稍后再看资源化为 /api/v1/watch-later，
        // 若将来有人加状态端点，最自然的落点是内容维度的单例关系 GET /api/v1/contents/{id}/watch-later
        // —— 该路径**未映射**（Spring 走全局出口，非 200），绝不是"可用状态端点"。
        assertThat(get("/api/v1/contents/" + video + "/watch-later", me.token).getStatusCode().value())
                .as("★ 不提供'是否在稍后再看'的查询端点（favorite-需求.md §六 / favorite.md §九③）——"
                        + "该路径**未映射**（Spring 走全局出口，非 200），绝不是'可用状态端点'")
                .isNotEqualTo(200);

        JsonNode interaction = Envelope.data(get("/search/IdSearch?contentId=" + video, me.token)).path("interaction");
        assertThat(interaction.path("isWatchLater").isMissingNode())
                .as("★ 详情互动态不得含 isWatchLater（加一个键就破了'除清单外看不到'）").isTrue();
        assertThat(interaction.propertyNames())
                .as("互动态键集是四维（T40 起加入 coin；不多不少）")
                .containsExactlyInAnyOrder("isLiked", "isFavorited", "isFollowed", "coin");
        assertThat(interaction.path("coin").asInt())
                .as("详情（视频）的 coin 是数量：本用例没人投过 ⇒ 0（不是 null —— null 只用于图文/列表）")
                .isZero();
    }

    // ========================================================================
    // HTTP（收藏侧读断言 + 稍后再看写）
    // ========================================================================

    private ResponseEntity<String> add(String token, long contentId) {
        // `T58` 契约变更：contentId 进路径（PUT /api/v1/watch-later/{id}，接口契约.md §2.3/§2.4）。
        return put("/api/v1/watch-later/" + contentId, null, token);
    }

    private JsonNode status(String token, long contentId) {
        return Envelope.data(get("/api/v1/contents/" + contentId + "/favorite", token));
    }

    private long count(String token, long contentId) {
        return Envelope.data(get("/api/v1/contents/" + contentId + "/favorite-count", token)).asLong();
    }

    private ResponseEntity<String> postForm(String path, String key, String value, String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(key, value);
        return send(spec -> {
            RestClient.RequestBodySpec request = spec.uri(path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED);
            if (token != null) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return request.body(form);
        });
    }

    // ========================================================================
    // 夹具与独立 oracle
    // ========================================================================

    private TestUser register(String phone, String username) {
        String token = registerAndGetToken(phone, username, PASSWORD);
        return new TestUser(userIdOf(phone), token);
    }

    private record TestUser(long id, String token) {
    }

    private long oracleFavoriteItemRows(long userId, long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_item WHERE user_id = ? AND content_id = ?",
                Long.class, userId, contentId);
        return n == null ? 0L : n;
    }

    private long oracleWatchLaterRows(long userId, long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_later WHERE user_id = ? AND content_id = ?",
                Long.class, userId, contentId);
        return n == null ? 0L : n;
    }

    private long oracleFavoriteCountColumn(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT favorite_count FROM content WHERE id = ?", Long.class, contentId);
        return n == null ? 0L : n;
    }

    private long insertVideoContent(long authorId, String title) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description) VALUES (?, 1, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setString(2, title);
            ps.setString(3, "T17 隔离测试描述");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        long contentId = key.longValue();
        jdbcTemplate.update("INSERT INTO content_media (content_id, url, type, sort) VALUES (?, ?, 1, 1)",
                contentId, "/upload/video/" + contentId + ".mp4");
        jdbcTemplate.update("INSERT INTO content_media (content_id, url, type, sort) VALUES (?, ?, 3, 0)",
                contentId, "/upload/cover/" + contentId + ".png");
        return contentId;
    }
}
