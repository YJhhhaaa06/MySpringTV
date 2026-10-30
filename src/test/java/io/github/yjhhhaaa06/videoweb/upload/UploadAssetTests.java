package io.github.yjhhhaaa06.videoweb.upload;

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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单文件上传端点（{@code POST /api/v1/uploads/{type}}）的契约测试。
 *
 * <h2>★ 本类钉住的核心不变量：**只落盘、不绑定**</h2>
 * 这是"上传与提交分离"的承重点：文件上传成功后，{@code content_media} / {@code content}
 * **必须一行都不多**。若端点顺手写了媒体行，编辑中的媒体就会立刻对观众可见，
 * "保存前看不到改动"这条产品语义当场消失。
 *
 * <h2>独立 oracle</h2>
 * 文件的字节数与 URL 目录一律直查磁盘 / 直接比对形状，不信任接口回显；
 * 视频时长用**手搓 mp4**（{@code timescale=1000}，{@code duration=秒数×1000}）构造，
 * 期望值由用例自己定（6 秒），不从被测解析器读。
 *
 * <h2>反向验证（注入 ⇒ 必红）</h2>
 * ① 端点里加一行 {@code contentMediaDao.addMedia} ⇒ "不绑定"用例红；
 * ② 视频分支不解析时长 ⇒ 时长用例红；
 * ③ 把 {@code fromDir} 校验删掉 ⇒ 非法 type 用例红（会把任意目录名当合法类型）。
 */
class UploadAssetTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    @Autowired
    private UploadProperties uploadProperties;

    @BeforeEach
    void resetContentState() {
        jdbcTemplate.update("DELETE FROM content_media");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ==================== 1. 成功路径 ====================

    @Test
    @DisplayName("★上传视频：返回 url + 时长（6 秒），文件真的落在 video 目录，且不写任何内容/媒体行")
    void 上传视频_只落盘不绑定() throws IOException {
        String token = registerAndGetToken("13800000301", "asset-video", PASSWORD);
        byte[] video = mp4Seconds(6);

        ResponseEntity<String> resp = upload("video", resource(video, "clip.mp4"), "video/mp4", token);

        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        String url = Envelope.str(resp, "url");
        assertThat(url).startsWith("/upload/video/").endsWith(".mp4");
        assertThat(Envelope.data(resp).path("durationSeconds").asInt())
                .as("手搓 6 秒 ⇒ 期望值 6（独立 oracle，不是读解析器）")
                .isEqualTo(6);

        // 磁盘 oracle：文件存在且字节数 == 源字节数
        Path disk = diskPathOf(url);
        assertThat(disk).exists();
        assertThat(Files.size(disk)).isEqualTo(video.length);

        // ★ 绑定 oracle：content / content_media 一行都不多
        assertThat(countRows("content_media")).as("上传端点不得写 content_media").isZero();
        assertThat(countRows("content")).as("上传端点不得建内容行").isZero();
    }

    @Test
    @DisplayName("上传图片 / 封面：落在各自目录，durationSeconds 为 null（不是 0）")
    void 上传图片与封面_时长为null() {
        String token = registerAndGetToken("13800000302", "asset-image", PASSWORD);

        ResponseEntity<String> image = upload("image", resource(pngBytes(), "a.png"), "image/png", token);
        assertThat(image.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.str(image, "url")).startsWith("/upload/image/").endsWith(".png");
        assertThat(Envelope.data(image).path("durationSeconds").isNull()).isTrue();

        ResponseEntity<String> cover = upload("cover", resource(pngBytes(), "c.png"), "image/png", token);
        assertThat(cover.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.str(cover, "url")).startsWith("/upload/cover/").endsWith(".png");
        assertThat(Envelope.data(cover).path("durationSeconds").isNull()).isTrue();

        assertThat(countRows("content_media")).isZero();
    }

    // ==================== 2. 参数与鉴权 ====================

    @Test
    @DisplayName("非法 type（含大写 / 未知目录）⇒ 400，且磁盘不新增任何文件")
    void 非法类型_400且不落盘() throws IOException {
        String token = registerAndGetToken("13800000303", "asset-type", PASSWORD);
        long before = mediaFileCount();

        for (String type : new String[]{"VIDEO", "avatar"}) {
            ResponseEntity<String> resp = upload(type, resource(pngBytes(), "a.png"), "image/png", token);
            assertThat(resp.getStatusCode().value()).as(type).isEqualTo(400);
            assertThat(Envelope.msg(resp)).isEqualTo("type 不合法，应为 video/image/cover");
        }

        assertThat(mediaFileCount()).as("非法 type 不得落盘").isEqualTo(before);
    }

    @Test
    @DisplayName("空文件 ⇒ 400『请选择要上传的文件』；坏后缀 ⇒ 400『文件类型不支持』，两者都不落盘")
    void 空文件与坏后缀_400且不落盘() throws IOException {
        String token = registerAndGetToken("13800000304", "asset-empty", PASSWORD);
        long before = mediaFileCount();

        ResponseEntity<String> empty = upload("image", resource(new byte[0], "a.png"), "image/png", token);
        assertThat(empty.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(empty)).isEqualTo("请选择要上传的文件");

        ResponseEntity<String> badSuffix = upload("video", resource(mp4Seconds(1), "clip.exe"), "video/mp4", token);
        assertThat(badSuffix.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(badSuffix)).isEqualTo("文件类型不支持");

        assertThat(mediaFileCount()).as("失败路径不得落盘").isEqualTo(before);
    }

    @Test
    @DisplayName("未登录 ⇒ 401（类级 @RequiresLogin 覆盖新端点）")
    void 未登录_401() {
        ResponseEntity<String> resp = upload("image", resource(pngBytes(), "a.png"), "image/png", null);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    // ==================== 夹具与 oracle ====================

    private ResponseEntity<String> upload(String type, Resource file, String contentType, String token) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(contentType));
        form.add("file", new HttpEntity<>(file, headers));
        return postMultipart("/api/v1/uploads/" + type, form, token, null);
    }

    private long countRows(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    /** 测试媒体根下的全部普通文件数（"失败不落盘"的独立 oracle）。 */
    private long mediaFileCount() throws IOException {
        Path root = Path.of(uploadProperties.root());
        if (!Files.exists(root)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    private Path diskPathOf(String url) {
        assertThat(url).startsWith("/upload/");
        Path path = Path.of(uploadProperties.root());
        for (String segment : url.substring("/upload/".length()).split("/")) {
            path = path.resolve(segment);
        }
        return path;
    }

    /**
     * 手搓一个只含 {@code ftyp + mdat + moov(mvhd)} 的 mp4（{@code moov} 在尾部，非 faststart）。
     * 与 {@code UploadDurationTests} 的同名夹具同形——故意各留一份：用例的期望值必须来自
     * "用例自己造的字节"，而不是被测实现。
     */
    private static byte[] mp4Seconds(int seconds) {
        ByteBuffer mvhd = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        mvhd.put((byte) 0).put(new byte[3]);
        mvhd.putInt(0).putInt(0);
        mvhd.putInt(1000);
        mvhd.putInt(seconds * 1000);

        byte[] moov = box("moov", box("mvhd", mvhd.array()));
        byte[] ftyp = box("ftyp", "isomiso2avc1mp41".getBytes(StandardCharsets.ISO_8859_1));
        byte[] mdat = box("mdat", new byte[1024]);
        return concat(ftyp, mdat, moov);
    }

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    }

    private static Resource resource(byte[] content, String fileName) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private static byte[] box(String type, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(8 + payload.length);
        buffer.put(type.getBytes(StandardCharsets.ISO_8859_1));
        buffer.put(payload);
        return buffer.array();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
