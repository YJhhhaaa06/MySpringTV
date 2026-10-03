package io.github.yjhhhaaa06.videoweb.observability;

import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可观测面测试（第三批 T2）的共用夹具：把**运行期真实日志**搬进断言。
 *
 * <h2>三个 sink，对应三个输出端</h2>
 * <ul>
 *   <li>{@code system} → 根 logger（{@code system.log} 看到的事件：应用日志 / 里程碑）；</li>
 *   <li>{@code access} → {@code access} 专属 logger；</li>
 *   <li>{@code audit} → {@code audit} 专属 logger。</li>
 * </ul>
 * {@code error} 输出端**不单独开 sink**：它挂 root（阈值 ERROR），故 root sink 里
 * ERROR 事件与它同源；"是否真的分了文件"由 {@code LogOutputSplitTests} 读真实文件证明。
 *
 * <h2>★ 为什么要"轮询"而不是"发完请求直接断言"</h2>
 * 访问行写在过滤器链的 {@code finally} 里，而 HTTP 响应可能**先**被客户端读到
 * （Tomcat 提交响应即对端可见，finally 还在后面几微秒）。故本夹具一律用
 * {@link #awaitAccessLine} / {@link #awaitSystemLine} 轮询到超时——老 pytest 也是这么做的
 * （那里的注释同样写着"响应先于落盘"）。
 */
abstract class AbstractObservabilityTest extends AbstractHttpIntegrationTest {

    /** 访问行里的 16 位 hex 请求标识。 */
    protected static final Pattern REQ_PATTERN = Pattern.compile("req=([0-9a-f]{16})");

    private static final Pattern COST_PATTERN = Pattern.compile("cost=(\\d+)ms");

    /** 轮询上限：本地请求毫秒级，3s 是"远大于必要"的安全值（超时即真失败）。 */
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(3);

    protected LogCapture system;
    protected LogCapture access;
    protected LogCapture audit;

    @BeforeEach
    void attachLogCaptures() {
        system = LogCapture.root();
        access = LogCapture.access();
        audit = LogCapture.audit();
        clearLogs();
    }

    @AfterEach
    void detachLogCaptures() {
        // 不 detach 会让 appender 跨测试类累积（logger 是 JVM 级单例）
        closeQuietly(audit);
        closeQuietly(access);
        closeQuietly(system);
    }

    private static void closeQuietly(LogCapture capture) {
        if (capture != null) {
            capture.close();
        }
    }

    /** 清空三个 sink（每个"动作"前调用；日志是全局资源，不清必然串味）。 */
    protected void clearLogs() {
        system.clear();
        access.clear();
        audit.clear();
    }

    protected List<String> systemLines() {
        return system.lines();
    }

    protected List<String> accessLines() {
        return access.lines();
    }

    protected List<String> auditLines() {
        return audit.lines();
    }

    /** 从 any 行里找第一个含 needle 的；没有返回 null（供"不存在"断言）。 */
    protected static String firstLineContaining(List<String> lines, String needle) {
        return lines.stream().filter(line -> line.contains(needle)).findFirst().orElse(null);
    }

    /** 轮询访问行：命中 {@code path=<pathMarker>} 且含 {@code marker} 的那一行。 */
    protected String awaitAccessLine(String pathMarker, String marker) {
        return access.awaitLine(
                line -> line.contains(" path=" + pathMarker + " ") && line.contains(marker),
                "access 行 path=" + pathMarker + " 且含 " + marker,
                AWAIT_TIMEOUT);
    }

    /** 轮询应用日志行（根 logger）：命中含 {@code marker} 的那一行。 */
    protected String awaitSystemLine(String marker) {
        return system.awaitLine(line -> line.contains(marker), "system 行含 " + marker, AWAIT_TIMEOUT);
    }

    /** 取访问行的请求标识；没有 req 字段即断言失败（16 位 hex 是契约）。 */
    protected static String reqIdOf(String line) {
        Matcher matcher = REQ_PATTERN.matcher(line);
        assertThat(matcher.find()).as("日志行必须带 req=<16 位 hex>: %s", line).isTrue();
        return matcher.group(1);
    }

    /** 取访问行的耗时（毫秒）。 */
    protected static long costOf(String line) {
        Matcher matcher = COST_PATTERN.matcher(line);
        assertThat(matcher.find()).as("访问行必须带 cost=<n>ms: %s", line).isTrue();
        return Long.parseLong(matcher.group(1));
    }
}
