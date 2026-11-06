package io.github.yjhhhaaa06.videoweb.favorite;

import io.github.yjhhhaaa06.videoweb.common.config.UploadProperties;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收藏夹封面**写路径**契约（{@code R-33} / {@code T110}）。
 *
 * <h2>钉住什么</h2>
 * <ul>
 *   <li><b>上传 = 落盘 + 写 cover_ref + 读侧生效</b>：URL 形状、磁盘字节、DB 值三者一致；</li>
 *   <li>★ <b>替换删旧</b>：第二次上传后旧文件必须不在盘上（只断 DB 新值会漏掉孤儿）；</li>
 *   <li>★ <b>恢复自动</b>：{@code DELETE .../cover} ⇒ 204、DB 写 NULL、旧自定义文件删除，
 *       读侧回到「最新一条收藏」的封面；★ 重复恢复仍 204（幂等）；</li>
 *   <li>★ <b>删夹删文件</b>：自定义封面所在夹被删除后，文件也消失（AfterCommit 成功路径）；</li>
 *   <li><b>作用域</b>：另一用户的夹与文件不受影响；</li>
 *   <li><b>拒绝路径零副作用</b>：401 / 403 / 404 / 空文件 / 坏后缀 / 非图片 Content-Type / &gt;2MB
 *       都不落盘、不改库。</li>
 * </ul>
 *
 * <h2>独立 oracle</h2>
 * 文件存在性直查测试媒体根 {@code target/test-media/favorite-cover}；DB 值直查
 * {@code favorite_folder.cover_ref}；自动封面的期望 URL 由夹具里 content_media 的 cover 行写死。
 * 反向注入（去掉归属校验 / 去掉旧文件清理 / 去掉大小校验 / 恢复不写 NULL）都应变红。
 */
class FavoriteFolderCoverWriteTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    @Autowired
    private UploadProperties uploadProperties;

    @BeforeEach
    void 清空本域相关表与封面目录() throws IOException {
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content_media");
        jdbcTemplate.update("DELETE FROM content");
        cleanDir("favorite-cover");
    }

    // ==================== 上传 ====================

    @Test
    @DisplayName("★上传：200 无 data；URL 形状 / 磁盘字节 / DB cover_ref 三者一致；读侧生效")
    void 上传落盘并写库() throws IOException {
        TestUser me = register("13800003101", "封面上传甲");
        long folder = insertFolder(me.id, "上传夹", false, false);
        byte[] bytes = jpegBytes();

        ResponseEntity<String> resp = uploadCover(folder, bytes, "cover.jpg", "image/jpeg", me.token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        assertThat(Envelope.code(resp)).isEqualTo("OK");
        assertThat(resp.getBody()).as("R-33 上传成功不返回 data（与改名 / 私密同口径）").doesNotContain("\"data\"");

        String url = coverRefOf(folder);
        assertThat(url).startsWith("/upload/favorite-cover/").endsWith(".jpg");
        Path disk = diskPathOf(url);
        assertThat(disk).exists();
        assertThat(Files.size(disk)).isEqualTo(bytes.length);

        JsonNode item = folderOf(listFolders(me.token), folder);
        assertThat(item.path("coverUrl").asString()).isEqualTo(url);
        assertHasCustomCover(item, true);
    }

    @Test
    @DisplayName("★替换：旧文件被删、新文件生效（只看 DB 新值会漏掉孤儿）")
    void 替换自定义删旧文件() throws IOException {
        TestUser me = register("13800003102", "封面上传乙");
        long folder = insertFolder(me.id, "替换夹", false, false);

        String first = coverRefOfAfterUpload(folder, jpegBytes(), "one.jpg", "image/jpeg", me.token);
        Path firstDisk = diskPathOf(first);
        assertThat(firstDisk).exists();

        String second = coverRefOfAfterUpload(folder, pngBytes(), "two.png", "image/png", me.token);
        assertThat(second).startsWith("/upload/favorite-cover/").endsWith(".png").isNotEqualTo(first);

        assertThat(firstDisk).as("旧自定义文件必须被删（否则每次换封面都留一份孤儿）").doesNotExist();
        assertThat(diskPathOf(second)).exists();
        assertThat(coverRefOf(folder)).isEqualTo(second);
    }

    @Test
    @DisplayName("★恢复自动：204 + DB 写 NULL + 旧文件删除；读侧回到最新一条收藏的封面")
    void 恢复自动删旧文件并回到自动() throws IOException {
        TestUser me = register("13800003103", "封面上传丙");
        long folder = insertFolder(me.id, "恢复夹", false, false);

        String custom = coverRefOfAfterUpload(folder, jpegBytes(), "custom.jpg", "image/jpeg", me.token);
        Path customDisk = diskPathOf(custom);
        assertThat(customDisk).exists();

        // 自动封面的期望值由夹具写死：最新一条收藏的 content_media cover 行。
        long video = insertVideo(me.id, "/upload/cover/auto.png");
        insertItem(folder, me.id, video);

        ResponseEntity<String> resp = delete("/api/v1/favorite-folders/" + folder + "/cover", me.token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(204);
        assertThat(coverRefOf(folder)).as("DB 写 NULL = 恢复自动").isNull();
        assertThat(customDisk).as("旧自定义文件必须删掉").doesNotExist();

        JsonNode item = folderOf(listFolders(me.token), folder);
        assertThat(item.get("coverUrl").asString())
                .as("恢复后立刻回到自动封面，不需要第二套刷新逻辑")
                .isEqualTo("/upload/cover/auto.png");
        assertHasCustomCover(item, false);
    }

    @Test
    @DisplayName("★恢复幂等：本来没有自定义封面 ⇒ 仍 204，且不误删别的文件")
    void 恢复幂等且不误删() throws IOException {
        TestUser me = register("13800003104", "封面上传丁");
        long folder = insertFolder(me.id, "无自定义夹", false, false);
        long otherFolder = insertFolder(me.id, "有自定义夹", false, false);
        String otherCover = coverRefOfAfterUpload(otherFolder, jpegBytes(), "other.jpg", "image/jpeg", me.token);
        Path otherDisk = diskPathOf(otherCover);
        assertThat(otherDisk).exists();

        ResponseEntity<String> first = delete("/api/v1/favorite-folders/" + folder + "/cover", me.token);
        ResponseEntity<String> second = delete("/api/v1/favorite-folders/" + folder + "/cover", me.token);
        assertThat(first.getStatusCode().value()).isEqualTo(204);
        assertThat(second.getStatusCode().value()).as("重复恢复也必须 204（幂等）").isEqualTo(204);
        assertThat(coverRefOf(folder)).isNull();
        assertThat(otherDisk).as("另一个夹的封面文件不能被误删").exists();
    }

    @Test
    @DisplayName("★删夹：自定义封面文件在提交后一起删除（AfterCommit）")
    void 删夹删自定义封面文件() throws IOException {
        TestUser me = register("13800003105", "封面上传戊");
        long folder = insertFolder(me.id, "待删夹", false, false);
        long otherFolder = insertFolder(me.id, "另一个夹", false, false);
        String url = coverRefOfAfterUpload(folder, jpegBytes(), "delete-me.jpg", "image/jpeg", me.token);
        String otherUrl = coverRefOfAfterUpload(otherFolder, jpegBytes(), "keep-me.jpg", "image/jpeg", me.token);
        Path disk = diskPathOf(url);
        Path otherDisk = diskPathOf(otherUrl);
        assertThat(disk).exists();
        assertThat(otherDisk).exists();

        ResponseEntity<String> resp = delete("/api/v1/favorite-folders/" + folder, me.token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(204);
        assertThat(disk).as("删夹后封面文件必须消失（否则是孤儿）").doesNotExist();
        assertThat(otherDisk).as("★ 干扰行：另一个夹的封面不能被误删").exists();
        assertThat(folderExists(folder)).isFalse();
        assertThat(folderExists(otherFolder)).as("另一个夹必须原样保留").isTrue();
    }

    // ==================== 默认夹 / 私密夹 ====================

    @Test
    @DisplayName("默认收藏夹与私密收藏夹都允许自定义封面")
    void 默认夹与私密夹都可设() throws IOException {
        TestUser me = register("13800003106", "封面上传己");
        long defaultFolder = insertFolder(me.id, "默认收藏夹", true, false);
        long privateFolder = insertFolder(me.id, "私密夹", false, true);

        String defaultCover = coverRefOfAfterUpload(defaultFolder, jpegBytes(), "d.jpg", "image/jpeg", me.token);
        String privateCover = coverRefOfAfterUpload(privateFolder, jpegBytes(), "p.jpg", "image/jpeg", me.token);
        assertThat(defaultCover).startsWith("/upload/favorite-cover/");
        assertThat(privateCover).startsWith("/upload/favorite-cover/");

        JsonNode defaultItem = folderOf(listFolders(me.token), defaultFolder);
        assertHasCustomCover(defaultItem, true);
        JsonNode privateItem = folderOf(listFolders(me.token), privateFolder);
        assertHasCustomCover(privateItem, true);
    }

    // ==================== 拒绝路径 ====================

    @Test
    @DisplayName("未登录 ⇒ 401；他人的夹 ⇒ 403；不存在的夹 ⇒ 404；三者都不落盘 / 不改库")
    void 拒绝路径零副作用() throws IOException {
        TestUser me = register("13800003107", "封面上传庚");
        TestUser other = register("13800003108", "封面上传辛");
        long mine = insertFolder(me.id, "我的夹", false, false);
        long others = insertFolder(other.id, "别人的夹", false, false);
        long before = coverFileCount();

        assertThat(uploadCover(mine, jpegBytes(), "a.jpg", "image/jpeg", null).getStatusCode().value())
                .as("未登录").isEqualTo(401);
        assertThat(uploadCover(others, jpegBytes(), "a.jpg", "image/jpeg", me.token).getStatusCode().value())
                .as("不是我的夹").isEqualTo(403);
        assertThat(uploadCover(999999L, jpegBytes(), "a.jpg", "image/jpeg", me.token).getStatusCode().value())
                .as("夹不存在").isEqualTo(404);
        assertThat(delete("/api/v1/favorite-folders/" + others + "/cover", me.token).getStatusCode().value())
                .as("恢复也要校验归属").isEqualTo(403);

        assertThat(coverFileCount()).as("被拒请求不得落盘").isEqualTo(before);
        assertThat(coverRefOf(mine)).isNull();
        assertThat(coverRefOf(others)).isNull();
    }

    @Test
    @DisplayName("空文件 / 坏后缀 / 非图片 Content-Type / >2MB ⇒ 400，且不落盘、不改库")
    void 文件校验400不落盘() throws IOException {
        TestUser me = register("13800003109", "封面上传壬");
        long folder = insertFolder(me.id, "校验夹", false, false);
        long before = coverFileCount();

        ResponseEntity<String> empty = uploadCover(folder, new byte[0], "a.jpg", "image/jpeg", me.token);
        assertThat(empty.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(empty)).isEqualTo("请选择要上传的文件");

        ResponseEntity<String> badSuffix = uploadCover(folder, jpegBytes(), "a.exe", "image/jpeg", me.token);
        assertThat(badSuffix.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(badSuffix)).isEqualTo("文件类型不支持");

        ResponseEntity<String> fakeImage = uploadCover(folder, jpegBytes(), "a.jpg", "text/plain", me.token);
        assertThat(fakeImage.getStatusCode().value()).as("只判后缀会落成打不开的图").isEqualTo(400);
        assertThat(Envelope.msg(fakeImage)).isEqualTo("文件类型不支持");

        ResponseEntity<String> tooBig = uploadCover(folder, new byte[2 * 1024 * 1024 + 1],
                "big.jpg", "image/jpeg", me.token);
        assertThat(tooBig.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(tooBig)).isEqualTo("封面图片不能超过2M");

        assertThat(coverFileCount()).isEqualTo(before);
        assertThat(coverRefOf(folder)).isNull();
    }

    // ==================== HTTP / 夹具 ====================

    private ResponseEntity<String> uploadCover(long folderId, byte[] content, String fileName,
                                               String contentType, String token) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(contentType));
        form.add("file", new HttpEntity<>(resource(content, fileName), headers));
        return putMultipart("/api/v1/favorite-folders/" + folderId + "/cover", form, token);
    }

    private String coverRefOfAfterUpload(long folderId, byte[] bytes, String fileName,
                                         String contentType, String token) {
        ResponseEntity<String> resp = uploadCover(folderId, bytes, fileName, contentType, token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        String url = coverRefOf(folderId);
        assertThat(url).as("上传成功后 cover_ref 必须已写").isNotNull();
        return url;
    }

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

    private String coverRefOf(long folderId) {
        return jdbcTemplate.queryForObject(
                "SELECT cover_ref FROM favorite_folder WHERE id = ?", String.class, folderId);
    }

    private boolean folderExists(long folderId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_folder WHERE id = ?", Long.class, folderId);
        return n != null && n > 0;
    }

    private Path diskPathOf(String url) {
        assertThat(url).startsWith("/upload/");
        Path path = Path.of(uploadProperties.root());
        for (String segment : url.substring("/upload/".length()).split("/")) {
            path = path.resolve(segment);
        }
        return path;
    }

    private long coverFileCount() throws IOException {
        Path dir = Path.of(uploadProperties.root(), "favorite-cover");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    private void cleanDir(String subdir) throws IOException {
        Path dir = Path.of(uploadProperties.root(), subdir);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
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

    private void insertItem(long folderId, long userId, long contentId) {
        jdbcTemplate.update("INSERT INTO favorite_item (folder_id, user_id, content_id) VALUES (?, ?, ?)",
                folderId, userId, contentId);
    }

    private long insertVideo(long authorId, String coverUrl) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description) VALUES (?, 1, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setString(2, "R-33 视频 " + authorId);
            ps.setString(3, "R-33 描述");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        long contentId = key.longValue();
        jdbcTemplate.update("INSERT INTO content_media (content_id, url, type, sort) VALUES (?, ?, 1, 1)",
                contentId, "/upload/video/" + contentId + ".mp4");
        jdbcTemplate.update("INSERT INTO content_media (content_id, url, type, sort) VALUES (?, ?, 3, 1)",
                contentId, coverUrl);
        return contentId;
    }

    private static Resource resource(byte[] content, String fileName) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    /** 服务端不解析图片内容，只校验 Content-Type + 后缀；字节只需非空。 */
    private static byte[] jpegBytes() {
        return new byte[]{0x01, 0x02, 0x03};
    }

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    }

    /** 键必须存在、值必须是指定布尔（`path().asBoolean()` 对缺键也返回 false，单用会假绿）。 */
    private static void assertHasCustomCover(JsonNode item, boolean expected) {
        assertThat(item.has("hasCustomCover")).as("hasCustomCover 键必须显式存在").isTrue();
        assertThat(item.path("hasCustomCover").asBoolean()).isEqualTo(expected);
    }
}
