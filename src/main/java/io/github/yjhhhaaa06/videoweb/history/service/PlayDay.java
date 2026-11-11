package io.github.yjhhhaaa06.videoweb.history.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 「计过数的自然日」的口径（`R-26`）—— 播放量去重账本 {@code content_play_dedup.play_date} 的取值规则。
 *
 * <h2>为什么需要一个具名类型，而不是一行 {@code LocalDate.now()}</h2>
 * 这是一条**产品规则**（"服务器时间的每个自然日"），它有两个容易写错的点，写在两个地方就一定会漂：
 * <ol>
 *   <li><b>时区</b>：日界必须与《接口契约》§2.7 的 **+08:00** 序列化口径同源。用 UTC 会让每天
 *       0:00–8:00 的"今天"错成昨天 —— 用户在凌晨看视频，播放量会重复计/计不上，而且**只在那 8 小时里错**，
 *       白天跑测试永远发现不了（唯一的确定性判别力在 {@code PlayDayTests}）；</li>
 *   <li><b>谁来算</b>：必须**在 Java 里算好再传参**，不能用 SQL 的 {@code CURDATE()} ——
 *       后者取 MySQL 会话的 {@code time_zone}（容器里常是 UTC），同样差 8 小时，
 *       而且把这条规则藏进了 SQL，测试拨不动时钟。</li>
 * </ol>
 *
 * <p>本类**刻意只做一件事**：把"某一时刻属于哪个业务日"变成一个纯函数，好让用例用固定时刻断言
 * （{@code 2026-10-31T16:30Z} 必须落在 {@code 2026-11-01}）。生产路径只有 {@link #today()} 一个调用点。
 */
final class PlayDay {

    /**
     * 业务时区：**固定** {@code Asia/Shanghai}，不跟随 JVM 默认时区。
     * 与《接口契约》§2.7（全部时间串按 +08:00 解释）是同一个决定的两个面。
     */
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private PlayDay() {
    }

    /** 某一时刻属于哪个业务日（纯函数；用例用它钉日界）。 */
    static LocalDate of(Instant instant) {
        return LocalDate.ofInstant(instant, ZONE);
    }

    /** 此刻的业务日（`/play` 的唯一调用点）。 */
    static LocalDate today() {
        return of(Instant.now());
    }
}
