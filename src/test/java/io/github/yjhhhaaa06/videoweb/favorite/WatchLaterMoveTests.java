package io.github.yjhhhaaa06.videoweb.favorite;

import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收藏三期 <b>T17</b>：稍后再看"移入收藏夹"（{@code POST /watchlater/move}）的契约测试。
 *
 * <h2>覆盖什么</h2>
 * <ul>
 *   <li>★ <b>成功</b>：同事务内"加入目标夹 + 从池中移除"—— oracle 断 {@code favorite_item} 有行、
 *       {@code watch_later} 无行、且 {@code content.favorite_count} 被维护成 1（T12 不变量）；</li>
 *   <li>★ <b>缺省 folderId = 默认夹（懒建）</b>（决策 D4）；</li>
 *   <li>★ <b>目标夹已满 ⇒ 400</b>（复用 {@code FavoriteService.addItem} 的容量闸，T16）；</li>
 *   <li>★ <b>内容不存在 / 已软删 ⇒ 404</b>（R-07「失效不可移入」）；<b>非视频 ⇒ 400</b>；</li>
 *   <li>★ <b>目标夹已有同内容 ⇒ 409 且稍后再看条目保留</b>（事务回滚 ⇒ 不是"移出后丢失"）；</li>
 *   <li>目标夹不存在 404 / 非我 403（口径同 {@code /favorite/add}）。</li>
 * </ul>
 *
 * <h2>★ 为什么把 custom-folder-max-items 覆盖成 3（夹具策略，不是放松断言）</h2>
 * "目标夹已满 ⇒ 400"要一个满夹。用真实上限（500）得造 500 个内容 + 500 条收藏记录 ——
 * 纯为验证"容量闸是否被 move 这条新入口继承"，代价与判别力不成比例。故本类把**自建夹上限**覆盖成
 * <b>3</b>（确定、廉价），不影响 {@code /favorite/add} 已由 T16 覆盖的真实上限用例。
 * 期望值仍是"满夹（=3 条）再加 ⇒ 400"，与真实上限口径**同构**（只是数字变小）。
 *
 * <h2>独立性</h2>
 * 三处关键终态都用**独立 oracle** 直查 DB（{@code favorite_item} 行、{@code watch_later} 行、
 * {@code content.favorite_count} 列），不信接口回显。
 */
