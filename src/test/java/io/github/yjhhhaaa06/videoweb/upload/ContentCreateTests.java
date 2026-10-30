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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/v1/contents}（JSON 媒体清单创建）的契约测试。
 *
 * <h2>它钉住的三件事</h2>
 * <ol>
 *   <li><b>清单即目标状态</b>：提交的 URL 与顺序原样落成 {@code content_media} 行
 *       （视频/封面 sort=1，图片按数组顺序 1..n）；</li>
 *   <li><b>媒体校验在写库之前</b>：类型不匹配、文件不在盘上、URL 属于别的作品、清单内重复
 *       ⇒ 400 且**一行内容/媒体都不落**；</li>
 *   <li><b>旧 multipart 兼容面共享同一段业务逻辑</b>（本类不重复测，见 {@code UploadFlowTests}）。</li>
 * </ol>
 *
 * <h2>独立 oracle</h2>
 * 内容行 / 媒体行 / 文件字节一律直查 {@code jdbcTemplate} 与磁盘，不读接口回显；
 * 视频时长用**手搓 mp4**（5 秒）构造，期望值由用例自己定。
 */
class ContentCreateTests extends AbstractHttpIntegrationTest {

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
    @DisplayName("★创建视频：内容行 + 视频/封面两行落库（时长 5 秒）、文件在盘、详情可读")
    void 创建视频_落内容与媒体行() {
        String token = registerAndGetToken("13800000401", "create-video", PASSWORD);
        String videoUrl = upload(token, "video", mp4Seconds(5), "clip.mp4", "video/mp4");
        String coverUrl = upload(token, "cover", pngBytes(), "cover.png", "image/png");

        ResponseEntity<String> resp = post("/api/v1/contents",
                createBody("VIDEO", "新建视频", "视频简介", 1,
                        media(videoUrl, coverUrl, null)), token);

        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        long contentId = Envelope.num(resp, "contentId");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT user_id, type, title, description, category_id, is_deleted FROM content WHERE id = ?",
                contentId);
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(userIdOf("13800000401"));
        assertThat(row).containsEntry("type", 1)
                .containsEntry("title", "新建视频")
                .containsEntry("description", "视频简介")
                .containsEntry("category_id", 1)
                .containsEntry("is_deleted", 0);

        List<Map<String, Object>> media = mediaRows(contentId);
        assertThat(media).hasSize(2);
        assertThat(media.get(0)).containsEntry("url", videoUrl).containsEntry("type", 1)
                .containsEntry("sort", 1);
        assertThat(((Number) media.get(0).get("duration_seconds")).intValue()).isEqualTo(5);
        assertThat(media.get(1)).containsEntry("url", coverUrl).containsEntry("type", 3)
                .containsEntry("sort", 1);
        assertThat(media.get(1).get("duration_seconds")).isNull();

        assertThat(diskPath(videoUrl)).exists();
        assertThat(diskPath(coverUrl)).exists();

