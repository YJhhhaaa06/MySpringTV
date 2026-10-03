package io.github.yjhhhaaa06.videoweb.resilience;

import io.github.yjhhhaaa06.videoweb.content.AbstractContentIntegrationTest;
import io.github.yjhhhaaa06.videoweb.content.cache.ContentCache;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.model.cache.ContentCacheDTO;
import io.github.yjhhhaaa06.videoweb.follow.cache.FollowCache;
import io.github.yjhhhaaa06.videoweb.follow.dao.FollowDao;
import io.github.yjhhhaaa06.videoweb.like.cache.LikeCache;
import io.github.yjhhhaaa06.videoweb.like.dao.ContentLikeDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 单飞接线（第三批 T3 / 账 B1）——在**真实读路径**上证明"并发打同一 miss key，回源 DB 只发生一次"。
 *
 * <h2>手法</h2>
 * 用 {@link MockitoSpyBean} 把被读路径调用的 DAO 装载方法替换为"先睡一小段再返回定值"，
 * 既拉宽并发窗口、又避免依赖真实 DB 数据；再用 N 个线程**同时**打同一 miss key
 * （Redis 前置清空 ⇒ 必然 miss）。没有单飞时 N 个线程各自进 loader ⇒ DAO 被调 N 次；
 * 有单飞时只有 leader 进 loader、其余等待并共享结果 ⇒ DAO 被调 1 次。
 *
 * <h2>覆盖的四条读路径</h2>
 * <ul>
 *   <li>内容详情（{@code ContentCache.getContent} → 共享引擎 {@code CacheAside.get}）；</li>
 *   <li>索引懒重建（{@code ContentCache.ensureIndex} 的并发惊群）；</li>
 *   <li>点赞状态（{@code LikeCache} 自持三态机的 miss 回填）；</li>
 *   <li>关注状态（{@code FollowCache} 自持三态机的 miss 回填）。</li>
 * </ul>
 *
 * <h2>★ 反向验证</h2>
 * 去掉对应读路径的 {@code singleFlight.get(...)} 包裹，相应用例会因 DAO 调用次数 &gt; 1 而变红。
 */
@Tag("resilience")
class SingleFlightCacheTests extends AbstractContentIntegrationTest {

    private static final int THREADS = 8;
    private static final long LOAD_MILLIS = 300;

    @Autowired
    private ContentCache contentCache;

    @Autowired
    private LikeCache likeCache;

    @Autowired
    private FollowCache followCache;

    @MockitoSpyBean
    private ContentDao contentDao;

    @MockitoSpyBean
    private ContentLikeDao contentLikeDao;

    @MockitoSpyBean
    private FollowDao followDao;

    @BeforeEach
    void resetStubs() {
        Mockito.reset(contentDao, contentLikeDao, followDao);
    }

    @Test
    @DisplayName("★内容详情：并发 miss 只回源 DB 一次")
    void 内容详情并发miss只回源一次() throws Exception {
        long contentId = 9201L;
        clearRedis();
        ContentCacheDTO dto = new ContentCacheDTO();
        dto.setId(contentId);
        dto.setType(2);                 // 图文：媒体行可以没有 ⇒ 无需再桩媒体 DAO
        dto.setTitle("sf-content");
        doAnswer(slowReturn(dto)).when(contentDao).findContent(contentId);

        List<Object> results = runConcurrently(() -> contentCache.getContent(contentId));

        assertThat(results).as("所有并发读者都应拿到同一内容").doesNotContainNull();
        verify(contentDao, times(1)).findContent(contentId);
    }

    @Test
    @DisplayName("★索引懒重建：并发稀缺只触发一次全表重建（防惊群）")
    void 索引并发重建只回源一次() throws Exception {
        clearRedis();
        doAnswer(slowReturn(List.of())).when(contentDao).findAllContent();

        runConcurrently(() -> contentCache.getRecommendByFilter(null, null, 10));

        verify(contentDao, times(1)).findAllContent();
    }

    @Test
    @DisplayName("★点赞状态：并发 miss 只回源 DB 一次")
    void 点赞状态并发miss只回源一次() throws Exception {
        long userId = 9203L;
        long contentId = 9204L;
        clearRedis();
        doAnswer(slowReturn(Set.of())).when(contentLikeDao).findLikedContentIdsByUser(userId);

        List<Object> results = runConcurrently(() -> likeCache.isContentLiked(userId, contentId));

        assertThat(results).as("并发读者都应得到一致答案（未点赞 = false）").containsOnly(false);
        verify(contentLikeDao, times(1)).findLikedContentIdsByUser(userId);
    }

    @Test
    @DisplayName("★关注状态：并发 miss 只回源 DB 一次")
    void 关注状态并发miss只回源一次() throws Exception {
        long userId = 9205L;
        long targetId = 9206L;
        clearRedis();
        doAnswer(slowReturn(List.of())).when(followDao).findAllFollowedUserIds(userId);

        List<Object> results = runConcurrently(() -> followCache.isFollowing(userId, targetId));

        assertThat(results).as("并发读者都应得到一致答案（未关注 = false）").containsOnly(false);
        verify(followDao, times(1)).findAllFollowedUserIds(userId);
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    /**
     * 先睡 {@link #LOAD_MILLIS} 再返回定值——拉宽并发窗口，让 N 个线程真的堆在同一次在飞任务上。
     * 不用 {@code callRealMethod()}：这些 DAO 是 MyBatis 接口，Mockito 无法对接口调真实方法。
     */
    private static Answer<Object> slowReturn(Object value) {
        return invocation -> {
            Thread.sleep(LOAD_MILLIS);
            return value;
        };
    }

    /** 同期启动 N 个线程执行同一任务，收集结果；任一任务失败即抛出。 */
    private static List<Object> runConcurrently(Supplier<Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.get();
                }));
            }
            gate.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    results.add(future.get(20, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    throw new AssertionError("并发任务失败", e.getCause());
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
