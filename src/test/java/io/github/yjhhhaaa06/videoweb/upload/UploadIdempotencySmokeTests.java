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
 * <h2>multipart 的指纹：<b>仅路径级</b>（`T69` 起）</h2>
 * 过滤器只对 {@code application/json} 包装 request（避免把整个文件读进内存）⇒ multipart 的指纹是
 * {@code SHA-256(method + "\n" + 实际路径)}，<b>不含 body</b>。由此得到两条**都写进断言**的性质：
 * <ul>
 *   <li>{@link #跨端点同key_各建一条()}：换端点时路径不同 ⇒ 指纹不同 —— 但本仓两条上传端点的
 *       {@code slug} 也不同（{@code video} / {@code post}），同一个 clientKey 落在**两个 Redis 键**上，
 *       指纹根本不会被比对 ⇒ 结果是"各建一条"而不是 422。这条用例钉住的是**键空间的隔离**。</li>
 *   <li>{@link #同path换文件_仍是重放_已知代价()}：同 path 换文件 ⇒ 指纹相同 ⇒ **仍是重放**。
 *       这是**有意保留的已知代价**（multipart 没有 body 指纹），见
 *       {@code architecture/tech/idempotency/写接口幂等.md} §十三 第 3 条。</li>
 * </ul>
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

    // ==================== T69：multipart 的路径级指纹 ====================

    @Test
    @DisplayName("跨端点同 key：videos 与 posts 各建一条（key 空间按 slug 隔离 ⇒ 路径级指纹在这两条端点之间不会相遇）")
    void 跨端点同key_各建一条() {
        String token = registerAndGetToken(PHONE, "idem-cross", PASSWORD);
        long authorId = userIdOf(PHONE);
        String key = clientKey("cross-endpoint");

        ResponseEntity<String> video = postMultipart("/api/v1/contents/videos", videoForm(authorId),
                token, idemKey(key));
        assertThat(video.getStatusCode()).isEqualTo(HttpStatus.OK);
        long videoContentId = Envelope.num(video, "contentId");

        ResponseEntity<String> post = postMultipart("/api/v1/contents/posts", postForm(), token, idemKey(key));

        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("换了端点 ⇒ 必须是新执行，不是重放").isNull();
        assertThat(Envelope.num(post, "contentId")).isNotEqualTo(videoContentId);
        assertThat(countContent()).as("两条端点各建一条").isEqualTo(2);

        // ★ 为什么这里不是 422（任务卡 `T69` 场景 3 的原判据在本实现下不成立）：
        //   key = `idem:{slug}:{uid}:{clientKey}`，videos 的 slug 是 `video`、posts 是 `post`
        //   ⇒ 同一个 clientKey 天然落在**两个不同的 Redis 键**上，`ACQUIRE` 各占各的，
        //   指纹**连比对的机会都没有**。跨端点串味是被**键命名空间**挡住的 —— 比 422 更强
        //   （连占位都不共享）。下面两行把这个事实钉住：合并 slug 的实现会在这里变红。
        assertThat(idemKeys()).as("两条端点各自一个占位键（slug 不同）")
                .anyMatch(k -> k.startsWith("idem:video:"))
                .anyMatch(k -> k.startsWith("idem:post:"));
    }

    @Test
    @DisplayName("同 path 换文件 ⇒ 仍是重放（已知代价：multipart 指纹只含 method + 路径，不含 body）")
    void 同path换文件_仍是重放_已知代价() {
        String token = registerAndGetToken(OTHER_PHONE, "idem-swap-file", PASSWORD);
        long authorId = userIdOf(OTHER_PHONE);
        String key = clientKey("swap-file");

        ResponseEntity<String> first = postMultipart("/api/v1/contents/videos",
                videoForm(authorId, "T69 换文件-第一份", "test_cover.png", "image/png"),
                token, idemKey(key));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        long contentId = Envelope.num(first, "contentId");

        // ★ 标题与封面文件都换了 ⇒ multipart body 确实不同。但指纹是**仅路径级**的
        //   ⇒ 第二次仍被判成"同一次请求"。
        ResponseEntity<String> second = postMultipart("/api/v1/contents/videos",
                videoForm(authorId, "T69 换文件-第二份", "test_image.jpg", "image/jpeg"),
                token, idemKey(key));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("★★ 这是**有意保留的已知代价**（不是 bug、也不是漏接线）：multipart 无 body 指纹"
                        + "⇒ 同 path 换文件仍是重放。要识别它必须把上传通路改成流式摘要，"
                        + "读文件进内存更贵 —— 见 {@code architecture/tech/idempotency/写接口幂等.md} §十三 第 3 条")
                .isEqualTo("true");
        assertThat(Envelope.num(second, "contentId"))
                .as("回的是**第一份**那个 contentId（第二份的标题/封面从未生效）")
                .isEqualTo(contentId);
        assertThat(second.getBody()).as("重放逐字节相同").isEqualTo(first.getBody());
        assertThat(countContent()).as("同 key 两次只落一条 content").isEqualTo(1);
    }

    // ==================== multipart 夹具（同 UploadFlowTests 形态） ====================

    private MultiValueMap<String, Object> videoForm(long authorId) {
        return videoForm(authorId, "T66 幂等视频 " + authorId, "test_cover.png", "image/png");
    }

    /**
     * 视频表单：标题与封面可换（`T69` 的"同 path 换文件"用例要造一份**不同的 multipart body**）。
     * 封面换成 {@code test_image.jpg} 仍是合法后缀（{@code UploadType.COVER} 允许 {@code .jpg/.png}）。
     */
    private MultiValueMap<String, Object> videoForm(long authorId, String title,
                                                    String coverFileName, String coverContentType) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("title", title);
        form.add("description", "T66 idempotency smoke");
        form.add("categoryId", "1");
        addFilePart(form, "video", "test_video.mp4", "video/mp4");
        addFilePart(form, "cover", coverFileName, coverContentType);
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

    /** 当前 {@code idem:*} 键（跨端点用例要证明两个端点各占一个键）。 */
    private Set<String> idemKeys() {
        Set<String> keys = redis.keys("idem:*");
        return keys == null ? Set.of() : keys;
    }

    private long countMedia(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_media WHERE content_id = ?", Long.class, contentId);
        return n == null ? 0 : n;
    }
}
