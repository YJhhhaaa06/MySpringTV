package io.github.yjhhhaaa06.videoweb.upload;

import io.github.yjhhhaaa06.videoweb.common.idempotency.IdempotencyFilter;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上传两条端点的**幂等冒烟**（写接口幂等专项 `T66`；全矩阵归 `T68`）。
 *
 * <p>只钉两件事：同 key 重发时 {@code content} 行数只 +1、第二次带 {@code Idempotent-Replayed}。
 * "无新文件落盘"的**全量**断言（含文件计数、媒体行、孤儿清理）留给 `T68`——这里的 multipart
 * 造法照 {@code UploadFlowTests} 现有形态（{@code MultiValueMap} + {@code HttpEntity<Resource>}，
 * 不用 {@code MultipartBodyBuilder}：它引用 Reactive 的 {@code Publisher}，本仓没有 WebFlux 依赖）。
 *
 * <h2>为什么会话里能"第二个请求不落盘"</h2>
 * 幂等过滤器排在 {@code DispatcherServlet} 之前 ⇒ 命中 {@code DONE} 时**直接重放快照**，
 * multipart 根本没被解析。这条冒烟同时是"链序位置正确"的一个可观察证据：
 * 若把机制挪进 Controller/AOP，第二个请求会先落盘再幂等，{@code content} 行数就会是 2。
 *
 * <h2>multipart 没有指纹（刻意的）</h2>
 * 过滤器只对 {@code application/json} 包装 request；multipart 不包装（避免把整个文件读进内存），
 * 因此 {@code Snapshot#fingerprint} 为空 ⇒ 重放路径**不比对载荷**。这不是缺陷，是已登记的取舍
 * （见 {@code architecture/tech/idempotency/写接口幂等.md} §九）。
 */
class UploadIdempotencySmokeTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";
    private static final String PHONE = "13800009101";
    private static final String OTHER_PHONE = "13800009102";

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void resetUploadState() {
        jdbcTemplate.update("DELETE FROM content_media");
        jdbcTemplate.update("DELETE FROM content");
        Set<String> keys = redis.keys("idem:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("上传视频：同 key 重发 ⇒ content 行只 +1、第二次带 Idempotent-Replayed；另一用户同 key 独立建一条")
    void 上传视频_同key重发只建一条内容() {
        String token = registerAndGetToken(PHONE, "idem-video", PASSWORD);
        String otherToken = registerAndGetToken(OTHER_PHONE, "idem-video-other", PASSWORD);
        long authorId = userIdOf(PHONE);
        String key = clientKey("video");

        ResponseEntity<String> first = postMultipart("/api/v1/contents/videos", videoForm(authorId), token, idemKey(key));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("首次响应不带重放标记").isNull();
        long contentId = Envelope.num(first, "contentId");
        assertThat(contentId).isPositive();

        ResponseEntity<String> second = postMultipart("/api/v1/contents/videos", videoForm(authorId), token, idemKey(key));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED)).isEqualTo("true");
        assertThat(second.getBody()).as("重放逐字节相同（含同一个 contentId）").isEqualTo(first.getBody());
        assertThat(Envelope.num(second, "contentId")).isEqualTo(contentId);

        // 独立 oracle：行数直查 DB（不信接口回显）
        assertThat(countContent()).as("同 key 两次只落一条 content").isEqualTo(1);
        assertThat(countMedia(contentId)).as("视频 + 封面两条媒体行").isEqualTo(2);

        // ★ 对照：另一个用户持同一个 clientKey ⇒ 独立的一条内容（Redis key 里含 uid）
        ResponseEntity<String> other = postMultipart("/api/v1/contents/videos", videoForm(userIdOf(OTHER_PHONE)),
                otherToken, idemKey(key));

        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(other.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("另一个用户命中别人的快照 = key 里漏了 uid").isNull();
        assertThat(Envelope.num(other, "contentId")).isNotEqualTo(contentId);
        assertThat(countContent()).isEqualTo(2);
    }

    @Test
    @DisplayName("上传图文：同 key 重发 ⇒ content 行只 +1、第二次带 Idempotent-Replayed")
    void 上传图文_同key重发只建一条内容() {
        String token = registerAndGetToken(PHONE, "idem-post", PASSWORD);
        String key = clientKey("post");

        ResponseEntity<String> first = postMultipart("/api/v1/contents/posts", postForm(), token, idemKey(key));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        long contentId = Envelope.num(first, "contentId");
        assertThat(contentId).isPositive();

        ResponseEntity<String> second = postMultipart("/api/v1/contents/posts", postForm(), token, idemKey(key));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED)).isEqualTo("true");
        assertThat(Envelope.num(second, "contentId")).isEqualTo(contentId);
        assertThat(countContent()).as("同 key 两次只落一条 content").isEqualTo(1);
        assertThat(countMedia(contentId)).as("零媒体也允许发布（旧版口径）").isZero();
    }

    // ==================== multipart 夹具（同 UploadFlowTests 形态） ====================

    private MultiValueMap<String, Object> videoForm(long authorId) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("title", "T66 幂等视频 " + authorId);
        form.add("description", "T66 idempotency smoke");
        form.add("categoryId", "1");
        addFilePart(form, "video", "test_video.mp4", "video/mp4");
        addFilePart(form, "cover", "test_cover.png", "image/png");
        return form;
    }

    private MultiValueMap<String, Object> postForm() {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("title", "T66 幂等图文");
        form.add("categoryId", "0");
        return form;
    }

    /** 文件 part：文件名取自素材，contentType 显式指定（对齐旧 pytest 的 files 元组）。 */
    private static void addFilePart(MultiValueMap<String, Object> form, String name,
                                    String fileName, String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(contentType));
        form.add(name, new HttpEntity<>(new FileSystemResource(testResource(fileName)), headers));
    }

    private ResponseEntity<String> postMultipart(String path, MultiValueMap<String, Object> form,
                                                 String token, HttpHeaders extraHeaders) {
        return send(spec -> {
            RestClient.RequestBodySpec request = spec.uri(path).contentType(MediaType.MULTIPART_FORM_DATA);
            if (token != null) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            if (extraHeaders != null && !extraHeaders.isEmpty()) {
                request = request.headers(headers -> extraHeaders.forEach(headers::addAll));
            }
            return request.body(form);
        });
    }

    private static String clientKey(String tag) {
        return "t66." + tag + "." + UUID.randomUUID();
    }

    private static HttpHeaders idemKey(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(IdempotencyFilter.HEADER_KEY, key);
        return headers;
    }

    private long countContent() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content", Long.class);
        return n == null ? 0 : n;
    }

    private long countMedia(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_media WHERE content_id = ?", Long.class, contentId);
        return n == null ? 0 : n;
    }
}
