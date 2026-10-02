package io.github.yjhhhaaa06.videoweb.upload;

import io.github.yjhhhaaa06.videoweb.common.config.UploadProperties;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S7 upload 切片的行为测试（3 端点 + A7 闭合 + 静态媒体访问）。
 *
 * <h2>三条纪律（《测试策略》§四）</h2>
 * <ol>
 *   <li><b>断言可观察行为</b>：HTTP 状态 + 信封 + **DB 终态 + 文件系统终态**（S7 特有的第二 oracle）；</li>
 *   <li><b>独立 oracle 复算</b>：媒体 url / sort / 行数一律直查 {@code jdbcTemplate}，
 *       磁盘文件一律走 {@link UploadProperties} 解析的绝对路径直查 {@link Files}——都不信任接口回显；</li>
 *   <li><b>语义翻译而非机械复制</b>：保留旧 pytest 的**意图**（smoke S-07/S-08/S-09、
 *       test_edit_work 的换源、test_delete_content 的磁盘断言），形式按新架构重写。</li>
 * </ol>
 *
 * <h2>素材与媒体根</h2>
 * 三个真实素材取自**外部目录**（{@code TV_TEST_RESOURCE_DIR}，默认沿用老项目的
 * {@code D:\dev\WorkSpace\VideoPlatform\TestResource}）——大二进制不入 git，缺失即失败不降级
 * （见 {@code AbstractIntegrationTest.testResource} 与《决策留痕表》B-15）。
 * 落盘根由 {@code AbstractIntegrationTest} 覆盖为项目内 {@code target/test-media}
 * ——测试**永不触碰**真实媒体根（{@code UploadPropertiesBindingTests} 直接断言这一点）。
 *
 * <p>磁盘断言用"**目标字节数 == 源素材字节数**"（而不是写死常量），这样换一份素材仍能证明
 * "字节被原样拷贝"。
 *
 * <h2>为什么手工拼 multipart 而不用 {@code MultipartBodyBuilder}</h2>
 * 后者（spring-web）引用 {@code org.reactivestreams.Publisher}，本项目没有 WebFlux/Reactive
 * 依赖 ⇒ 类加载 {@code NoClassDefFoundError}。手工用 {@code MultiValueMap<String, Object>}
 * + {@code HttpEntity<Resource>}（文件名取自 Resource，contentType 显式指定）是同一底层协议的
 * 最小表达，且**不引入任何新依赖**（依赖冻结纪律，SOP §五）。
 */
class UploadFlowTests extends AbstractHttpIntegrationTest {

    /** 素材文件名（外部目录 {@link #testResourceDir()} 下的三个真实媒体）。 */
    private static final String FIXTURE_VIDEO = "test_video.mp4";
    private static final String FIXTURE_COVER = "test_cover.png";
    private static final String FIXTURE_IMAGE = "test_image.jpg";

    private static final String PASSWORD = "abc123456";

    @Autowired
    private UploadProperties uploadProperties;

