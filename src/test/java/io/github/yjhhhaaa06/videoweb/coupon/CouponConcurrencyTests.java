package io.github.yjhhhaaa06.videoweb.coupon;

import io.github.yjhhhaaa06.videoweb.common.exception.ConflictException;
import io.github.yjhhhaaa06.videoweb.coupon.service.CouponService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1 并发语义测试——**本切片最有价值的测试**（《切片计划》§一 验收要点）。
 *
 * <h2>为什么必须有它</h2>
 * coupon 是唯一"零跨模块依赖"却带**并发正确性**的模块，且它演练的正是 user 切片没有的新模式：
 * <b>唯一键竞态 + 原子扣减</b>。这两条都不能靠单线程用例证明——
 * 单线程下"先查再扣"也能通过，只有真并发才能把 TOCTOU 窗口暴露出来。
 *
 * <h2>测什么（两条独立性质）</h2>
 * <ol>
 *   <li>{@link #库存为一的券并发抢购只成功一次()}：**库存原子性**。
 *       N 个不同用户同时抢库存 1 的券 ⇒ 恰好 1 人成功。
 *       若有人把 {@code UPDATE ... WHERE stock > 0} 改成"先 SELECT 判断再 UPDATE"，
 *       多个线程会同时读到 stock=1 而全部成功 ⇒ 超发，本用例失败。</li>
 *   <li>{@link #同一用户并发抢券库存只扣一次()}：**唯一键兜底 + 回滚**。
 *       同一用户 N 个并发请求抢库存 5 的券 ⇒ 恰好 1 次成功，且库存**恰好减 1**。
 *       失败者撞 {@code uk_coupon_user} ⇒ 整笔回滚，刚扣掉的库存被还原。
 *       若事务边界被破坏（去掉 {@code @Transactional}），库存会被扣 N 次 ⇒ 本用例失败。</li>
 * </ol>
 *
 * <h2>为什么走 Service 而不走 HTTP</h2>
 * 被测性质属于"事务 + SQL"层，与路由/信封无关；直接调 Service 能同时得到
 * **真实的并发**与**可断言的异常类型**（HTTP 层会把它们统一压成 409，反而丢失信息）。
 * 函数/契约层的行为已由 {@link CouponFlowTests} 覆盖。
 *
 * <h2>失败即真缺陷</h2>
 * 本类**不放宽**异常断言：失败者的异常类型必须精确匹配预期
 * （期望 {@link ConflictException} 就不得是 {@code DeadlockLoserDataAccessException}）。
 * 放宽会掩盖死锁/锁等待问题，那正是并发测试要抓的东西。
 *
 * <p>数据隔离：用户 id 由测试直接指定（{@code coupon_order.user_id} 无外键指向 users），
 * 故无需真的注册用户——让并发测试保持"纯事务层"且不被 bcrypt 拖慢。
 */
class CouponConcurrencyTests extends AbstractCouponIntegrationTest {

    private static final int THREADS = 8;

    @Autowired
    private CouponService couponService;

    @Test
    @DisplayName("库存为 1 的券被 8 个用户并发抢：恰好 1 人成功、库存 0、订单 1 行（★库存原子性）")
    void 库存为一的券并发抢购只成功一次() throws Exception {
        long couponId = insertCoupon("限量一张", 1, WINDOW_OPEN);
        long[] userIds = userIdsFrom(3001L, THREADS);

        List<GrabOutcome> outcomes = runConcurrentGrabs(couponId, userIds);

        long successes = outcomes.stream().filter(GrabOutcome::success).count();
        assertThat(successes)
                .as("库存为 1 时并发抢券必须恰好成功 1 次——多于 1 次即超发（扣减不是原子的）")
                .isEqualTo(1);

        // 失败者必须是"库存不足"这一类业务冲突，不得是死锁/锁超时等基础设施异常
        assertThat(failuresOf(outcomes))
                .as("失败原因应全部是 ConflictException（库存已被抢空）；出现其它异常说明存在并发缺陷")
                .allSatisfy(t -> assertThat(t).isInstanceOf(ConflictException.class));

        // 独立 oracle：DB 终态
        assertThat(stockOf(couponId)).as("库存必须恰好为 0，且不得为负").isZero();
        assertThat(countOrders(couponId, null)).as("恰好产生 1 行订单").isEqualTo(1L);

        long winnerId = userIds[indexOfFirstSuccess(outcomes)];
        assertThat(countOrders(couponId, winnerId))
                .as("订单必须落在唯一的成功者名下")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("同一用户 8 个并发请求抢库存 5 的券：成功 1 次、库存恰减 1（★唯一键兜底 + 回滚）")
    void 同一用户并发抢券库存只扣一次() throws Exception {
        long couponId = insertCoupon("同一用户限量", 5, WINDOW_OPEN);
        long userId = 4001L;
        long[] userIds = new long[THREADS];
        java.util.Arrays.fill(userIds, userId);

        List<GrabOutcome> outcomes = runConcurrentGrabs(couponId, userIds);

        assertThat(outcomes.stream().filter(GrabOutcome::success).count())
                .as("唯一键 uk_coupon_user 保证同一用户只可能成功一次")
                .isEqualTo(1);

        assertThat(failuresOf(outcomes))
                .as("失败者应是撞唯一键（DuplicateKeyException）——即 DB 层兜住了竞态")
                .allSatisfy(t -> assertThat(t).isInstanceOf(DuplicateKeyException.class));

        // ★核心断言★ 库存恰减 1：7 个失败者扣掉的库存必须全部被回滚还原。
        //   若 @Transactional 被去掉，这里会是 5-8 = -3（或被 CHECK 约束挡成 500），测试失败。
        assertThat(stockOf(couponId))
                .as("失败请求必须整笔回滚：库存只能被扣一次")
                .isEqualTo(4);
        assertThat(countOrders(couponId, userId)).isEqualTo(1L);
    }

    // ==================== 并发执行器 ====================

    /** 一次抢券尝试的结果：成功，或失败并带上**原始异常**（供上层精确断言类型）。 */
    private record GrabOutcome(boolean success, Throwable error) {
    }

    /**
     * 用 {@link CountDownLatch} 做真实的同时起跑（而非"提交后各跑各的"）：
     * 全部线程先阻塞在 {@code startGate}，主线程放行后一起冲——
     * 这样才能制造出对同一行的锁竞争。
     */
    private List<GrabOutcome> runConcurrentGrabs(long couponId, long[] userIds) throws Exception {
        int n = userIds.length;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(n);
        List<Future<GrabOutcome>> futures = new ArrayList<>(n);
        try {
            for (long userId : userIds) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    try {
                        couponService.grabCoupon(couponId, userId);
                        return new GrabOutcome(true, null);
                    } catch (Throwable t) {
                        return new GrabOutcome(false, t);
                    } finally {
                        finished.countDown();
                    }
                }));
            }

            startGate.countDown();

            assertThat(finished.await(60, TimeUnit.SECONDS))
                    .as("并发请求在 60 秒内未全部结束——疑似死锁或锁等待超时（InnoDB 默认锁等待上限 50s）")
                    .isTrue();

            List<GrabOutcome> outcomes = new ArrayList<>(n);
            for (Future<GrabOutcome> future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<Throwable> failuresOf(List<GrabOutcome> outcomes) {
        return outcomes.stream().filter(o -> !o.success()).map(GrabOutcome::error).toList();
    }

    private int indexOfFirstSuccess(List<GrabOutcome> outcomes) {
        for (int i = 0; i < outcomes.size(); i++) {
            if (outcomes.get(i).success()) {
                return i;
            }
        }
        throw new AssertionError("没有任何请求成功——与断言的成功数不一致");
    }

    private long[] userIdsFrom(long start, int count) {
        long[] ids = new long[count];
        for (int i = 0; i < count; i++) {
            ids[i] = start + i;
        }
        return ids;
    }
}
