package io.github.yjhhhaaa06.videoweb.upload;

import tools.jackson.databind.JsonNode;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 视频时长（T46）的**端到端**契约：上传 / 换源 → {@code content_media.duration_seconds}
 * → 内容载荷（{@code durationSeconds}）。
 *
 * <h2>为什么三层都要断言（这是本任务最容易出假绿的地方）</h2>
 * 时长的链路有三段，任一段断了上层的断言都看不出来：
 * <pre>
 *   ① 解析器        Mp4DurationReader（本类不测，见 Mp4DurationReaderTests 的手搓字节）
 *   ② DB 落库       content_media.duration_seconds
 *   ③ 载荷投影      ContentCache.buildContentMedia → ContentCacheDTO → copyCommon → VO
 * </pre>
 * ★ **只断 ② 会漏掉最隐蔽的一种失败**：DB 里有值，但 {@code copyCommon} 没搬这个字段
 * ⇒ 详情 JSON 恒 {@code null}，而 DB 断言**照样绿**。所以每条用例都同时钉 DB 终态与 HTTP 回显。
 *
 * <h2>期望值怎么来</h2>
 * 手搓 mp4 的时长是**用例自己定的**（{@code mp4Seconds(3)} ⇒ 3 秒），不是从被测实现读出来的。
 * 这就是独立 oracle：换个解析实现（甚至换成 ffmpeg）也不影响期望值。
 *
 * <h2>反向验证（注入 ⇒ 必红）</h2>
 * ① {@code addMedia} 不写 duration ⇒ "时长落库"用例红；
 * ② {@code buildContentMedia} 不 setDurationSeconds ⇒ 回显用例红（DB 断言仍绿——这正是要防的假绿）；
 * ③ {@code copyCommon} 漏 setDurationSeconds ⇒ 回显用例红；
 * ④ {@code replaceMedia} 对 type=1 不调 {@code updateMediaDuration} ⇒ "换源刷新"红；
 * ⑤ 把"type=2/3 不调用"改成无条件调用 ⇒ "换封面不清空视频时长"红。
 */
class UploadDurationTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";
    private static final String TITLE_KEYWORD = "zqtdur";

    @BeforeEach
    void resetContentState() {
        jdbcTemplate.update("DELETE FROM content_media");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ==================== 发布 ====================

    @Test
    @DisplayName("★发布视频：手搓 3s mp4 ⇒ DB 落库 3 + 详情回显 3（两段都断，防'只落库不投影'假绿）")
    void 发布视频_时长落库并回显() {
        String token = registerAndGetToken("13800000201", "dur-upload", PASSWORD);

        long contentId = uploadVideo(token, TITLE_KEYWORD + " 三秒", mp4Seconds(3), "clip.mp4", "video/mp4");

        assertThat(durationInDb(contentId))
                .as("② DB 终态：发布落在 type=1 的媒体行上（封面行 null）")
                .isEqualTo(3);
        assertThat(Envelope.data(get("/search/IdSearch?contentId=" + contentId, null))
                .path("durationSeconds").asInt())
                .as("③ 载荷投影：详情必须回显（copyCommon / buildContentMedia 任一处漏了就恒 null）")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("★详情 JSON 键集：含 durationSeconds，且既有 18 个键一个都不少（精确 19 键，契约跟随 T48）")
    void 详情键集含durationSeconds且不抖落既有键() {
        String token = registerAndGetToken("13800000202", "dur-keys", PASSWORD);
        long contentId = uploadVideo(token, TITLE_KEYWORD + " 键集", mp4Seconds(4), "keys.mp4", "video/mp4");

        JsonNode data = Envelope.data(get("/search/IdSearch?contentId=" + contentId, null));

        assertThat(data.has("durationSeconds")).as("新键必须在").isTrue();
        // ★ T48 起键集 18 → 19（新增 playCount）：这是**契约跟随**，不是测试迎合 ——
        //   依据 = architecture/domain/history/history.md §五「第 5 个公共计数」：
        //   播放量与赞/评/藏/币一样只在详情平铺（不进 ContentCacheDTO ⇒ 列表仍零计数）。
        assertThat(keysOf(data))
                .as("★ 精确键集：多一个 / 少一个都是契约变更（durationSeconds / playCount 之外不许有别的漂移）")
                .containsExactlyInAnyOrder(
                        "id", "authorId", "type", "title", "description", "categoryId", "commentEnabled",
                        "authorName", "coverUrl", "videoUrl", "imageUrls", "createTime", "interaction",
                        "likeCount", "commentCount", "favoriteCount", "coinCount", "durationSeconds",
                        "playCount");
    }

    @Test
    @DisplayName("列表响应同样带 durationSeconds（慢变字段，不是计数 ⇒ 不受 T43'列表零计数'约束）")
    void 列表也带时长() {
        String token = registerAndGetToken("13800000203", "dur-list", PASSWORD);
        long contentId = uploadVideo(token, TITLE_KEYWORD + " 列表时长", mp4Seconds(7), "list.mp4", "video/mp4");

        JsonNode page = Envelope.data(get("/search/keywordSearch?keyword=" + TITLE_KEYWORD, null));
        JsonNode first = page.path("list").get(0);

        assertThat(first.path("id").asLong()).as("夹具需命中本用例的内容").isEqualTo(contentId);
        assertThat(first.path("durationSeconds").asInt()).isEqualTo(7);
        assertThat(first.has("durationSeconds")).isTrue();
    }

    @Test
    @DisplayName("★解析失败仍 200：坏文件（扩展名 .mp4）⇒ 时长未知，发布不受影响")
    void 解析失败仍200() {
        String token = registerAndGetToken("13800000204", "dur-badfile", PASSWORD);

        ResponseEntity<String> resp = uploadVideoRaw(token, TITLE_KEYWORD + " 坏文件",
                "this-is-not-an-mp4".getBytes(StandardCharsets.UTF_8), "broken.mp4", "video/mp4");

        assertThat(resp.getStatusCode().value())
                .as("★ 时长只服务进度百分比 ⇒ 异常 mp4 不能把发布变成新的失败点")
                .isEqualTo(200);
        long contentId = Envelope.num(resp, "contentId");
        assertThat(durationInDb(contentId)).as("未知 ⇒ null（不是 0）").isNull();
        assertThat(Envelope.data(get("/search/IdSearch?contentId=" + contentId, null))
                .path("durationSeconds").isNull()).as("回显也是 null").isTrue();
    }

    @Test
    @DisplayName("图文：durationSeconds 为 null（没有视频行可投影）")
    void 图文时长为null() {
        String token = registerAndGetToken("13800000205", "dur-post", PASSWORD);

        MultiValueMap<String, Object> form = newForm();
        addText(form, "title", TITLE_KEYWORD + " 图文");
        addText(form, "categoryId", "2");
        addFilePart(form, "cover", pngBytes(), "image/png");
        ResponseEntity<String> resp = postMultipart("/api/v1/contents/posts", form, token);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        long contentId = Envelope.num(resp, "contentId");
        assertThat(Envelope.data(get("/search/IdSearch?contentId=" + contentId, null))
                .path("durationSeconds").isNull()).isTrue();
    }

    // ==================== 换源 ====================

    @Test
    @DisplayName("★换源视频：时长按新文件刷新（3s → 9s），且详情立刻回显新值")
    void 换源视频_时长刷新() {
        String token = registerAndGetToken("13800000206", "dur-replace", PASSWORD);
        long contentId = uploadVideo(token, TITLE_KEYWORD + " 换源", mp4Seconds(3), "old.mp4", "video/mp4");
        assertThat(durationInDb(contentId)).isEqualTo(3);

        MultiValueMap<String, Object> form = newForm();
        addFilePart(form, "file", resource(mp4Seconds(9), "new.mp4"), "video/mp4");
        ResponseEntity<String> resp = putMultipart("/api/v1/contents/" + contentId + "/media?type=1&sort=1", form, token);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(durationInDb(contentId))
                .as("★ 只在上传时解析会留'时长永远停在旧值'的显式不一致 ⇒ 换源必须重解析")
                .isEqualTo(9);
        assertThat(Envelope.data(get("/search/IdSearch?contentId=" + contentId, null))
                .path("durationSeconds").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("★换封面（type=3）不得清空视频行的时长")
    void 换封面不清空视频时长() {
        String token = registerAndGetToken("13800000207", "dur-cover", PASSWORD);
        long contentId = uploadVideo(token, TITLE_KEYWORD + " 换封面", mp4Seconds(5), "v.mp4", "video/mp4");

        MultiValueMap<String, Object> form = newForm();
        addFilePart(form, "file", pngBytes(), "image/png");
        ResponseEntity<String> resp = putMultipart("/api/v1/contents/" + contentId + "/media?type=3&sort=1", form, token);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(durationInDb(contentId))
                .as("★ 换封面走的是另一行；无条件 updateMediaDuration 会把视频行时长写成 null")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("换源成坏文件（type=1）：时长回到未知 null（不保留旧时长——旧时长配新视频会算错百分比）")
    void 换源成坏文件时长回到未知() {
        String token = registerAndGetToken("13800000208", "dur-replace-bad", PASSWORD);
        long contentId = uploadVideo(token, TITLE_KEYWORD + " 换坏", mp4Seconds(6), "v.mp4", "video/mp4");

        MultiValueMap<String, Object> form = newForm();
        addFilePart(form, "file", new ByteArrayResource("garbage".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "garbage.mp4";
            }
        }, "video/mp4");
        ResponseEntity<String> resp = putMultipart("/api/v1/contents/" + contentId + "/media?type=1&sort=1", form, token);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(durationInDb(contentId)).isNull();
    }

    // ==================== 夹具 / oracle ====================

    /** 独立 oracle：直查 media 行（不信接口回显）。 */
    private Integer durationInDb(long contentId) {
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT duration_seconds FROM content_media WHERE content_id = ? AND type = 1",
                Integer.class, contentId);
        assertThat(rows).as("应恰好一条视频媒体行").hasSize(1);
        return rows.get(0);
    }

    private long uploadVideo(String token, String title, byte[] video, String fileName, String contentType) {
        ResponseEntity<String> resp = uploadVideoRaw(token, title, video, fileName, contentType);
        assertThat(resp.getStatusCode().value()).as("上传应成功：" + resp.getBody()).isEqualTo(200);
        return Envelope.num(resp, "contentId");
    }

    private ResponseEntity<String> uploadVideoRaw(String token, String title, byte[] video,
                                                  String fileName, String contentType) {
        MultiValueMap<String, Object> form = newForm();
        addText(form, "title", title);
        addText(form, "categoryId", "1");
        addFilePart(form, "video", resource(video, fileName), contentType);
        addFilePart(form, "cover", pngBytes(), "image/png");
        return postMultipart("/api/v1/contents/videos", form, token);
    }

    /**
     * 手搓一个只含 {@code ftyp + mdat + moov(mvhd)} 的 mp4：{@code timescale=1000}、
     * {@code duration = seconds*1000} ⇒ 解析结果恰为 {@code seconds}。
     * 刻意把 {@code moov} 放在**文件尾**（非 faststart）—— 真实手机导出的文件就是这样。
     */
    private static byte[] mp4Seconds(int seconds) {
        ByteBuffer mvhd = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        mvhd.put((byte) 0).put(new byte[3]);   // version 0 + flags
        mvhd.putInt(0).putInt(0);              // creation / modification
        mvhd.putInt(1000);                     // timescale
        mvhd.putInt(seconds * 1000);           // duration

        byte[] moov = box("moov", box("mvhd", mvhd.array()));
        byte[] ftyp = box("ftyp", "isomiso2avc1mp41".getBytes(StandardCharsets.ISO_8859_1));
        byte[] mdat = box("mdat", new byte[1024]);
        return concat(ftyp, mdat, moov);
    }

    /** 最小 PNG 头（够 `saveFile` 认扩展名 + contentType；内容不参与断言）。 */
    private static Resource pngBytes() {
        return resource(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, "img.png");
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

    private static List<String> keysOf(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }

    private static MultiValueMap<String, Object> newForm() {
        return new LinkedMultiValueMap<>();
    }

    private static void addText(MultiValueMap<String, Object> form, String name, String value) {
        form.add(name, value);
    }

    private static void addFilePart(MultiValueMap<String, Object> form, String name,
                                    Resource resource, String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(contentType));
        form.add(name, new HttpEntity<>(resource, headers));
    }

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
}
