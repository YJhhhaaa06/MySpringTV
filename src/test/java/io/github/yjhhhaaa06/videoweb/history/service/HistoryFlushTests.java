package io.github.yjhhhaaa06.videoweb.history.service;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.config.HistoryBufferProperties;
import io.github.yjhhhaaa06.videoweb.history.cache.HistoryBufferRedisOps;
import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓冲落库编排（`T51-2`）：`/flush` 与轮询兜底共用的那段逻辑。
 *
 * <p>口径唯一事实源 = {@code architecture/domain/history/history.md} §12.3–12.5。
 *
 * <p>★ <b>为什么把轮询周期调到 1h</b>：本类会**手动驱动** {@code pollOnce()}，
 * 而 {@code HistoryFlushScheduler} 在真实上下文里也会按周期跑 —— 两边同时动同一批键，
 * 断言会随机看到"已经被兜底清掉"的中间态（偶发红）。把首次触发推到 1h 之外，测试期就只有一个驱动者。
 * ⚠️ 相应地 {@code buffer-ttl} 必须调大，否则构造期不变式（ttl &gt; idle + poll + retention）不成立。
 */
@TestPropertySource(properties = {
        "video.history.buffer.poll-interval=1h",
        "video.history.buffer.buffer-ttl=3h"
})
class HistoryFlushTests extends AbstractIntegrationTest {

    private static final long U_IDLE = 9201L;
    private static final long U_ACTIVE = 9202L;
    private static final long U_OTHER = 9203L;
    private static final long C1 = 8901L;
    private static final long C2 = 8902L;

    /** 整秒（列是 {@code datetime} 秒精度，毫秒部分会被截掉）。 */
    private static final long TS = 1_700_000_000_000L;

    @Autowired
    private HistoryFlushService flushService;

    @Autowired
    private HistoryBufferRedisOps bufferOps;

    @Autowired
    private HistoryBufferProperties props;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void cleanState() {
        jdbc.update("DELETE FROM watch_history WHERE user_id IN (?,?,?)", U_IDLE, U_ACTIVE, U_OTHER);
        for (long userId : new long[]{U_IDLE, U_ACTIVE, U_OTHER}) {
            redis.delete(CacheKeys.historyReport(userId));
            redis.opsForZSet().remove(CacheKeys.HISTORY_DIRTY, String.valueOf(userId));
        }
    }

    @Test
    @DisplayName("flush：进度落库，update_time 来自缓冲的接收时刻（不是数据库当前时间）")
    void flushWritesProgressAndExplicitUpdateTime() {
        bufferOps.write(U_IDLE, C1, 500, TS, props.bufferTtl());

        int written = flushService.flushUser(U_IDLE);

        assertThat(written).isEqualTo(1);
        assertThat(progressOf(U_IDLE, C1)).isEqualTo(500);
        assertThat(updateTimeOf(U_IDLE, C1).getTime()).isEqualTo(TS);
    }

    @Test
    @DisplayName("flush 后：缓冲 key 与脏成员都被清掉、且不再被下一轮扫到")
    void flushClearsBufferAndDirtyMember() {
        bufferOps.write(U_IDLE, C1, 500, TS, props.bufferTtl());

        flushService.flushUser(U_IDLE);

        assertThat(redis.hasKey(CacheKeys.historyReport(U_IDLE))).isFalse();
        assertThat(redis.opsForZSet().score(CacheKeys.HISTORY_DIRTY, String.valueOf(U_IDLE))).isNull();
    }

    /**
     * 僵尸成员：hash 已随 TTL 消失、脏成员还在。
     * 不清掉它就会因 score 最老而**每轮占满兜底名额** ⇒ 真实用户永远取不到。
     */
    @Test
    @DisplayName("flush 对\"只有脏成员、没有 hash\"的用户也完成清理")
    void flushOnZombieMemberClearsIt() {
        redis.opsForZSet().add(CacheKeys.HISTORY_DIRTY, String.valueOf(U_IDLE), (double) TS);

        int written = flushService.flushUser(U_IDLE);

        assertThat(written).isZero();
        assertThat(redis.opsForZSet().score(CacheKeys.HISTORY_DIRTY, String.valueOf(U_IDLE))).isNull();
        assertThat(rowCount(U_IDLE)).isZero();
    }

