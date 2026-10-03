package io.github.yjhhhaaa06.videoweb.common.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 脱敏与单行化（{@link LogMasker}）的**纯函数**契约（第三批 T2）。
 *
 * <h2>为什么必须有这一层测试</h2>
 * 出口脱敏（logback 的 {@code %maskedMsg}）是"手机号不落日志"这条安全红线的**唯一**机制。
 * 集成测试只能证明"我构造的那个请求没泄漏"；本类证明**规则本身**：
 * 形态识别、掩码形状、边界（非手机号不动、空值不炸、一条消息里多个号码全掩）。
 *
 * <p>带 {@code @Tag("observability")}：它属 T2 的验收集，
 * 于是 {@code python tools/run_tests.py --group observability} 一次跑完 T2 的全部交付。
 * （纯函数测试不排除，它们**本就在默认回归集里**——tag 只是让"按任务选跑"更完整。）
 */
@Tag("observability")
class LogMaskerTests {

    @Test
    @DisplayName("手机号被掩成 前3位****后4位")
    void 手机号被掩码() {
        assertThat(LogMasker.mask("phone=13812345678")).isEqualTo("phone=138****5678");
    }

    @Test
    @DisplayName("★ 夹在其它字符里的号码也要掩（框架回显参数是真实泄漏路径）")
    void 夹在其它内容里的号码也掩码() {
        assertThat(LogMasker.mask("For input string: \"13812345678abc\""))
                .isEqualTo("For input string: \"138****5678abc\"");
    }

    @Test
    @DisplayName("一条消息里的多个号码全部掩码")
    void 多个号码全部掩码() {
        assertThat(LogMasker.mask("13812345678 -> 13987654321"))
                .isEqualTo("138****5678 -> 139****4321");
    }

    @ParameterizedTest(name = "非手机号不动: {0}")
    @ValueSource(strings = {
            "userId=42",
            "contentId=1234567890",
            // 12 位：多一位就不是手机号形态，不该被吞掉（避免把 id 误掩成号码）
            "138123456789",
            // 第 2 位是 2：不合法号段
            "12812345678",
            // 10 位：不足
            "1381234567"
    })
    void 非手机号形态不动(String text) {
        assertThat(LogMasker.mask(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("null / 空串原样返回（日志里空消息不该因脱敏变成 null）")
    void 空值不构造新对象() {
        assertThat(LogMasker.mask(null)).isNull();
        assertThat(LogMasker.mask("")).isEmpty();
    }

    @Test
    @DisplayName("换行折成 \\n 字面量，守住一条记录一行")
    void 换行折成字面量() {
        assertThat(LogMasker.singleLine("a\nb")).isEqualTo("a\\nb");
        assertThat(LogMasker.singleLine("a\r\nb")).isEqualTo("a\\nb");
        assertThat(LogMasker.singleLine("a\rb")).isEqualTo("a\\nb");
        assertThat(LogMasker.singleLine(null)).isEmpty();
    }
}
