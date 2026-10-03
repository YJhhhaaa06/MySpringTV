package io.github.yjhhhaaa06.videoweb.observability;

import io.github.yjhhhaaa06.videoweb.content.AbstractContentIntegrationTest;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentMediaDao;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import io.github.yjhhhaaa06.videoweb.support.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    @DisplayName("添加视频 / 添加动态 / 删除内容 各在提交后落恰好一条 INFO 里程碑")
    void 发布与删除各在提交后落一条里程碑() {
        long authorId = authorId();

        try (LogCapture system = LogCapture.root()) {
            system.clear();
            long videoId = contentService.addVideo(authorId, "obs-mile-video", "d", 1,
                    "/upload/video/obs-mile.mp4", "/upload/cover/obs-mile.png");
            String videoLine = lineContaining(system.lines(), "msg=添加视频成功, contentId=" + videoId);
            assertThat(videoLine).contains("level=INFO")
                    .contains("logger=io.github.yjhhhaaa06.videoweb.content.event.ContentMilestoneListener");
            assertThat(countContaining(system.lines(), "msg=添加视频成功, contentId=" + videoId)).isEqualTo(1);

            system.clear();
            long postId = contentService.addPost(authorId, "obs-mile-post", "d", 2,
                    "/upload/cover/obs-mile-post.png", List.of("/upload/image/obs-mile-post_1.jpg"));
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
                .when(contentMediaDao).addMedia(anyLong(), anyString(), anyInt(), anyInt());

        int contentRowsBefore = countContentRows();

        try (LogCapture system = LogCapture.root()) {
            system.clear();
            assertThatThrownBy(() -> contentService.addVideo(authorId, "obs-mile-rollback", "d", 1,
                    "/upload/video/rollback.mp4", "/upload/cover/rollback.png"))
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
}