    /**
     * ★ 轮询的全部意义：只兜"没正常上报退出"的用户。
     * 正常退出的走 `/flush`；刚上报过的活跃用户**不该**被兜底（否则"兜底"就退化成"周期性写库"）。
     */
    @Test
    @DisplayName("轮询只落库\"够闲\"的用户，活跃用户不动")
    void pollFlushesOnlyIdleUsers() {
        long now = System.currentTimeMillis();
        bufferOps.write(U_IDLE, C1, 100, now - props.idleThreshold().toMillis() - 10_000L, props.bufferTtl());
        bufferOps.write(U_ACTIVE, C2, 200, now, props.bufferTtl());

        flushService.pollOnce();

        assertThat(progressOf(U_IDLE, C1)).isEqualTo(100);
        assertThat(progressOf(U_ACTIVE, C2))
                .as("活跃用户应留在缓冲里等退出信号")
                .isNull();
        assertThat(redis.opsForZSet().score(CacheKeys.HISTORY_DIRTY, String.valueOf(U_ACTIVE))).isNotNull();
    }

    /**
     * ★ 成员级 TTL（不是给 ZSET 加 EXPIRE）：score 早于 {@code now - bufferTtl} 意味着
     * 对应 hash **已经过期**，成员必须先被剪掉 —— 否则它永远占着每轮的前 N 个名额。
     */
    @Test
    @DisplayName("轮询先剪枝：score 早于 bufferTtl 的成员被摘掉，且不会被落库")
    void pollPrunesMembersOlderThanBufferTtl() {
        long stale = System.currentTimeMillis() - props.bufferTtl().toMillis() - 60_000L;
        redis.opsForHash().put(CacheKeys.historyReport(U_OTHER), String.valueOf(C1), "700:" + stale);
        redis.opsForZSet().add(CacheKeys.HISTORY_DIRTY, String.valueOf(U_OTHER), (double) stale);

        flushService.pollOnce();

        assertThat(redis.opsForZSet().score(CacheKeys.HISTORY_DIRTY, String.valueOf(U_OTHER))).isNull();
        assertThat(rowCount(U_OTHER))
                .as("剪掉的是 hash 已过期的成员，不该被当成待落库数据")
                .isZero();
    }

    /**
     * ★ flush 只写进度与时间，**绝不碰** `play_counted`（那是 `/play` 的唯一职责）。
     * 两条夹具刻意反着放：已有行是 1（防"被重置成 0"），新行是 0（防"被写成 1"）——
     * 单夹具的话，注入任一方向的缺陷都不会红。
     */
    @Test
    @DisplayName("flush 不覆盖 play_counted（已计的不清、未计的不置）")
    void flushDoesNotTouchPlayCounted() {
        jdbc.update("INSERT INTO watch_history (user_id, content_id, progress_seconds, play_counted) "
                + "VALUES (?,?,?,1)", U_IDLE, C1, 1);
        jdbc.update("INSERT INTO watch_history (user_id, content_id, progress_seconds, play_counted) "
                + "VALUES (?,?,?,0)", U_IDLE, C2, 1);
        bufferOps.write(U_IDLE, C1, 500, TS, props.bufferTtl());
        bufferOps.write(U_IDLE, C2, 600, TS, props.bufferTtl());

        flushService.flushUser(U_IDLE);

        assertThat(progressOf(U_IDLE, C1)).isEqualTo(500);
        assertThat(playCountedOf(U_IDLE, C1)).isEqualTo(1);
        assertThat(progressOf(U_IDLE, C2)).isEqualTo(600);
        assertThat(playCountedOf(U_IDLE, C2)).isZero();
    }

