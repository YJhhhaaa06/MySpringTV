package io.github.yjhhhaaa06.videoweb.common.log;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.slf4j.MDC;

/**
 * logback 转换字 {@code %reqid}（第三批 T2）：
 * **有值才输出** {@code " req=<值>"}，无值整段不出现。
 *
 * <h2>为什么不直接用内建的 {@code %X{reqId}}</h2>
 * {@code %X} 在键缺失时输出空串，于是每条日志都会留下一个悬空的 {@code req= } 字段——
 * 让 grep / awk 抽字段多一列噪声，也让"清空后不含 req="这一判据失效。
 * TV 的 {@code LogFormatter} 明确是"无值时字段整段省略"，本类把这个口径搬到新栈上。
 *
 * <h2>为什么值仍要过 {@link LogMasker#singleLine}</h2>
 * reqId 由 {@link RequestIdFilter} 生成（恒为 16 位十六进制），但转换字是**通用出口**——
 * 若将来允许上游透传 header，值就不可信了。仍走一遍单行化，守住"一条记录一行"不变式。
 *
 * <p><b>⚠️ 将来若开放上游透传，必须在这里补 {@link LogMasker#mask} </b>：
 * 那时值由客户端提供，可能**直接就是**手机号之类的 PII，而本字段是 pattern 里
 * **唯一不经 {@code %maskedMsg} 的用户可影响字段**——只单行化不脱敏即是一处死角。
 */
public class RequestIdConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId == null || requestId.isBlank()) {
            return "";
        }
        return " req=" + LogMasker.singleLine(requestId);
    }
}
