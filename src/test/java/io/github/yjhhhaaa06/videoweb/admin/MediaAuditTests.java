package io.github.yjhhhaaa06.videoweb.admin;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S8：admin **媒体审计与恢复**（{@code /api/admin/media/*}）的行为测试。
 *
 * <h2>语义翻译自哪些旧用例</h2>
 * 旧 pytest {@code test_admin.py}（{@code TestMediaOps}）：scan 结构、restore 的 400/404 错误路径、
 * 以及对"孤儿且缺失"项的**正向**恢复。
 *
 * <h2>★ 三套 oracle（本切片最多）</h2>
 * <ol>
 *   <li><b>MySQL</b>：{@code content_media.file_exists} / {@code last_verify_time}、
 *       {@code content.file_exists} 聚合——扫描与恢复的产物都落在这几列；</li>
 *   <li><b>文件系统</b>：测试媒体根下的真实文件（恢复必须把字节写回去）；</li>
 *   <li><b>响应结构</b>：scan 的 8 个键与每条明细的 {@code status}。</li>
 * </ol>
 * 三者必须**同时**满足才算通过——只看响应就会漏掉"接口说恢复了但盘上没文件"。
 *
 * <h2>★ 为什么扫描的断言都按**行**而不是按"全局计数"</h2>
 * 测试媒体根（{@code target/test-media}）是**跨测试类共享**的目录（不清空，避免与 S7 的用例互相干扰）。
 * 因此"盘上共有几个文件"这种全局计数没有判别力；本类一律按本次造的行断言。
 * 而 {@code total/existing/missing/invalid} 四个计数是**按 DB 行**算的，DB 每个用例前被清空 ⇒ 可用。
 */
class MediaAuditTests extends AbstractAdminIntegrationTest {

    private static final String ADMIN_PHONE = "13900004001";
    private static final String ADMIN = "admin-media";
    private static final String AUTHOR_PHONE = "13900004002";
    private static final String AUTHOR = "admin-media-author";

    // ========================================================================
    // 扫描
    // ========================================================================

    @Test
    @DisplayName("★扫描：EXISTS / MISSING / INVALID_URL 三态分类 + 孤儿媒体 + 纯文字内容 + 回写校验列")
    void 扫描_分类与结构() {
        String adminToken = registerAdmin(ADMIN_PHONE, ADMIN);
        registerNormal(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);

        // 内容 A：三条媒体行，分别制造 EXISTS / MISSING / INVALID_URL
        long contentA = insertContent(authorId, CONTENT_TYPE_VIDEO, "扫描A", "d", 1);
        String urlExists = "/upload/video/scan_exists_" + contentA + ".mp4";
        writeMediaFile(urlExists, "exists");
        long mediaExists = insertMedia(contentA, urlExists, MEDIA_TYPE_VIDEO, 1);

        String urlMissing = "/upload/cover/scan_missing_" + contentA + ".png";
        deleteMediaFileQuietly(urlMissing);
        long mediaMissing = insertMedia(contentA, urlMissing, MEDIA_TYPE_COVER, 1);

        long mediaInvalid = insertMedia(contentA, "/upload/other/scan_bad.mp4", MEDIA_TYPE_IMAGE, 1);

        // 孤儿媒体：content_id 指向不存在的内容
        long orphan = insertMedia(999999999L, "/upload/image/scan_orphan.jpg", MEDIA_TYPE_IMAGE, 1);

        // 内容 B：纯文字（无任何媒体行）⇒ 视为完整
        long contentB = insertContent(authorId, CONTENT_TYPE_IMAGE, "扫描B_纯文字", "d", 2);

        try {
            ResponseEntity<String> resp = post("/api/admin/media/scan", null, adminToken);
            assertThat(resp.getStatusCode().value()).as("扫描应 200: %s", resp.getBody()).isEqualTo(200);

            JsonNode data = Envelope.data(resp);
            // 全部契约键（旧 pytest 逐键断言其中 8 个；items 也在契约里）
            for (String key : List.of("scanTime", "total", "existing", "missing", "invalid",
                    "notScanned", "orphanMediaIds", "contentsWithoutMedia", "items")) {
                assertThat(data.has(key)).as("扫描结果缺少键 " + key).isTrue();
            }

            assertThat(data.path("total").asInt()).as("全部媒体行").isEqualTo(4);
            assertThat(data.path("existing").asInt()).isEqualTo(1);
            assertThat(data.path("missing").asInt()).as("缺失的一条 + 孤儿那条（url 合法但文件不在）").isEqualTo(2);
            assertThat(data.path("invalid").asInt()).isEqualTo(1);
            assertThat(data.path("notScanned").asInt()).as("本实现一次扫完，恒 0").isZero();

            assertThat(ids(data.path("orphanMediaIds"))).containsExactly(orphan);
            assertThat(ids(data.path("contentsWithoutMedia"))).containsExactly(contentB);

            JsonNode items = data.path("items");
            assertThat(items.size()).isEqualTo(4);

            JsonNode existsItem = itemByMediaId(items, mediaExists);
            assertThat(existsItem.path("status").asString()).isEqualTo("EXISTS");
            assertThat(existsItem.path("fileExists").asBoolean()).isTrue();
            assertThat(existsItem.path("contentTitle").asString()).as("明细带内容标题（JOIN）").isEqualTo("扫描A");
            assertThat(existsItem.path("url").asString()).isEqualTo(urlExists);
            assertThat(existsItem.path("expectedPath").asString())
                    .as("url 反解出的磁盘路径必须是**真实存在**的那个")
                    .isNotBlank();
            assertThat(Files.isRegularFile(java.nio.file.Path.of(existsItem.path("expectedPath").asString())))
                    .isTrue();

            JsonNode missingItem = itemByMediaId(items, mediaMissing);
            assertThat(missingItem.path("status").asString()).isEqualTo("MISSING");
            assertThat(missingItem.path("fileExists").asBoolean()).isFalse();

            JsonNode invalidItem = itemByMediaId(items, mediaInvalid);
            assertThat(invalidItem.path("status").asString()).isEqualTo("INVALID_URL");
            assertThat(invalidItem.path("expectedPath").isNull()).as("非法 URL 反解不出路径").isTrue();

            assertThat(itemByMediaId(items, orphan).path("contentTitle").isNull())
                    .as("孤儿媒体查不到标题").isTrue();

            // ---- DB oracle：校验列被回写 ----
            assertThat(mediaFileExistsColumn(mediaExists)).isEqualTo(1);
            assertThat(mediaFileExistsColumn(mediaMissing)).isEqualTo(0);
            assertThat(mediaFileExistsColumn(mediaInvalid)).isEqualTo(0);
            assertThat(mediaLastVerifyTime(mediaExists)).as("扫描必须记校验时间").isNotNull();

            // ---- DB oracle：内容级聚合（A 有缺失 ⇒ 0；B 无媒体 ⇒ 完整 1）----
            assertThat(contentFileExistsColumn(contentA)).as("A 引用的文件不全 ⇒ 聚合 0").isEqualTo(0);
            assertThat(contentFileExistsColumn(contentB)).as("纯文字内容视为完整 ⇒ 聚合 1").isEqualTo(1);
        } finally {
            deleteMediaFileQuietly(urlExists);
        }
    }

    @Test
    @DisplayName("/media/list 与 /media/scan 是同一个实现（TV 原样：两者都写库）")
    void 扫描_两个端点同实现() {
        String adminToken = registerAdmin(ADMIN_PHONE, ADMIN);
        registerNormal(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        insertMedia(insertContent(authorId, CONTENT_TYPE_VIDEO, "同实现", "d", 1),
                "/upload/image/same_impl.jpg", MEDIA_TYPE_IMAGE, 1);

        JsonNode viaGet = Envelope.data(get("/api/admin/media/list", adminToken));
        JsonNode viaPost = Envelope.data(post("/api/admin/media/scan", null, adminToken));

        assertThat(viaGet.path("total").asInt()).isEqualTo(1);
        assertThat(viaPost.path("total").asInt()).isEqualTo(1);
        assertThat(viaGet.path("items").size()).isEqualTo(viaPost.path("items").size());
    }

    // ========================================================================
    // 恢复：错误路径（文案与旧版逐字一致）
    // ========================================================================

    @Test
    @DisplayName("恢复错误路径：缺 mediaId 400；缺文件 400；媒体不存在 404；URL 非法 400；扩展名不匹配 400")
    void 恢复_错误路径() {
        String adminToken = registerAdmin(ADMIN_PHONE, ADMIN);
        registerNormal(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);

        // 1) 缺 mediaId → 400（走全局缺参出口；旧版是 parseMediaId 的 ParamException，同为 400）
        assertThat(post("/api/admin/media/restore", null, adminToken).getStatusCode().value()).isEqualTo(400);

        // 2) 有 mediaId、但缺 file part → 400「请选择要恢复的文件」（multipart 请求，part 名不是 file）
        MultiValueMap<String, Object> noFile = multipartForm();
        addFilePart(noFile, "dummy", "x.txt", "x".getBytes(StandardCharsets.UTF_8), "text/plain");
        ResponseEntity<String> missingFile =
                postMultipart("/api/admin/media/restore?mediaId=1", noFile, adminToken);
        assertThat(missingFile.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(missingFile)).isEqualTo("请选择要恢复的文件");

        // 3) 媒体不存在 → 404
        MultiValueMap<String, Object> oneFile = multipartForm();
        addFilePart(oneFile, "file", "restore.mp4", "x".getBytes(StandardCharsets.UTF_8), "video/mp4");
        ResponseEntity<String> notFound =
                postMultipart("/api/admin/media/restore?mediaId=999999999", oneFile, adminToken);
        assertThat(notFound.getStatusCode().value()).isEqualTo(404);
        assertThat(Envelope.msg(notFound)).isEqualTo("媒体资源不存在: mediaId=999999999");

        // 4) URL 非法（目录段不是 video/image/cover）→ 400
        long badUrlMedia = insertMedia(authorId, "/upload/other/bad.mp4", MEDIA_TYPE_VIDEO, 1);
        ResponseEntity<String> badUrl = postMultipart(
                "/api/admin/media/restore?mediaId=" + badUrlMedia, oneFile, adminToken);
        assertThat(badUrl.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(badUrl)).isEqualTo("媒体 URL 不合法，无法定位文件: /upload/other/bad.mp4");

        // 5) 扩展名不匹配 → 400
        long mp4Media = insertMedia(authorId, "/upload/video/ext_check.mp4", MEDIA_TYPE_VIDEO, 1);
        MultiValueMap<String, Object> aviFile = multipartForm();
        addFilePart(aviFile, "file", "wrong.avi", "x".getBytes(StandardCharsets.UTF_8), "video/x-msvideo");
        ResponseEntity<String> extMismatch =
                postMultipart("/api/admin/media/restore?mediaId=" + mp4Media, aviFile, adminToken);
        assertThat(extMismatch.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(extMismatch)).isEqualTo("文件扩展名不匹配，目标需要 .mp4");
    }

    // ========================================================================
    // 恢复：正向（写文件 + 回写校验列 + 重算内容级聚合）
    // ========================================================================

    @Test
    @DisplayName("★正向恢复：文件按原 URL 写回磁盘、媒体行置 file_exists=1、内容级聚合重算为 1")
    void 恢复_正向写回() throws IOException {
        String adminToken = registerAdmin(ADMIN_PHONE, ADMIN);
        registerNormal(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);

        long contentId = insertContent(authorId, CONTENT_TYPE_VIDEO, "恢复正向", "d", 1);
        String url = "/upload/video/restore_positive_" + contentId + ".mp4";
        long mediaId = insertMedia(contentId, url, MEDIA_TYPE_VIDEO, 1);
        deleteMediaFileQuietly(url);
        assertThat(Files.exists(diskPathOf(url))).as("前置：目标文件必须**先不存在**").isFalse();

        byte[] payload = "restored-bytes".getBytes(StandardCharsets.UTF_8);
        MultiValueMap<String, Object> form = multipartForm();
        addFilePart(form, "file", "uploaded.mp4", payload, "video/mp4");

        try {
            ResponseEntity<String> resp = postMultipart(
                    "/api/admin/media/restore?mediaId=" + mediaId, form, adminToken);

            assertThat(resp.getStatusCode().value()).as("恢复应 200: %s", resp.getBody()).isEqualTo(200);
            JsonNode data = Envelope.data(resp);
            assertThat(data.path("mediaId").asLong()).isEqualTo(mediaId);
            assertThat(data.path("url").asString()).isEqualTo(url);
            assertThat(data.path("fileExists").asBoolean()).isTrue();
            assertThat(data.path("size").asLong()).as("字节数 = 写回的字节数").isEqualTo(payload.length);

            // ---- 文件系统 oracle ----
            assertThat(diskPathOf(url)).exists();
            assertThat(Files.readAllBytes(diskPathOf(url))).isEqualTo(payload);

            // ---- DB oracle ----
            assertThat(mediaFileExistsColumn(mediaId)).isEqualTo(1);
            assertThat(mediaLastVerifyTime(mediaId)).isNotNull();
            assertThat(contentFileExistsColumn(contentId))
                    .as("恢复后该内容引用的文件齐了 ⇒ 内容级聚合重算为 1")
                    .isEqualTo(1);
        } finally {
            deleteMediaFileQuietly(url);
        }
    }

    // ==================== 夹具 ====================

    private static JsonNode itemByMediaId(JsonNode items, long mediaId) {
        for (JsonNode item : items) {
            if (item.path("mediaId").asLong() == mediaId) {
                return item;
            }
        }
        throw new AssertionError("扫描明细应包含 mediaId=" + mediaId + "，实际: " + items);
    }

    private static java.util.List<Long> ids(JsonNode array) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (JsonNode node : array) {
            ids.add(node.asLong());
        }
        return ids;
    }
}
