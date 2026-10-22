package io.github.yjhhhaaa06.videoweb.history.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.config.HistoryBufferProperties;
import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 上报缓冲内核（`T51-1`）——**纯 Redis 断言，不碰 DB**。
 *
 * <p>口径唯一事实源 = {@code history-上报缓冲.md} §2–§4。
 * 本类只验证"缓冲这一层"的性质：原子写、原始串保真、条件删、够闲筛选、TTL。
 * 落库 / 503 / 轮询编排在 `T51-2`（`HistoryFlushTests`）。
 *
 * <p>★ 用固定用户 id（{@value #USER_IDLE} 起）而不是随机：{@code history:dirty} 是**全局**键，
 * 用固定 id 才能在每个用例前精确清理，不依赖"别的用例没写过它"。
 *
 * <p>★ <b>为什么把轮询周期推到 1h</b>（{@code T51-2} 引入 {@code HistoryFlushScheduler} 之后）：
 * 本类故意用 {@code ts = 1000/2000}（1970 年）来测"条件删"，而兜底轮询会把这种 score
 * 直接**剪枝**掉 —— 15s 的首轮触发会让断言随机看到"已被剪掉"的中间态。
 * 推远首轮触发，测试期就只剩本类一个驱动者。⚠️ 同时 {@code buffer-ttl} 要调大，
 * 否则构造期不变式（ttl &gt; idle + poll + retention）不成立。
 */
@TestPropertySource(properties = {
        "video.history.buffer.poll-interval=1h",
        "video.history.buffer.buffer-ttl=3h"
})
class HistoryReportBufferTests extends AbstractIntegrationTest {

    /** 够闲用例用（写入时刻拨到 70s 前）。 */
    private static final long USER_IDLE = 9101L;
    /** 活跃用例用（写入时刻 = 现在）。 */
    private static final long USER_ACTIVE = 9102L;
    /** 通用写入用例用。 */
    private static final long USER_WRITE = 9103L;

    private static final long CONTENT_ID = 8801L;

    @Autowired
    private HistoryReportBuffer buffer;

    @Autowired
    private HistoryBufferRedisOps ops;

    @Autowired
    private HistoryBufferProperties props;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void cleanBufferOfTestUsers() {
        for (long userId : new long[]{USER_IDLE, USER_ACTIVE, USER_WRITE}) {
            redis.delete(CacheKeys.historyReport(userId));
            redis.opsForZSet().remove(CacheKeys.HISTORY_DIRTY, String.valueOf(userId));
        }
    }

    /**
     * ★ 本用例钉住"写完之后 hash 与 dirty **同时**就位"。
     *
     * <p>判别力边界（如实登记）：它拦得住**漏掉 {@code ZADD}** 这一类缺陷（实测：删除该行 ⇒ 本类 4 红），
     * 但**拦不住**"改成两次往返、两条命令都成功"——那种实现的末态完全相同，只有"第二条命令失败"时才暴露，
     * 需要给 `RedisConnectionFactory` 注入故障才能测（T51-2 再评估是否值得做）。
     * 本仓纪律要求"去掉那行实现会红"，这一条满足；但别把它当成"原子性已被完整证明"。
     */
    @Test
    @DisplayName("一次写入：hash 的值与脏集合的 score 同时就位，且时间戳同源")
    void writeIsAtomicAcrossHashAndDirtySet() {
        long ts = 1_700_000_000_123L;

        buffer.recordAt(USER_WRITE, CONTENT_ID, 500, ts);

        assertThat(redis.opsForHash().get(CacheKeys.historyReport(USER_WRITE), String.valueOf(CONTENT_ID)))
                .as("hash 里必须带上同一个 receiveTs")
                .isEqualTo("500:" + ts);
        assertThat(redis.opsForZSet().score(CacheKeys.HISTORY_DIRTY, String.valueOf(USER_WRITE)))
                .as("dirty 的 score 必须与之同源（同一个 ts 只传一次）")
                .isEqualTo((double) ts);
    }

    @Test
    @DisplayName("同一内容重复上报：保留最新值，不追加 field")
    void repeatedReportOverwritesSameField() {
        buffer.recordAt(USER_WRITE, CONTENT_ID, 500, 1_000L);
        buffer.recordAt(USER_WRITE, CONTENT_ID, 900, 2_000L);

        assertThat(redis.opsForHash().size(CacheKeys.historyReport(USER_WRITE))).isEqualTo(1L);
        assertThat(redis.opsForHash().get(CacheKeys.historyReport(USER_WRITE), String.valueOf(CONTENT_ID)))
                .isEqualTo("900:2000");
        assertThat(redis.opsForZSet().score(CacheKeys.HISTORY_DIRTY, String.valueOf(USER_WRITE)))
                .isEqualTo(2000.0);
    }

    @Test
    @DisplayName("快照返回原始串与全部 field（条件删要靠它比对，不能先解析再序列化）")
    void snapshotKeepsRawValues() {
        buffer.recordAt(USER_WRITE, CONTENT_ID, 500, 1_000L);

        Map<String, String> snapshot = buffer.snapshot(USER_WRITE);

        assertThat(snapshot).containsExactly(Map.entry(String.valueOf(CONTENT_ID), "500:1000"));
    }

    /**
     * ★ 非数字 field（脏键 / 人工写入）**不能**在读取时被跳过：跳过的字段永远不会进
     * {@code acknowledge} 的删除清单 ⇒ {@code HLEN > 0} ⇒ 脏成员永远摘不掉。
     * 本用例钉住"读取如实、丢弃由落库层决定"这条分工。
     */
    @Test
    @DisplayName("非数字 field 也出现在快照里，从而能被删掉")
    void snapshotKeepsNonNumericFields() {
        redis.delete(CacheKeys.historyReport(USER_WRITE));
        redis.opsForHash().put(CacheKeys.historyReport(USER_WRITE), "not-a-content-id", "abc:bad");
        redis.opsForHash().put(CacheKeys.historyReport(USER_WRITE), String.valueOf(CONTENT_ID), "500:1000");

        Map<String, String> snapshot = buffer.snapshot(USER_WRITE);

        assertThat(snapshot).containsKeys("not-a-content-id", String.valueOf(CONTENT_ID));

        buffer.acknowledge(USER_WRITE, snapshot);
        assertThat(redis.hasKey(CacheKeys.historyReport(USER_WRITE)))
                .as("全部 field 都被确认掉后，hash 必须消失（否则脏成员永远留着）")
                .isFalse();
    }

    @Test
    @DisplayName("确认后：hash 清空、脏成员被摘掉（否则轮询会一直空跑）")
    void acknowledgeRemovesHashAndDirtyMember() {
        buffer.recordAt(USER_WRITE, CONTENT_ID, 500, 1_000L);
        Map<String, String> snapshot = buffer.snapshot(USER_WRITE);

        long removed = buffer.acknowledge(USER_WRITE, snapshot);

        assertThat(removed).isEqualTo(1L);
        assertThat(redis.hasKey(CacheKeys.historyReport(USER_WRITE))).isFalse();
        assertThat(redis.opsForZSet().score(CacheKeys.HISTORY_DIRTY, String.valueOf(USER_WRITE))).isNull();
    }

    /**
     * ★ 这条是"读 → 写 → **条件**删"的核心判别力。
     *
     * <p>场景：落库期间用户又上报了更新的进度。无条件删（或"弹出"后回写）会让新值丢失/被旧值覆盖；
     * 本实现只在"值仍是落库时那个"时才删 ⇒ 新值留下，下一轮再写。
     */
    @Test
    @DisplayName("确认只删值未变的 field：窗口内到来的新上报必须留下")
    void acknowledgeOnlyRemovesUnchangedFields() {
        buffer.recordAt(USER_WRITE, CONTENT_ID, 500, 1_000L);
        Map<String, String> snapshot = buffer.snapshot(USER_WRITE);

        buffer.recordAt(USER_WRITE, CONTENT_ID, 900, 2_000L);
        long removed = buffer.acknowledge(USER_WRITE, snapshot);

        assertThat(removed).as("值已变 ⇒ 不该删").isZero();
        assertThat(redis.opsForHash().get(CacheKeys.historyReport(USER_WRITE), String.valueOf(CONTENT_ID)))
                .as("新值必须还在")
                .isEqualTo("900:2000");
        assertThat(redis.opsForZSet().score(CacheKeys.HISTORY_DIRTY, String.valueOf(USER_WRITE)))
                .as("还有脏数据 ⇒ 脏标记不能被摘")
                .isEqualTo(2000.0);
    }

    /**
     * ★ 兜底判据只有一条：{@code score < now - idleThreshold}。
     * 用 {@code recordAt} 拨时间，不 {@code Thread.sleep}（60s 级阈值没法真等）。
     */
    @Test
    @DisplayName("够闲的用户被取出，活跃用户不被取出")
    void idleUserIdsSelectsOnlyUsersPastThreshold() {
        long now = System.currentTimeMillis();
        long threshold = props.idleThreshold().toMillis();

        buffer.recordAt(USER_IDLE, CONTENT_ID, 100, now - threshold - 10_000L);
        buffer.recordAt(USER_ACTIVE, CONTENT_ID, 200, now);

        assertThat(buffer.idleUserIds())
                .as("空闲超过阈值的用户应被兜底")
                .contains(USER_IDLE)
                .as("刚上报过的用户不应被兜底（正常退出走 flush）")
                .doesNotContain(USER_ACTIVE);
    }

    /**
     * ★ 断言必须**贴着配置值**，不能只写 {@code isPositive()}：
     * 把 Lua 里的 {@code EXPIRE KEYS[1] ARGV[5]} 改成常量 {@code EXPIRE KEYS[1] 60} 时，
     * "正数且 ≤ 配置" 的弱断言照样绿 —— 而缓冲会比配置早 29 分钟消失。
     */
    @Test
    @DisplayName("缓冲 TTL 贴着配置值（不能被常量 / 截断悄悄改写）")
    void bufferHasConfiguredTtl() {
        buffer.recordAt(USER_WRITE, CONTENT_ID, 500, 1_000L);

        long expected = props.bufferTtl().toSeconds();

        assertThat(redis.getExpire(CacheKeys.historyReport(USER_WRITE)))
                .isBetween(expected - 2, expected);
    }

    /** 活跃用户不能被 TTL 中途淘汰：每次上报都要把 TTL 拉回满值。 */
    @Test
    @DisplayName("每次上报都续期：被压短的 TTL 在下次上报后回到配置值")
    void eachReportRenewsTtl() {
        buffer.recordAt(USER_WRITE, CONTENT_ID, 500, 1_000L);
        redis.expire(CacheKeys.historyReport(USER_WRITE), Duration.ofSeconds(5));

        buffer.recordAt(USER_WRITE, CONTENT_ID, 600, 2_000L);

        long expected = props.bufferTtl().toSeconds();
        assertThat(redis.getExpire(CacheKeys.historyReport(USER_WRITE)))
                .isBetween(expected - 2, expected);
    }

    /**
     * ★ 亚秒 TTL 的坑：{@code Duration.toSeconds()} 向下取整 ⇒ {@code 300ms} 变成 {@code "0"}，
     * 而 Redis 的 {@code EXPIRE key 0} 是**立即删除**（且返回 1、不报错）⇒ 刚写的值凭空消失。
     */
    @Test
    @DisplayName("亚秒 TTL 不会变成 EXPIRE 0（否则写完即被删）")
    void subSecondTtlDoesNotDeleteKey() {
        ops.write(USER_WRITE, CONTENT_ID, 500, 1_000L, Duration.ofMillis(300));

        assertThat(redis.hasKey(CacheKeys.historyReport(USER_WRITE))).isTrue();
        assertThat(redis.getExpire(CacheKeys.historyReport(USER_WRITE))).isPositive();
    }

    /**
     * ★ <b>僵尸脏成员</b>：hash 因 TTL 过期 / 被淘汰而消失，但 {@code history:dirty}（无 TTL）里
     * 的成员还在。典型成因 = 应用停机超过 {@code buffer-ttl}。
     *
     * <p>这些成员 score 最老 ⇒ 每轮 {@code ZRANGEBYSCORE LIMIT 50} 都会**先占满名额**，
     * 真正待落库的用户永远取不到 ⇒ 兜底整体失效。故"空快照也必须走一趟确认"。
     */
    @Test
    @DisplayName("僵尸脏成员（hash 已没了）在确认后被摘掉")
    void acknowledgeClearsZombieMemberWithoutHash() {
        redis.opsForZSet().add(CacheKeys.HISTORY_DIRTY, String.valueOf(USER_IDLE), 1_000.0);

        long removed = buffer.acknowledge(USER_IDLE, Map.of());

        assertThat(removed).isZero();
        assertThat(redis.opsForZSet().score(CacheKeys.HISTORY_DIRTY, String.valueOf(USER_IDLE)))
                .as("不清掉就会被每轮兜底反复扫到并占满名额")
                .isNull();
    }

    /**
     * 键形状冻结（`CacheKeys` 类头承诺"测试里硬编码形状"）。
     *
     * <p>⚠️ 这条在今天的实现上是绿的 —— 它的价值是**改 key 字符串时变红**，
     * 而不是证明当前行为。改名会让已部署实例里正在缓冲的数据变成永不落库的孤儿。
     */
    @Test
    @DisplayName("键形状冻结：history:report:{id} / history:dirty")
    void keyShapesAreFrozen() {
        assertThat(CacheKeys.historyReport(7L)).isEqualTo("history:report:7");
        assertThat(CacheKeys.HISTORY_DIRTY).isEqualTo("history:dirty");
    }

    @Test
    @DisplayName("点查单条：命中返回进度，未命中返回 empty")
    void readOneReturnsProgressOrEmpty() {
        buffer.recordAt(USER_WRITE, CONTENT_ID, 321, 1_000L);

        assertThat(buffer.cachedProgress(USER_WRITE, CONTENT_ID)).hasValue(321);
        assertThat(buffer.cachedProgress(USER_WRITE, CONTENT_ID + 1)).isEmpty();
    }

    /**
     * 自检的契约是"**绝不阻断启动**"：托管 Redis 禁用 CONFIG 是合法情形。
     * 上下文能起来 + 调用不抛，即已证明；值本身（noeviction / 其他）不作为断言（环境相关）。
     */
    @Test
    @DisplayName("maxmemory-policy 自检不抛异常（读不到也合法）")
    void policyProbeNeverThrows() {
        assertThatCode(() -> ops.maxmemoryPolicy()).doesNotThrowAnyException();
        Optional<String> policy = ops.maxmemoryPolicy();
        assertThat(policy).isNotNull();
    }
}
