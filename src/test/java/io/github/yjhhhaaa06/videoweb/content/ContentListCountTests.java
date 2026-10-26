package io.github.yjhhhaaa06.videoweb.content;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheMissSignal;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.content.cache.ContentCountBatchAggregate;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.model.ContentCountField;
import io.github.yjhhhaaa06.videoweb.content.model.ContentCountRow;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * GAP-01 验收：**列表计数批量聚合读**（{@code ContentCountBatchAggregate}）。
 *
 * <h2>期望值怎么来（独立 oracle）</h2>
 * 每条期望值都先按业务口径写出，再与实现比对：播放 / 赞 / 评的"真值"是
 * {@code content} 表上的列（T44-1 起单源化），所以断言直查 DB 列，**不信接口回显**；
 * 缓存侧的"回填了哪些键"只能直读 {@link org.springframework.data.redis.core.StringRedisTemplate}
 * 观测（DB 与接口都看不出"多回填了一个键"）。
 *
 * <h2>每件都能被一行实现改动弄红</h2>
 * <ol>
 *   <li><b>只回填 miss 的 (id, field)</b>：预热 play 键为 999、只让 like / comment 冷读 ⇒
 *       play 键必须仍是 999；改成"回填全部"会被列快照 3 覆盖 ⇒ 红。</li>
 *   <li><b>只读被请求的维度</b>：网格卡片只请求 {@code PLAY} ⇒ like / comment 键**一个都不许建**；
 *       改成"固定读五维"⇒ 红。</li>
 *   <li><b>不做单飞</b>：并发两次批量读 ⇒ DB **恰好 2 次**；若加了单飞会合并成 1 次 ⇒ 红。</li>
 *   <li><b>Redis 失败不回填</b>：pipeline 抛 {@link CacheUnavailableException} ⇒ 一趟 IN 直查、
 *       零 SET；改成"降级后仍回填" ⇒ 红。</li>
 *   <li><b>脏值自愈</b>：{@code "abc"} 按 miss，回填覆盖后恢复数字；不先 DEL。</li>
 *   <li><b>计数不进内容载荷</b>：{@code content:{id}} 的 JSON 不含任何计数字段（T43 的口径未破）。</li>
 *   <li><b>缺行按 0、不缓存</b>：防御分支不得把 0 钉进 TTL。</li>
 * </ol>
 */
class ContentListCountTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900013001";
    private static final String AUTHOR = "listcount-author";

    @MockitoSpyBean
    private ContentCountBatchAggregate aggregate;

    @MockitoSpyBean
    private ContentDao contentDao;

    @BeforeEach
    void resetSpies() {
        Mockito.reset(aggregate, contentDao);
        CacheMissSignal.clear();
    }

    // ==================== 端点：冷启动一趟 IN + 只回填请求的维度 ====================

    @Test
    @DisplayName("★冷启动：网格卡片只请求 play ⇒ 恰好一条 IN、只回填 play 键（like/comment 一个都不建）")
    void 冷启动一趟IN且只回填play键() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long c1 = insertVideoContent(authorId, "列表计数一");
        long c2 = insertVideoContent(authorId, "列表计数二");
        long c3 = insertVideoContent(authorId, "列表计数三");
        jdbcTemplate.update("UPDATE content SET play_count = 11 WHERE id = ?", c1);
        jdbcTemplate.update("UPDATE content SET play_count = 22 WHERE id = ?", c2);
        jdbcTemplate.update("UPDATE content SET play_count = 33 WHERE id = ?", c3);

        JsonNode list = Envelope.data(get("/api/v1/contents/recommended", null));

        // 独立 oracle：三条播放量分别等于 DB 列
        assertThat(itemOf(list, c1).path("playCount").asLong()).isEqualTo(11L);
        assertThat(itemOf(list, c2).path("playCount").asLong()).isEqualTo(22L);
        assertThat(itemOf(list, c3).path("playCount").asLong()).isEqualTo(33L);

        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(contentDao, times(1)).findCountColumnsByIds(ids.capture());
        assertThat(ids.getValue())
                .as("★ 一趟 IN 收齐本页全部 id（逐条调 findCountColumns = 3 条 SQL ⇒ 红）")
                .containsExactlyInAnyOrder(c1, c2, c3);

        assertThat(redis.opsForValue().get(CacheKeys.contentPlayCount(c1)))
                .as("miss 的 play 键被回填为列值").isEqualTo("11");
        assertThat(redis.hasKey(CacheKeys.contentLikeCount(c1)))
                .as("★ 本面没请求赞维 ⇒ 连键都不许建（固定读五维 ⇒ 红）").isFalse();
        assertThat(redis.hasKey(CacheKeys.contentCommentCount(c1)))
                .as("★ 本面没请求评论维 ⇒ 连键都不许建").isFalse();
    }

    @Test
    @DisplayName("★计数不进内容载荷：content:{id} 的 JSON 不含任何计数字段")
    void 计数不进内容载荷() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "载荷不含计数");
        jdbcTemplate.update("UPDATE content SET play_count = 9 WHERE id = ?", contentId);

        Envelope.data(get("/api/v1/contents/recommended", null));

        String cached = cachedContentJson(contentId);
        assertThat(cached).as("前置：载荷已缓存").isNotNull();
        assertThat(cached)
                .as("★ T43 的核心口径未破：计数是独立 key，不进 content:{id}")
                .doesNotContain("playCount", "likeCount", "commentCount",
                        "favoriteCount", "coinCount");
    }

    // ==================== 聚合层：有选择回填 / 不做单飞 / 失败 / 脏值 / 缺行 ====================

    @Test
    @DisplayName("★有选择回填：play 键命中 999 不被覆盖；like/comment 冷读回源并回填")
    void 只回填miss的键_命中键不被覆盖() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "列表计数选择性回填");
        jdbcTemplate.update("UPDATE content SET like_count = 1, comment_count = 2, play_count = 3 WHERE id = ?",
                contentId);

        redis.opsForValue().set(CacheKeys.contentPlayCount(contentId), "999");

        Map<Long, ContentCountRow> rows = aggregate.readCounts(List.of(contentId),
                Set.of(ContentCountField.LIKE, ContentCountField.COMMENT, ContentCountField.PLAY));

        ContentCountRow row = rows.get(contentId);
        assertThat(row.likeCount()).as("like 冷读 ⇒ 列真值").isEqualTo(1L);
        assertThat(row.commentCount()).as("comment 冷读 ⇒ 列真值").isEqualTo(2L);
        assertThat(row.playCount())
                .as("★ play 键命中 ⇒ 用缓存值（cache-aside；不是列快照 3）").isEqualTo(999L);

        assertThat(redis.opsForValue().get(CacheKeys.contentPlayCount(contentId)))
                .as("★ 命中键绝不被覆盖（回填全部会让它变成 3 ⇒ 红）").isEqualTo("999");
        assertThat(redis.opsForValue().get(CacheKeys.contentLikeCount(contentId)))
                .as("miss 的 like 键被回填").isEqualTo("1");
        assertThat(redis.opsForValue().get(CacheKeys.contentCommentCount(contentId)))
                .as("miss 的 comment 键被回填").isEqualTo("2");
    }

    @Test
    @DisplayName("★不做单飞：并发两次批量冷读 ⇒ DB 恰好 2 次（合并成 1 次说明加了单飞 ⇒ 红）")
    void 并发两次批量读不回源更多也不合并() throws Exception {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "列表计数无单飞");

        // 恒全 miss：把线程时序从 Redis 连接池里摘出来，"每趟各自回源"才被纯净观测
        doReturn(java.util.Arrays.asList(null, null, null)).when(aggregate).pipeline(any());

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Map<Long, ContentCountRow>>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return aggregate.readCounts(List.of(contentId),
                            Set.of(ContentCountField.LIKE, ContentCountField.COMMENT, ContentCountField.PLAY));
                }));
            }
            ready.await();
            go.countDown();
            for (Future<Map<Long, ContentCountRow>> future : futures) {
                assertThat(future.get()).as("两次读都要有结果").containsKey(contentId);
            }
        } finally {
            pool.shutdownNow();
        }

        verify(contentDao, times(2)).findCountColumnsByIds(eq(Set.of(contentId)));
    }

    @Test
    @DisplayName("★Redis 失败：一趟 IN 直查、零回填、CacheMissSignal 命中")
    void redis失败降级直查且不回填() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "列表计数降级");
        jdbcTemplate.update("UPDATE content SET like_count = 4, comment_count = 5, play_count = 6 WHERE id = ?",
                contentId);

        doThrow(new CacheUnavailableException("redis down")).when(aggregate).pipeline(any());

        Map<Long, ContentCountRow> rows = aggregate.readCounts(List.of(contentId),
                Set.of(ContentCountField.LIKE, ContentCountField.COMMENT, ContentCountField.PLAY));

        ContentCountRow row = rows.get(contentId);
        assertThat(row.likeCount()).isEqualTo(4L);
        assertThat(row.commentCount()).isEqualTo(5L);
        assertThat(row.playCount()).isEqualTo(6L);
        verify(contentDao, times(1)).findCountColumnsByIds(eq(List.of(contentId)));
        assertThat(redis.hasKey(CacheKeys.contentLikeCount(contentId))
                        || redis.hasKey(CacheKeys.contentCommentCount(contentId))
                        || redis.hasKey(CacheKeys.contentPlayCount(contentId)))
                .as("★ 降级路径不回填（下次读重试）").isFalse();
        assertThat(CacheMissSignal.consume()).as("整趟降级 = 没走成缓存").isTrue();
    }

    @Test
    @DisplayName("★脏值自愈：\"abc\" 按 miss，回填覆盖为列值；不先 DEL")
    void 脏值按miss回填覆盖() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "列表计数脏值");
        jdbcTemplate.update("UPDATE content SET play_count = 8 WHERE id = ?", contentId);
        redis.opsForValue().set(CacheKeys.contentPlayCount(contentId), "abc");

        ContentCountRow row = aggregate.readCounts(List.of(contentId), Set.of(ContentCountField.PLAY))
                .get(contentId);

        assertThat(row.playCount()).as("脏值不外显（按 miss 回源列）").isEqualTo(8L);
        assertThat(redis.opsForValue().get(CacheKeys.contentPlayCount(contentId)))
                .as("★ 回填覆盖脏值").isEqualTo("8");
    }

    @Test
    @DisplayName("★缺行：按 0 作答且不缓存（防御分支，正常列表不会出现）")
    void 缺行按0且不缓存() {
        long missingId = 999_999_999L;

        ContentCountRow row = aggregate.readCounts(List.of(missingId), Set.of(ContentCountField.PLAY))
                .get(missingId);

        assertThat(row).as("每个入参 id 恰好一条（不重不漏）").isNotNull();
        assertThat(row.playCount()).isZero();
        assertThat(redis.hasKey(CacheKeys.contentPlayCount(missingId)))
                .as("★ 0 不能钉进 TTL（那会把'读不到'伪装成'没有'）").isFalse();
    }

    // ==================== 夹具 ====================

    /** 从列表节点里取指定 id 的条目（找不到直接失败——防"夹具没造上 / 被读面跳过"的假绿）。 */
    private static JsonNode itemOf(JsonNode list, long contentId) {
        for (JsonNode node : list) {
            if (node.path("id").asLong() == contentId) {
                return node;
            }
        }
        throw new AssertionError("列表里没有 id=" + contentId + "（夹具未命中或被读面跳过）");
    }
}