    /**
     * ★ <b>毒批</b>：一条坏数据不能让整批回滚，更不能毒死同行数据。
     *
     * <p>这里用"超出 `datetime` 取值范围的接收时刻"造一个**真实的**数据异常（不需要 mock）：
     * 整批 INSERT 会被 MySQL 拒 ⇒ 服务降级逐条 ⇒ 好的那条写进去、坏的那条被丢弃并确认掉。
     * 若没有逐条降级，两条都不会落库（整批回滚）。
     */
    @Test
    @DisplayName("整批失败后逐条降级：坏行被丢弃，好行照常落库")
    void poisonRowDoesNotKillTheWholeBatch() {
        long outOfDatetimeRange = 253_402_300_800_000L;   // 公元 10000 年，超出 MySQL datetime
        bufferOps.write(U_IDLE, C1, 300, TS, props.bufferTtl());
        bufferOps.write(U_IDLE, C2, 400, outOfDatetimeRange, props.bufferTtl());

        int written = flushService.flushUser(U_IDLE);

        assertThat(written).isEqualTo(1);
        assertThat(progressOf(U_IDLE, C1))
                .as("好行必须落库（否则就是被坏行毒死）")
                .isEqualTo(300);
        assertThat(progressOf(U_IDLE, C2))
                .as("坏行被丢弃，且不能以错误的值落库")
                .isNull();
        assertThat(redis.hasKey(CacheKeys.historyReport(U_IDLE)))
                .as("坏行也要被确认掉，否则 HLEN>0 ⇒ 脏成员永不消失")
                .isFalse();
    }

    /**
     * ★ 坏值必须"丢弃 + 确认"：只丢弃不确认 ⇒ 那个 field 永远留在 hash 里 ⇒ {@code HLEN>0}
     * ⇒ 脏成员永远摘不掉（与僵尸成员同族）。
     */
    @Test
    @DisplayName("无法解析的值被丢弃，但对应 field 会被删掉（否则脏成员永不消失）")
    void flushDropsUnparsableValueButRemovesField() {
        redis.opsForHash().put(CacheKeys.historyReport(U_IDLE), String.valueOf(C1), "not-a-progress");
        redis.opsForZSet().add(CacheKeys.HISTORY_DIRTY, String.valueOf(U_IDLE), (double) TS);

        int written = flushService.flushUser(U_IDLE);

        assertThat(written).isZero();
        assertThat(rowCount(U_IDLE)).isZero();
        assertThat(redis.hasKey(CacheKeys.historyReport(U_IDLE))).isFalse();
        assertThat(redis.opsForZSet().score(CacheKeys.HISTORY_DIRTY, String.valueOf(U_IDLE))).isNull();
    }

    /** LWW 口径未变：缓冲里的进度直接覆盖库里的值（含"更小"的值，例如重看从头）。 */
    @Test
    @DisplayName("flush 覆盖已有进度（后到即新，允许变小）")
    void flushOverwritesProgressWithNewerValue() {
        jdbc.update("INSERT INTO watch_history (user_id, content_id, progress_seconds) VALUES (?,?,?)",
                U_IDLE, C1, 900);
        bufferOps.write(U_IDLE, C1, 120, TS, props.bufferTtl());

        flushService.flushUser(U_IDLE);

        assertThat(progressOf(U_IDLE, C1)).isEqualTo(120);
    }

    // ==================== 断言辅助 ====================

    private Integer progressOf(long userId, long contentId) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT progress_seconds FROM watch_history WHERE user_id = ? AND content_id = ?",
                Integer.class, userId, contentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Timestamp updateTimeOf(long userId, long contentId) {
        return jdbc.queryForObject(
                "SELECT update_time FROM watch_history WHERE user_id = ? AND content_id = ?",
                Timestamp.class, userId, contentId);
    }

    private Integer playCountedOf(long userId, long contentId) {
        return jdbc.queryForObject(
                "SELECT play_counted FROM watch_history WHERE user_id = ? AND content_id = ?",
                Integer.class, userId, contentId);
    }

    private int rowCount(long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM watch_history WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }
}
