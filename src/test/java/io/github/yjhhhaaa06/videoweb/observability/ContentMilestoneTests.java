package io.github.yjhhhaaa06.videoweb.observability;

import io.github.yjhhhaaa06.videoweb.content.AbstractContentIntegrationTest;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentMediaDao;
import io.github.yjhhhaaa06.videoweb.content.model.dto.MediaManifest;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import io.github.yjhhhaaa06.videoweb.support.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * 内容里程碑 + **{@code AFTER_COMMIT} 机制的存在证明**（第三批 T2 / 账 B12）。
 *
 * <h2>为什么单列一个类（而不是并进 {@code MilestoneLogTests}）</h2>
 * 内容里程碑的正确性**全在时序上**：{@code addVideo}/{@code addPost}/{@code deleteContent}
 * 都是 {@code @Transactional}，若把日志写在方法体里，"提交失败"会留下一条假成功行。
 * 本类用**成对**用例把这条时序钉死：
 *
 * <pre>
 * {@link #发布与删除各在提交后落一条里程碑()}  正常提交 → 里程碑出现
 * {@link #事务回滚后不产生里程碑()}            注入异常 → 回滚 → 里程碑**一条都没有** + 数据也确实没落库
 * </pre>
 *
 * 第二个用例是 {@code AFTER_COMMIT} 的**唯一存在证明**：把日志改回事务内直写，
 * 它会立刻变红（那两种实现在"正常提交"用例下都会过）。
 * 与 {@code LikeCacheTests.事务回滚后缓存未被污染} 是同一族手法（S3 立下的规矩）。
 *
 * <p>断言不需要轮询：{@code AFTER_COMMIT} 监听器在**提交线程同步执行**，
 * 故 {@code addVideo(...)} 返回时日志行已经落在 sink 里。
 */
@Tag("observability")
class ContentMilestoneTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13800009301";
    private static final String AUTHOR = "obs-mile-author";

    @Autowired
    private ContentService contentService;

    /** 用于制造"事务内后续语句失败" ⇒ 回滚 ⇒ 里程碑不得产生。 */
    @MockitoSpyBean
    private ContentMediaDao contentMediaDao;

    /**
     * 清单校验会检查"文件真实存在" ⇒ 为固定 URL 写真实素材（而不是再 spy 一个
     * {@code ContentMediaManifestService}）：少一个 spy 就少一种 Spring 上下文，
     * 避免全量回归时测试上下文缓存被挤爆（本仓 spy 组合已接近缓存上限）。
     */
    @BeforeEach
    void writeMediaFiles() throws IOException {
        Path root = Path.of(testMediaRoot());
        write(root, "/upload/video/obs-mile.mp4", mp4Seconds(12));
        write(root, "/upload/video/rollback.mp4", mp4Seconds(1));
        write(root, "/upload/cover/obs-mile.png", pngBytes());
        write(root, "/upload/cover/obs-mile-post.png", pngBytes());
        write(root, "/upload/cover/rollback.png", pngBytes());
        write(root, "/upload/image/obs-mile-post_1.jpg", pngBytes());
    }

    @Test
    @DisplayName("添加视频 / 添加动态 / 删除内容 各在提交后落恰好一条 INFO 里程碑")
    void 发布与删除各在提交后落一条里程碑() {
        long authorId = authorId();

        try (LogCapture system = LogCapture.root()) {
            system.clear();
            long videoId = contentService.createVideo(authorId, "obs-mile-video", "d", 1,
                    new MediaManifest("/upload/video/obs-mile.mp4", "/upload/cover/obs-mile.png", null));
            String videoLine = lineContaining(system.lines(), "msg=添加视频成功, contentId=" + videoId);
            assertThat(videoLine).contains("level=INFO")
                    .contains("logger=io.github.yjhhhaaa06.videoweb.content.event.ContentMilestoneListener");
            assertThat(countContaining(system.lines(), "msg=添加视频成功, contentId=" + videoId)).isEqualTo(1);

            system.clear();
            long postId = contentService.createPost(authorId, "obs-mile-post", "d", 2,
                    new MediaManifest(null, "/upload/cover/obs-mile-post.png",
                            List.of("/upload/image/obs-mile-post_1.jpg")));
            assertThat(lineContaining(system.lines(), "msg=添加动态成功, contentId=" + postId))
                    .contains("level=INFO");

            system.clear();
            contentService.deleteContent(videoId, authorId);
            assertThat(lineContaining(system.lines(), "msg=删除内容成功, contentId=" + videoId))
                    .contains("level=INFO");
        }
    }

    @Test
    @DisplayName("★ 事务回滚后不产生里程碑（AFTER_COMMIT 的存在证明）")
    void 事务回滚后不产生里程碑() {
        long authorId = authorId();
        // 让「建内容行之后的第二条媒体行插入」失败 ⇒ 整个 addVideo 事务回滚。
        // 借 SpyBean 而不是真造 DB 故障：故障点要**可复现且落在事务内**。
        doThrow(new IllegalStateException("模拟提交前失败"))
                .when(contentMediaDao).addMedia(anyLong(), anyString(), anyInt(), anyInt(), any());

        int contentRowsBefore = countContentRows();

        try (LogCapture system = LogCapture.root()) {
            system.clear();
            assertThatThrownBy(() -> contentService.createVideo(authorId, "obs-mile-rollback", "d", 1,
                    new MediaManifest("/upload/video/rollback.mp4", "/upload/cover/rollback.png", null)))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(countContaining(system.lines(), "msg=添加视频成功"))
                    .as("回滚 ⇒ AFTER_COMMIT 监听器根本不执行 ⇒ 不得有里程碑")
                    .isZero();
        }

        // 先证"事务真的回滚了"，否则上面那条负向断言可能是空过
        assertThat(countContentRows()).as("内容行不得落库").isEqualTo(contentRowsBefore);
    }

    private long authorId() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR, PASSWORD);
        return userIdOf(AUTHOR_PHONE);
    }

    private static String lineContaining(List<String> lines, String needle) {
        return lines.stream().filter(line -> line.contains(needle)).findFirst()
                .orElseThrow(() -> new AssertionError("应存在含 " + needle + " 的日志行；实得:\n"
                        + String.join("\n", lines)));
    }

    private static long countContaining(List<String> lines, String needle) {
        return lines.stream().filter(line -> line.contains(needle)).count();
    }

    /** 测试媒体根 + URL（{@code /upload/{dir}/{file}} → {@code root/{dir}/{file}}）。 */
    private static void write(Path root, String url, byte[] bytes) throws IOException {
        Path target = root;
        for (String segment : url.substring("/upload/".length()).split("/")) {
            target = target.resolve(segment);
        }
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    /** 手搓 mp4（{@code timescale=1000}，时长 = 秒数）：只满足"文件真实存在 + 能解析时长"。 */
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
