package io.github.yjhhhaaa06.videoweb.history.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「计过数的自然日」的日界契约（`R-26`）。
 *
 * <h2>为什么这条必须是**单测**，而不是靠 HTTP 用例</h2>
 * 日界写错（用 UTC 而不是 +08:00）的**唯一可观察差异**是每天 0:00–8:00 的 8 小时：
 * 其余时间两者同日期 ⇒ HTTP 用例在白天跑永远绿。本机又在 +08:00，等到凌晨才红毫无意义。
 * 所以用**固定时刻 + 纯函数**钉住：{@code 2026-10-31T16:30Z} 必须属于 {@code 11-01}。
 *
 * <p>期望值出自口径本身（"服务器时间 +08:00 的自然日"），不是从实现推出来的：
 * 16:30Z + 8h = 次日 00:30（上海），所以业务日只能是 11-01。
 *
 * <h2>反向验证（注入 ⇒ 必红）</h2>
 * 把 {@link PlayDay#ZONE} 改成 {@code ZoneOffset.UTC} ⇒ 两条用例同时红；
 * 把 {@code of(instant)} 改成 {@code LocalDate.now()}（忽略入参）⇒ 第一条红。
 */
class PlayDayTests {

    @Test
    @DisplayName("日界在上海时区的零点切换：15:59:59Z 仍是当天，16:00:00Z 进入次日")
    void 日界按业务时区零点切() {
        assertThat(PlayDay.of(Instant.parse("2026-10-31T15:59:59Z")))
                .as("上海时间 10-31 23:59:59 ⇒ 业务日 10-31")
                .isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(PlayDay.of(Instant.parse("2026-10-31T16:00:00Z")))
                .as("上海时间 11-01 00:00:00 ⇒ 业务日 11-01（跨日就在这里发生）")
                .isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    @DisplayName("★业务日 ≠ UTC 日：同一个时刻在 UTC 口径下会落回前一天（这就是那 8 小时的坑）")
    void 业务日不等于UTC日() {
        Instant instant = Instant.parse("2026-10-31T16:30:00Z");

        assertThat(PlayDay.of(instant)).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(LocalDate.ofInstant(instant, ZoneOffset.UTC))
                .as("★ 若实现用 UTC，账本会记成 10-31 ⇒ 用户在上海时间 0:00–8:00 的播放被记到昨天，"
                        + "当天还能再计一次（播放量虚高），且只有这 8 小时能复现")
                .isEqualTo(LocalDate.of(2026, 10, 31));
    }
}
