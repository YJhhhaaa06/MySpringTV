package io.github.yjhhhaaa06.videoweb.common.log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志脱敏与单行化工具（第三批 T2 / 账 B12 的"敏感字段过滤"）。
 *
 * <h2>为什么必须存在（**安全项，不是可观测项**）</h2>
 * 老项目的 25 条 e2e 里有一条是"注册请求带手机号 ⇒ 日志里搜不到明文手机号"，
 * 它保护的是**真实的安全红线**：日志会被采集、转发、长期留存，落盘一次的 PII 无法收回。
 *
 * <h2>放在哪一层（**出口**，不是调用点）</h2>
 * TV 在调用点手写 {@code StringUtil.maskForLog("phone", phone)}——**要每个调用点记得写**，
 * 漏一个就泄一次。本项目的口径反过来：把脱敏做成日志 pattern 的**出口转换字**
 * {@code %maskedMsg}（见 {@link SensitiveDataConverter}），**所有输出端**（控制台 + 四个文件）
 * 共用同一条出口 ⇒ "忘记脱敏"这件事不再需要靠纪律，而是结构上不可能发生。
 *
 * <h2>两条规则</h2>
 * <ol>
 *   <li><b>手机号 → {@code 138****1234}</b>：匹配 11 位大陆手机号（{@code (?<!\d)1[3-9]\d{9}(?!\d)}），
 *       保留前 3 后 4（可定位、不可还原）。两侧的数字边界让**夹在其它字符里**的形态也命中
 *       （如异常消息里的 {@code For input string: "138…abc"}）——这正是 TV 那类
 *       "参数被框架回显进异常消息"的实际泄漏路径；同时避免把 12 位以上的自增 id 前缀啃掉。</li>
 *   <li><b>换行 → {@code \n} 字面量</b>：守住"一条记录一行"不变式（TV {@code LogFormatter} 同款），
 *       否则一条带换行的消息会把日志行结构切碎、让结构化解析失效。</li>
 * </ol>
 *
 * <h2>已知边界（如实声明）</h2>
 * <ol>
 *   <li>只按**形态**识别手机号，不识别其它 PII（身份证 / 银行卡 / 邮箱 / 密码）。
 *       密码之所以不需要在此脱敏，是因为本项目**没有任何调用点把密码写进日志**
 *       （由 review 与契约测试共同保证）；若将来出现，应在此处扩展而不是回到调用点手写。</li>
 *   <li><b>只认连续 11 位</b>：带分隔符或国际区号的写法（{@code 138-0000-9601}、
 *       {@code +8613800001234}）**不命中**；被截断在 11 位中间的号码也不命中。
 *       本仓当前没有任何调用点会产生这两种形态（参数回显走的是原样字符串），
 *       故不构成缺陷；但**若要支持，必须在本类扩展**，不要在调用点各写一遍。</li>
 * </ol>
 */
public final class LogMasker {

    /**
     * 大陆手机号：1 + [3-9] + 9 位数字。
     *
     * <p>两侧用**数字边界**（{@code (?<!\d)} / {@code (?!\d)}）而不是词边界：词边界
     * （{@code \b}）在 `abc138…def` 里与"数字边界"等价，但挡不住 `138123456789`
     * （12 位数字）——那种情况下前 11 位会被当成号码掩掉，把一个**自增 id** 误掩成手机号。
     * 数字边界既能命中"夹在其它字符里"的真实泄漏形态，又不会啃掉更长数字的前缀。
     */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    private LogMasker() {
    }

    /** 掩码后形态：前 3 位 + 4 个星号 + 后 4 位（{@code 138****1234}）。 */
    static String maskPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    /**
     * 把文本里所有手机号形态替换为掩码。{@code null} / 空串原样返回（日志里空消息很常见，
     * 不该因为"脱敏"而变成 null）。
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = PHONE.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(maskPhone(matcher.group())));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 把换行折成 {@code \n} 字面量（CRLF 先处理，避免拆成两个转义），
     * 保证"一条记录 = 一行"。{@code null} 视作空串。
     */
    static String singleLine(String message) {
        if (message == null) {
            return "";
        }
        return message.replace("\r\n", "\\n").replace("\r", "\\n").replace("\n", "\\n");
    }

    /** 出口两步合一：先单行化再脱敏（顺序无关，但写成一个方法让出口只有一处调用）。 */
    static String maskMessage(String message) {
        return mask(singleLine(message));
    }
}
