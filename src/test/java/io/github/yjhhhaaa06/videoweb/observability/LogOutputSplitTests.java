package io.github.yjhhhaaa06.videoweb.observability;

import io.github.yjhhhaaa06.videoweb.support.LogCapture;
import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * **文件级**分流证明（第三批 T2 / 账 B12）——翻译老项目 {@code test_log_outputs.py}（5 条）的意图。
 *
 * <h2>为什么必须读真实文件</h2>
 * 内存 appender 只能证明"事件走了哪个 logger"，证明不了"**真的落进了哪个文件**"——
 * 后者取决于 appender 装配与 {@code additivity}，是配置的另一半。老 pytest 的口头禅是
 * "装配层的单测只到装配层，真实分流只能由读文件断言"，本类就是那一层。
 * （{@code audit.log} 的分流由 {@code AuditLogTests} 断言——那里才有管理端夹具去触发审计动作。）
 *
 * <h2>★ 负向断言的自证：用访问行当"写入完成"的信号</h2>
 * "error.log 里没有这条 req" 这种断言很容易**空过**（写还没落盘就查）。本类的做法是：
 * 先等到该请求的**访问行**——访问行写在过滤器链的 {@code finally}，
 * **晚于**处理期内的 WARN/ERROR 落盘 ⇒ 看到访问行 = 该请求的所有应用日志都已写完。
 * 这比 sleep 可靠，也比"再看一遍 system.log"更直接。
 */
@Tag("observability")
class LogOutputSplitTests extends AbstractObservabilityTest {

    private static final String ACCESS_LOGGER_MARK = " logger=access ";

    /** ERROR 路径的确定触发点（见 {@link #error只收ERROR()}）。 */
    @MockitoSpyBean
    private UserDao userDao;

    @BeforeEach
    void resetSpies() {
        reset(userDao);
    }

    @Test
    @DisplayName("全文件不变式：access 行只落 access.log，且不泄进 system.log")
    void access与system互不污染() {
        clearLogs();
        get("/start", null);
        awaitAccessLine("/start", "status=200");

        List<String> accessLines = structuredLines(LogCapture.accessFile());
        List<String> systemLines = structuredLines(LogCapture.systemFile());

        assertThat(accessLines).as("access.log 必须已有结构化行，否则本断言无从证伪").isNotEmpty();
        assertThat(systemLines).as("system.log 必须已有结构化行").isNotEmpty();

        assertThat(accessLines)
                .as("access.log 只应有访问行")
                .allMatch(line -> line.contains(ACCESS_LOGGER_MARK));
        assertThat(systemLines.stream().filter(line -> line.contains(ACCESS_LOGGER_MARK)).toList())
                .as("访问行泄进了 system.log（additivity 未生效？）")
                .isEmpty();
    }

    @Test
    @DisplayName("error.log 只收 ERROR，且本请求的 ERROR 确实落在里面")
    void error只收ERROR() {
        String token = registerAndGetToken("13800009801", "obs-split-error", "abc123456");
        doThrow(new DataIntegrityViolationException("boom: 模拟写库失败"))
                .when(userDao).updateUsername(anyLong(), anyString());
        clearLogs();
        ResponseEntity<String> failure = post(
                "/user/changeUserName?userName=obs-split-error-probe", null, token);
        assertThat(failure.getStatusCode().value()).isEqualTo(500);
        String requestId = reqIdOf(awaitAccessLine("/user/changeUserName", "status=500"));

        List<String> errorLines = structuredLines(LogCapture.errorFile());

        assertThat(errorLines).as("error.log 必须有内容（本请求已产生 ERROR）").isNotEmpty();
        assertThat(errorLines.stream().filter(line -> !line.contains(" level=ERROR ")).toList())
                .as("error.log 出现非 ERROR 行（阈值过滤器失效？）")
                .isEmpty();
        assertThat(errorLines.stream().filter(line -> line.contains("req=" + requestId + " ")).toList())
                .as("本请求的 ERROR 应在 error.log 里（同一 req 可串联）")
                .isNotEmpty();
    }

    @Test
    @DisplayName("★ 可预期拒绝（WARN）不进 error.log；未处理异常（ERROR）进 error.log")
    void 拒绝与异常的分流() {
        String token = registerAndGetToken("13800009802", "obs-split-user", "abc123456");

        // ① 可预期拒绝：/comment/show 缺 contentId ⇒ ParamException ⇒ 400 + WARN
        clearLogs();
        ResponseEntity<String> rejection = get("/comment/show", null);
        assertThat(rejection.getStatusCode().value()).isEqualTo(400);
        String rejectionReq = reqIdOf(awaitAccessLine("/comment/show", "status=400"));
        assertThat(awaitSystemLine("req=" + rejectionReq + " "))
                .contains("level=WARN")
                .contains("msg=业务异常");

        // ② 未处理异常：打桩让写库失败 ⇒ DataAccessException ⇒ 500 + ERROR（带堆栈）
        doThrow(new DataIntegrityViolationException("boom: 模拟写库失败"))
                .when(userDao).updateUsername(anyLong(), anyString());
        clearLogs();
        ResponseEntity<String> failure = post(
                "/user/changeUserName?userName=obs-split-reject-probe", null, token);
        assertThat(failure.getStatusCode().value()).isEqualTo(500);
        String failureReq = reqIdOf(awaitAccessLine("/user/changeUserName", "status=500"));

        List<String> errorLines = structuredLines(LogCapture.errorFile());
        assertThat(errorLines.stream().filter(line -> line.contains("req=" + rejectionReq + " ")).toList())
                .as("可预期拒绝（WARN）不得落 error.log")
                .isEmpty();
        assertThat(errorLines.stream().filter(line -> line.contains("req=" + failureReq + " ")).toList())
                .as("未处理异常必须有一条 ERROR 记录落 error.log（同 req 可串联）")
                .isNotEmpty();
    }

    /** 文件里的结构化记录行（以 {@code ts=} 开头；异常堆栈续行不算记录）。 */
    private static List<String> structuredLines(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> line.startsWith("ts="))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
