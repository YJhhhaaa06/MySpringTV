package io.github.yjhhhaaa06.videoweb.coin;

import io.github.yjhhhaaa06.videoweb.coin.cache.CoinRedisOps;
import io.github.yjhhhaaa06.videoweb.coin.dao.ContentCoinDao;
import io.github.yjhhhaaa06.videoweb.coin.service.CoinService;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 投币专项 T39：coin 缓存层（两个 key）+ 内容删除级联的**缓存语义与时序**测试。
 *
 * <h2>为什么必须单列一个类</h2>
 * {@code CoinAddTests} / {@code CoinSignInTests} 钉的是"接口返回什么"，它们**无法**证明缓存
 * 这一侧的语义：三态读、空标记、条件写、提交后时机，在"有缓存 / 无缓存"两种实现下都能给出
 * 同样的 HTTP 响应。本类专门构造**只有正确实现才能通过**的场面，两侧各用独立 oracle：
 * <ul>
 *   <li><b>DB 侧</b>：直查 {@code content_coin} / {@code content.coin_count} / {@code users.coin_deci}
 *       —— 不信接口回显；</li>
 *   <li><b>Redis 侧</b>：{@link org.springframework.data.redis.core.StringRedisTemplate} 直读键值
 *       —— 键名**硬编码字面量**（键形状是冻结契约，见 {@code CacheKeys} 类注释）。</li>
 * </ul>
 *
 * <h2>★ 核心手法：预热 → 制造提交 / 回滚，两条用例唯一差别是事务结局</h2>
 * <ul>
 *   <li>{@link #事务回滚后两个key保持预热值()}：预热两个 key ⇒ 在外层事务里投币（事件在事务内
 *       发布）⇒ {@code setRollbackOnly} ⇒ 断言缓存**保持预热原值**。这是 {@code AFTER_COMMIT}
 *       的**真正存在证明**——事件已经发布、事务却回滚，若监听器不是提交后执行（如普通
 *       {@code @EventListener}），这里会看到 key 被更新。</li>
 *   <li>{@link #提交成功后两个key被条件写更新()}：同样预热，事务提交 ⇒ 断言两个 key 被更新
 *       （排除"缓存永不更新"的假绿）。</li>
 * </ul>
 *
 * <h2>其余不变式</h2>
 * <ul>
 *   <li>{@link #冷key投币不创建半套缓存()} —— 条件写的判别力；</li>
 *   <li>{@link #写路径最后一步失败后事务回滚且缓存未被污染()} —— 事务回滚 + 缓存不落；</li>
 *   <li>{@link #空集写空标记且下次读不回源()} / {@link #投币必须清掉空标记()} —— 空标记；</li>
 *   <li>{@link #miss时全量回填()} / {@link #批量计数一趟回源并回填()} —— 回填与批量纪律；</li>
 *   <li>{@link #回填非空集时清掉空标记()} —— 回填脚本里 {@code DEL KEYS[2]} 的判别力；</li>
 *   <li>{@link #内容删除只失效计数不删行()} —— 与 like 的 {@code deleteByContentId} 有意不同；</li>
 *   <li>{@link #Redis不可用时降级DB且不抛()} / {@link #DB失败不被降级吞掉()} —— 两种失败的区分。</li>
 * </ul>
 *
 * <h2>★ 反向验证清单（实测记录见任务回写）</h2>
 * <ol>
 *   <li>去掉两个 Lua 的 {@code if EXISTS} 守卫（无条件 {@code HSET} / {@code INCRBY}）
 *       ⇒ {@link #冷key投币不创建半套缓存()} 必红；</li>
 *   <li>把 {@code CoinChangedListener} 的 {@code AFTER_COMMIT} 改成普通 {@code @EventListener}
 *       （等价于"服务体内直接调"）⇒ {@link #事务回滚后两个key保持预热值()} 必红。</li>
 * </ol>
 */
class CoinCacheTests extends AbstractCoinIntegrationTest {

    /** 键形状是契约：这里写死字面量，改名必须让本类失败（同 like / favorite 的测试口径）。 */
    private static final String USER_COIN_AMOUNT_KEY = "user:coinAmount:";
    private static final String CONTENT_COIN_COUNT_KEY = "content:coinCount:";
    private static final String EMPTY_PREFIX = "empty:";

    private static final int VIDEO = 1;

    @Autowired
    private CoinService coinService;

    @Autowired
    private ContentService contentService;

    @Autowired
    private PlatformTransactionManager txManager;

    @MockitoSpyBean
    private ContentCoinDao contentCoinDao;

    @MockitoSpyBean
    private ContentDao contentDao;

    @MockitoSpyBean
    private CoinRedisOps coinRedisOps;

    /**
     * ★ reset 必须在**被测请求之前**（父类先清 DB / Redis，本方法再清桩）。
     * 否则上一条用例的 stub 会跨用例生效，出现"单独跑绿、整类跑红"的幽灵。
     */
    @BeforeEach
    void resetStubs() {
        Mockito.reset(contentCoinDao, contentDao, coinRedisOps);
    }

    // ==================== 条件写：冷 key 不建半套 ====================

    @Test
    @DisplayName("冷 key 投币不创建半套缓存（两个 key 一个字节都不写）；DB 真理仍正确")
    void 冷key投币不创建半套缓存() {
        TestUser author = registerUser("13910003901", "coincache_author_1");
        TestUser voter = registerUser("13910003911", "coincache_voter_1");
        setCoinDeci(voter.id(), 20);
        long contentId = insertVideoContent(author.id());

        coinService.add(voter.id(), contentId, 1);

        assertThat(hasKey(USER_COIN_AMOUNT_KEY + voter.id()))
                .as("条件写：HASH 原本不存在 ⇒ 不得创建").isFalse();
        assertThat(hasKey(CONTENT_COIN_COUNT_KEY + contentId))
                .as("条件写：计数 key 原本不存在 ⇒ 不得创建").isFalse();
        assertThat(hasKey(EMPTY_PREFIX + USER_COIN_AMOUNT_KEY + voter.id()))
                .as("冷 key 投币只清空标记，不得留下新标记").isFalse();
        // DB 是真理源：读回来必须正确（读 miss 会回源并回填）
        assertThat(amountOf(voter.id(), contentId)).isEqualTo(1);
        assertThat(coinCountOf(contentId)).isEqualTo(1);
        assertThat(coinService.getCoinAmount(voter.id(), contentId)).isEqualTo(1);
        assertThat(coinService.getCoinCount(contentId)).isEqualTo(1);
        assertThat(hashValue(voter.id(), contentId)).as("读 miss 后回填 HASH").isEqualTo("1");
        assertThat(countValue(contentId)).as("读 miss 后回填计数").isEqualTo("1");
    }

    @Test
    @DisplayName("★已加载 ⇒ 写**新总量**：已投 1 枚再投 1 枚 ⇒ HASH=2（写成 delta 会得 1）、计数 INCRBY=2")
    void 已加载时条件写命中且写的是累计总量() {
        TestUser author = registerUser("13910003902", "coincache_author_2");
        TestUser voter = registerUser("13910003912", "coincache_voter_2");
        setCoinDeci(voter.id(), 40);
        long other = insertVideoContent(author.id());
        long target = insertVideoContent(author.id());
        // 前置：该用户对**另一条**内容已有 1 枚、对目标已有 1 枚（DB 直插）
        setAmount(voter.id(), other, 1);
        setCoinCount(other, 1);
        setAmount(voter.id(), target, 1);
        setCoinCount(target, 1);
        // 读一次完成全量回填：HASH 里两条都是 1、目标计数 key 是 "1"
        assertThat(coinService.getCoinAmount(voter.id(), target)).isEqualTo(1);
        assertThat(hashValue(voter.id(), other)).as("前置：HASH 已加载且含另一条").isEqualTo("1");
        assertThat(hashValue(voter.id(), target)).as("前置：目标是 1 枚").isEqualTo("1");
        assertThat(coinService.getCoinCount(target)).isEqualTo(1);
        assertThat(countValue(target)).isEqualTo("1");

        coinService.add(voter.id(), target, 1);

        // ★ 判别力：HSET 写 delta（1）而不是 newAmount（2）⇒ 这里会读到 "1"；
        //   计数若写成 SET delta 而不是 INCRBY ⇒ 这里也会是 "1"。
        assertThat(hashValue(voter.id(), target))
                .as("HASH 已加载 ⇒ HSET 写**新总量** = 1 + 1 = 2").isEqualTo("2");
        assertThat(hashValue(voter.id(), other))
                .as("只写目标 field，别的 field 不得被覆盖/删除").isEqualTo("1");
        assertThat(countValue(target))
                .as("计数 key 已加载 ⇒ INCRBY delta ⇒ 1 + 1 = 2").isEqualTo("2");
        assertThat(amountOf(voter.id(), target)).as("DB 关系行也累加到 2").isEqualTo(2);
        assertThat(coinCountOf(target)).as("DB 计数列随之为 2").isEqualTo(2);
    }

    // ==================== ★ 事务时序：AFTER_COMMIT 的存在证明 ====================

    @Test
    @DisplayName("★事务回滚后两个 key 保持**预热原值**（AFTER_COMMIT 的存在证明：事件已发布、事务回滚 ⇒ 缓存没被动过）")
    void 事务回滚后两个key保持预热值() {
        TestUser author = registerUser("13910003904", "coincache_author_4");
        TestUser voter = registerUser("13910003914", "coincache_voter_4");
        setCoinDeci(voter.id(), 20);
        long contentId = insertVideoContent(author.id());

        // 预热：两个 key 都存在 ⇒ "缓存有没有被更新"才有可观察的痕迹
        // ★ 刻意**不**断言"回滚后两个 key 不存在"：冷 key 本来就不存在 ⇒ 那种断言在
        //   "事件没发"与"事件发了但回滚"两个世界都绿，是零判别力的。要观测的是"值没变"。
        String emptyKey = EMPTY_PREFIX + USER_COIN_AMOUNT_KEY + voter.id();
        redis.opsForValue().set(CONTENT_COIN_COUNT_KEY + contentId, "0", Duration.ofMinutes(15));
        redis.opsForHash().put(USER_COIN_AMOUNT_KEY + voter.id(), "999999999", "1");
        redis.expire(USER_COIN_AMOUNT_KEY + voter.id(), Duration.ofMinutes(15));
        assertThat(hasKey(CONTENT_COIN_COUNT_KEY + contentId)).isTrue();
        assertThat(hashValue(voter.id(), contentId)).as("前置：目标 field 还不存在").isNull();

        // 外层事务：add 加入该事务，事件在**事务内**发布，随后整体回滚
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            coinService.add(voter.id(), contentId, 1);
            assertThat(hashValue(voter.id(), contentId))
                    .as("★ 事务尚未提交 ⇒ 条件写（AFTER_COMMIT）此刻不得已经发生")
                    .isNull();
            assertThat(countValue(contentId))
                    .as("★ 事务尚未提交 ⇒ 计数 key 仍应是预热值 0")
                    .isEqualTo("0");
            status.setRollbackOnly();
            return null;
        });

        // ① DB 侧回滚了
        assertThat(amountOf(voter.id(), contentId)).as("投币行已回滚").isNull();
        assertThat(coinCountOf(contentId)).isZero();
        assertThat(coinDeciOf(voter.id())).as("扣款已回滚").isEqualTo(20);

        // ② ★ 缓存侧也必须"没动过"：回滚 ⇒ 监听器根本不执行
        assertThat(hashValue(voter.id(), contentId))
                .as("若把缓存写挪进事务体 / 用普通 @EventListener ⇒ 这里 field 已是 1 ⇒ 变红")
                .isNull();
        assertThat(countValue(contentId))
                .as("回滚不得让计数 key 变成 1（AFTER_COMMIT 的存在证明）")
                .isEqualTo("0");
        assertThat(hasKey(emptyKey)).as("预热时本就没有空标记").isFalse();
    }

    @Test
    @DisplayName("★正对照：同样预热、正常提交 ⇒ 两个 key 被条件写更新（排除\"缓存永不更新\"的假绿）")
    void 提交成功后两个key被条件写更新() {
        TestUser author = registerUser("13910003905", "coincache_author_5");
        TestUser voter = registerUser("13910003915", "coincache_voter_5");
        setCoinDeci(voter.id(), 20);
        long contentId = insertVideoContent(author.id());

        redis.opsForValue().set(CONTENT_COIN_COUNT_KEY + contentId, "0", Duration.ofMinutes(15));
        redis.opsForHash().put(USER_COIN_AMOUNT_KEY + voter.id(), "999999999", "1");
        redis.expire(USER_COIN_AMOUNT_KEY + voter.id(), Duration.ofMinutes(15));

        coinService.add(voter.id(), contentId, 1);

        assertThat(hashValue(voter.id(), contentId)).as("提交后 HASH 写新总量").isEqualTo("1");
        assertThat(countValue(contentId)).as("提交后计数 key +1").isEqualTo("1");
        assertThat(amountOf(voter.id(), contentId)).isEqualTo(1);
        assertThat(coinCountOf(contentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("写路径最后一步失败 ⇒ 整笔回滚、缓存保持预热值（事务内缓存写的前提）")
    void 写路径最后一步失败后事务回滚且缓存未被污染() {
        TestUser author = registerUser("13910003903", "coincache_author_3");
        TestUser voter = registerUser("13910003913", "coincache_voter_3");
        setCoinDeci(voter.id(), 20);
        long contentId = insertVideoContent(author.id());

        // ★ 必须预热：不预热时"两个 key 不存在"是恒真的（条件写只在 key 已存在时才写）⇒
        //   那种断言在"事件没发"与"事件发了但事务回滚"两个世界都绿，零判别力。
        redis.opsForValue().set(CONTENT_COIN_COUNT_KEY + contentId, "0", Duration.ofMinutes(15));
        redis.opsForHash().put(USER_COIN_AMOUNT_KEY + voter.id(), "999999999", "1");
        redis.expire(USER_COIN_AMOUNT_KEY + voter.id(), Duration.ofMinutes(15));

        // updateCoinCount 是 add 的**最后一步写**：打桩它失败 ⇒ "已有成功写之后"才回滚，
        // 对"有事务 vs 无事务"有鉴别力（首步失败两个世界都"什么都没写"）。
        doThrow(new DataAccessException("模拟计数更新失败") {
        }).when(contentDao).updateCoinCount(anyLong(), anyInt());

        assertThatThrownBy(() -> coinService.add(voter.id(), contentId, 1))
                .isInstanceOf(DataAccessException.class);

        assertThat(amountOf(voter.id(), contentId)).as("关系行已回滚").isNull();
        assertThat(coinCountOf(contentId)).isZero();
        assertThat(coinDeciOf(voter.id())).as("扣款已回滚").isEqualTo(20);
        assertThat(authorCoinDeciOf(author.id())).as("★作者收益也已回滚（四笔写都要验）").isZero();
        assertThat(hashValue(voter.id(), contentId))
                .as("失败路径不得写入目标 field（事件在最后一步写之后才发布）").isNull();
        assertThat(hashValue(voter.id(), 999999999L))
                .as("★ HASH 保持预热原值（无事务时这条会看到哨兵被清/被重写）").isEqualTo("1");
        assertThat(countValue(contentId))
                .as("★ 计数 key 保持预热值 0（监听器根本没执行）").isEqualTo("0");
    }

    // ==================== 三态读 / 空标记 / 回填 ====================

    @Test
    @DisplayName("空集 ⇒ 写空标记（短 TTL），下次读不再回源 DB")
    void 空集写空标记且下次读不回源() {
        TestUser voter = registerUser("13910003906", "coincache_voter_6");
        String emptyKey = EMPTY_PREFIX + USER_COIN_AMOUNT_KEY + voter.id();

        assertThat(coinService.getCoinAmount(voter.id(), 123456L)).isZero();
        assertThat(hasKey(emptyKey)).as("空集不建数据 key，改写空标记").isTrue();
        assertThat(hasKey(USER_COIN_AMOUNT_KEY + voter.id())).as("空 HASH 不存在").isFalse();
        assertThat(ttlSecondsOf(emptyKey))
                .as("空标记是**短 TTL**（口径 60s，与数据 key 的 15m 不同）")
                .isBetween(1L, 60L);
        verify(contentCoinDao, times(1)).findAmountsByUser(voter.id());

        // ★ 命中空标记 ⇒ 不回源（单条读两次，DB 仍只查过一次）
        assertThat(coinService.getCoinAmount(voter.id(), 654321L)).isZero();
        verify(contentCoinDao, times(1)).findAmountsByUser(voter.id());
    }

    @Test
    @DisplayName("miss ⇒ 全量回填 HASH 两个字段、TTL 为正；再读不再回源")
    void miss时全量回填() {
        TestUser voter = registerUser("13910003907", "coincache_voter_7");
        long first = insertVideoContent(voter.id());
        long second = insertVideoContent(voter.id());
        setAmount(voter.id(), first, 1);
        setAmount(voter.id(), second, 2);

        assertThat(coinService.getCoinAmount(voter.id(), first)).isEqualTo(1);

        String hashKey = USER_COIN_AMOUNT_KEY + voter.id();
        assertThat(redis.opsForHash().entries(hashKey))
                .as("全量回填：两条 amount > 0 的都在（不是只回填被读的那一条）")
                .containsEntry(String.valueOf(first), "1")
                .containsEntry(String.valueOf(second), "2")
                .hasSize(2);
        assertThat(ttlSecondsOf(hashKey))
                .as("回填 TTL 是数据 key 的 coin-ttl（15m），必须**显著长于空标记的 60s**"
                        + "（写成 60s / 1s 也算错，故不能只断言 isPositive）")
                .isGreaterThan(60L);

        // 再读一条**没投过**的内容：命中 HASH（field 缺失 ⇒ 0），不再回源
        assertThat(coinService.getCoinAmount(voter.id(), 888888L)).isZero();
        verify(contentCoinDao, times(1)).findAmountsByUser(voter.id());
    }

    @Test
    @DisplayName("★回填非空集必须清掉空标记（Lua 里的 DEL KEYS[2]）：否则 60s 假否定会挡住真数据")
    void 回填非空集时清掉空标记() {
        // 这是**命令层**用例（口径同 LikeCacheTests#非空回填清掉空标记）：空标记与数据 key 并存
        // 的唯一现实来源是并发（回填与空标记写入交错），走读路径构造不出来 ⇒ 直接驱动回填脚本。
        long userId = 555001L;
        String hashKey = USER_COIN_AMOUNT_KEY + userId;
        String emptyKey = EMPTY_PREFIX + hashKey;
        redis.opsForValue().set(emptyKey, "1", Duration.ofSeconds(60));
        assertThat(hasKey(emptyKey)).as("前置：空标记已立起").isTrue();

        coinRedisOps.backfillCoinAmountHash(hashKey, emptyKey, Map.of(42L, 2), Duration.ofMinutes(15));

        assertThat(redis.opsForHash().entries(hashKey)).as("回填写入数据").containsEntry("42", "2");
        assertThat(hasKey(emptyKey))
                .as("★ 若不 DEL 空标记：读路径先看它 ⇒ 刚回填的 2 会被 60s 假否定成 0")
                .isFalse();
    }

    @Test
    @DisplayName("★投币必须清掉空标记：清掉后读能自愈拿到刚投的 1（否则 60s 假否定）")
    void 投币必须清掉空标记() {
        TestUser author = registerUser("13910003908", "coincache_author_8");
        TestUser voter = registerUser("13910003918", "coincache_voter_8");
        setCoinDeci(voter.id(), 20);
        long contentId = insertVideoContent(author.id());

        assertThat(coinService.getCoinAmount(voter.id(), contentId)).isZero();   // 立起空标记
        String emptyKey = EMPTY_PREFIX + USER_COIN_AMOUNT_KEY + voter.id();
        assertThat(hasKey(emptyKey)).as("前置：空标记已建立").isTrue();

        coinService.add(voter.id(), contentId, 1);

        assertThat(hasKey(emptyKey))
                .as("条件写必须清空标记，否则 60s 内读会给出假的 0")
                .isFalse();
        assertThat(coinService.getCoinAmount(voter.id(), contentId))
                .as("清标记后读 miss → 回源 DB → 得到刚投的 1")
                .isEqualTo(1);
    }

    // ==================== 内容删除级联 ====================

    @Test
    @DisplayName("★内容删除只失效 count 缓存、不删 content_coin 行（与 like 的 deleteByContentId 有意不同）")
    void 内容删除只失效计数不删行() {
        TestUser voter = registerUser("13910003909", "coincache_voter_9");
        long contentId = insertVideoContent(voter.id());
        setAmount(voter.id(), contentId, 1);
        setCoinCount(contentId, 1);

        // 前置：把计数 key 加载出来（否则"有没有被失效"不可观察）
        assertThat(coinService.getCoinCount(contentId)).isEqualTo(1);
        assertThat(hasKey(CONTENT_COIN_COUNT_KEY + contentId)).isTrue();

        // 走真实框架入口：ContentService.hideContent 在**事务内**发布
        // ContentCacheChangedEvent.remove ⇒ AFTER_COMMIT 时 ContentRemovedCoinListener 失效。
        // ⚠️ 这里不直接 import content.event：ArchUnit 规则 3 只允许在 coin.event 包里消费
        //    别域事件，测试类落在 coin 包同样受约束；走内容域的公开 Service 反而更接近真实级联。
        contentService.hideContent(contentId);

        assertThat(hasKey(CONTENT_COIN_COUNT_KEY + contentId))
                .as("内容删除 ⇒ 计数 key 失效").isFalse();
        assertThat(amountOf(voter.id(), contentId))
                .as("★ content_coin 行必须留存（不可取消 + 不变量 coin_count == SUM(amount)）")
                .isEqualTo(1);
        assertThat(coinCountOf(contentId))
                .as("计数列也不得被内容删除改掉（只失效缓存）")
                .isEqualTo(1);
    }

    // ==================== 批量 ====================

    @Test
    @DisplayName("批量计数：冷 miss 一趟 DB 回填、覆盖全部 id（DB 没有的 = 0）；已缓存的再读不再查 DB")
    void 批量计数一趟回源并回填() {
        long first = insertVideoContent(424242L);
        long second = insertVideoContent(424242L);
        long absent = 99999999L;
        setCoinCount(first, 3);
        setCoinCount(second, 5);

        Map<Long, Long> cold = coinService.batchGetCoinCount(List.of(first, second, absent));

        assertThat(cold)
                .as("返回完整映射：DB 里没有的 id 记 0")
                .containsEntry(first, 3L)
                .containsEntry(second, 5L)
                .containsEntry(absent, 0L);
        verify(contentDao, times(1)).findCoinCountsByIds(anyCollection());
        assertThat(countValue(first)).as("命中项逐个回填").isEqualTo("3");
        assertThat(countValue(second)).isEqualTo("5");
        assertThat(hasKey(CONTENT_COIN_COUNT_KEY + absent))
                .as("DB 里没有的 id **不缓存**（内容不存在是 404 路径）")
                .isFalse();

        // 再读**已缓存**的两个 id ⇒ 一趟 MGET 足够，不再查 DB
        Mockito.clearInvocations(contentDao);
        assertThat(coinService.batchGetCoinCount(List.of(first, second)))
                .containsEntry(first, 3L)
                .containsEntry(second, 5L);
        verify(contentDao, never()).findCoinCountsByIds(anyCollection());
    }

    // ==================== ★ 两种失败的区分 ====================

    @Test
    @DisplayName("★Redis 不可用：三个读方法降级 DB；条件写失败不抛、投币照常提交且相关 key 被失效")
    void Redis不可用时降级DB且不抛() {
        TestUser author = registerUser("13910003910", "coincache_author_10");
        TestUser voter = registerUser("13910003920", "coincache_voter_10");
        long contentId = insertVideoContent(author.id());
        setAmount(voter.id(), contentId, 1);
        setCoinCount(contentId, 1);

        doThrow(new CacheUnavailableException("模拟 Redis 故障"))
                .when(coinRedisOps).existingOf(anyString(), anyString());
        doThrow(new CacheUnavailableException("模拟 Redis 故障"))
                .when(coinRedisOps).getString(anyString());
        doThrow(new CacheUnavailableException("模拟 Redis 故障"))
                .when(coinRedisOps).mget(anyList());

        assertThat(coinService.getCoinAmount(voter.id(), contentId)).isEqualTo(1);
        assertThat(coinService.getCoinCount(contentId)).isEqualTo(1);
        assertThat(coinService.batchGetCoinCount(List.of(contentId))).containsEntry(contentId, 1L);

        // ★ 写路径也必须验：读桩不覆盖 onCoinChanged 里的两台条件写。
        //   先把三个 key 预热出来，"写失败 ⇒ 尽力失效让读自愈"才有可观察痕迹。
        String hashKey = USER_COIN_AMOUNT_KEY + voter.id();
        String emptyKey = EMPTY_PREFIX + hashKey;
        String countKey = CONTENT_COIN_COUNT_KEY + contentId;
        redis.opsForHash().put(hashKey, "999999999", "1");
        redis.opsForValue().set(emptyKey, "1", Duration.ofSeconds(60));
        redis.opsForValue().set(countKey, "0", Duration.ofMinutes(15));
        doThrow(new CacheUnavailableException("模拟 Redis 故障"))
                .when(coinRedisOps).applyCoinAmountConditionalWrite(
                        anyString(), anyString(), anyLong(), anyInt());
        doThrow(new CacheUnavailableException("模拟 Redis 故障"))
                .when(coinRedisOps).applyCoinCountConditionalIncr(anyString(), anyInt());

        setCoinDeci(voter.id(), 20);
        // ★ 不抛就是契约（缓存故障不能让已提交的业务 500）
        coinService.add(voter.id(), contentId, 1);

        assertThat(amountOf(voter.id(), contentId)).as("业务写入不受缓存故障影响").isEqualTo(2);
        assertThat(coinCountOf(contentId)).isEqualTo(2);
        assertThat(coinDeciOf(voter.id())).isEqualTo(10);
        assertThat(hasKey(hashKey)).as("条件写失败 ⇒ 尽力失效 HASH，让下次读 miss 自愈").isFalse();
        assertThat(hasKey(emptyKey))
                .as("★ 空标记也必须失效（残留它 ⇒ 刚提交的币被 60s 假否定）").isFalse();
        assertThat(hasKey(countKey)).as("计数 key 也失效（避免陈旧 0 存活到 TTL）").isFalse();
    }

    @Test
    @DisplayName("★DB 失败不被降级吞掉：三个读入口的 DB 异常都必须上抛（不得吞成 0）")
    void DB失败不被降级吞掉() {
        TestUser voter = registerUser("13910003921", "coincache_voter_11");
        doThrow(new DataAccessException("模拟 DB 故障") {
        }).when(contentCoinDao).findAmountsByUser(anyLong());

        assertThatThrownBy(() -> coinService.getCoinAmount(voter.id(), 777777L))
                .as("getCoinAmount 的回源故障必须冒泡（→ 500），不得被降级成 0")
                .isInstanceOf(DataAccessException.class);

        // ★ 另外两条读入口各自 catch 的也是 CacheUnavailableException（不是 Exception）——
        //   只测 getCoinAmount 时，把 getCoinCount / 批量的 catch 放宽成 Exception 不会红。
        doThrow(new DataAccessException("模拟 DB 故障") {
        }).when(contentDao).findCoinCount(anyLong());
        assertThatThrownBy(() -> coinService.getCoinCount(888888L))
                .as("getCoinCount 的 DB 故障必须上抛").isInstanceOf(DataAccessException.class);

        doThrow(new DataAccessException("模拟 DB 故障") {
        }).when(contentDao).findCoinCountsByIds(anyCollection());
        assertThatThrownBy(() -> coinService.batchGetCoinCount(List.of(999999L)))
                .as("批量回源的 DB 故障必须上抛").isInstanceOf(DataAccessException.class);
    }

    // ==================== helpers ====================

    private long insertVideoContent(long authorId) {
        return insertContent(authorId, VIDEO, "coin-cache-video-" + authorId);
    }

    private boolean hasKey(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    private String hashValue(long userId, long contentId) {
        Object value = redis.opsForHash().get(USER_COIN_AMOUNT_KEY + userId, String.valueOf(contentId));
        return value == null ? null : value.toString();
    }

    private String countValue(long contentId) {
        return redis.opsForValue().get(CONTENT_COIN_COUNT_KEY + contentId);
    }

    private Long ttlSecondsOf(String key) {
        return redis.getExpire(key, TimeUnit.SECONDS);
    }
}
