package io.github.yjhhhaaa06.videoweb.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.core.rolling.RollingFileAppender;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * 日志捕获夹具（第三批 T2）：把**真实的 logback 输出**搬进测试断言。
 *
 * <h2>★ 为什么在"被打日志的那条线程"里就渲染好（这是本夹具最容易写错的地方）</h2>
 * 本夹具用的是**运行期真实的 {@link PatternLayout}**（从 root 的 SYSTEM 输出端取），
 * 而 pattern 里的 {@code %reqid} 读的是**MDC**——MDC 是**线程本地**的。
 * 真实文件输出端之所以能写出 {@code req=…}，是因为它就在**请求线程**里渲染。
 * 若先把 {@code ILoggingEvent} 收进内存、事后在**测试线程**里渲染，
 * {@code %reqid} 会读到测试线程那把空 MDC，于是**所有行都不带 req=**——
 * 第一版就是这么踩的（32 例里 14 例红，全是"缺 req="）。故渲染必须发生在 {@code append()} 里。
 *
 * <h2>为什么不直接读日志文件</h2>
 * 文件是最终交付形态，必须读（"分流"只能由文件证，见 {@code LogOutputSplitTests}），
 * 但文件写入与 HTTP 响应之间存在毫秒级竞态（响应先于过滤器 finally 落盘），
 * 主力断言走内存 sink 才稳定；两边分工：内存查"内容"，文件查"分流"。
 *
 * <h2>★ 两个 logger 的 additivity=false 决定了捕获范围</h2>
 * {@code access} / {@code audit} 两个 logger 配成 {@code additivity=false}，
 * 故挂 {@code root} 的 sink **看不到**访问行与审计行——这本身就是"里程碑不进 access/audit
 * 输出端"的机制证明。
 *
 * <h2>用法（try-with-resources，别忘 close）</h2>
 * <pre>{@code
 * try (LogCapture system = LogCapture.root(); LogCapture access = LogCapture.access()) {
 *     system.clear();
 *     ... 发一次请求 ...
 *     assertThat(system.linesContaining("msg=登录成功")).hasSize(1);
 * }
 * }</pre>
 */
public final class LogCapture implements AutoCloseable {

    /** 访问输出端的 logger 名（与 {@code AccessLogFilter.ACCESS_LOGGER_NAME} 同值，测试侧写死字面量）。 */
    public static final String ACCESS_LOGGER = "access";

    /** 审计输出端的 logger 名（与 {@code AuditLog.AUDIT_LOGGER_NAME} 同值）。 */
    public static final String AUDIT_LOGGER = "audit";

    /** 轮询默认上限：本地请求毫秒级，3s 是"远大于必要"的安全值（超时即真失败）。 */
    public static final Duration DEFAULT_AWAIT = Duration.ofSeconds(3);

    private final Logger logger;
    private final RenderingAppender appender;

    private LogCapture(Logger logger) {
        this.logger = logger;
        this.appender = new RenderingAppender(patternLayout());
        this.appender.setContext(logger.getLoggerContext());
        this.appender.start();
        logger.addAppender(this.appender);
    }

    /** 捕获根 logger（= {@code system.log} 看到的那些事件：INFO+ 的应用日志）。 */
    public static LogCapture root() {
        return new LogCapture(rootLogger());
    }

    /** 捕获访问输出端。 */
    public static LogCapture access() {
        return new LogCapture(loggerOf(ACCESS_LOGGER, rootLogger()));
    }

