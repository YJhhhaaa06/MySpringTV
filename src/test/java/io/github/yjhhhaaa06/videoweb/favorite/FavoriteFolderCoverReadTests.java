package io.github.yjhhhaaa06.videoweb.favorite;

import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import tools.jackson.databind.JsonNode;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收藏夹封面**读路径**契约（{@code R-33} / {@code T109}）。
 *
 * <h2>钉住什么</h2>
 * <ul>
 *   <li><b>自定义优先</b>：{@code cover_ref} 非空 ⇒ {@code coverUrl} 就是它，
 *       {@code hasCustomCover = true}，不再看最新收藏；</li>
 *   <li><b>自动 = 最新一条收藏</b>（视频 / 图文都算）：按 {@code create_time DESC, id DESC}
 *       取第一条，取它的 {@code coverUrl}；</li>
 *   <li>★ <b>不回退</b>：最新一条失效 / 无封面 ⇒ {@code coverUrl = null}（前端占位），
 *       **不得**跳到上一条可用收藏；</li>
 *   <li>空夹 ⇒ {@code null}；另一夹 / 另一用户互不干扰；</li>
 *   <li>公开面只回带 {@code coverUrl}，不泄露 {@code coverRef} / {@code hasCustomCover}。</li>
 * </ul>
 *
 * <h2>独立 oracle</h2>
 * 自定义优先与排序的期望值由用例按业务口径写死（{@code cover_ref} 直查 DB；最新一条按夹具的
 * {@code create_time + id} 复算），不读实现中间量。反向注入（去掉自定义优先 / 去掉最新一条 /
 * 加回退 / 把公开面多吐字段）都应变红，见 {@code T109} 验收。
 */
class FavoriteFolderCoverReadTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    @BeforeEach
    void 清空本域相关表() {
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content_media");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ==================== 自定义优先 ====================

    @Test
    @DisplayName("★自定义封面优先：cover_ref 非空 ⇒ coverUrl=它、hasCustomCover=true，忽略最新收藏")
    void 自定义封面优先() {
        TestUser me = register("13800003001", "封面用户甲");
        long folder = insertFolder(me.id, "自定义夹", false, false);
        long video = insertVideo(me.id, "/upload/cover/video-1.png");
        insertItem(folder, me.id, video, "2026-11-06 10:00:00");
        String custom = "/upload/favorite-cover/custom-1.jpg";
        setCoverRef(folder, custom);

        JsonNode item = folderOf(listFolders(me.token), folder);
        assertThat(item.has("coverUrl")).as("R-33 起我的夹列表必须回带 coverUrl").isTrue();
        assertThat(item.path("coverUrl").asString()).as("自定义封面必须压过自动封面").isEqualTo(custom);
        assertHasCustomCover(item, true);
        assertThat(item.has("coverRef")).as("内部 ref 不得进 JSON").isFalse();
    }

    // ==================== 自动封面 ====================

    @Test
    @DisplayName("★自动=最新一条收藏：更新的图文封面压过更早的视频封面（不按类型过滤）")
    void 自动取最新一条收藏_图文也算() {
        TestUser me = register("13800003002", "封面用户乙");
        long folder = insertFolder(me.id, "自动夹", false, false);
        long video = insertVideo(me.id, "/upload/cover/video-old.png");
        long post = insertPost(me.id, "/upload/cover/post-new.png");
        insertItem(folder, me.id, video, "2026-11-06 10:00:00");
        insertItem(folder, me.id, post, "2026-11-06 10:01:00");

        JsonNode item = folderOf(listFolders(me.token), folder);
        assertThat(item.path("coverUrl").asString())
                .as("最新一条是图文也要用它自己的封面（R-33 已拍板：不按视频/图文过滤）")
                .isEqualTo("/upload/cover/post-new.png");
        assertHasCustomCover(item, false);
    }

    @Test
    @DisplayName("★最新一条失效 ⇒ coverUrl=null，不回退到上一条可用收藏")
    void 最新一条失效不向前回退() {
        TestUser me = register("13800003003", "封面用户丙");
        long folder = insertFolder(me.id, "失效最新夹", false, false);
        long olderValid = insertVideo(me.id, "/upload/cover/older-valid.png");
        long newestDeleted = insertVideo(me.id, "/upload/cover/newest-deleted.png");
        insertItem(folder, me.id, olderValid, "2026-11-06 10:00:00");
        insertItem(folder, me.id, newestDeleted, "2026-11-06 10:01:00");
        softDelete(newestDeleted);

        JsonNode item = folderOf(listFolders(me.token), folder);
        assertThat(item.has("coverUrl")).as("键必须显式存在").isTrue();
        assertThat(item.get("coverUrl").isNull())
                .as("最新一条被下架 ⇒ 视频不见了占位；若实现在 SQL/JAVA 里回退，这条会红")
                .isTrue();
        assertHasCustomCover(item, false);
    }

    @Test
    @DisplayName("最新一条无封面（纯文字图文）⇒ null 占位；空夹 ⇒ null 占位")
    void 最新无封面与空夹都是null() {
        TestUser me = register("13800003004", "封面用户丁");
        long textPostFolder = insertFolder(me.id, "纯文字图文夹", false, false);
        long textPost = insertPost(me.id, null);
        insertItem(textPostFolder, me.id, textPost, "2026-11-06 10:00:00");
        long emptyFolder = insertFolder(me.id, "空夹", false, false);

        JsonNode textPostItem = folderOf(listFolders(me.token), textPostFolder);
        assertThat(textPostItem.get("coverUrl").isNull())
                .as("图文能装出来但没有封面 ⇒ 也是占位（不编图）").isTrue();
        JsonNode emptyItem = folderOf(listFolders(me.token), emptyFolder);
        assertThat(emptyItem.get("coverUrl").isNull()).as("空夹 Cover 自动解析不出 ⇒ null").isTrue();
    }

    @Test
    @DisplayName("同秒收藏按 id 倒序取最新（create_time 是秒精度，必须有稳定 tie-breaker）")
    void 同秒收藏按id倒序() {
        TestUser me = register("13800003005", "封面用户戊");
        long folder = insertFolder(me.id, "同秒夹", false, false);
        long first = insertVideo(me.id, "/upload/cover/first.png");
        long second = insertVideo(me.id, "/upload/cover/second.png");
        insertItem(folder, me.id, first, "2026-11-06 10:00:00");
        insertItem(folder, me.id, second, "2026-11-06 10:00:00");

        JsonNode item = folderOf(listFolders(me.token), folder);
        assertThat(item.path("coverUrl").asString())
                .as("同秒时后插入（id 更大）的算最新，与夹内列表 ORDER BY 完全一致")
                .isEqualTo("/upload/cover/second.png");
    }

    // ==================== 作用域 / 公开面 ====================

    @Test
    @DisplayName("作用域：A 的封面不影响 B；同一用户两个夹各自取自己的最新一条")
    void 封面作用域不串夹() {
        TestUser alice = register("13800003006", "封面用户己");
        TestUser bob = register("13800003007", "封面用户庚");
        long aliceA = insertFolder(alice.id, "A 夹", false, false);
        long aliceB = insertFolder(alice.id, "B 夹", false, false);
        long bobFolder = insertFolder(bob.id, "B 的夹", false, false);

        long aliceVideoA = insertVideo(alice.id, "/upload/cover/alice-a.png");
        long aliceVideoB = insertVideo(alice.id, "/upload/cover/alice-b.png");
        long bobVideo = insertVideo(bob.id, "/upload/cover/bob.png");
        insertItem(aliceA, alice.id, aliceVideoA, "2026-11-06 10:00:00");
        insertItem(aliceB, alice.id, aliceVideoB, "2026-11-06 10:00:00");
        insertItem(bobFolder, bob.id, bobVideo, "2026-11-06 10:00:00");

        assertThat(folderOf(listFolders(alice.token), aliceA).path("coverUrl").asString())
                .isEqualTo("/upload/cover/alice-a.png");
        assertThat(folderOf(listFolders(alice.token), aliceB).path("coverUrl").asString())
                .isEqualTo("/upload/cover/alice-b.png");
        assertThat(folderOf(listFolders(bob.token), bobFolder).path("coverUrl").asString())
                .isEqualTo("/upload/cover/bob.png");
        assertThat(folderOf(listFolders(alice.token), aliceA).path("coverUrl").asString())
                .as("A 的解析不得串到 B 的夹").isNotEqualTo("/upload/cover/bob.png");
    }

    @Test
    @DisplayName("公开面自定义封面：coverUrl=custom；仍不吐 hasCustomCover / coverRef")
    void 公开面自定义封面只给URL() {
        TestUser me = register("13800003009", "封面用户壬");
        long publicFolder = insertFolder(me.id, "自定义公开夹", false, false);
        String custom = "/upload/favorite-cover/public-custom.jpg";
        setCoverRef(publicFolder, custom);

        ResponseEntity<String> resp = get("/api/v1/users/" + me.id + "/favorite-folders", null);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode item = Envelope.data(resp).get(0);
        assertThat(item.path("coverUrl").asString()).isEqualTo(custom);
        assertThat(item.has("hasCustomCover")).as("公开面不给'是不是自定义'").isFalse();
        assertThat(item.has("coverRef")).isFalse();
    }

    @Test
    @DisplayName("公开面：生效 coverUrl 可见；hasCustomCover / coverRef 不得出现；私密夹仍不返回")
    void 公开面只带生效封面() {
        TestUser me = register("13800003008", "封面用户辛");
        long publicFolder = insertFolder(me.id, "公开夹", false, false);
        long privateFolder = insertFolder(me.id, "私密夹", false, true);
        long video = insertVideo(me.id, "/upload/cover/public.png");
        insertItem(publicFolder, me.id, video, "2026-11-06 10:00:00");
        long privateVideo = insertVideo(me.id, "/upload/cover/private.png");
        insertItem(privateFolder, me.id, privateVideo, "2026-11-06 10:00:00");

        ResponseEntity<String> resp = get("/api/v1/users/" + me.id + "/favorite-folders", null);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode data = Envelope.data(resp);
        assertThat(data.size()).as("私密夹不出现").isEqualTo(1);
        JsonNode item = data.get(0);
        assertThat(item.path("name").asString()).isEqualTo("公开夹");
        assertThat(item.path("coverUrl").asString()).isEqualTo("/upload/cover/public.png");
        assertThat(item.has("hasCustomCover")).as("公开面不给'是不是自定义'").isFalse();
        assertThat(item.has("coverRef")).isFalse();
    }

    // ==================== HTTP / 夹具 ====================

    private TestUser register(String phone, String username) {
        String token = registerAndGetToken(phone, username, PASSWORD);
        return new TestUser(userIdOf(phone), token);
    }

    private record TestUser(long id, String token) {
    }

    private ResponseEntity<String> listFolders(String token) {
        return get("/api/v1/favorite-folders", token);
    }

    private static JsonNode folderOf(ResponseEntity<String> listResponse, long folderId) {
        JsonNode data = Envelope.data(listResponse);
        for (JsonNode node : data) {
            if (node.path("id").asLong() == folderId) {
                return node;
            }
        }
        throw new AssertionError("列表里应含夹 " + folderId + "，实际=" + data);
    }

    private long insertFolder(long userId, String name, boolean isDefault, boolean isPrivate) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO favorite_folder (user_id, name, is_default, is_private) VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, name);
            ps.setInt(3, isDefault ? 1 : 0);
            ps.setInt(4, isPrivate ? 1 : 0);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("favorite_folder 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    private void setCoverRef(long folderId, String coverRef) {
        jdbcTemplate.update("UPDATE favorite_folder SET cover_ref = ? WHERE id = ?", coverRef, folderId);
    }

    private void insertItem(long folderId, long userId, long contentId, String createTime) {
        jdbcTemplate.update(
                "INSERT INTO favorite_item (folder_id, user_id, content_id, create_time) VALUES (?, ?, ?, ?)",
                folderId, userId, contentId, Timestamp.valueOf(LocalDateTime.parse(createTime.replace(' ', 'T'))));
    }

    private long insertVideo(long authorId, String coverUrl) {
        long contentId = insertContent(authorId, 1);
        jdbcTemplate.update("INSERT INTO content_media (content_id, url, type, sort) VALUES (?, ?, 1, 1)",
                contentId, "/upload/video/" + contentId + ".mp4");
        jdbcTemplate.update("INSERT INTO content_media (content_id, url, type, sort) VALUES (?, ?, 3, 1)",
                contentId, coverUrl);
        return contentId;
    }

    private long insertPost(long authorId, String coverUrl) {
        long contentId = insertContent(authorId, 2);
        if (coverUrl != null) {
            jdbcTemplate.update("INSERT INTO content_media (content_id, url, type, sort) VALUES (?, ?, 3, 1)",
                    contentId, coverUrl);
        }
        return contentId;
    }

    private long insertContent(long authorId, int type) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description) VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setInt(2, type);
            ps.setString(3, "R-33 测试内容 " + authorId);
            ps.setString(4, "R-33 测试描述");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    private void softDelete(long contentId) {
        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", contentId);
    }

    /** 键必须存在、值必须是指定布尔（`path().asBoolean()` 对缺键也返回 false，单用会假绿）。 */
    private static void assertHasCustomCover(JsonNode item, boolean expected) {
        assertThat(item.has("hasCustomCover")).as("hasCustomCover 键必须显式存在").isTrue();
        assertThat(item.path("hasCustomCover").asBoolean()).isEqualTo(expected);
    }
}