@TestPropertySource(properties = "video.favorite.custom-folder-max-items=3")
class WatchLaterMoveTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";
    private static final int SMALL_FOLDER_LIMIT = 3;

    @BeforeEach
    void 清空本域相关表() {
        jdbcTemplate.update("DELETE FROM watch_later");
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content_media");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ========================================================================
    // 1. 成功
    // ========================================================================

    @Test
    @DisplayName("★move 成功：内容进目标夹 + 出稍后再看（同事务）；favorite_count 维护为 1")
    void move成功() {
        TestUser me = register("13800007001", "移动用户");
        long video = insertVideoContent(me.id, "要移动的视频");
        long folder = insertFolder(me.id, "目标夹");
        add(me.token, video);
        assertThat(oracleWatchLaterRows(me.id, video)).isEqualTo(1L);

        ResponseEntity<String> resp = move(me.token, video, folder);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        assertThat(oracleFavoriteItemRows(folder, me.id, video))
                .as("★ 内容进了目标夹（oracle 直查 favorite_item）").isEqualTo(1L);
        assertThat(oracleWatchLaterRows(me.id, video))
                .as("★ 内容出了稍后再看（同事务的另一半）").isZero();
        assertThat(oracleFavoriteCountColumn(video))
                .as("★ 复用 addItem ⇒ favorite_count 被重算维护（T12 不变量）").isEqualTo(1L);
    }

    @Test
    @DisplayName("★move 缺省 folderId ⇒ 用（懒建的）默认夹（决策 D4，同 /favorite/add）")
    void move缺省进默认夹() {
        TestUser me = register("13800007002", "默认夹用户");
        long video = insertVideoContent(me.id, "默认夹视频");
        add(me.token, video);
        assertThat(oracleDefaultFolderCount(me.id)).as("移动前：还没有默认夹（懒建）").isZero();

        assertThat(move(me.token, video, null).getStatusCode().value()).isEqualTo(200);

        assertThat(oracleDefaultFolderCount(me.id)).as("★ 缺省 folderId ⇒ 就地懒建默认夹").isEqualTo(1L);
        assertThat(oracleFavoriteItemInDefaultFolder(me.id, video)).as("内容落在默认夹里").isEqualTo(1L);
        assertThat(oracleWatchLaterRows(me.id, video)).as("并出了稍后再看").isZero();
    }

    // ========================================================================
    // 2. 失败面
    // ========================================================================

    @Test
    @DisplayName("★目标夹已满 ⇒ 400（复用 addItem 容量闸）；稍后再看条目**保留**")
    void move目标夹已满400() {
        TestUser me = register("13800007003", "满夹用户");
        long folder = insertFolder(me.id, "会满的夹");
        for (int i = 0; i < SMALL_FOLDER_LIMIT; i++) {
            insertFavoriteItem(folder, me.id, insertVideoContent(me.id, "填充" + i));
        }
        long video = insertVideoContent(me.id, "要移入的");
        add(me.token, video);

        assertThat(move(me.token, video, folder).getStatusCode().value())
                .as("★ 目标夹已满（3 条）⇒ 400").isEqualTo(400);
        assertThat(oracleFavoriteItemRows(folder, me.id, video)).as("未写入目标夹").isZero();
        assertThat(oracleWatchLaterRows(me.id, video))
                .as("★ 被拒 ⇒ 稍后再看条目保留（不丢）").isEqualTo(1L);
    }

    @Test
    @DisplayName("★内容已软删 ⇒ 404（R-07 失效不可移入）；稍后再看条目保留")
    void move内容已失效404() {
        TestUser me = register("13800007004", "失效用户");
        long video = insertVideoContent(me.id, "会失效的");
        long folder = insertFolder(me.id, "目标夹");
        add(me.token, video);
        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", video);

        assertThat(move(me.token, video, folder).getStatusCode().value())
                .as("失效内容不可移入 ⇒ 404").isEqualTo(404);
        assertThat(oracleWatchLaterRows(me.id, video)).as("条目保留（不丢）").isEqualTo(1L);
        assertThat(oracleFavoriteItemRows(folder, me.id, video)).isZero();
    }

    @Test
    @DisplayName("★非视频内容 move ⇒ 400（与 add 同一条'只能加视频'口径）")
    void move非视频400() {
        TestUser me = register("13800007005", "非视频用户");
        long text = insertTextContent(me.id, "图文帖子");

        assertThat(move(me.token, text, null).getStatusCode().value())
                .as("稍后再看只装视频（favorite-需求.md §六）").isEqualTo(400);
    }

    @Test
    @DisplayName("★目标夹已有同内容 ⇒ 409（撞 uk_folder_content）；稍后再看条目保留（不是'移出后丢失'）")
    void move目标夹已有同内容409() {
        TestUser me = register("13800007006", "重复用户");
        long video = insertVideoContent(me.id, "已在夹里的");
        long folder = insertFolder(me.id, "目标夹");
        // 先把内容真正收藏进目标夹（该夹 1 条 < 上限 3，容量闸会放行到插入那一步）
        assertThat(postForm("/favorite/add", me.token,
                "contentId", String.valueOf(video), "folderId", String.valueOf(folder))
                .getStatusCode().value()).isEqualTo(200);
        // 再加入稍后再看
        add(me.token, video);

        assertThat(move(me.token, video, folder).getStatusCode().value())
                .as("★ 目标夹已有同内容 ⇒ 409（同 /favorite/move 的幂等拒绝）").isEqualTo(409);
        assertThat(oracleWatchLaterRows(me.id, video))
                .as("★ 事务回滚 ⇒ 稍后再看条目**保留**（B 站口径：移动失败不回退源）").isEqualTo(1L);
        assertThat(oracleFavoriteItemRows(folder, me.id, video)).as("目标夹仍只有原来的 1 行").isEqualTo(1L);
    }

    @Test
    @DisplayName("归属：目标夹非我 ⇒ 403；不存在 ⇒ 404（口径同 /favorite/add）")
    void move目标夹归属错误线() {
        TestUser me = register("13800007007", "甲");
        TestUser other = register("13800007008", "乙");
        long video = insertVideoContent(me.id, "内容");
        long theirs = insertFolder(other.id, "乙的夹");
        add(me.token, video);

        assertThat(move(me.token, video, theirs).getStatusCode().value())
                .as("别人的夹 ⇒ 403").isEqualTo(403);
        assertThat(move(me.token, video, 999999999L).getStatusCode().value())
                .as("不存在的夹 ⇒ 404").isEqualTo(404);
        assertThat(oracleWatchLaterRows(me.id, video)).as("两次被拒 ⇒ 条目保留").isEqualTo(1L);
    }

    // ========================================================================
    // HTTP
    // ========================================================================

    private ResponseEntity<String> add(String token, long contentId) {
        return postForm("/watchlater/add", token, "contentId", String.valueOf(contentId));
    }

    private ResponseEntity<String> move(String token, long contentId, Long folderId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("contentId", String.valueOf(contentId));
        if (folderId != null) {
            form.add("folderId", String.valueOf(folderId));
        }
        return send(spec -> {
            RestClient.RequestBodySpec request = spec.uri("/watchlater/move")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED);
            if (token != null) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return request.body(form);
        });
    }

    private ResponseEntity<String> postForm(String path, String token, String... kv) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            form.add(kv[i], kv[i + 1]);
        }
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

    private long oracleWatchLaterRows(long userId, long contentId) {
        return count("SELECT COUNT(*) FROM watch_later WHERE user_id = ? AND content_id = ?", userId, contentId);
    }

    private long oracleFavoriteItemRows(long folderId, long userId, long contentId) {
        return count("SELECT COUNT(*) FROM favorite_item WHERE folder_id = ? AND user_id = ? AND content_id = ?",
                folderId, userId, contentId);
    }

    private long oracleFavoriteCountColumn(long contentId) {
        return count("SELECT favorite_count FROM content WHERE id = ?", contentId);
    }

    private long oracleDefaultFolderCount(long userId) {
        return count("SELECT COUNT(*) FROM favorite_folder WHERE user_id = ? AND is_default = 1", userId);
    }

    private long oracleFavoriteItemInDefaultFolder(long userId, long contentId) {
        return count("SELECT COUNT(*) FROM favorite_item i JOIN favorite_folder f ON f.id = i.folder_id "
                + "WHERE i.user_id = ? AND i.content_id = ? AND f.is_default = 1", userId, contentId);
    }

    private long count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    private long insertFolder(long userId, String name) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO favorite_folder (user_id, name) VALUES (?, ?)", new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, name);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("favorite_folder 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    /** 直插一条收藏记录（"把夹填满"夹具用；不参与被测写路径）。 */
    private void insertFavoriteItem(long folderId, long userId, long contentId) {
        jdbcTemplate.update(
                "INSERT INTO favorite_item (folder_id, user_id, content_id) VALUES (?, ?, ?)",
                folderId, userId, contentId);
    }

    private long insertVideoContent(long authorId, String title) {
        long contentId = insertContentRow(authorId, title, 1);
        jdbcTemplate.update("INSERT INTO content_media (content_id, url, type, sort) VALUES (?, ?, 1, 1)",
                contentId, "/upload/video/" + contentId + ".mp4");
        jdbcTemplate.update("INSERT INTO content_media (content_id, url, type, sort) VALUES (?, ?, 3, 0)",
                contentId, "/upload/cover/" + contentId + ".png");
        return contentId;
    }

    private long insertTextContent(long authorId, String title) {
        return insertContentRow(authorId, title, 2);
    }

    private long insertContentRow(long authorId, String title, int type) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description) VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setInt(2, type);
            ps.setString(3, title);
            ps.setString(4, "T17 移动测试描述");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }
}