    @BeforeEach
    void resetContentState() {
        // 与 AbstractContentIntegrationTest 同口径：content / content_media 无外键，删除顺序无所谓。
        // （upload 测试的断言全部按本次新建的 contentId 定位，清表只是让"文件计数"类断言更干净。）
        jdbcTemplate.update("DELETE FROM content_media");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ==================== 1. 发布成功路径 ====================

    @Test
    @DisplayName("上传视频：content + content_media(type1/3) 落库、磁盘两文件落盘、详情可读到 videoUrl")
    void 上传视频成功() throws IOException {
        String phone = "13800000101";
        String token = registerAndGetToken(phone, "uploader-video", PASSWORD);

        MultiValueMap<String, Object> form = newForm();
        addText(form, "title", "s7_video_title");
        addText(form, "description", "s7 video desc");
        addText(form, "categoryId", "1");
        addVideoPart(form);
        addCoverPart(form);

        ResponseEntity<String> resp = postMultipart("/api/upload/video", form, token);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        long contentId = Envelope.num(resp, "contentId");
        assertThat(contentId).isPositive();

        // ---- DB oracle ----
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT user_id, type, title, description, category_id, is_deleted FROM content WHERE id = ?",
                contentId);
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(userIdOf(phone));
        assertThat(row.get("type")).isEqualTo(1);
        assertThat(row.get("title")).isEqualTo("s7_video_title");
        assertThat(row.get("description")).isEqualTo("s7 video desc");
        assertThat(row.get("category_id")).isEqualTo(1);
        assertThat(row.get("is_deleted")).isEqualTo(0);

        List<Map<String, Object>> media = mediaRows(contentId);
        assertThat(media).hasSize(2);
        assertThat(media.get(0)).containsEntry("type", 1).containsEntry("sort", 1);
        assertThat(media.get(1)).containsEntry("type", 3).containsEntry("sort", 1);
        String videoUrl = (String) media.get(0).get("url");
        String coverUrl = (String) media.get(1).get("url");
        assertThat(videoUrl).startsWith("/upload/video/").endsWith(".mp4");
        assertThat(coverUrl).startsWith("/upload/cover/").endsWith(".png");

        // ---- 文件系统 oracle（字节数与源素材一致）----
        assertThat(diskPathOf(videoUrl)).exists();
        assertThat(Files.size(diskPathOf(videoUrl))).isEqualTo(sourceSize(FIXTURE_VIDEO));
        assertThat(Files.size(diskPathOf(coverUrl))).isEqualTo(sourceSize(FIXTURE_COVER));

        // ---- 读回（旧 smoke S-08 的意图：上传后详情能看到 videoUrl）----
        ResponseEntity<String> detail = get("/search/IdSearch?contentId=" + contentId, null);
        assertThat(Envelope.code(detail)).isEqualTo(200);
        assertThat(Envelope.str(detail, "videoUrl")).endsWith(videoUrl);
    }

    @Test
    @DisplayName("上传图文：封面(type3) + 多图(type2) 按 sort 1..n 落库，详情 imageUrls 有序")
    void 上传图文成功() throws IOException {
        String token = registerAndGetToken("13800000102", "uploader-post", PASSWORD);

        MultiValueMap<String, Object> form = newForm();
        addText(form, "title", "s7_post_title");
        addText(form, "description", "s7 post desc");
        addText(form, "categoryId", "0");
        addCoverPart(form);
        addImagePart(form);
        addImagePart(form);

        ResponseEntity<String> resp = postMultipart("/api/upload/post", form, token);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        long contentId = Envelope.num(resp, "contentId");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT type FROM content WHERE id = ?", Integer.class, contentId))
                .as("图文内容的 content.type = 2")
                .isEqualTo(2);

        List<Map<String, Object>> media = mediaRows(contentId);
        assertThat(media).hasSize(3);
        // ORDER BY type,sort：先两张图（sort 1、2），后封面（sort 1）
        assertThat(media.get(0)).containsEntry("type", 2).containsEntry("sort", 1);
        assertThat(media.get(1)).containsEntry("type", 2).containsEntry("sort", 2);
        assertThat(media.get(2)).containsEntry("type", 3).containsEntry("sort", 1);
        assertThat((String) media.get(0).get("url")).startsWith("/upload/image/").endsWith(".jpg");

        for (Map<String, Object> item : media) {
            Path path = diskPathOf((String) item.get("url"));
            assertThat(path).exists();
            String fixture = ((Number) item.get("type")).intValue() == 3 ? FIXTURE_COVER : FIXTURE_IMAGE;
            assertThat(Files.size(path)).isEqualTo(sourceSize(fixture));
        }

        ResponseEntity<String> detail = get("/search/IdSearch?contentId=" + contentId, null);
        assertThat(Envelope.data(detail).path("imageUrls")).hasSize(2);
    }

