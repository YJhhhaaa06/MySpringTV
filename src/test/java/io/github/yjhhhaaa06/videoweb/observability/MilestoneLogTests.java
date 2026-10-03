package io.github.yjhhhaaa06.videoweb.observability;

import io.github.yjhhhaaa06.videoweb.support.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 里程碑日志端到端（第三批 T2 / 账 B12）——翻译老项目 {@code test_milestone_log.py}（9 条）的**意图**。
 *
 * <h2>三条判据（就是那张"注入 → 观察"表的原话）</h2>
 * <ol>
 *   <li><b>成功发 INFO 里程碑</b>，且**恰好一条**（多一条说明"同一动作记了两处"）；</li>
 *   <li><b>失败不发</b>——注册/登录失败路径不得留成功痕迹；</li>
 *   <li><b>预期拒绝是 WARN 不是 ERROR</b>，且**不进 error 输出端**（"把可预期输入错误报成运维事故"
 *       是 TV 时代修过的口径）。</li>
 * </ol>
 *
 * <h2>里程碑只在 {@code system} 输出端</h2>
 * {@link #里程碑不写向access与audit输出端()} 断言它。注意这是**结构性**的：
 * {@code access}/{@code audit} 两个 logger 是 {@code additivity=false}，
 * 所以挂 root 的捕获器根本看不到它们的事件——这正是"分流靠机制而非过滤"的体现。
 *
 * <h2>覆盖的是"7 个补点"里的 user 侧 2 点</h2>
 * 内容侧 3 点（添加视频 / 添加动态 / 删除内容）在
 * {@code observability.ContentMilestoneTests}（那条需要 DB 夹具与回滚注入）。
 * 关系侧 2 点（关注 / 取关）在 S4 已存在并有各自用例，本任务只把它们纳入"文件级零重叠"的扫描。
 */
@Tag("observability")
class MilestoneLogTests extends AbstractObservabilityTest {

    private static final String PASSWORD = "abc123456";

    @Test
    @DisplayName("注册成功恰好一条 INFO 里程碑，且只记 userId（手机号/密码不落盘）")
    void 注册成功发一条INFO里程碑() {
        String phone = "13800009201";
        clearLogs();

        registerAndGetToken(phone, "obs-mile-reg", PASSWORD);
        long userId = userIdOf(phone);

        String line = awaitSystemLine("msg=用户注册成功, userId=" + userId);
        assertThat(line).contains("level=INFO");
        assertThat(line)
                .as("里程碑由业务类自己记（不是异常出口代记）")
                .contains("logger=io.github.yjhhhaaa06.videoweb.user.service.UserService");
        assertThat(line).contains("req=");
        assertThat(line).as("只记 userId，账号（手机号）不落盘").doesNotContain(phone);
        assertThat(countContaining(systemLines(), "msg=用户注册成功, userId=" + userId))
                .as("同一动作只能有一条里程碑")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("登录成功恰好一条 INFO 里程碑")
    void 登录成功发一条INFO里程碑() {
        String phone = "13800009202";
        registerAndGetToken(phone, "obs-mile-login", PASSWORD);
        long userId = userIdOf(phone);
        clearLogs();

        ResponseEntity<String> response = post("/user/login",
                Map.of("phone", phone, "password", PASSWORD), null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        String line = awaitSystemLine("msg=登录成功, userId=" + userId);
        assertThat(line).contains("level=INFO").contains("req=");
        assertThat(line).doesNotContain(phone);
        assertThat(countContaining(systemLines(), "msg=登录成功, userId=" + userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 失败不发里程碑：密码错误只记 WARN 结论行，且不产生 ERROR")
    void 登录失败不发里程碑且记WARN() {
        String phone = "13800009203";
        registerAndGetToken(phone, "obs-mile-fail", PASSWORD);
        clearLogs();

        ResponseEntity<String> response = post("/user/login",
                Map.of("phone", phone, "password", "wrong-password"), null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);

        // 先证"请求确实发生了、日志确实写了"，否则下面的"没有"可能是空过
        String rejection = awaitSystemLine("msg=业务异常");
        assertThat(rejection).contains("level=WARN");
        assertThat(rejection).as("可预期拒绝必须落在 WARN，不得是 ERROR").doesNotContain("level=ERROR");
        // ★ 结论行**不带堆栈**（TV LOG_CONVENTION「包装点即源头」）：可预期拒绝不该占排障现场的篇幅。
        //   `%maskedMsg` 把堆栈渲染进**同一条**记录（内嵌换行），故用"无内嵌换行 / 无 at 行"判定。
        assertThat(rejection)
                .as("可预期拒绝的结论行不得带堆栈（否则与 ERROR 的现场价值无法区分）")
                .doesNotContain("\n")
                .doesNotContain("\tat ");

        List<String> lines = systemLines();
        assertThat(countContaining(lines, "msg=登录成功")).as("失败路径不得留成功里程碑").isZero();
        assertThat(lines.stream().filter(l -> l.contains("level=ERROR")).toList())
                .as("可预期拒绝不得进 ERROR（否则会落 error 输出端、被当成运维事故）")
                .isEmpty();
    }

    @Test
    @DisplayName("首次登录失败（用户不存在）同样不发里程碑")
    void 用户不存在不发里程碑() {
        clearLogs();
        ResponseEntity<String> response = post("/user/login",
                Map.of("phone", "13800009299", "password", PASSWORD), null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);

        awaitSystemLine("msg=业务异常");
        assertThat(countContaining(systemLines(), "msg=登录成功")).isZero();
    }

    @Test
    @DisplayName("★ 文件级：里程碑行只出现在 system.log（access / audit / error 三个文件里都没有）")
    void 里程碑只落system文件() {
        String phone = "13800009204";
        registerAndGetToken(phone, "obs-mile-split", PASSWORD);
        // 等访问行一并落盘，保证下面读文件时本轮的三条记录都已写出
        awaitSystemLine("msg=用户注册成功");
        awaitAccessLine("/user/register", "status=200");

        String milestoneFingerprint = "msg=用户注册成功";
        assertThat(structuredLines(LogCapture.systemFile()))
                .as("system.log 必须真的有这条里程碑，否则下面的零命中是空过")
                .anyMatch(line -> line.contains(milestoneFingerprint));

        // ⚠️ 这一条必须读**真实文件**，不能用内存 sink：把 appender 挂在 access/audit logger 上
        //    是收不到 UserService 的日志的（additivity 只向上冒泡）⇒ 那种写法**恒真、永不可能变红**。
        //    文件级断言才可证伪：只要有人把 root 也挂到 access/audit 的输出端，这里就红。
        for (Path file : List.of(LogCapture.accessFile(), LogCapture.auditFile(), LogCapture.errorFile())) {
            assertThat(structuredLines(file).stream()
                    .filter(line -> line.contains(milestoneFingerprint)).toList())
                    .as("%s 泄漏了里程碑行（里程碑只该落 system）", file)
                    .isEmpty();
        }
    }

    /** 文件里的结构化记录行（以 {@code ts=} 开头；异常堆栈续行不算记录）。 */
    private static List<String> structuredLines(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith("ts="))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long countContaining(List<String> lines, String needle) {
        return lines.stream().filter(line -> line.contains(needle)).count();
    }
}
