package io.github.yjhhhaaa06.videoweb.common.time;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 站内**自然日**的唯一来源（投币签到口径，见 {@code architecture/domain/coin/coin.md} §三 / §四.2）。
 *
 * <h2>为什么必须有一个专门的口径源</h2>
 * 签到是"每日一次"，幂等判据落在 {@code users.last_sign_date} 这一列上——**它问的是"哪一天"，
 * 不是"何时"**。这件事有两个后果，都要求"今天"只能有一个答案：
 * <ol>
 *   <li><b>不能靠 JVM 默认时区</b>：本机开发时区是东八区，但 CI / 容器常跑 UTC。
 *       用 {@link LocalDate#now()} 会让同一时刻在两种环境里落在不同的"今天"——
 *       最坏情况是同一天能签两次（容器跨过 UTC 零点但上海还没跨），幂等被环境配置击穿。</li>
 *   <li><b>不能靠 DB 时钟</b>：{@code last_sign_date} 是 {@code date} 列，运行时比较的
 *       {@code :today} 由应用给出；若改用 {@code CURDATE()}，测试就无法固定"今天是哪天"
 *       （时区不同、容器时钟不同），且库侧时区是连接串参数、与服务端时区是两件事。</li>
 * </ol>
 *
 * <p>选 {@code Asia/Shanghai} 的依据不是"作者在东八区"，而是**与库内既有时间口径一致**：
 * 数据源连接串已钉 {@code serverTimezone=Asia/Shanghai}，站内 {@code datetime} 列的历史语义
 * 就是上海时间（见 {@code 数据与Schema演进}）。签到日必须落在同一套日历上，
 * 否则"今天签的"可能与"今天发的"不是同一天。
 *
 * <p>⚠️ 本类只提供**日**口径。需要时刻（{@code datetime} 列 / TTL / 日志）的地方仍用
 * {@link java.time.Instant} 或 {@link java.time.LocalDateTime}，不要顺手把这里扩成"时间工具类"——
 * 那会让"签到按哪天算"这条唯一的契约被稀释。
 */
public final class SiteTime {

    /** 站内日历的时区。全仓唯一声明点（别在别处再 {@code ZoneId.of("Asia/Shanghai")}）。 */
    public static final ZoneId SITE_ZONE = ZoneId.of("Asia/Shanghai");

    /** 站内"今天"（上海自然日）。所有"按日"的判定都必须经过这里。 */
    public static LocalDate today() {
        return LocalDate.now(SITE_ZONE);
    }

    private SiteTime() {
    }
}
