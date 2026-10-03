package io.github.yjhhhaaa06.videoweb.observability;

import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志配置的**结构性契约**（第三批 T2 / 账 B12）。
 *
 * <h2>为什么必须有这一类测试（而不是只测"日志内容对不对"）</h2>
 * {@code logback-spring.xml} 是一条**隐性契约**：四个输出端靠
 * {@code logger additivity=false} 隔离、脱敏靠 pattern 里的 {@code %maskedMsg}、
 * 单行不变式靠 {@code %nopex}。这些都没有编译器帮忙——把 {@code additivity} 去掉、
 * 或把 {@code %nopex} 删掉，代码照样编译、大多数用例照样绿，只有"日志里出现两份堆栈"
 * 或"审计行泄进 system.log"这种**要人去翻文件才看得见**的症状。
 *
 * <p>本类把这条契约变成会失败的测试：改配置而不改测试 ⇒ 立刻红。
 *
 * <p>断言全部走 **logback 运行期对象**（{@link LoggerContext} / {@link Appender}），
 * 不解析 XML 文本——文本会因为格式调整而误报，运行期对象才是"实际生效的东西"。
 */
@Tag("observability")
class LogbackConfigTests extends AbstractIntegrationTest {

    /** 输出端名字（与 logback-spring.xml 中的 appender name 一致；改名字要同时改这里）。 */
    private static final String SYSTEM_APPENDER = "SYSTEM";
    private static final String ERROR_APPENDER = "ERROR";
    private static final String ACCESS_APPENDER = "ACCESS";
    private static final String AUDIT_APPENDER = "AUDIT";

    private static Logger loggerOf(String name) {
        org.slf4j.Logger raw = LoggerFactory.getLogger(name);
        if (!(raw instanceof Logger logbackLogger)) {
            throw new AssertionError("SLF4J 后端不是 logback：日志配置契约无从断言");
        }
        return logbackLogger;
    }

    @Test
    @DisplayName("★ 后端必须是 logback（本文件所有断言的前提）")
    void 后端是logback() {
        assertThat(LoggerFactory.getILoggerFactory()).isInstanceOf(LoggerContext.class);
    }

    @Test
    @DisplayName("access / audit 是专属 logger：additivity=false（输出端互不污染的唯一机制）")
    void 专属logger不向root传播() {
        Logger access = loggerOf("access");
        assertThat(access.isAdditive())
                .as("access 必须 additivity=false，否则访问行进 system.log（老 pytest 有一条专门断言它）")
                .isFalse();
        assertThat(access.getAppender(ACCESS_APPENDER)).as("access 必须挂 ACCESS 输出端").isNotNull();
        assertThat(access.getLevel()).isEqualTo(Level.INFO);
        assertThat(loggerOf(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender(ACCESS_APPENDER))
                .as("ACCESS 不该也挂在 root 上（否则同一行会落两处）")
                .isNull();

        Logger audit = loggerOf("audit");
        assertThat(audit.isAdditive())
                .as("audit 必须 additivity=false，否则审计行进 system.log")
                .isFalse();
        assertThat(audit.getAppender(AUDIT_APPENDER)).as("audit 必须挂 AUDIT 输出端").isNotNull();
        assertThat(audit.getLevel())
                .as("审计阈值固定 INFO：不得被 logging.level.* 静默")
                .isEqualTo(Level.INFO);
    }

    @Test
    @DisplayName("根 logger 挂 system + error + 控制台三个输出端")
    void 根logger装配() {
        Logger root = loggerOf(org.slf4j.Logger.ROOT_LOGGER_NAME);
        assertThat(root.getAppender(SYSTEM_APPENDER)).isNotNull();
        assertThat(root.getAppender(ERROR_APPENDER)).isNotNull();
        assertThat(root.getAppender("CONSOLE")).isNotNull();
        assertThat(root.getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    @DisplayName("四个文件输出端都把当前文件钉在固定名上（测试与运维都靠它定位）")
    void 输出端当前文件固定名() {
        // ⚠️ ACCESS / AUDIT 挂在各自的专属 logger 上（additivity=false），不在 root 上
        assertThat(LogCapture.systemFile().getFileName().toString()).isEqualTo("system.log");
        assertThat(LogCapture.errorFile().getFileName().toString()).isEqualTo("error.log");
        assertThat(LogCapture.accessFile().getFileName().toString()).isEqualTo("access.log");
        assertThat(LogCapture.auditFile().getFileName().toString()).isEqualTo("audit.log");
    }

    @Test
    @DisplayName("★ pattern 三要素：%reqid（请求关联）、%maskedMsg（出口脱敏）、%nopex（防两份堆栈）")
    void pattern三要素() {
        String pattern = patternOf(LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME), SYSTEM_APPENDER);

        assertThat(pattern).contains("%reqid");
        assertThat(pattern).contains("%maskedMsg");
        assertThat(pattern)
                .as("必须保留 %nopex：%maskedMsg 已自行渲染堆栈，缺它 logback 会再追加一份**未脱敏**的堆栈")
                .contains("%nopex");
    }

    private static String patternOf(org.slf4j.Logger rawLogger, String appenderName) {
        Logger logger = (Logger) rawLogger;
        Appender<ILoggingEvent> appender = logger.getAppender(appenderName);
        assertThat(appender).as(appenderName + " 输出端应存在").isNotNull();
        Encoder<?> encoder = ((RollingFileAppender<?>) appender).getEncoder();
        assertThat(encoder)
                .as("文件输出端应使用 PatternLayoutEncoder（否则本断言拿不到 pattern）")
                .isInstanceOf(ch.qos.logback.classic.encoder.PatternLayoutEncoder.class);
        // LayoutWrappingEncoder#getLayout 拿到的就是运行期真正在用的 PatternLayout
        assertThat((Object) ((LayoutWrappingEncoder<?>) encoder).getLayout()).isNotNull();
        return ((ch.qos.logback.classic.encoder.PatternLayoutEncoder) encoder).getPattern();
    }
}
