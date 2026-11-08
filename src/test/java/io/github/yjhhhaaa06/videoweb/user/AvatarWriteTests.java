package io.github.yjhhhaaa06.videoweb.user;

import io.github.yjhhhaaa06.videoweb.common.config.UploadProperties;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import io.github.yjhhhaaa06.videoweb.user.service.AvatarUrlResolver;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 头像写路径的契约测试（`R-27` / `T80`）。
 *
 * <h2>钉住什么</h2>
 * <ul>
 *   <li><b>未选过 ⇒ 按 userId 回落默认</b>：`GET /users/me` 的 `avatarUrl` 键必须**显式存在**
 *       且等于独立复算的 {@code /avatars/default-(userId%6+1).svg}；</li>
 *   <li><b>自定义上传 = 落盘 + 写 avatar_ref</b>：URL 形状、磁盘字节、DB 值三者一致；</li>
 *   <li>★ <b>替换删旧</b>：第二次上传后旧文件必须不在盘上（只断言 DB 新值会漏掉孤儿）；</li>
 *   <li>★ <b>切默认删旧</b>：从自定义切到 `default:3` 时旧自定义文件被删，DB 写 `default:3`；</li>
 *   <li><b>作用域</b>：另一用户的 `avatar_ref` 仍是 NULL、`/users/me` 仍是自己的回落 URL；</li>
 *   <li><b>拒绝路径零副作用</b>：401 / 空文件 / 坏后缀 / >2MB / 非法默认编号都不落盘、不改库。</li>
 * </ul>
 *
 * <h2>独立 oracle</h2>
 * 默认 URL 由用例自己按 `userId % 6 + 1` 复算（不调用生产 `AvatarRefs`）；文件存在性直查
 * {@code target/test-media/avatar}；DB 值直查 {@code users.avatar_ref}。
 *
 * <h2>反向验证（注入 ⇒ 必红）</h2>
 * ① 上传/切默认后不删旧文件 ⇒ "替换删旧 / 切默认删旧" 两条红；
 * ② `updateAvatarRef` 的 SQL 去掉 `where id=?` ⇒ "另一用户不受影响" 红；
 * ③ 默认回落公式改成 `userId % 6`（少 +1）⇒ "新用户回落" 红；
 * ④ 去掉大小校验 ⇒ ">2MB" 红。
 */
class AvatarWriteTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "Password123";

    @Autowired
    private UploadProperties uploadProperties;

    @Autowired
    private AvatarUrlResolver avatarUrlResolver;

    @BeforeEach
    void cleanAvatarDir() throws IOException {
        Path dir = Path.of(uploadProperties.root(), "avatar");
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

    // ==================== 读侧回落 ====================

    @Test
    @DisplayName("新用户：avatarUrl 键显式存在，且按 userId%6+1 回落默认（独立复算）")
    void 新用户按userId回落默认() {
        String token = registerAndGetToken("13800004101", "avatar-default", PASSWORD);
        long userId = userIdOf("13800004101");

        JsonNode data = Envelope.data(get("/api/v1/users/me", token));
        assertThat(data.path("avatarUrl").isTextual())
                .as("必须是显式字符串；缺键时 path().asString() 会返回空串，把'没做'读成'空头像'")
                .isTrue();
        assertThat(data.path("avatarUrl").asString())
                .as("未选过 ⇒ 按 userId % 6 + 1 回落（与前端 UserAvatar 历史分配一致）")
                .isEqualTo(fallbackUrl(userId));
        assertThat(avatarRefOf(userId)).as("新用户的 DB 选择仍是 NULL").isNull();
    }

    // ==================== 自定义上传 ====================

    @Test
    @DisplayName("★上传自定义：URL / 磁盘字节 / DB avatar_ref 三者一致")
    void 上传自定义落盘并写库() throws IOException {
        String token = registerAndGetToken("13800004102", "avatar-upload", PASSWORD);
        long userId = userIdOf("13800004102");
        byte[] bytes = jpegBytes();

        ResponseEntity<String> resp = uploadAvatar(bytes, "face.jpg", "image/jpeg", token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);

        String url = Envelope.str(resp, "avatarUrl");
        assertThat(url).startsWith("/upload/avatar/").endsWith(".jpg");
        assertThat(Envelope.data(resp).path("userId").asLong()).isEqualTo(userId);

        Path disk = diskPathOf(url);
        assertThat(disk).exists();
        assertThat(Files.size(disk)).isEqualTo(bytes.length);
        assertThat(avatarRefOf(userId)).isEqualTo(url);

        // 读侧也必须是同一个 URL（GET /users/me 与 GET /users/{id} 都走 AvatarRefs）
        assertThat(Envelope.data(get("/api/v1/users/me", token)).path("avatarUrl").asString()).isEqualTo(url);
        assertThat(Envelope.data(get("/api/v1/users/" + userId, null)).path("avatarUrl").asString())
                .as("他人主页/播放页侧栏也读同一个生效 URL")
                .isEqualTo(url);
    }

    @Test
    @DisplayName("R-37：写完头像后 JVM 缓存立即失效（先预热旧值，再上传 / 切默认）")
    void 写后失效Jvm缓存() {
        String token = registerAndGetToken("13800004107", "avatar-cache", PASSWORD);
        long userId = userIdOf("13800004107");

        assertThat(avatarUrlResolver.resolve(userId))
                .as("预热：未选过 ⇒ 按 userId 回落默认")
                .isEqualTo(fallbackUrl(userId));

        String uploaded = Envelope.str(uploadAvatar(jpegBytes(), "cache.jpg", "image/jpeg", token), "avatarUrl");
        assertThat(avatarUrlResolver.resolve(userId))
                .as("没有 invalidate 的话，这里会继续返回预热时的默认 URL（最长 5min）")
                .isEqualTo(uploaded);

        ResponseEntity<String> resp = put("/api/v1/users/me/avatar/default", Map.of("avatarId", 3), token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        assertThat(avatarUrlResolver.resolve(userId))
                .as("切默认也必须失效")
                .isEqualTo("/avatars/default-3.svg");
    }

    @Test
    @DisplayName("★替换自定义：旧文件被删，新文件生效（只看 DB 新值会漏掉孤儿）")
    void 替换自定义删旧文件() throws IOException {
        String token = registerAndGetToken("13800004103", "avatar-replace", PASSWORD);
        long userId = userIdOf("13800004103");

        String first = Envelope.str(uploadAvatar(jpegBytes(), "one.jpg", "image/jpeg", token), "avatarUrl");
        Path firstDisk = diskPathOf(first);
        assertThat(firstDisk).exists();

        String second = Envelope.str(uploadAvatar(pngBytes(), "two.png", "image/png", token), "avatarUrl");
        assertThat(second).startsWith("/upload/avatar/").endsWith(".png").isNotEqualTo(first);

        assertThat(firstDisk).as("旧自定义文件必须被删（否则每次换头像都留一份孤儿）").doesNotExist();
        assertThat(diskPathOf(second)).exists();
        assertThat(avatarRefOf(userId)).isEqualTo(second);
    }

    @Test
    @DisplayName("★切换默认：写 default:3，且删除旧的自定义文件")
    void 切换默认删旧自定义() throws IOException {
        String token = registerAndGetToken("13800004104", "avatar-choose", PASSWORD);
        long userId = userIdOf("13800004104");

        String custom = Envelope.str(uploadAvatar(jpegBytes(), "old.jpg", "image/jpeg", token), "avatarUrl");
        Path customDisk = diskPathOf(custom);
        assertThat(customDisk).exists();

        ResponseEntity<String> resp = put("/api/v1/users/me/avatar/default",
                Map.of("avatarId", 3), token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        assertThat(Envelope.str(resp, "avatarUrl")).isEqualTo("/avatars/default-3.svg");

        assertThat(avatarRefOf(userId)).as("DB 存的是选择，不是最终 URL").isEqualTo("default:3");
        assertThat(customDisk).as("从自定义切默认时，旧自定义文件必须删除").doesNotExist();

        assertThat(Envelope.data(get("/api/v1/users/me", token)).path("avatarUrl").asString())
                .isEqualTo("/avatars/default-3.svg");
    }

    @Test
    @DisplayName("作用域：A 换头像不影响 B（DB 对照 + 读侧对照）")
    void 换头像只影响自己() {
        String tokenA = registerAndGetToken("13800004105", "avatar-scope-a", PASSWORD);
        String tokenB = registerAndGetToken("13800004106", "avatar-scope-b", PASSWORD);
        long userIdA = userIdOf("13800004105");
        long userIdB = userIdOf("13800004106");

        String urlA = Envelope.str(uploadAvatar(jpegBytes(), "a.jpg", "image/jpeg", tokenA), "avatarUrl");

        assertThat(avatarRefOf(userIdA)).isEqualTo(urlA);
        assertThat(avatarRefOf(userIdB)).as("B 的列必须仍是 NULL（去掉 where id=? 时这里先红）").isNull();
        assertThat(Envelope.data(get("/api/v1/users/me", tokenB)).path("avatarUrl").asString())
                .isEqualTo(fallbackUrl(userIdB));
    }

    // ==================== 拒绝路径 ====================

    @Test
    @DisplayName("未登录 ⇒ 401，且不落盘")
    void 未登录401不落盘() throws IOException {
        long before = avatarFileCount();
        ResponseEntity<String> resp = uploadAvatar(jpegBytes(), "a.jpg", "image/jpeg", null);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(avatarFileCount()).as("401 不得落盘").isEqualTo(before);
    }

    @Test
    @DisplayName("空文件 / 坏后缀 ⇒ 400，且不落盘")
    void 空文件与坏后缀400不落盘() throws IOException {
        String token = registerAndGetToken("13800004107", "avatar-bad-file", PASSWORD);
        long before = avatarFileCount();

        ResponseEntity<String> empty = uploadAvatar(new byte[0], "a.jpg", "image/jpeg", token);
        assertThat(empty.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(empty)).isEqualTo("请选择要上传的文件");

        ResponseEntity<String> badSuffix = uploadAvatar(jpegBytes(), "a.exe", "image/jpeg", token);
        assertThat(badSuffix.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(badSuffix)).isEqualTo("文件类型不支持");

        // 后缀合法但 Content-Type 不是图片：只判后缀会落成一张打不开的"头像"
        ResponseEntity<String> fakeImage = uploadAvatar(jpegBytes(), "a.jpg", "text/plain", token);
        assertThat(fakeImage.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(fakeImage)).isEqualTo("文件类型不支持");

        assertThat(avatarFileCount()).isEqualTo(before);
    }

    @Test
    @DisplayName(">2MB ⇒ 400「头像图片不能超过2M」，且不落盘")
    void 超过2MB400不落盘() throws IOException {
        String token = registerAndGetToken("13800004108", "avatar-too-big", PASSWORD);
        long before = avatarFileCount();

        ResponseEntity<String> resp = uploadAvatar(new byte[2 * 1024 * 1024 + 1], "big.jpg", "image/jpeg", token);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(resp)).isEqualTo("头像图片不能超过2M");
        assertThat(avatarFileCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("默认编号非法（缺字段 / 0 / 7）⇒ 400，库不变")
    void 默认编号非法400() {
        String token = registerAndGetToken("13800004109", "avatar-bad-id", PASSWORD);
        long userId = userIdOf("13800004109");

        assertThat(put("/api/v1/users/me/avatar/default", Map.of(), token).getStatusCode().value())
                .as("缺字段由 @NotNull 拦下").isEqualTo(400);
        assertThat(put("/api/v1/users/me/avatar/default", Map.of("avatarId", 0), token).getStatusCode().value())
                .isEqualTo(400);
        assertThat(put("/api/v1/users/me/avatar/default", Map.of("avatarId", 7), token).getStatusCode().value())
                .isEqualTo(400);

        assertThat(avatarRefOf(userId)).as("被拒请求不得改库").isNull();
    }

    // ==================== 夹具与 oracle ====================

    private ResponseEntity<String> uploadAvatar(byte[] content, String fileName, String contentType, String token) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(contentType));
        form.add("file", new HttpEntity<>(resource(content, fileName), headers));
        return putMultipart("/api/v1/users/me/avatar", form, token);
    }

    private String avatarRefOf(long userId) {
        return jdbcTemplate.queryForObject("SELECT avatar_ref FROM users WHERE id = ?", String.class, userId);
    }

    private Path diskPathOf(String url) {
        assertThat(url).startsWith("/upload/");
        Path path = Path.of(uploadProperties.root());
        for (String segment : url.substring("/upload/".length()).split("/")) {
            path = path.resolve(segment);
        }
        return path;
    }

    private long avatarFileCount() throws IOException {
        Path dir = Path.of(uploadProperties.root(), "avatar");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    /** 独立复算：不得调用生产 `AvatarRefs`，期望值必须由用例自己写出来。 */
    private static String fallbackUrl(long userId) {
        return "/avatars/default-" + (userId % 6 + 1) + ".svg";
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
}
