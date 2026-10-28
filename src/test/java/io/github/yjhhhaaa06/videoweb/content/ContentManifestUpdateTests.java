package io.github.yjhhhaaa06.videoweb.content;

import io.github.yjhhhaaa06.videoweb.common.config.UploadProperties;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentMediaDao;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * {@code PUT /api/v1/contents/{id}} 的**媒体清单覆盖**契约测试。
 *
 * <h2>它钉住什么</h2>
 * <ol>
 *   <li><b>{@code media} 出现即整份覆盖</b>：缺席的旧图片/封面删除、新增的插入、
 *       数组顺序即 {@code sort} 1..n；清单外旧文件在提交后被清理；</li>
 *   <li><b>{@code media} 缺省 = 不动媒体</b>（只改文案时媒体行 id 都不变）；</li>
 *   <li><b>校验共享</b>：跨作品 URL / 重复 / 缺文件 / 类型错位与创建同一套；</li>
 *   <li><b>已绑定 URL 即使文件缺失也允许</b>（否则"文件已丢"的作品连标题都改不了）；</li>
 *   <li><b>事务</b>：媒体写入失败时，已成功的内容行更新与已执行的删除一起回滚。</li>
 * </ol>
 *
 * <h2>独立 oracle</h2>
 * 媒体行（url/type/sort/duration）与文件存在性一律直查 DB / 磁盘；事务用例的失败点
 * 落在"已有成功写之后"（{@code addMedia}），用 spy 制造可复现的提交前失败。
 */
class ContentManifestUpdateTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    @Autowired
    private UploadProperties uploadProperties;

    @MockitoSpyBean
    private ContentMediaDao contentMediaDao;

    @BeforeEach
    void resetState() {
        Mockito.reset(contentMediaDao);
        jdbcTemplate.update("DELETE FROM content_media");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ==================== 1. 只改元数据 ====================

    @Test
    @DisplayName("★media 缺省：只改标题/简介/分区，媒体行 id 一个都不变")
    void 只改元数据_媒体不动() {
        String token = registerAndGetToken("13800000601", "edit-meta", PASSWORD);
        Created video = createVideo(token, "旧标题", "旧简介", 1);

        Map<String, Long> idsBefore = mediaIds(video.contentId());
        assertThat(idsBefore).hasSize(2);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "新标题");
        body.put("description", "新简介");
        body.put("categoryId", 3);

        ResponseEntity<String> resp = put("/api/v1/contents/" + video.contentId(), body, token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT title, description, category_id FROM content WHERE id = ?", video.contentId());
        assertThat(row).containsEntry("title", "新标题")
                .containsEntry("description", "新简介")
                .containsEntry("category_id", 3);

        // 媒体行 id 不变 = 没有重建行（不是"删了再插回同 url"）
        assertThat(mediaIds(video.contentId()))
                .as("media 缺省 ⇒ 媒体行一行都不动（id 也不变）")
                .isEqualTo(idsBefore);

        // 详情回读
        assertThat(Envelope.data(get("/api/v1/contents/" + video.contentId(), null))
                .path("title").asString()).isEqualTo("新标题");
        assertThat(Envelope.data(get("/api/v1/contents/" + video.contentId(), null))
                .path("categoryId").asInt()).isEqualTo(3);
    }

    // ==================== 2. 换视频 / 换封面 ====================

    @Test
    @DisplayName("★换视频：旧行/旧文件消失，新行带新时长；封面不动")
    void 换视频_旧文件清理与新时长() {
        String token = registerAndGetToken("13800000602", "edit-video", PASSWORD);
        Created video = createVideo(token, "换源前", "d", 1);
        String newVideoUrl = upload(token, "video", mp4Seconds(9), "new.mp4", "video/mp4");

        ResponseEntity<String> resp = put("/api/v1/contents/" + video.contentId(),
                updateBody("换源后", "d", null,
                        media(newVideoUrl, video.coverUrl(), null)), token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);

        List<Map<String, Object>> rows = mediaRows(video.contentId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("url", newVideoUrl).containsEntry("type", 1);
        assertThat(((Number) rows.get(0).get("duration_seconds")).intValue())
                .as("新视频重新解析时长").isEqualTo(9);
        assertThat(rows.get(1)).containsEntry("url", video.coverUrl()).containsEntry("type", 3);

        assertThat(diskPath(video.videoUrl())).as("被换掉的旧视频文件必须删除").doesNotExist();
        assertThat(diskPath(newVideoUrl)).exists();
        assertThat(diskPath(video.coverUrl())).as("未换的封面文件必须保留").exists();
        assertThat(Envelope.data(get("/api/v1/contents/" + video.contentId(), null))
                .path("videoUrl").asString()).isEqualTo(newVideoUrl);
    }

    // ==================== 3. 图文增删改序 ====================

    @Test
    @DisplayName("★图文：删一张 + 追加一张 + 重排，DB 顺序/文件清理与清单一致")
    void 图文增删改序() {
        String token = registerAndGetToken("13800000603", "edit-post", PASSWORD);
        String a = upload(token, "image", pngBytes(), "a.png", "image/png");
        String b = upload(token, "image", pngBytes(), "b.png", "image/png");
        String cover = upload(token, "cover", pngBytes(), "c.png", "image/png");
        Created post = createPost(token, "图文", List.of(a, b), cover, "d", 2);
        String d = upload(token, "image", pngBytes(), "d.png", "image/png");

        // 目标：删 a、保留 b 并前移、追加 d
        ResponseEntity<String> resp = put("/api/v1/contents/" + post.contentId(),
                updateBody("图文", "d", null, media(null, cover, List.of(b, d))), token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);

        assertThat(mediaRowsOfType(post.contentId(), 2))
                .containsExactly(
                        Map.of("url", b, "sort", 1),
                        Map.of("url", d, "sort", 2));
        assertThat(mediaRowsOfType(post.contentId(), 3))
                .containsExactly(Map.of("url", cover, "sort", 1));

        assertThat(diskPath(a)).as("被移除图片的文件必须删除").doesNotExist();
        assertThat(diskPath(b)).exists();
        assertThat(diskPath(d)).exists();
        assertThat(diskPath(cover)).exists();

        assertThat(Envelope.data(get("/api/v1/contents/" + post.contentId(), null)).path("imageUrls"))
                .extracting(node -> node.asString())
                .containsExactly(b, d);
    }

    @Test
    @DisplayName("★清空媒体：media:{} ⇒ 图文封面与图片全删、文件全删；再补封面只加一行")
    void 清空媒体与补封面() {
        String token = registerAndGetToken("13800000604", "edit-clear", PASSWORD);
        String a = upload(token, "image", pngBytes(), "a.png", "image/png");
        String cover = upload(token, "cover", pngBytes(), "c.png", "image/png");
        Created post = createPost(token, "图文", List.of(a), cover, "d", 2);

        ResponseEntity<String> cleared = put("/api/v1/contents/" + post.contentId(),
                updateBody("图文", "d", null, Map.of()), token);
        assertThat(cleared.getStatusCode().value()).as("%s", cleared.getBody()).isEqualTo(200);
        assertThat(mediaRows(post.contentId())).isEmpty();
        assertThat(diskPath(a)).doesNotExist();
        assertThat(diskPath(cover)).doesNotExist();
        assertThat(Envelope.data(get("/api/v1/contents/" + post.contentId(), null)).path("imageUrls").isNull())
                .isTrue();

        // 无封面图文补封面：只出现 type=3 一行
        String newCover = upload(token, "cover", pngBytes(), "new.png", "image/png");
        ResponseEntity<String> added = put("/api/v1/contents/" + post.contentId(),
                updateBody("图文", "d", null, media(null, newCover, null)), token);
        assertThat(added.getStatusCode().value()).as("%s", added.getBody()).isEqualTo(200);
        assertThat(mediaRowsOfType(post.contentId(), 3))
                .containsExactly(Map.of("url", newCover, "sort", 1));
        assertThat(Envelope.data(get("/api/v1/contents/" + post.contentId(), null))
                .path("coverUrl").asString()).isEqualTo(newCover);
    }

    // ==================== 4. 清单约束 ====================

    @Test
    @DisplayName("★跨作品占用 / 重复 / 缺文件 / 目录错位 ⇒ 400，且目标内容一行不改")
    void 清单约束与创建同口径() {
        String token = registerAndGetToken("13800000605", "edit-constraint", PASSWORD);
        String a = upload(token, "image", pngBytes(), "a.png", "image/png");
        String b = upload(token, "image", pngBytes(), "b.png", "image/png");
        Created owner = createPost(token, "图文A", List.of(a), null, "d", 2);
        Created target = createPost(token, "图文B", List.of(b), null, "d", 2);

        assertUpdateRejected(token, target.contentId(),
                updateBody("t", "d", null, media(null, null, List.of(a))),
                "媒体资源已被其他内容引用");
        assertUpdateRejected(token, target.contentId(),
                updateBody("t", "d", null, media(null, null, List.of(b, b))),
                "媒体资源重复");
        assertUpdateRejected(token, target.contentId(),
                updateBody("t", "d", null, media(null, null, List.of("/upload/image/missing.jpg"))),
                "媒体文件不存在");
        assertUpdateRejected(token, target.contentId(),
                updateBody("t", "d", null, media(null, null, List.of("/upload/cover/wrong.png"))),
                "图片地址不合法");

        // 目标内容原样；A 的文件还在
        assertThat(mediaRowsOfType(target.contentId(), 2))
                .containsExactly(Map.of("url", b, "sort", 1));
        assertThat(diskPath(a)).exists();
        assertThat(mediaRows(owner.contentId())).hasSize(1);
    }

    @Test
    @DisplayName("类型约束：视频缺视频/缺封面/带图、图文带视频 ⇒ 400")
    void 类型约束() {
        String token = registerAndGetToken("13800000606", "edit-type", PASSWORD);
        Created video = createVideo(token, "v", "d", 1);
        String image = upload(token, "image", pngBytes(), "x.png", "image/png");

        assertUpdateRejected(token, video.contentId(),
                updateBody("v", "d", null, media(null, video.coverUrl(), null)), "请选择视频文件");
        assertUpdateRejected(token, video.contentId(),
                updateBody("v", "d", null, media(video.videoUrl(), null, null)), "请选择封面图片");
        assertUpdateRejected(token, video.contentId(),
                updateBody("v", "d", null, media(video.videoUrl(), video.coverUrl(), List.of(image))),
                "视频不能带图片");

        Created post = createPost(token, "图文", List.of(image), null, "d", 2);
        assertUpdateRejected(token, post.contentId(),
                updateBody("p", "d", null, media(video.videoUrl(), null, null)), "图文不能带视频");
    }

    // ==================== 5. 已绑定资源 / 权限 / 上限 ====================

    @Test
    @DisplayName("★已绑定 URL 即使文件缺失也允许提交（否则文件丢失的作品连标题都改不了）")
    void 已绑定URL不校验文件存在() throws IOException {
        String token = registerAndGetToken("13800000607", "edit-broken", PASSWORD);
        String a = upload(token, "image", pngBytes(), "a.png", "image/png");
        Created post = createPost(token, "图文", List.of(a), null, "d", 2);
        Files.delete(diskPath(a));

        ResponseEntity<String> metaOnly = put("/api/v1/contents/" + post.contentId(),
                updateBody("改标题", "d", null, null), token);
        assertThat(metaOnly.getStatusCode().value()).as("%s", metaOnly.getBody()).isEqualTo(200);

        ResponseEntity<String> sameMedia = put("/api/v1/contents/" + post.contentId(),
                updateBody("改标题2", "d", null, media(null, null, List.of(a))), token);
        assertThat(sameMedia.getStatusCode().value()).as("%s", sameMedia.getBody()).isEqualTo(200);
        assertThat(mediaRowsOfType(post.contentId(), 2))
                .containsExactly(Map.of("url", a, "sort", 1));
    }

    @Test
    @DisplayName("权限与边界：非作者 403、空标题先 400、不存在 404、未登录 401、分区越界 400")
    void 权限与边界() {
        String author = registerAndGetToken("13800000608", "edit-owner", PASSWORD);
        String other = registerAndGetToken("13800000609", "edit-other", PASSWORD);
        Created post = createPost(author, "图文", List.of(), null, "d", 2);

        assertThat(put("/api/v1/contents/" + post.contentId(),
                updateBody("被别人改", "d", null, null), other).getStatusCode().value()).isEqualTo(403);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM content WHERE id = ?", String.class, post.contentId())).isEqualTo("图文");

        // 标题校验先于归属校验（TV 口径）
        assertThat(put("/api/v1/contents/" + post.contentId(),
                updateBody(" ", "d", null, null), other).getStatusCode().value()).isEqualTo(400);

        assertThat(put("/api/v1/contents/987654321",
                updateBody("t", "d", null, null), author).getStatusCode().value()).isEqualTo(404);
        assertThat(put("/api/v1/contents/" + post.contentId(),
                updateBody("t", "d", null, null), null).getStatusCode().value()).isEqualTo(401);
        assertThat(put("/api/v1/contents/" + post.contentId(),
                updateBody("t", "d", 10, null), author).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("★GAP-31：5001~10000 字的图文可以编辑（旧上限 5000 的缺陷已修）")
    void 长图文可编辑() {
        String token = registerAndGetToken("13800000610", "edit-longpost", PASSWORD);
        String longDescription = "y".repeat(10_000);
        Created post = createPost(token, "长图文", List.of(), null, longDescription, 2);

        ResponseEntity<String> resp = put("/api/v1/contents/" + post.contentId(),
                updateBody("新标题", longDescription, null, null), token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT description FROM content WHERE id = ?", String.class, post.contentId()))
                .hasSize(10_000);

        // 视频侧收紧到 1000（发布同源）
        Created video = createVideo(token, "v", "d", 1);
        assertUpdateRejected(token, video.contentId(),
                updateBody("v", "x".repeat(1001), null, null), "简介不超过1000字");
    }

    // ==================== 6. 事务 ====================

    @Test
    @DisplayName("★事务：媒体插入失败 ⇒ 内容行更新与已执行的删除一起回滚；旧文件不被删")
    void 媒体写入失败整体回滚() {
        String token = registerAndGetToken("13800000611", "edit-tx", PASSWORD);
        String a = upload(token, "image", pngBytes(), "a.png", "image/png");
        String b = upload(token, "image", pngBytes(), "b.png", "image/png");
        Created post = createPost(token, "旧标题", List.of(a, b), null, "旧简介", 2);
        String d = upload(token, "image", pngBytes(), "d.png", "image/png");

        // 目标：删 b、追加 d。reconcile 先删 b，再插 d 时失败 ⇒ 两者都要回滚。
        doThrow(new IllegalStateException("模拟媒体插入失败"))
                .when(contentMediaDao).addMedia(anyLong(), anyString(), anyInt(), anyInt(), any());

        ResponseEntity<String> resp = put("/api/v1/contents/" + post.contentId(),
                updateBody("新标题", "新简介", null, media(null, null, List.of(a, d))), token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(500);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM content WHERE id = ?", String.class, post.contentId()))
                .as("内容行更新必须回滚").isEqualTo("旧标题");
        assertThat(mediaRowsOfType(post.contentId(), 2))
                .as("被删的 b 与未插入的 d 都要回滚到原态")
                .containsExactly(Map.of("url", a, "sort", 1), Map.of("url", b, "sort", 2));
        assertThat(diskPath(b)).as("事务失败不得删旧文件（Controller 未走到提交后段）").exists();
        assertThat(diskPath(d)).as("未绑定的新上传文件保留为孤儿").exists();
    }

    // ==================== 夹具与 oracle ====================

    private record Created(long contentId, String videoUrl, String coverUrl) {
    }

    private Created createVideo(String token, String title, String description, int categoryId) {
        String videoUrl = upload(token, "video", mp4Seconds(5), "clip.mp4", "video/mp4");
        String coverUrl = upload(token, "cover", pngBytes(), "cover.png", "image/png");
        ResponseEntity<String> resp = post("/api/v1/contents",
                createBody("VIDEO", title, description, categoryId,
                        media(videoUrl, coverUrl, null)), token);
        assertThat(resp.getStatusCode().value()).as("造夹具：创建视频必须成功: %s", resp.getBody())
                .isEqualTo(200);
        return new Created(Envelope.num(resp, "contentId"), videoUrl, coverUrl);
    }

    private Created createPost(String token, String title, List<String> imageUrls, String coverUrl,
                               String description, int categoryId) {
        ResponseEntity<String> resp = post("/api/v1/contents",
                createBody("POST", title, description, categoryId,
                        media(null, coverUrl, imageUrls)), token);
        assertThat(resp.getStatusCode().value()).as("造夹具：创建图文必须成功: %s", resp.getBody())
                .isEqualTo(200);
        return new Created(Envelope.num(resp, "contentId"), null, coverUrl);
    }

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

    private static Map<String, Object> updateBody(String title, String description,
                                                  Integer categoryId, Map<String, Object> media) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("description", description);
        if (categoryId != null) {
            body.put("categoryId", categoryId);
        }
        if (media != null) {
            body.put("media", media);
        }
        return body;
    }

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

    private void assertUpdateRejected(String token, long contentId, Map<String, Object> body,
                                      String expectedMsg) {
        ResponseEntity<String> resp = put("/api/v1/contents/" + contentId, body, token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(400);
        assertThat(Envelope.msg(resp)).isEqualTo(expectedMsg);
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

    private List<Map<String, Object>> mediaRows(long contentId) {
        return jdbcTemplate.queryForList(
                "SELECT url, type, sort, duration_seconds FROM content_media WHERE content_id = ? ORDER BY type, sort",
                contentId);
    }

    private List<Map<String, Object>> mediaRowsOfType(long contentId, int type) {
        return jdbcTemplate.queryForList(
                "SELECT url, sort FROM content_media WHERE content_id = ? AND type = ? ORDER BY sort",
                contentId, type);
    }

    private Map<String, Long> mediaIds(long contentId) {
        Map<String, Long> ids = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT url, id FROM content_media WHERE content_id = ? ORDER BY type, sort", contentId)) {
            ids.put((String) row.get("url"), ((Number) row.get("id")).longValue());
        }
        return ids;
    }

    private Path diskPath(String url) {
        Path path = Path.of(uploadProperties.root());
        for (String segment : url.substring("/upload/".length()).split("/")) {
            path = path.resolve(segment);
        }
        return path;
    }

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
