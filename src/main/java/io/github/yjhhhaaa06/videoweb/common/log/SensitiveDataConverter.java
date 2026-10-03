package io.github.yjhhhaaa06.videoweb.common.log;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;

/**
 * logback 转换字 {@code %maskedMsg}（第三批 T2）：**日志出口的统一脱敏点**。
 *
 * <h2>它输出什么</h2>
 * <ol>
 *   <li>渲染后的消息（{@code {} } 占位符已填充，与 {@code %msg} 同源）→ 单行化 + 手机号掩码；</li>
 *   <li>若有异常：换行后接**同样脱敏过**的异常堆栈。</li>
 * </ol>
 *
 * <h2>★ 为什么连堆栈一起管</h2>
 * PII 进日志的路径不止"消息"一条：异常消息会被框架回显进堆栈首行
 * （例如 {@code DataIntegrityViolationException: Duplicate entry '138…' for key 'users.phone'}，
 * 或 {@code MethodArgumentTypeMismatchException} 回显被拒参数）。只脱敏消息等于留半扇门。
 *
 * <h2>★ 为什么 pattern 里必须同时写 {@code %nopex}</h2>
 * logback 的 {@code PatternLayout} 在 pattern **不含**异常转换字时，会**自动**在行尾追加
 * 一份完整堆栈。本转换字已经自己渲染堆栈 ⇒ 不写 {@code %nopex} 会得到**两份堆栈**
 * （第二份还是未脱敏的）。{@code %nopex}（{@code NopThrowableInformationConverter}）
 * 正是为此提供的"占位但什么都不输出"的转换字——它在 pattern 里出现即被视为"异常已处理"。
 * 这条约束是**隐性契约**：改 pattern 的人必须保留它，故 {@code LogbackConfigTests} 直接断言之。
 */
public class SensitiveDataConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        StringBuilder builder = new StringBuilder(160);
        builder.append(LogMasker.maskMessage(event.getFormattedMessage()));
        if (event.getThrowableProxy() != null) {
            builder.append('\n')
                    .append(LogMasker.mask(ThrowableProxyUtil.asString(event.getThrowableProxy())));
        }
        return builder.toString();
    }
}
