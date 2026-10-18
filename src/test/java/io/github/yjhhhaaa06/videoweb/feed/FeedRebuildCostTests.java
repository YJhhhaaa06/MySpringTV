package io.github.yjhhhaaa06.videoweb.feed;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.config.RateLimitProperties;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.feed.cache.FeedCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

/**
 * 任务主体自付费的**接线**契约（限流专项 T27；口径唯一事实源 = {@code architecture/tech/rate-limit/限流.md}
 * §四）——断言"重建被执行 ⇒ 服务器桶按 {@code task-cost} 减少"，而不是断言 Lua 脚本内部的
 * 扣减数学（那由 {@code RateLimitRedisOpsTests} 的独立 oracle 覆盖）。
 *
 * <h2>oracle</h2>
 * 直读 Redis 的 {@code ratelimit:server} / {@code ratelimit:user:{id}}（不信任任何回显）。
 * ★ **满桶起步**是本类的精确性来源：桶存量 = cap 时，"先补后扣"的补充量被 {@code min(cap, …)}
 * 截断吸收 ⇒ 扣后值**精确等于** {@code cap − task-cost}，无需任何时钟近似
 * （本环境 refill=10^6/s，半桶起步的断言会被"断言窗口内的补充量"淹没——别改掉满桶起步）。
 *
 * <h2>★ 为什么关掉 MQ 消费者（{@code auto-startup=false}）</h2>
 * 本类断言的是**全局桶的绝对值**——任何异步重建（关注事件 → AFTER_COMMIT → 去抖 1s → MQ 消费线程）
 * 都会在夹具设值与断言之间偷偷扣一次 {@code task-cost}，把精确断言打红（T25 匿名桶夹具前提的同族问题）。
 * 本仓生产里 {@code rebuildInbox} 的唯一调用方就是重建消费者 ⇒ 关掉本上下文的消费者后，
 * 唯一到达扣减点的路径就是用例里的直接调用（F-24 那类"别域异步调用进验证窗口"在此被结构性排除）。
 *
 * <h2>★ 反向验证</h2>
 * 去掉 {@code FeedRebuildService} 里的 {@code deductTaskCost(userId)} 一行
 * ⇒ {@link #重建执行_服务器桶按taskCost扣减} 与 {@link #DB失败仍计费_计费点在重建步骤之前} 必红；
 * 把扣减挪到锁之前 ⇒ {@link #锁跳过不扣_谁干活谁付费} 必红；
 * 让任务侧误碰用户桶 ⇒ {@link #用户桶分毫未动} 必红。
 */
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class FeedRebuildCostTests extends AbstractFeedIntegrationTest {

    private static final String PHONE_VIEWER = "13900008001";
    private static final String PHONE_AUTHOR = "13900008002";

    /** 服务器桶 key（与 {@code CacheKeys} 一致由生产代码传入；此处直接取，防两边漂移）。 */
    private static final String SERVER_KEY = CacheKeys.rateLimitServer();

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private FeedCache feedCache;

    @MockitoSpyBean
    private ContentDao contentDao;

    @BeforeEach
    void resetStubs() {
        Mockito.reset(contentDao);
    }

    @Test
    @DisplayName("★重建被执行 ⇒ 服务器桶精确减少 task-cost（满桶起步 ⇒ 无时钟近似）")
    void 重建执行_服务器桶按taskCost扣减() {
        long viewer = registerUser(PHONE_VIEWER, "viewer");
        int taskCost = rateLimitProperties.server().taskCost();
        long t0 = System.currentTimeMillis();
        putBucket(SERVER_KEY, String.valueOf(rateLimitProperties.server().capacity()), t0);

        feedRebuildService.rebuildInbox(viewer);

        String raw = rawOf(SERVER_KEY);
        assertThat(raw).as("扣减必须写回服务器桶（接线存在性的直接判据）").isNotNull();
        assertThat(tokensOf(raw))
                .as("满桶起步 ⇒ 补充被 min(cap,…) 截断吸收 ⇒ 精确 = cap − taskCost")
                .isEqualTo(rateLimitProperties.server().capacity() - (double) taskCost);
        assertThat(lastRefillMsOf(raw))
                .as("写即刷新：lastRefillMs 至少推进到夹具设值时刻").isGreaterThanOrEqualTo(t0);
    }

    @Test
    @DisplayName("★用户桶分毫未动：值与 lastRefillMs 逐字保留（任务成本不属于任何用户）")
    void 用户桶分毫未动() {
        long viewer = registerUser(PHONE_VIEWER, "viewer");
        String userKey = CacheKeys.rateLimitUser(viewer);
        long t0 = System.currentTimeMillis();
        putBucket(userKey, "5", t0);

        feedRebuildService.rebuildInbox(viewer);

        assertThat(rawOf(userKey))
                .as("任务侧只扣服务器桶；用户桶连 lastRefillMs 都不许变（字节级对照）")
                .isEqualTo("5:" + t0);
    }

    @Test
    @DisplayName("★锁跳过（并发去重 / Redis 不可用降级）⇒ 不扣——没干活不付钱")
    void 锁跳过不扣_谁干活谁付费() {
        long viewer = registerUser(PHONE_VIEWER, "viewer");
        long t0 = System.currentTimeMillis();
        putBucket(SERVER_KEY, "100", t0);
        assertThat(feedCache.tryAcquireRebuildLock(viewer, "pre-held-token"))
                .as("夹具预占重建锁 ⇒ rebuildInbox 必走跳过分支").isTrue();

        feedRebuildService.rebuildInbox(viewer);

        assertThat(rawOf(SERVER_KEY))
                .as("锁跳过 = 本次没干活 = 不计费（扣减点在锁成功之后）")
                .isEqualTo("100:" + t0);
        feedCache.releaseRebuildLock(viewer, "pre-held-token");
    }

    @Test
    @DisplayName("★DB 失败仍计费：计费点在重建步骤之前——'试图干活'即扣，不成功不退")
    void DB失败仍计费_计费点在重建步骤之前() {
        long viewer = registerUser(PHONE_VIEWER, "viewer");
        long author = registerUser(PHONE_AUTHOR, "author");
        insertFollowRow(viewer, author);
        insertVideoContent(author, "本应进窗口的内容");
        int taskCost = rateLimitProperties.server().taskCost();
        long t0 = System.currentTimeMillis();
        putBucket(SERVER_KEY, String.valueOf(rateLimitProperties.server().capacity()), t0);
        // 让"替换窗口"事务失败（同 FeedMechanismTests 的注入点）⇒ 重建中止、降级吞掉
        doThrow(new DataAccessException("模拟窗口重算失败") {
        }).when(contentDao).findRecentContentIdsByUsers(anyList(), anyInt());

        feedRebuildService.rebuildInbox(viewer);

        assertThat(countInboxRows(viewer)).as("对照：重建确实被中止（窗口零行）").isZero();
        assertThat(isSynced(viewer)).as("对照：同步态也未落").isFalse();
        assertThat(tokensOf(rawOf(SERVER_KEY)))
                .as("已过锁、已计费：DB 失败不退钱（'谁干活谁付费'对'试图干活'同样成立）")
                .isEqualTo(rateLimitProperties.server().capacity() - (double) taskCost);
    }

    // ==================== 夹具与 oracle ====================

    private long registerUser(String phone, String username) {
        registerAndGetToken(phone, username, PASSWORD);
        return userIdOf(phone);
    }

    /** 直写桶状态（紧凑字符串 {@code "tokens:lastRefillMs"}，与脚本的存储形态同形）。 */
    private void putBucket(String key, String tokens, long lastMs) {
        redis.opsForValue().set(key, tokens + ":" + lastMs);
    }

    private String rawOf(String key) {
        return redis.opsForValue().get(key);
    }

    private double tokensOf(String raw) {
        return Double.parseDouble(raw.split(":")[0]);
    }

    private long lastRefillMsOf(String raw) {
        return Long.parseLong(raw.split(":")[1]);
    }
}
