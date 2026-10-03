package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可观测面的配置（第三批 T2）。
 *
 * <p>目前只有一项：访问日志的**慢请求阈值**。口径对齐 TV {@code AppConfig.getLogSlowRequestMs()}
 * ⟵ {@code app.properties} 的 {@code log.slowRequestMs}（默认 1000ms）——
 * 达到阈值只把访问行的 {@code slow} 打为 1，**不另起一行、不设独立性能日志文件**（TV D7 的刻意取舍：
 * 性能信息挂在访问行上，避免"两条数据源要自己对时间"）。
 *
 * <h2>同命名空间下的 {@code video.log.dir} 不在此类</h2>
 * 日志目录由 {@code logback-spring.xml} 用 {@code <springProperty source="video.log.dir"/>}
 * 直接消费（日志系统在容器启动早期就要落盘，先于任何 bean）。把那把键也声明进本类会造成
 * **"声明而未使用"的 Java 字段**——正是《遗留台账》B11 记的那类噪声，故不声明，只在
 * {@code application.yaml} 的注释里说明它的唯一消费者是 logback。
 *
 * @param slowRequestMs 慢请求阈值（毫秒）。{@code <= 0} 视为非法并回落 1000——
 *                      配错不该让访问日志抛异常（观测面出问题不得影响业务）
 */
@ConfigurationProperties(prefix = "video.log")
public record LogProperties(long slowRequestMs) {

    /** 阈值缺省值（对齐 TV {@code log.slowRequestMs=1000}）。 */
    public static final long DEFAULT_SLOW_REQUEST_MS = 1000L;

    public LogProperties {
        if (slowRequestMs <= 0) {
            slowRequestMs = DEFAULT_SLOW_REQUEST_MS;
        }
    }
}