        // 读侧：详情把清单原样投影出来
        assertThat(Envelope.data(get("/api/v1/contents/" + contentId, null)).path("videoUrl").asString())
                .isEqualTo(videoUrl);
        assertThat(Envelope.data(get("/api/v1/contents/" + contentId, null)).path("durationSeconds").asInt())
                .isEqualTo(5);
    }

    @Test
    @DisplayName("★创建图文：封面可选、图片按数组顺序落 sort 1..n；空简介落 TV 占位 \"-\"")
    void 创建图文_图片顺序与空简介占位() {
        String token = registerAndGetToken("13800000402", "create-post", PASSWORD);
        String coverUrl = upload(token, "cover", pngBytes(), "cover.png", "image/png");
        String first = upload(token, "image", pngBytes(), "a.png", "image/png");
        String second = upload(token, "image", pngBytes(), "b.png", "image/png");

        // ★ 清单顺序与上传/插入顺序**相反**：若实现按 media id 排序而不是按清单下标，
        //   期望值（second 在前）会红——这是排序用例的判别力，不是随意的夹具顺序。
        ResponseEntity<String> resp = post("/api/v1/contents",
                createBody("POST", "新建图文", "", 2,
                        media(null, coverUrl, List.of(second, first))), token);

        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        long contentId = Envelope.num(resp, "contentId");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT description FROM content WHERE id = ?", String.class, contentId))
                .as("空简介仍落 \"-\"（发布口径，与编辑的空串不同）")
                .isEqualTo("-");

        assertThat(mediaRows(contentId)).hasSize(3);
        assertThat(mediaRowsOfType(contentId, 3))
                .as("封面只有一行").containsExactly(Map.of("url", coverUrl, "sort", 1));
        assertThat(mediaRowsOfType(contentId, 2))
                .as("图片按清单顺序写 sort 1..n（与上传顺序相反）")
                .containsExactly(
                        Map.of("url", second, "sort", 1),
                        Map.of("url", first, "sort", 2));

        assertThat(Envelope.data(get("/api/v1/contents/" + contentId, null)).path("imageUrls"))
                .extracting(node -> node.asString())
                .containsExactly(second, first);
    }

    // ==================== 2. 清单约束（写库之前拒绝） ====================

    @Test
    @DisplayName("视频清单：缺视频 / 缺封面 / 带图片 ⇒ 400，且零内容零媒体行")
    void 视频清单约束() {
        String token = registerAndGetToken("13800000403", "constraint-video", PASSWORD);
        String videoUrl = upload(token, "video", mp4Seconds(1), "clip.mp4", "video/mp4");
        String coverUrl = upload(token, "cover", pngBytes(), "cover.png", "image/png");
        String imageUrl = upload(token, "image", pngBytes(), "x.png", "image/png");

        assertRejected(token, createBody("VIDEO", "t", "d", 1, media(null, coverUrl, null)),
                "请选择视频文件");
        assertRejected(token, createBody("VIDEO", "t", "d", 1, media(videoUrl, null, null)),
                "请选择封面图片");
        assertRejected(token, createBody("VIDEO", "t", "d", 1,
                media(videoUrl, coverUrl, List.of(imageUrl))), "视频不能带图片");
        assertNoContent();
    }

    @Test
    @DisplayName("图文清单：带视频 / 重复 URL / 目录错位 ⇒ 400，且零内容零媒体行")
    void 图文清单约束() {
        String token = registerAndGetToken("13800000404", "constraint-post", PASSWORD);
        String videoUrl = upload(token, "video", mp4Seconds(1), "clip.mp4", "video/mp4");
        String coverUrl = upload(token, "cover", pngBytes(), "cover.png", "image/png");
        String imageUrl = upload(token, "image", pngBytes(), "x.png", "image/png");

        assertRejected(token, createBody("POST", "t", "d", 2,
                media(videoUrl, coverUrl, null)), "图文不能带视频");
        assertRejected(token, createBody("POST", "t", "d", 2,
                media(null, coverUrl, List.of(imageUrl, imageUrl))), "媒体资源重复");
        // 把封面 URL 塞进图片位：目录是 cover，不是 image
        assertRejected(token, createBody("POST", "t", "d", 2,
                media(null, coverUrl, List.of(coverUrl))), "图片地址不合法");
        assertNoContent();
    }

    @Test
    @DisplayName("★新资源必须真实存在，且不能是别的作品已引用的 URL")
    void 新资源存在性与跨作品占用() {
        String token = registerAndGetToken("13800000405", "constraint-owner", PASSWORD);
        String imageUrl = upload(token, "image", pngBytes(), "a.png", "image/png");

        assertRejected(token, createBody("POST", "t", "d", 2,
                media(null, null, List.of("/upload/image/never-uploaded.jpg"))),
                "媒体文件不存在");

        // A：先正常引用 imageUrl
        ResponseEntity<String> first = post("/api/v1/contents",
                createBody("POST", "A", "d", 2, media(null, null, List.of(imageUrl))), token);
        assertThat(first.getStatusCode().value()).as("%s", first.getBody()).isEqualTo(200);
        long contentA = Envelope.num(first, "contentId");
        assertThat(mediaRows(contentA)).hasSize(1);

        // B：同一 URL 再引用 ⇒ 拒绝（否则 A/B 共享文件，删一方会删掉对方的文件）
        assertRejected(token, createBody("POST", "B", "d", 2,
                media(null, null, List.of(imageUrl))), "媒体资源已被其他内容引用");

        // 独立 oracle：A 的行与文件都还在；B 没有落任何内容
        assertThat(mediaRows(contentA)).hasSize(1);
        assertThat(diskPath(imageUrl)).exists();
        assertThat(countContentRows()).as("只应有 A 这一条内容").isEqualTo(1);
    }

    // ==================== 3. 元数据校验 ====================

    @Test
    @DisplayName("简介上限按类型：视频 1000 / 图文 10000（GAP-31 的发布侧口径）")
    void 简介上限按类型() {
        String token = registerAndGetToken("13800000406", "desc-limit", PASSWORD);
        String videoUrl = upload(token, "video", mp4Seconds(1), "clip.mp4", "video/mp4");
        String coverUrl = upload(token, "cover", pngBytes(), "cover.png", "image/png");

        assertRejected(token, createBody("VIDEO", "t", "x".repeat(1001), 1,
                media(videoUrl, coverUrl, null)), "简介不得超过1000字");

        ResponseEntity<String> okPost = post("/api/v1/contents",
                createBody("POST", "长简介", "y".repeat(10_000), 2, media(null, null, null)), token);
        assertThat(okPost.getStatusCode().value()).as("%s", okPost.getBody()).isEqualTo(200);
        assertRejected(token, createBody("POST", "t", "y".repeat(10_001), 2,
                media(null, null, null)), "内容不得超过10000字");
    }

    @Test
    @DisplayName("声明式校验：缺 type/title/categoryId/media、标题超 50、分区越界 ⇒ 400（errors[] 指到字段）")
    void 声明式校验字段可定位() {
        String token = registerAndGetToken("13800000407", "declarative", PASSWORD);

        Map<String, Object> noTitle = createBody("POST", "t", "d", 1, media(null, null, null));
        noTitle.remove("title");
        assertFieldRejected(token, noTitle, "title");

        Map<String, Object> noType = createBody("POST", "t", "d", 1, media(null, null, null));
        noType.remove("type");
        assertFieldRejected(token, noType, "type");

        Map<String, Object> noCategory = createBody("POST", "t", "d", 1, media(null, null, null));
        noCategory.remove("categoryId");
        assertFieldRejected(token, noCategory, "categoryId");

        Map<String, Object> noMedia = createBody("POST", "t", "d", 1, media(null, null, null));
        noMedia.remove("media");
        assertFieldRejected(token, noMedia, "media");

        assertFieldRejected(token, createBody("POST", "x".repeat(51), "d", 1, media(null, null, null)),
                "title");
        assertFieldRejected(token, createBody("POST", "t", "d", 10, media(null, null, null)),
                "categoryId");
        assertNoContent();
    }

    @Test
    @DisplayName("未登录创建 ⇒ 401（HTTP 侧；注解机制由 SecurityContractTests 直接钉住，勿当判别力）")
    void 未登录_401() {
        ResponseEntity<String> resp = post("/api/v1/contents",
                createBody("POST", "t", "d", 1, media(null, null, null)), null);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    // ==================== 夹具与 oracle ====================

    private void assertRejected(String token, Map<String, Object> body, String expectedMsg) {
        ResponseEntity<String> resp = post("/api/v1/contents", body, token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(400);
        assertThat(Envelope.msg(resp)).isEqualTo(expectedMsg);
    }

    private void assertFieldRejected(String token, Map<String, Object> body, String field) {
        ResponseEntity<String> resp = post("/api/v1/contents", body, token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(400);
        assertThat(Envelope.errors(resp)).as("校验失败必须带 errors[]").isNotEmpty();
        assertThat(Envelope.errors(resp).get(0).path("field").asString()).isEqualTo(field);
    }

    private void assertNoContent() {
        assertThat(countContentRows()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content_media", Long.class)).isZero();
    }

    private long countContentRows() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content", Long.class);
        return count == null ? 0 : count;
    }

    private String upload(String token, String type, byte[] bytes, String fileName, String contentType) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(contentType));
        form.add("file", new HttpEntity<>(resource(bytes, fileName), headers));
        ResponseEntity<String> resp = postMultipart("/api/v1/uploads/" + type, form, token, null);
        assertThat(resp.getStatusCode().value()).as("造夹具：上传必须成功: %s", resp.getBody()).isEqualTo(200);
        return Envelope.str(resp, "url");
    }

    /** 请求体：只放非空字段（缺省与 null 在服务端同义）。 */
    private static Map<String, Object> createBody(String type, String title, String description,
                                                  int categoryId, Map<String, Object> media) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", title);
        body.put("description", description);
        body.put("categoryId", categoryId);
        body.put("media", media);
        return body;
    }

    /** 媒体清单：null 字段省略（与显式 null 同义）。 */
    private static Map<String, Object> media(String videoUrl, String coverUrl, List<String> imageUrls) {
        Map<String, Object> media = new LinkedHashMap<>();
        if (videoUrl != null) {
            media.put("videoUrl", videoUrl);
        }
        if (coverUrl != null) {
            media.put("coverUrl", coverUrl);
        }
        if (imageUrls != null) {
            media.put("imageUrls", imageUrls);
        }
        return media;
    }

    private List<Map<String, Object>> mediaRows(long contentId) {
        return jdbcTemplate.queryForList(
                "SELECT url, type, sort, duration_seconds FROM content_media WHERE content_id = ? ORDER BY type, sort",
                contentId);
    }

    /** 某类型内的 (url, sort) 行（按 sort 升序；独立 oracle）。 */
    private List<Map<String, Object>> mediaRowsOfType(long contentId, int type) {
        return jdbcTemplate.queryForList(
                "SELECT url, sort FROM content_media WHERE content_id = ? AND type = ? ORDER BY sort",
                contentId, type);
    }

    private Path diskPath(String url) {
        Path path = Path.of(uploadProperties.root());
        for (String segment : url.substring("/upload/".length()).split("/")) {
            path = path.resolve(segment);
        }
        return path;
    }

    // ---------- 手搓素材（与 UploadAssetTests 同形；期望值由用例自己定） ----------

    private static byte[] mp4Seconds(int seconds) {
        ByteBuffer mvhd = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        mvhd.put((byte) 0).put(new byte[3]);
        mvhd.putInt(0).putInt(0);
        mvhd.putInt(1000);
        mvhd.putInt(seconds * 1000);
        return concat(
                box("ftyp", "isomiso2avc1mp41".getBytes(StandardCharsets.ISO_8859_1)),
                box("mdat", new byte[1024]),
                box("moov", box("mvhd", mvhd.array())));
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