    @Test
    @DisplayName("上传图文：无封面、零图片也可发布（0~n 张为旧版允许口径），简介缺省落 \"-\"")
    void 上传图文_零媒体也可发布() {
        String token = registerAndGetToken("13800000103", "uploader-post-min", PASSWORD);

        MultiValueMap<String, Object> form = newForm();
        addText(form, "title", "s7_post_min");
        addText(form, "categoryId", "0");
        // 不传 description、cover、image

        ResponseEntity<String> resp = postMultipart("/api/upload/post", form, token);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        long contentId = Envelope.num(resp, "contentId");
        assertThat(mediaRows(contentId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT description FROM content WHERE id = ?", String.class, contentId))
                .as("简介空 ⇒ 落库为 TV 的占位符 \"-\"")
                .isEqualTo("-");
    }

    // ==================== 2. 表单校验（文案与旧版逐字一致） ====================

    @Test
    @DisplayName("表单校验：标题/简介/分区文案与旧版逐字一致，且失败不落盘")
    void 表单校验失败不落盘() throws IOException {
        String token = registerAndGetToken("13800000104", "uploader-bad-form", PASSWORD);
        long before = mediaFileCount();

        // 标题为空
        MultiValueMap<String, Object> blankTitle = newForm();
        addText(blankTitle, "title", "   ");
        addText(blankTitle, "categoryId", "1");
        addVideoPart(blankTitle);
        addCoverPart(blankTitle);
        assertThat(Envelope.msg(postMultipart("/api/upload/video", blankTitle, token)))
                .isEqualTo("标题不能为空");

        // 标题 >50
        MultiValueMap<String, Object> longTitle = newForm();
        addText(longTitle, "title", "x".repeat(51));
        addText(longTitle, "categoryId", "1");
        addVideoPart(longTitle);
        addCoverPart(longTitle);
        assertThat(Envelope.msg(postMultipart("/api/upload/video", longTitle, token)))
                .isEqualTo("标题不得超过50字");

        // 视频简介 >1000
        MultiValueMap<String, Object> longVideoDesc = newForm();
        addText(longVideoDesc, "title", "t");
        addText(longVideoDesc, "description", "d".repeat(1001));
        addText(longVideoDesc, "categoryId", "1");
        addVideoPart(longVideoDesc);
        addCoverPart(longVideoDesc);
        assertThat(Envelope.msg(postMultipart("/api/upload/video", longVideoDesc, token)))
                .isEqualTo("简介不得超过1000字");

        // 图文简介 >10000（与视频的 1000 不同——TV 原样）
        MultiValueMap<String, Object> longPostDesc = newForm();
        addText(longPostDesc, "title", "t");
        addText(longPostDesc, "description", "d".repeat(10001));
        addText(longPostDesc, "categoryId", "0");
        assertThat(Envelope.msg(postMultipart("/api/upload/post", longPostDesc, token)))
                .isEqualTo("内容不得超过10000字");

        // 分区非法（多位数字）
        MultiValueMap<String, Object> badCategory = newForm();
        addText(badCategory, "title", "t");
        addText(badCategory, "categoryId", "12");
        addVideoPart(badCategory);
        addCoverPart(badCategory);
        assertThat(Envelope.msg(postMultipart("/api/upload/video", badCategory, token)))
                .isEqualTo("暂时没有这个分区");

        // ★ 所有失败都发生在落盘之前 ⇒ 文件数零增长
        assertThat(mediaFileCount())
                .as("表单校验失败不得留下任何文件（旧实现同为「校验先于 saveFile」）")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("文件类型不支持：扩展名非法 → 400 \"文件类型不支持\"，且一个文件都不落盘")
    void 文件类型不支持() throws IOException {
        String token = registerAndGetToken("13800000105", "uploader-bad-file", PASSWORD);
        long before = mediaFileCount();

        MultiValueMap<String, Object> form = newForm();
        addText(form, "title", "t");
        addText(form, "categoryId", "1");
        // video part 伪装成 .exe（contentType 非空但扩展名不在白名单）
        addFilePart(form, "video", new ByteArrayResource("not-a-video".getBytes()) {
            @Override
            public String getFilename() {
                return "evil.exe";
            }
        }, "application/octet-stream");
        addCoverPart(form);

        ResponseEntity<String> resp = postMultipart("/api/upload/video", form, token);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(resp)).isEqualTo("文件类型不支持");
        assertThat(mediaFileCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("缺 multipart part：video 缺失 → 400（D-11 记录：旧版 NPE 未捕获为 500）")
    void 缺必填part为400() {
        String token = registerAndGetToken("13800000106", "uploader-miss-part", PASSWORD);

        MultiValueMap<String, Object> form = newForm();
        addText(form, "title", "t");
        addText(form, "categoryId", "1");
        addCoverPart(form); // 只有 cover，没有 video

        ResponseEntity<String> resp = postMultipart("/api/upload/video", form, token);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    // ==================== 3. 换源（replace） ====================

    @Test
    @DisplayName("换源封面：URL 变化、新文件落盘、旧文件被删、详情读回新 URL")
    void 换源封面_新旧文件交替() throws IOException {
        String token = registerAndGetToken("13800000107", "uploader-replace-cover", PASSWORD);
        long contentId = uploadVideo(token, "s7_replace_cover");

        String oldUrl = mediaUrl(contentId, 3, 1);
        Path oldPath = diskPathOf(oldUrl);
        assertThat(oldPath).exists();

        MultiValueMap<String, Object> form = newForm();
        addFilePart(form, "file", fixture(FIXTURE_COVER), "image/png");
        ResponseEntity<String> resp = postMultipart(
                "/api/upload/replace?contentId=" + contentId + "&type=3&sort=1", form, token);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        String newUrl = Envelope.str(resp, "url");
        assertThat(newUrl).isNotEqualTo(oldUrl).startsWith("/upload/cover/");

        assertThat(mediaUrl(contentId, 3, 1)).isEqualTo(newUrl);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT file_exists FROM content WHERE id = ?", Integer.class, contentId))
                .as("换源后内容级 file_exists 置 1（TV 原样）")
                .isEqualTo(1);

        assertThat(diskPathOf(newUrl)).exists();
        assertThat(Files.size(diskPathOf(newUrl))).isEqualTo(sourceSize(FIXTURE_COVER));
        assertThat(oldPath).as("旧文件必须被删除（deleteFileByUrl）").doesNotExist();

        ResponseEntity<String> detail = get("/search/IdSearch?contentId=" + contentId, null);
        assertThat(Envelope.str(detail, "coverUrl")).endsWith(newUrl);
    }

    @Test
    @DisplayName("换源视频：详情 videoUrl 变化、新文件落盘、旧文件被删")
    void 换源视频_新旧文件交替() throws IOException {
        String token = registerAndGetToken("13800000108", "uploader-replace-video", PASSWORD);
        long contentId = uploadVideo(token, "s7_replace_video");

        String oldUrl = mediaUrl(contentId, 1, 1);
        Path oldPath = diskPathOf(oldUrl);
        assertThat(oldPath).exists();

        MultiValueMap<String, Object> form = newForm();
        addFilePart(form, "file", fixture(FIXTURE_VIDEO), "video/mp4");
        ResponseEntity<String> resp = postMultipart(
                "/api/upload/replace?contentId=" + contentId + "&type=1&sort=1", form, token);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        String newUrl = Envelope.str(resp, "url");
        assertThat(newUrl).isNotEqualTo(oldUrl);

        ResponseEntity<String> detail = get("/search/IdSearch?contentId=" + contentId, null);
        assertThat(Envelope.str(detail, "videoUrl")).endsWith(newUrl);
        assertThat(diskPathOf(newUrl)).exists();
        assertThat(oldPath).doesNotExist();
    }

    @Test
    @DisplayName("换源错误路径：type 非法 / 缺文件 → 400（文案与旧版一致）")
    void 换源参数错误() {
        String token = registerAndGetToken("13800000109", "uploader-replace-bad", PASSWORD);
        long contentId = uploadVideo(token, "s7_replace_bad");

        // type 非法（先于文件校验）
        ResponseEntity<String> badType = send(spec -> spec
                .uri("/api/upload/replace?contentId=" + contentId + "&type=5&sort=1")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        assertThat(badType.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(badType)).isEqualTo("type 不合法，应为 1/2/3");

        // 缺 file part
        MultiValueMap<String, Object> empty = newForm();
        ResponseEntity<String> noFile = postMultipart(
                "/api/upload/replace?contentId=" + contentId + "&type=1&sort=1", empty, token);
        assertThat(noFile.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(noFile)).isEqualTo("请选择要替换的文件");
    }

    @Test
    @DisplayName("换源所有权：替别人的作品换源 → 403（媒体定位先过归属校验）")
    void 换源非作者403() {
        String tokenA = registerAndGetToken("13800000110", "uploader-owner-a", PASSWORD);
        String tokenB = registerAndGetToken("13800000111", "uploader-owner-b", PASSWORD);
        long contentId = uploadVideo(tokenA, "s7_replace_forbidden");

        MultiValueMap<String, Object> form = newForm();
        addFilePart(form, "file", fixture(FIXTURE_COVER), "image/png");
        ResponseEntity<String> resp = postMultipart(
                "/api/upload/replace?contentId=" + contentId + "&type=3&sort=1", form, tokenB);

        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        assertThat(Envelope.msg(resp)).isEqualTo("只能操作自己的作品");
    }

    // ==================== 4. 鉴权（HTTP 级；机制级在 SecurityContractTests） ====================

    @Test
    @DisplayName("未登录上传：三个端点一律 401（/api/upload 是前缀保护）")
    void 未登录全部401() {
        MultiValueMap<String, Object> form = newForm();
        addText(form, "title", "t");
        addText(form, "categoryId", "1");
        addVideoPart(form);
        addCoverPart(form);

        for (String path : List.of("/api/upload/video", "/api/upload/post")) {
            ResponseEntity<String> resp = postMultipart(path, form, null);
            assertThat(resp.getStatusCode().value()).as(path).isEqualTo(401);
        }

        MultiValueMap<String, Object> replaceForm = newForm();
        addFilePart(replaceForm, "file", fixture(FIXTURE_COVER), "image/png");
        ResponseEntity<String> replace = postMultipart(
                "/api/upload/replace?contentId=1&type=1&sort=1", replaceForm, null);
        assertThat(replace.getStatusCode().value()).isEqualTo(401);
    }

    // ==================== 5. A7 闭合：删除后磁盘文件与 DB 行双端消失 ====================

    @Test
    @DisplayName("★ A7：删作品后 DB 媒体行消失 + 磁盘文件消失（双端断言）")
    void 删作品后磁盘文件消失() {
        String token = registerAndGetToken("13800000112", "uploader-a7-delete", PASSWORD);
        long contentId = uploadVideo(token, "s7_a7_delete");

        String videoUrl = mediaUrl(contentId, 1, 1);
        String coverUrl = mediaUrl(contentId, 3, 1);
        assertThat(diskPathOf(videoUrl)).exists();
        assertThat(diskPathOf(coverUrl)).exists();

        ResponseEntity<String> resp = postQuery("/content/delete",
                token, "contentId", String.valueOf(contentId));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        // DB 端：软删 + 媒体行清零（S5 已交付的行为）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM content WHERE id = ?", Integer.class, contentId)).isEqualTo(1);
        assertThat(mediaRows(contentId)).isEmpty();
        // 文件系统端：A7 补回后此前"残留"的文件必须消失
        assertThat(diskPathOf(videoUrl))
                .as("A7：删作品后磁盘文件必须被清理（S5 裁剪、S7 补回）")
                .doesNotExist();
        assertThat(diskPathOf(coverUrl)).doesNotExist();
    }

    @Test
    @DisplayName("★ A7：删单图后该图文件消失、其余图保留且 sort 重排连续")
    void 删单图后磁盘文件消失() {
        String token = registerAndGetToken("13800000113", "uploader-a7-media", PASSWORD);

        MultiValueMap<String, Object> form = newForm();
        addText(form, "title", "s7_a7_media");
        addText(form, "categoryId", "0");
        addImagePart(form);
        addImagePart(form);
        long contentId = Envelope.num(postMultipart("/api/upload/post", form, token), "contentId");

        String firstUrl = mediaUrl(contentId, 2, 1);
        String secondUrl = mediaUrl(contentId, 2, 2);
        Path firstPath = diskPathOf(firstUrl);
        assertThat(firstPath).exists();

        ResponseEntity<String> resp = postQuery("/content/mediaDelete", token,
                "contentId", String.valueOf(contentId), "type", "2", "sort", "1");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        // 删掉的是第 1 张：它的文件消失；第 2 张的文件仍在，且 sort 由 2 重排为 1
        assertThat(firstPath).as("被删图片的物理文件必须消失（A7）").doesNotExist();
        assertThat(diskPathOf(secondUrl)).exists();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT url, sort FROM content_media WHERE content_id = ? AND type = 2", contentId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("url")).isEqualTo(secondUrl);
        assertThat(rows.get(0).get("sort")).isEqualTo(1);
    }

    // ==================== 6. 静态媒体服务（B-10：/upload/** 可访问） ====================

    @Test
    @DisplayName("静态访问：上传后的 URL 匿名可 GET（字节数与源一致），且支持 Range")
    void 静态访问_可GET且支持Range() throws IOException {
        String token = registerAndGetToken("13800000114", "uploader-static", PASSWORD);
        long contentId = uploadVideo(token, "s7_static");
        String videoUrl = mediaUrl(contentId, 1, 1);

        // 匿名 GET（媒体 URL 不带鉴权——TV 的 /upload 挂载同样公开）
        ResponseEntity<byte[]> full = getBytes(videoUrl, null);
        assertThat(full.getStatusCode().value()).isEqualTo(200);
        assertThat(full.getBody()).hasSize((int) sourceSize(FIXTURE_VIDEO));

        // Range：视频播放依赖 206 分段
        ResponseEntity<byte[]> range = getBytes(videoUrl, "bytes=0-99");
        assertThat(range.getStatusCode().value())
                .as("Spring ResourceHttpRequestHandler 需支持字节区间（HTML5 视频拖动依赖）")
                .isEqualTo(206);
        assertThat(range.getBody()).hasSize(100);
    }

    // ==================== 共用夹具与 oracle ====================

    /** 上传一条视频（真实素材），返回 contentId。 */
    private long uploadVideo(String token, String title) {
        MultiValueMap<String, Object> form = newForm();
        addText(form, "title", title);
        addText(form, "description", "s7 fixture");
        addText(form, "categoryId", "1");
        addVideoPart(form);
        addCoverPart(form);
        ResponseEntity<String> resp = postMultipart("/api/upload/video", form, token);
        assertThat(resp.getStatusCode().value()).as("造夹具：上传视频必须成功").isEqualTo(200);
        return Envelope.num(resp, "contentId");
    }

    // ---------- multipart 表单构造（底层 MultiValueMap，避免 MultipartBodyBuilder 的 reactive 依赖） ----------

    private static MultiValueMap<String, Object> newForm() {
        return new LinkedMultiValueMap<>();
    }

    /** 文本 part（multipart 的普通表单字段）。 */
    private static void addText(MultiValueMap<String, Object> form, String name, String value) {
        form.add(name, value);
    }

    /** 文件 part：文件名取 {@link Resource#getFilename()}，contentType 显式指定（对齐旧 pytest 的 files 元组）。 */
    private static void addFilePart(MultiValueMap<String, Object> form, String name,
                                    Resource resource, String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(contentType));
        form.add(name, new HttpEntity<>(resource, headers));
    }

    private static void addVideoPart(MultiValueMap<String, Object> form) {
        addFilePart(form, "video", fixture(FIXTURE_VIDEO), "video/mp4");
    }

    private static void addCoverPart(MultiValueMap<String, Object> form) {
        addFilePart(form, "cover", fixture(FIXTURE_COVER), "image/png");
    }

    private static void addImagePart(MultiValueMap<String, Object> form) {
        addFilePart(form, "image", fixture(FIXTURE_IMAGE), "image/jpeg");
    }

    /** 外部目录里的素材文件（{@link FileSystemResource} 的 {@code getFilename()} 即文件名）。 */
    private static Resource fixture(String fileName) {
        return new FileSystemResource(testResource(fileName));
    }

    /** 源素材字节数（磁盘断言的独立 oracle：目标字节数必须等于它）。 */
    private static long sourceSize(String fileName) throws IOException {
        return Files.size(testResource(fileName));
    }

    /** multipart POST（{@code token == null} = 匿名；经基类 {@code send} 拿原始响应）。 */
    private ResponseEntity<String> postMultipart(String path, MultiValueMap<String, Object> form, String token) {
        return send(spec -> {
            RestClient.RequestBodySpec request = spec.uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA);
            if (token != null) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return request.body(form);
        });
    }

    /** POST + query 参数（{@code /content/delete?contentId=…} 形态）。 */
    private ResponseEntity<String> postQuery(String path, String token, String... keyValues) {
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            query.add(keyValues[i], keyValues[i + 1]);
        }
        return send(spec -> {
            RestClient.RequestBodySpec request = spec.uri(uriBuilder -> {
                var builder = uriBuilder.path(path);
                builder.queryParams(query);
                return builder.build();
            }).contentType(MediaType.APPLICATION_JSON);
            if (token != null) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return request;
        });
    }

    /** GET 二进制（静态媒体）：可选 Range 头；不经状态处理器，便于断言 200/206。 */
    private ResponseEntity<byte[]> getBytes(String path, String rangeHeader) {
        return client.get().uri(path)
                .headers(headers -> {
                    if (rangeHeader != null) {
                        headers.set("Range", rangeHeader);
                    }
                })
                .exchange((request, response) -> ResponseEntity
                        .status(response.getStatusCode())
                        .headers(response.getHeaders())
                        .body(response.bodyTo(byte[].class)), false);
    }

    /** {@code /upload/{dir}/{file}} → 磁盘绝对路径（与 {@link UploadProperties} 同源解析）。 */
    private Path diskPathOf(String url) {
        assertThat(url).as("媒体 url 形状必须是 /upload/{dir}/{file}").startsWith("/upload/");
        Path path = Path.of(uploadProperties.root());
        for (String segment : url.substring("/upload/".length()).split("/")) {
            path = path.resolve(segment);
        }
        return path;
    }

    /** 测试媒体根下的**全部**普通文件数（"失败不落盘"类断言的独立 oracle）。 */
    private long mediaFileCount() throws IOException {
        Path root = Path.of(uploadProperties.root());
        if (!Files.exists(root)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    private List<Map<String, Object>> mediaRows(long contentId) {
        return jdbcTemplate.queryForList(
                "SELECT url, type, sort FROM content_media WHERE content_id = ? ORDER BY type, sort",
                contentId);
    }

    private String mediaUrl(long contentId, int type, int sort) {
        return jdbcTemplate.queryForObject(
                "SELECT url FROM content_media WHERE content_id = ? AND type = ? AND sort = ?",
                String.class, contentId, type, sort);
    }
}