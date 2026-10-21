package io.github.yjhhhaaa06.videoweb.content;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.content.model.ContentCountColumns;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * T44-2 验收：**详情计数聚合读**（{@code ContentCountAggregate}）——
 * 公共计数从"各自独立回源"改为"一趟 MGET + miss 单飞回源一条多列点查 + 有选择回填"。
 * T48 起聚合的键数 **4 → 5**（新增播放量），本类的选择性回填 / 单飞 / 降级口径对五个键同样适用。
 *
 * <h2>本类钉住的四件事（每一件都能被一行实现改动弄红）</h2>
 * <ol>
 *   <li><b>有选择回填</b>（T44-2 的正确性核心）：命中键**一个都不许碰**——
 *       预热 favorite 键为旧值 555、只失效 like 键，详情后 favorite 键必须仍是 555。
 *       ★ 反向验证：把聚合层的回填目标从"miss 集"换成"全键集"（即恢复"回填全部"的朴素形态）
 *       ⇒ {@link #只回填miss的键_命中键不被覆盖()} 的"favorite 键仍 555"红（会被列快照 7 覆盖）；
 *       那正是讨论中否决的形态——快照覆盖未失效的键会重开"旧值复活"窗口；</li>
 *   <li><b>单飞合一</b>：同内容的并发冷读把回源收敛到至多 3 次（20 线程；无单飞 = 20 次）
 *       （eq(contentId) 收窄 + 另一内容做对照哨兵；不钉 times(1) 的理由见用例内注释——
 *       线程调度无法保证全部线程时间上重叠，atMost 对"去单飞"形态判别等价且稳定）；</li>
 *   <li><b>脏值自愈</b>：非数字缓存值按 miss 处理并被回填覆盖；</li>
 *   <li><b>404 短路在聚合之前</b>：内容删除（五键中四键已被 REMOVE 监听器失效；playCount 由 removeContent 失效）后的详情请求
 *       不得把计数键回填出来——预热在前保证"键不存在"有判别力（假绿形态①的规避）。</li>
 * </ol>
 *
 * <h2>与 T43 用例的分工（G8）</h2>
 * {@link ContentCountTests} 钉"计数正确 + 载荷形状"（HTTP 可观察行为，换实现不许改）；
 * 本类钉 T44-2 新引入的**聚合语义**（趟合并 / 有选择回填 / 单飞 / 降级）——它们是
 * 内容读路径内部机制，只能用缓存侧 oracle 直接观测。
 */
class ContentDetailCountAggregateTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900011001";
    private static final String AUTHOR = "agg-author";
    private static final String VIEWER_PHONE = "13900011002";
    private static final String VIEWER = "agg-viewer";

    @MockitoSpyBean
    private io.github.yjhhhaaa06.videoweb.content.cache.ContentCountAggregate aggregate;

    @MockitoSpyBean
    private io.github.yjhhhaaa06.videoweb.content.dao.ContentDao contentDao;

    @BeforeEach
    void resetSpies() {
        Mockito.reset(aggregate, contentDao);
    }

    @Test
    @DisplayName("★只回填 miss 的键：命中键（favorite=555 旧值）不被列快照覆盖，miss 键（like）被回填")
    void 只回填miss的键_命中键不被覆盖() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "聚合-有选择回填");
        String viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);

        // 预热：详情一次，五键全部回填（like="0"）
        JsonNode before = Envelope.data(get("/search/IdSearch?contentId=" + contentId, authorToken));
        assertThat(before.path("likeCount").asLong()).as("前置：0 赞").isZero();

        // viewer 点赞：写路径事务内维护列（like_count=1），提交后 Lua 条件 INCR（键已预热 "0"→"1"）
        assertThat(postForm("/like/content/add", query("contentId", String.valueOf(contentId)),
                viewerToken).getStatusCode().value()).isEqualTo(200);

        // 造"命中但陈旧"的 favorite 键（555 ≠ 列真值 7）：聚合层若"回填全部"就会把它盖成 7
        jdbcTemplate.update("UPDATE content SET favorite_count = 7 WHERE id = ?", contentId);
        redis.opsForValue().set(CacheKeys.contentFavoriteCount(contentId), "555");

        // 只失效 like 键（模拟点赞域 DEL 自愈窗口）
        redis.delete(CacheKeys.contentLikeCount(contentId));

        JsonNode data = Envelope.data(get("/search/IdSearch?contentId=" + contentId, authorToken));
        assertThat(data.path("likeCount").asLong())
                .as("★ like 键 miss ⇒ 回源列并回填（写路径维护列 = 1）").isEqualTo(1L);
        assertThat(data.path("favoriteCount").asLong())
                .as("favorite 键命中 ⇒ 返回缓存旧值（cache-aside 语义：失效权在各域写路径）").isEqualTo(555L);

        assertThat(redis.opsForValue().get(CacheKeys.contentLikeCount(contentId)))
                .as("★ miss 键被回填为列值").isEqualTo("1");
        assertThat(redis.opsForValue().get(CacheKeys.contentFavoriteCount(contentId)))
                .as("★ 命中键绝不被聚合层覆盖（回填全部会让它变成 7 → 此断言红）").isEqualTo("555");
    }

    @Test
    @DisplayName("★五键聚合（T48）：冷内容五键全 miss ⇒ 一趟回源；playCount 命中键不被列快照覆盖，脏值按 miss")
    void 五键聚合与播放量选择性回填() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "聚合-播放量五键");
        jdbcTemplate.update("UPDATE content SET play_count = 4 WHERE id = ?", contentId);

        // 冷内容：五个键全 miss ⇒ 一趟 findCountColumns 取齐五列，五个键按列值回填
        JsonNode cold = Envelope.data(get("/search/IdSearch?contentId=" + contentId, token));
        assertThat(cold.path("playCount").asLong()).as("playCount = 列真值 4").isEqualTo(4L);
        assertThat(redis.opsForValue().get(CacheKeys.contentPlayCount(contentId)))
                .as("★ 五键之一：miss 的 playCount 键被回填为列值").isEqualTo("4");
        verify(contentDao, times(1)).findCountColumns(eq(contentId));

        // 命中但陈旧（7 ≠ 列真值 4）+ 只失效 like ⇒ playCount 一个都不许碰（有选择回填）
        redis.opsForValue().set(CacheKeys.contentPlayCount(contentId), "7");
        redis.delete(CacheKeys.contentLikeCount(contentId));
        JsonNode warm = Envelope.data(get("/search/IdSearch?contentId=" + contentId, token));
        assertThat(warm.path("playCount").asLong())
                .as("playCount 键命中 ⇒ 返回缓存值（cache-aside：失效权在写路径）").isEqualTo(7L);
        assertThat(redis.opsForValue().get(CacheKeys.contentPlayCount(contentId)))
                .as("★ 命中键绝不被聚合层覆盖（回填全部会让它变成 4 ⇒ 此断言红）").isEqualTo("7");

        // 脏值：非数字 ⇒ 按 miss 处理，被回填覆盖
        redis.opsForValue().set(CacheKeys.contentPlayCount(contentId), "not-a-number");
        JsonNode dirty = Envelope.data(get("/search/IdSearch?contentId=" + contentId, token));
        assertThat(dirty.path("playCount").asLong()).as("★ 脏值不外显（按 miss 回源列）").isEqualTo(4L);
        assertThat(redis.opsForValue().get(CacheKeys.contentPlayCount(contentId)))
                .as("★ 脏值被回填覆盖为列值").isEqualTo("4");
    }

    @Test
    @DisplayName("★单飞合一：聚合层入口真并发冷读把回源收敛到至多 3 次（20 线程；去单飞 = 20 次）")
    void 并发冷读只回源一次() throws Exception {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long target = insertVideoContent(authorId, "聚合-单飞合一");
        long sentinel = insertVideoContent(authorId, "聚合-哨兵内容");

        // ⚠️ 必须直调 readCounts：单飞只合并"时间上重叠"的 miss——HTTP 全链路（鉴权 + 载荷装载）
        // 会让线程错峰到达，先行者的单飞条目已清理，后来者合法地重新回源（两次），那不是缺陷。
        // 聚合层入口的同一瞬间并发才是 SingleFlight 要治的"惊群"。
        // ⚠️ 还必须 stub 掉 pipeline（恒返回全 miss）：否则 20 线程在 Redis 连接池上排队，
        // 错峰进入单飞——那同样是环境时序，不是单飞语义。stub 后所有线程零延迟到达 flight，
        // "合一"才被纯净观测。★ 反向验证：去掉 SingleFlight（loader 直接执行）⇒ times(1) 红。
        doReturn(java.util.Arrays.asList(null, null, null, null, null)).when(aggregate).pipeline(any());

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 3);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ContentCountColumns>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit((Callable<ContentCountColumns>) () -> {
                    start.await();
                    return aggregate.readCounts(target);
                }));
            }
            // 哨兵：另一内容同样冷读 3 次——断言按主体收窄（eq）才不会把哨兵的回源数进来
            for (int i = 0; i < 3; i++) {
                futures.add(pool.submit((Callable<ContentCountColumns>) () -> {
                    start.await();
                    return aggregate.readCounts(sentinel);
                }));
            }
            start.countDown();
            for (Future<ContentCountColumns> f : futures) {
                assertThat(f.get().likeCount()).as("并发冷读全部给出列真值（0 赞）").isZero();
            }

            // ★ 断言形态说明：为什么是 atMost(3) 而不是 times(1)——
            //   单飞的保证是"时间上重叠的 miss 只回源一次"，线程调度无法保证 20 线程零抖动地
            //   全部重叠（实测有 1~2 个线程晚于 leader 全程到达，那是合法的错峰 miss）。
            //   要抓的错误形态 = "去掉 SingleFlight，每线程各回源" ⇒ 20 次；atMost(3) 对该形态
            //   与 times(1) 等价（20 ≫ 3），但对调度抖动稳定（times(1) 会假红）。
            verify(contentDao, atMost(3)).findCountColumns(eq(target));
            verify(contentDao, atMost(3)).findCountColumns(eq(sentinel));
        } finally {
            pool.shutdownNow();
            Mockito.reset(aggregate);   // stub 泄漏防护：pipeline 的恒 miss stub 不得带出本用例
        }
    }

    @Test
    @DisplayName("★脏值按 miss 处理并被回填覆盖（回填是覆盖写，天然清脏）")
    void 脏值自愈() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "聚合-脏值");

        assertThat(get("/search/IdSearch?contentId=" + contentId, token).getStatusCode().value())
                .as("前置：详情可读（五键回填）").isEqualTo(200);

        redis.opsForValue().set(CacheKeys.contentCoinCount(contentId), "not-a-number");

        JsonNode data = Envelope.data(get("/search/IdSearch?contentId=" + contentId, token));
        assertThat(data.path("coinCount").asLong())
                .as("★ 脏值不外显（按 miss 回源列：coin_count = 0）").isZero();
        assertThat(redis.opsForValue().get(CacheKeys.contentCoinCount(contentId)))
                .as("★ 脏值被回填覆盖为列值").isEqualTo("0");
    }

    @Test
    @DisplayName("★内容删除后的 404 请求不得回填计数键；favorite 键按既存口径残留（不订阅 REMOVE）")
    void 删除后404不回填计数键() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "聚合-404短路");

        // 预热五键（否则"键不存在"零判别力——假绿形态①）
        assertThat(get("/search/IdSearch?contentId=" + contentId, authorToken).getStatusCode().value())
                .isEqualTo(200);
        assertThat(redis.hasKey(CacheKeys.contentLikeCount(contentId))).isTrue();

        // 给 favorite 键造一个可区分的陈旧值：它不被删除事件失效（既存口径），若 404 路径
        // 误回填，它会被列快照覆盖 → 用 "999" vs 列值 0 可判别
        redis.opsForValue().set(CacheKeys.contentFavoriteCount(contentId), "999");

        assertThat(postForm("/content/delete", query("contentId", String.valueOf(contentId)),
                authorToken).getStatusCode().value()).as("前置：作者删除").isEqualTo(200);

        assertThat(get("/search/IdSearch?contentId=" + contentId, authorToken).getStatusCode().value())
                .as("前置：详情 404").isEqualTo(404);

        // like / comment / coin 三键的域订阅 REMOVE（点赞物理删行、评论级联软删、投币列清零式失效），
        // 删除事件会把它们 DEL；404 短路在计数聚合之前 ⇒ 无人回填
        assertThat(redis.hasKey(CacheKeys.contentLikeCount(contentId)))
                .as("★ like 键不被 404 路径回填（且删除事件已失效它）").isFalse();
        assertThat(redis.hasKey(CacheKeys.contentCommentCount(contentId)))
                .as("★ comment 键不被 404 路径回填").isFalse();
        assertThat(redis.hasKey(CacheKeys.contentCoinCount(contentId)))
                .as("★ coin 键不被 404 路径回填").isFalse();
        // ★ favorite 键按**既存口径**残留：favorite_item 行删除时不删、favorite_count 列不重算，
        // favorite 域不订阅 REMOVE（与"残留成员指向已删内容永不外显"同款——无读路径消费，TTL 自愈）。
        // 此断言钉的是"回填全部"回归：若聚合层无视 miss 集把 favorite 也回填，"999"会被列快照
        // 覆盖 → 红；"聚合被挪到 404 之前"的回归由上面 like/comment/coin 三键的 hasKey 断言钉住。
        assertThat(redis.opsForValue().get(CacheKeys.contentFavoriteCount(contentId)))
                .as("★ favorite 键保持删除前的陈旧值（既存口径：不订阅 REMOVE + 404 不回填）")
                .isEqualTo("999");
    }

    @Test
    @DisplayName("★Redis 失败整趟降级：直查 DB（经单飞）答案仍正确，且不回填")
    void Redis失败整趟降级() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "聚合-降级");

        jdbcTemplate.update("UPDATE content SET coin_count = 5 WHERE id = ?", contentId);

        try {
            doThrow(new CacheUnavailableException("模拟 Redis 故障"))
                    .when(aggregate).pipeline(any());
            JsonNode data = Envelope.data(get("/search/IdSearch?contentId=" + contentId, token));
            assertThat(data.path("coinCount").asLong())
                    .as("★ 降级直查 DB，计数仍正确").isEqualTo(5L);
            assertThat(data.path("likeCount").asLong()).isZero();
        } finally {
            Mockito.reset(aggregate);
        }
        // 降级不写回（对齐 CacheAside 的 D4）：冷键不得被降级路径回填
        assertThat(redis.hasKey(CacheKeys.contentCoinCount(contentId)))
                .as("★ 降级路径不回填（D4：写回交给下次 miss 自愈）").isFalse();
    }
}