    /** 捕获审计输出端。 */
    public static LogCapture audit() {
        return new LogCapture(loggerOf(AUDIT_LOGGER, rootLogger()));
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    private static Logger loggerOf(String name, Logger fallback) {
        return LoggerFactory.getLogger(name) instanceof Logger logbackLogger ? logbackLogger : fallback;
    }

    /**
     * 取**运行期真实**的 {@link PatternLayout}（挂在 root 的 SYSTEM 文件输出端上）。
     * 拿不到就 fail-fast——那说明日志配置没生效，后续断言全部无意义。
     */
    private static PatternLayout patternLayout() {
        if (!(rootLogger().getAppender("SYSTEM") instanceof RollingFileAppender<?> systemAppender)) {
            throw new AssertionError("root 上找不到 SYSTEM 输出端——logback-spring.xml 未生效？");
        }
        Encoder<?> encoder = systemAppender.getEncoder();
        if (!(encoder instanceof LayoutWrappingEncoder<?> wrapping)
                || !(wrapping.getLayout() instanceof PatternLayout pattern)) {
            throw new AssertionError("SYSTEM 输出端用的不是 PatternLayout，无法渲染日志行");
        }
        return pattern;
    }

    /** 当前捕获到的**渲染后**日志行（行尾换行已去掉）。 */
    public List<String> lines() {
        return List.copyOf(appender.lines);
    }

    /** 含 needle 的行。 */
    public List<String> linesContaining(String needle) {
        return lines().stream().filter(line -> line.contains(needle)).toList();
    }

    /** 含 needle 的行数（"恰好一条"这类断言的取值方式）。 */
    public long countLinesContaining(String needle) {
        return linesContaining(needle).size();
    }

    /** 含 needle 的第一行；没有返回 {@code null}。 */
    public String firstLineContaining(String needle) {
        List<String> hits = linesContaining(needle);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /**
     * 轮询到出现满足 {@code predicate} 的行为止。
     *
     * <h2>为什么必须轮询</h2>
     * 访问行写在过滤器链的 {@code finally} 里，而 HTTP 响应可能**先**被客户端读到
     * （Tomcat 提交响应即对端可见，finally 还在其后几微秒）。凡"发请求 → 断言日志"都要走这里。
     *
     * <h2>为什么错误信息要带上全部行</h2>
     * 否则"没等到"与"配置没生效"看起来一模一样（老 pytest 的 {@code wait_for_line} 同款考虑）。
     */
    public String awaitLine(Predicate<String> predicate, String description, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<String> hit = lines().stream().filter(predicate).findFirst();
            if (hit.isPresent()) {
                return hit.get();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待日志行时被中断", e);
            }
        }
        throw new AssertionError("超时未出现 " + description + "；该 sink 当前的行:\n"
                + String.join("\n", lines()));
    }

    /** 清空（每个"动作"前调用，只断言本动作产生的事件——日志是全局资源，不清必然串味）。 */
    public void clear() {
        appender.lines.clear();
    }

    // ==================== 真实文件路径（读文件做"分流"断言时用） ====================

    /**
     * 某输出端的**当前文件路径**（取自运行期配置，不写死）。
     *
     * <p>⚠️ 必须指定 {@code loggerName}：{@code ACCESS} / {@code AUDIT} 挂在各自的**专属 logger** 上
     * （{@code additivity=false}），不在 root 上——只查 root 会误判成"配置没生效"。
     */
    public static Path fileOf(String loggerName, String appenderName) {
        Logger logger = loggerName.equals(org.slf4j.Logger.ROOT_LOGGER_NAME)
                ? rootLogger() : loggerOf(loggerName, rootLogger());
        if (!(logger.getAppender(appenderName) instanceof RollingFileAppender<?> appender)) {
            throw new AssertionError("logger " + loggerName + " 上找不到输出端 " + appenderName
                    + "——logback-spring.xml 未生效？");
        }
        return Path.of(appender.getFile());
    }

    public static Path systemFile() {
        return fileOf(org.slf4j.Logger.ROOT_LOGGER_NAME, "SYSTEM");
    }

    public static Path errorFile() {
        return fileOf(org.slf4j.Logger.ROOT_LOGGER_NAME, "ERROR");
    }

    public static Path accessFile() {
        return fileOf(ACCESS_LOGGER, "ACCESS");
    }

    public static Path auditFile() {
        return fileOf(AUDIT_LOGGER, "AUDIT");
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }

    /**
     * 在 {@code append()} 里就用**运行期真实 layout** 渲染好的内存 appender。
     *
     * <p>渲染时机是本夹具的关键（见类注释）：{@code %reqid} 读线程本地 MDC，
     * 事后在测试线程渲染会全部丢字段。
     */
    private static final class RenderingAppender extends AppenderBase<ILoggingEvent> {
        private final PatternLayout layout;
        private final List<String> lines = new CopyOnWriteArrayList<>();

        private RenderingAppender(PatternLayout layout) {
            this.layout = layout;
        }

        @Override
        protected void append(ILoggingEvent event) {
            String rendered = layout.doLayout(event);
            lines.add(rendered.endsWith("\n") ? rendered.substring(0, rendered.length() - 1) : rendered);
        }
    }
}
