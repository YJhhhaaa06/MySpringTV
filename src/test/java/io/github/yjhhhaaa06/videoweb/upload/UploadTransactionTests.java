package io.github.yjhhhaaa06.videoweb.upload;

import io.github.yjhhhaaa06.videoweb.common.config.UploadProperties;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentMediaDao;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * S7 的两条**声明式机制生效证明**（SOP §三 DoD #7）。
 *
 * <h2>为什么单列一个类</h2>
 * DoD #7 的判据是自问「**这个断言的结果还可能由谁产生？**」——若还有别的来源，就还没测到目标机制。
 * 下面两条都刻意排除了"别的来源"，且都需要打桩/直读 Redis，与 {@code UploadFlowTests} 的
 * "纯端到端断言可观察行为"纪律不同类，故分开。
 *
 * <table>
 *   <caption>机制 → 证明</caption>
 *   <tr><th>机制</th><th>证明方式</th></tr>
 *   <tr><td>{@code @Transactional}（addVideo）</td>
 *       <td>媒体插入打桩抛错 ⇒ 断言 content 行**未落库**（回滚），且已落盘文件被补偿删除</td></tr>
 *   <tr><td>{@code @TransactionalEventListener(AFTER_COMMIT)}（缓存 REFRESH）</td>
 *       <td>上传成功后**不读任何接口**，直接查 Redis 里 {@code content:{id}} 已存在
 *           ——排除"读接口触发的 cache-aside 回填"这个替代来源</td></tr>
 * </table>
 */
class UploadTransactionTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";
    private static final String CONTENT_KEY_PREFIX = "content:";

    @Autowired
    private UploadProperties uploadProperties;

    @Autowired
    private StringRedisTemplate redis;

    @MockitoSpyBean
    private ContentMediaDao contentMediaDao;

    @BeforeEach
    void resetState() {
        Mockito.reset(contentMediaDao);
        jdbcTemplate.update("DELETE FROM content_media");
        jdbcTemplate.update("DELETE FROM content");
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("★机制证明：媒体插入失败 ⇒ 整个发布事务回滚（content 行不落库）+ 落盘文件被补偿删除")
    void 发布事务回滚且文件被补偿() throws IOException {
        String token = registerAndGetToken("13800000201", "uploader-tx", PASSWORD);
        long filesBefore = mediaFileCount();

        // 打桩：媒体行插入抛运行时异常（发生在 doAddContent 之后）
        doThrow(new RuntimeException("boom: 模拟媒体插入失败"))
                .when(contentMediaDao).addMedia(anyLong(), anyString(), anyInt(), anyInt());

        MultiValueMap<String, Object> form = newForm();
        form.add("title", "s7_tx_rollback");
        form.add("categoryId", "1");
        addFilePart(form, "video", fixture("test_video.mp4"), "video/mp4");
        addFilePart(form, "cover", fixture("test_cover.png"), "image/png");

        ResponseEntity<String> resp = postMultipart("/api/upload/video", form, token);

        // 1) 对外：非业务异常 → 500
        assertThat(resp.getStatusCode().value()).isEqualTo(500);

        // 2) ★事务机制证明★ content 行必须**不存在**。
        //    若 addVideo 上的 @Transactional 未生效（如自调用绕过代理），content 插入会被自动提交，
        //    此处将查到 1 行 —— 这正是 SOP 坑 12 要防的那种"测试照样绿、事务没开"的失效。
        Integer contentRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content", Integer.class);
        assertThat(contentRows)
                .as("@Transactional 必须真的生效：addMedia 失败要回滚已插入的 content 行")
                .isEqualTo(0);

        // 3) 补偿：本次落盘的两个文件被 Controller 的 catch 删除（落盘与 DB 无法原子的旧口径）
        assertThat(mediaFileCount())
                .as("失败补偿：本次上传落盘的文件必须被 deleteFileQuietly 清掉")
                .isEqualTo(filesBefore);
    }

    @Test
    @DisplayName("★机制证明：上传成功后内容缓存已被 AFTER_COMMIT 的 REFRESH 写入（无需任何读触发）")
    void 上传后缓存已提交后刷新() {
        String token = registerAndGetToken("13800000202", "uploader-cache", PASSWORD);

        MultiValueMap<String, Object> form = newForm();
        form.add("title", "s7_cache_refresh");
        form.add("categoryId", "1");
        addFilePart(form, "video", fixture("test_video.mp4"), "video/mp4");
        addFilePart(form, "cover", fixture("test_cover.png"), "image/png");

        ResponseEntity<String> resp = postMultipart("/api/upload/video", form, token);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        long contentId = Envelope.num(resp, "contentId");

        // ★本文**没有**调用任何读端点（否则 cache-aside 会回填，断言就失去鉴别力）。
        String cached = redis.opsForValue().get(CONTENT_KEY_PREFIX + contentId);
        assertThat(cached)
                .as("AFTER_COMMIT 的 REFRESH 必须已把新内容写入缓存；若为空则说明提交后副作用未触发")
                .isNotNull()
                .contains("s7_cache_refresh");
    }

    // ---------- 夹具 ----------

    private static MultiValueMap<String, Object> newForm() {
        return new LinkedMultiValueMap<>();
    }

    /** 外部目录里的真实素材（见 {@code AbstractIntegrationTest.testResource}）。 */
    private static Resource fixture(String fileName) {
        return new FileSystemResource(testResource(fileName));
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

    private long mediaFileCount() throws IOException {
        Path root = Path.of(uploadProperties.root());
        if (!Files.exists(root)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }
}