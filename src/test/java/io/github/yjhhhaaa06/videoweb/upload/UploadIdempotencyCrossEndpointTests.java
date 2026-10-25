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

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上传两条端点的**键空间隔离**（`T69` 结论；矩阵本体在 {@link AbstractUploadIdempotencyTests} 的两个子类）。
 *
 * <p>钉住一条容易写错的事实：同一 {@code clientKey} 打到 {@code videos} 与 {@code posts}
 * ⇒ **各建一条**，而不是 422。
 *
 * <h2>★ 为什么不是 422（`T69` 任务卡的原判据在本实现下不成立）</h2>
 * key = {@code idem:{slug}:{uid}:{clientKey}}，两条端点的 slug 不同（{@code video} / {@code post}）
 * ⇒ 同一个 clientKey 天然落在**两个不同的 Redis 键**上，{@code ACQUIRE} 各占各的，
 * 指纹**连比对的机会都没有**。跨端点串味是被**键命名空间**挡住的 —— 比 422 更强
 * （连占位都不共享）。若有人把两条端点的 slug 合并成同一个，本用例会立刻变红。
 */
class UploadIdempotencyCrossEndpointTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";
    private static final String PHONE = "13800009311";

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
        assertThat(idemKeys()).as("进门必须没有任何 idem:* 残留").isEmpty();
    }

    @Test
    @DisplayName("跨端点同 key：videos 与 posts 各建一条（key 空间按 slug 隔离 ⇒ 路径级指纹在这两条端点之间不会相遇）")
    void 跨端点同key_各建一条() {
        String token = registerAndGetToken(PHONE, "t68_cross", PASSWORD);
        String key = clientKey("cross-endpoint");

        ResponseEntity<String> video = postMultipart("/api/v1/contents/videos", videoForm(), token, idemKey(key));
        assertThat(video.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(video.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("首次请求不带重放标记").isNull();
        long videoContentId = Envelope.num(video, "contentId");

        ResponseEntity<String> post = postMultipart("/api/v1/contents/posts", postForm(), token, idemKey(key));

        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("换了端点 ⇒ 必须是新执行，不是重放").isNull();
        assertThat(Envelope.num(post, "contentId")).isNotEqualTo(videoContentId);
        assertThat(countContent()).as("两条端点各建一条").isEqualTo(2);

        assertThat(idemKeys()).as("两条端点各自一个占位键（slug 不同）")
                .anyMatch(k -> k.startsWith("idem:video:"))
                .anyMatch(k -> k.startsWith("idem:post:"));
    }

    // ==================== 夹具 ====================

    private static MultiValueMap<String, Object> videoForm() {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("title", "T68 跨端点视频");
        form.add("description", "T68 cross endpoint");
        form.add("categoryId", "1");
        addFilePart(form, "video", "test_video.mp4", "video/mp4");
        addFilePart(form, "cover", "test_cover.png", "image/png");
        return form;
    }

    private static MultiValueMap<String, Object> postForm() {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("title", "T68 跨端点图文");
        form.add("categoryId", "0");
        return form;
    }

    private static void addFilePart(MultiValueMap<String, Object> form, String name,
                                    String fileName, String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(contentType));
        form.add(name, new HttpEntity<>(new FileSystemResource(testResource(fileName)), headers));
    }

    private static String clientKey(String tag) {
        return "t68." + tag + "." + UUID.randomUUID();
    }

    private static HttpHeaders idemKey(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(IdempotencyFilter.HEADER_KEY, key);
        return headers;
    }

    private Set<String> idemKeys() {
        Set<String> keys = redis.keys("idem:*");
        return keys == null ? Set.of() : keys;
    }

    private long countContent() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content", Long.class);
        return n == null ? 0 : n;
    }
}
