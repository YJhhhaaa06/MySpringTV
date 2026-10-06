package io.github.yjhhhaaa06.videoweb.observability;

import io.github.yjhhhaaa06.videoweb.support.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 脱敏出口（{@code %maskedMsg}）的端到端契约（第三批 T2 / 账 B12 的 ★安全项）。
 *
 * <h2>为什么这条最重要</h2>
 * 老项目 25 条 e2e 里，"注册请求带手机号 ⇒ 日志里搜不到明文手机号"是唯一一条**有真实安全含义**的：
 * 日志会被采集、转发、长期留存，落盘一次的 PII 无法收回。所以它必须有自动化门禁，
 * 而且门禁要打在**输出**上（而不是"我以为调用点都记得脱敏"）。
 *
 * <h2>两层断言</h2>
 * <ol>
 *   <li><b>机制层</b>（{@link #日志出口把手机号掩码_消息与堆栈都掩()}）：往日志里**注入**
 *       一条含手机号的消息与含手机号的异常 ⇒ 观察渲染出的行是掩码形态。
 *       这是可控、确定的"注射-观察"，也是"运行时回显参数"这条真实泄漏路径的等价物。</li>
 *   <li><b>不变式层</b>（{@link #四个输出端的历史行都搜不到明文手机号()}）：
 *       扫描**四个真实文件**的全部历史行（剥掉随机的 {@code req=} 字段，否则 16 位 hex 会偶发命中
 *       手机号形态造成误报——老用例踩过这个坑）⇒ 一行都不许有明文手机号。</li>
 * </ol>
 */
@Tag("observability")
class SensitiveDataTests extends AbstractObservabilityTest {

    private static final Duration AWAIT = Duration.ofSeconds(3);

    /**
     * 长度 ≥16 的连续十六进制串（Docker 容器 id 64 位、 Testcontainers 打印各种 32/64 位 id）。
     * 它们不是 PII，但**包含的十进制子串会偶发命中手机号形态** ⇒ 扫描前必须先剥掉，
     * 否则这条安全不变式变成随机红（见本类不变式用例的注释）。
     */
    private static final Pattern LONG_HEX = Pattern.compile("(?i)[0-9a-f]{16,}");

    @Test
    @DisplayName("★ 日志出口把手机号掩码，消息与异常堆栈都不放过")
    void 日志出口把手机号掩码_消息与堆栈都掩() {
        String phone = "13800009601";

        try (LogCapture system = LogCapture.root()) {
            system.clear();

            // ① 消息里带号码（等价于"某处手滑把 phone 拼进了日志"）
            LoggerFactory.getLogger("observability.probe").warn("probe phone={}", phone);
            // ② 异常消息里带号码（等价于"框架把被拒参数回显进异常"，这是真实且常见的路径）
            LoggerFactory.getLogger("observability.probe")
                    .warn("probe throwable", new IllegalStateException("for input string: \"" + phone + "\""));

            String messageLine = system.awaitLine(l -> l.contains("probe phone="), "含号码的探针日志行", AWAIT);
            assertThat(messageLine)
                    .as("出口必须把号码掩成 前3****后4")
                    .contains("138****9601")
                    .doesNotContain(phone);

            system.awaitLine(l -> l.contains("probe throwable"), "含号码异常的探针日志行", AWAIT);
            // 整个 sink（含堆栈续行）都不许出现明文号码
            assertThat(String.join("\n", system.lines()))
                    .as("异常堆栈同样经过 %maskedMsg —— 只掩消息等于留半扇门")
                    .doesNotContain(phone)
                    .contains("138****9601");
        }
    }

    @Test
    @DisplayName("参数被框架回显的失败请求，日志里也没有明文手机号")
    void 框架回显参数的请求不泄漏() {
        String phone = "13800009602";
        clearLogs();

        // contentId 收 Long，非数字 ⇒ MethodArgumentTypeMismatchException（400），
        // 框架会把**被拒的值**回显进异常消息 ⇒ 这正是"参数进日志"的真实路径。
        get("/comment/show?contentId=" + phone + "abc", null);

        awaitSystemLine("msg=请求参数错误");
        assertThat(String.join("\n", systemLines()))
                .as("回显的参数被拒值必须已被脱敏")
                .doesNotContain(phone);
    }

    @Test
    @DisplayName("★ 不变式：四个输出端（system/error/access/audit）的历史行都搜不到明文手机号")
    void 四个输出端的历史行都搜不到明文手机号() {
        String phone = "13800009603";
        // 先制造一条"注册带手机号"的真实流量：这正是老用例 test_secrets_never_land_in_access_log 的场景
        registerAndGetToken(phone, "obs-sensitive", "abc123456");
        awaitSystemLine("msg=用户注册成功");
        // 等访问行一并落盘，保证下面扫到的是"本请求写完之后"的快照
        awaitAccessLine("/user/register", "status=200");

        // 扫描前先剥掉两类**随机串**，否则它们会偶发命中手机号形态 ⇒ 把"安全不变式"变成看运气的测试
        // （这不是"放宽标准"：随机串不是 PII；把假警报当泄漏，等于训练大家忽略这条不变式）。
        //   ① req=<16hex>：请求标识（老用例注释记着实测 26 行假阳性）；
        //   ② <16+hex>：Testcontainers 的容器 id（64 位），例如
        //      `Container redis:7-alpine is starting: 0530…16959153928…`，
        //      中间恰好含有长度 11 的纯数字段。2026-10-06 全量回归就是这样红的（与本批改动无关，
        //      id 每次随机 ⇒ 偶发）。
        // 判据：长度 ≥16 的连续十六进制串不可能是手机号（手机号是 11 位十进制，且此处是 id 位置），
        // 剥离后剩下的命中才是真嫌疑行。
        //
        // system / access 两个文件必然已有内容（本轮刚写过）；error / audit 允许本轮尚未产生
        // （它们的产生依赖"是否发生过 ERROR / 审计动作"），缺文件即跳过，但**存在就必须干净**。
        for (Path file : List.of(
                LogCapture.systemFile(), LogCapture.accessFile(),
                LogCapture.errorFile(), LogCapture.auditFile())) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            List<String> offenders = readLines(file).stream()
                    .map(line -> REQ_PATTERN.matcher(line).replaceAll("req=<id>"))
                    .map(line -> LONG_HEX.matcher(line).replaceAll("<hex>"))
                    .filter(line -> line.matches(".*1[3-9]\\d{9}.*"))
                    .toList();
            assertThat(offenders)
                    .as("%s 出现明文手机号（出口脱敏失效）", file)
                    .isEmpty();
        }

        // 自证"确实读到了本轮写过的文件"，否则上面的扫描可能是空过
        assertThat(Files.isRegularFile(LogCapture.systemFile())).isTrue();
        assertThat(Files.isRegularFile(LogCapture.accessFile())).isTrue();
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
