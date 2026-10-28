package io.github.yjhhhaaa06.videoweb.upload;

import io.github.yjhhhaaa06.videoweb.common.idempotency.IdempotencyFilter;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/v1/contents} 的**幂等运行期契约**（JSON 清单创建）。
 *
 * <h2>为什么单列</h2>
 * 与 multipart 上传端点不同，JSON body 可被过滤器完整缓存 ⇒ 指纹是 **body 级**：
 * "同 key 换内容" 必须 422（multipart 上只能到路径级，这是已知差异）。
 * 本类钉住四件事：重放同一内容、换内容 422、缺 key 不幂等、失败墓碑可重试。
 *
 * <h2>独立 oracle</h2>
 * 内容行 / 媒体行一律直查 DB，不信接口回显（重放响应可能整段来自 Redis 快照）。
 */
class ContentCreateIdempotencyTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";
    private static final String CLIENT_KEY = "create-video-1";

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM content_media");
        jdbcTemplate.update("DELETE FROM content");
        Set<String> keys = redis.keys("idem:content:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("★同 key 同 body：第二次命中重放（同一 contentId），DB 只有一条内容")
    void 同key同body_重放() {
        String token = registerAndGetToken("13800000501", "idem-create", PASSWORD);
        Map<String, Object> body = videoBody(token);

        ResponseEntity<String> first = postWithKey(body, token, CLIENT_KEY);
        ResponseEntity<String> second = postWithKey(body, token, CLIENT_KEY);

        assertThat(first.getStatusCode().value()).as("%s", first.getBody()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).as("%s", second.getBody()).isEqualTo(200);
        long firstId = Envelope.num(first, "contentId");
        assertThat(Envelope.num(second, "contentId")).as("重放必须返回同一个 contentId").isEqualTo(firstId);
        assertThat(second.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("第二次必须是重放（响应头只在重放时出现）").isEqualTo("true");

        assertThat(countRows("content")).isEqualTo(1);
        assertThat(countRows("content_media")).isEqualTo(2);
    }

    @Test
    @DisplayName("★同 key 不同 body：422，且第二次不得建内容")
    void 同key不同body_422() {
        String token = registerAndGetToken("13800000502", "idem-conflict", PASSWORD);
        Map<String, Object> body = videoBody(token);

        ResponseEntity<String> first = postWithKey(body, token, CLIENT_KEY);
        assertThat(first.getStatusCode().value()).isEqualTo(200);

        Map<String, Object> changed = new LinkedHashMap<>(body);
        changed.put("title", "换了个标题");
        ResponseEntity<String> conflict = postWithKey(changed, token, CLIENT_KEY);

        assertThat(conflict.getStatusCode().value()).as("换 body 必须 422：%s", conflict.getBody())
                .isEqualTo(422);
        assertThat(Envelope.code(conflict)).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(countRows("content")).isEqualTo(1);
    }

    @Test
    @DisplayName("★缺 key：两次相同请求各建一条内容（不是幂等端点）")
    void 缺key_不幂等() {
        String token = registerAndGetToken("13800000503", "idem-missing", PASSWORD);
        // 用**无媒体**的图文：同一 URL 不能同时属于两条内容（跨作品占用校验），
        // 那会把"缺 key 不幂等"的观察混进媒体校验的拒因里。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "POST");
        body.put("title", "缺 key 的两条");
        body.put("description", "d");
        body.put("categoryId", 2);
        body.put("media", new LinkedHashMap<>());

        long firstId = Envelope.num(post("/api/v1/contents", body, token), "contentId");
        long secondId = Envelope.num(post("/api/v1/contents", body, token), "contentId");

        assertThat(secondId).as("缺 key ⇒ 普通请求").isNotEqualTo(firstId);
        assertThat(countRows("content")).isEqualTo(2);
    }

    @Test
    @DisplayName("★失败墓碑：首次 400 后同 key 可用合法 body 重试成功")
    void 失败后同key可重试() {
        String token = registerAndGetToken("13800000504", "idem-retry", PASSWORD);
        Map<String, Object> valid = videoBody(token);
        Map<String, Object> invalid = new LinkedHashMap<>(valid);
        invalid.remove("title");

        ResponseEntity<String> failed = postWithKey(invalid, token, CLIENT_KEY);
        assertThat(failed.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<String> retried = postWithKey(valid, token, CLIENT_KEY);
        assertThat(retried.getStatusCode().value()).as("%s", retried.getBody()).isEqualTo(200);
        assertThat(countRows("content")).as("只应成功建一条").isEqualTo(1);
    }

    // ==================== 夹具 ====================

    private Map<String, Object> videoBody(String token) {
        String videoUrl = upload(token, "video", mp4Seconds(1), "clip.mp4", "video/mp4");
        String coverUrl = upload(token, "cover", pngBytes(), "cover.png", "image/png");

        Map<String, Object> media = new LinkedHashMap<>();
        media.put("videoUrl", videoUrl);
        media.put("coverUrl", coverUrl);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "VIDEO");
        body.put("title", "幂等创建");
        body.put("description", "d");
        body.put("categoryId", 1);
        body.put("media", media);
        return body;
    }

    private ResponseEntity<String> postWithKey(Map<String, Object> body, String token, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(IdempotencyFilter.HEADER_KEY, key);
        return post("/api/v1/contents", body, token, headers);
    }

    private long countRows(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    private String upload(String token, String type, byte[] bytes, String fileName, String contentType) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(contentType));
        form.add("file", new HttpEntity<>(resource(bytes, fileName), headers));
        return Envelope.str(postMultipart("/api/v1/uploads/" + type, form, token, null), "url");
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
