package io.github.yjhhhaaa06.videoweb.comment;

import io.github.yjhhhaaa06.videoweb.comment.cache.CommentRedisOps;
import io.github.yjhhhaaa06.videoweb.comment.dao.CommentDao;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.content.AbstractContentIntegrationTest;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * S5 的**评论缓存语义与时序**测试（决策表 G-3 / G-4 / G-6 的固化）。
 *
 * <h2>本轮最有价值的两条</h2>
 * <ol>
 *   <li><b>{@link #事务回滚后评论缓存未被污染()} + {@link #提交后主楼缓存被失效()}</b>——
 *       AFTER_COMMIT 的存在证明。两者的**唯一差别**是事务成功还是回滚；把失效挪回事务内即失败。</li>
 *   <li><b>{@link #窗口装载只装被看的那一页()}</b>——T10-A 的核心性质：
 *       取第 1 页只装 1 条主楼，取第 3 页把水位推进到 3。
 *       这条一旦坏了（比如有人改成"一次装全量"），功能仍然全对、测试也全绿，
 *       只有**成本**悄悄回到 U-20 的老路。故必须直接断言 Redis 里的水位。</li>
 * </ol>
 *
 * <h2>其余不变式</h2>
 * <ul>
 *   <li>{@link #空标记让无评论的内容不再回源DB()} —— 防穿透</li>
 *   <li>{@link #Redis不可用时降级DB且不写回()} —— 两种失败的区分之一</li>
 *   <li>{@link #DB失败上抛而不是返回空列表()} —— ★ 两种失败的区分之二
 *       （TV 在这里会返回"没有评论"；本切片按 G-4 纠正）</li>
 *   <li>{@link #评论点赞后定向失效该主楼的replies字段()} /
 *       {@link #增回复只定向失效所在主楼的replies字段()} —— 失效重映射的**粒度**</li>
 * </ul>
 */
class CommentCacheTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900004001";
    private static final String AUTHOR = "ccache-author";
    private static final String VIEWER_PHONE = "13900004002";
    private static final String VIEWER = "ccache-viewer";

    @MockitoSpyBean
    private CommentDao commentDao;

    @MockitoSpyBean
    private CommentRedisOps commentRedisOps;

    @MockitoSpyBean
    private ContentDao contentDao;

    @BeforeEach
    void resetStubs() {
        Mockito.reset(commentDao, commentRedisOps, contentDao);
    }

    // ========================================================================
    // ★ 事务时序：AFTER_COMMIT 的存在证明
    // ========================================================================

    @Test
    @DisplayName("★事务回滚后评论缓存未被污染（AFTER_COMMIT 的存在证明）")
    void 事务回滚后评论缓存未被污染() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "回滚用内容");
        for (int i = 0; i < 3; i++) {
            insertMainComment(contentId, authorId, "主楼 " + i);
        }
        // 预热：分页读一次 ⇒ roots LIST 被装载（3 条）
        get("/comment/show?contentId=" + contentId + "&page=1&pageSize=10", null);
        Long prewarmed = redis.opsForList().size(rootsKey(contentId));
        assertThat(prewarmed).as("预热必须真的把主楼装进 LIST 才有判别力").isEqualTo(3L);

        // 让事务内的计数更新抛异常 ⇒ 整笔回滚
        doThrow(new DataAccessException("模拟写入失败") {
        }).when(contentDao).updateCommentCount(anyLong(), anyInt());

        ResponseEntity<String> resp = post("/comment/add",
                java.util.Map.of("contentId", contentId, "message", "回滚的评论"), token);
        assertThat(resp.getStatusCode().value()).as("写入失败应非 200：%s", resp.getBody())
                .isNotEqualTo(200);

        // ★ 断言：roots LIST **保持预热原值**。若失效被挪进事务内，这里会是 0 / key 不存在。
        assertThat(redis.opsForList().size(rootsKey(contentId)))
                .as("事务回滚 ⇒ 监听器不触发 ⇒ 评论缓存不得被改动")
                .isEqualTo(3L);
        assertThat(commentRows(contentId)).as("评论行也确实回滚了").isEqualTo(3);
    }

    @Test
    @DisplayName("★反向对照：提交成功后主楼缓存被失效（排除『永不失效』的假绿）")
    void 提交后主楼缓存被失效() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "提交用内容");
        for (int i = 0; i < 3; i++) {
            insertMainComment(contentId, authorId, "主楼 " + i);
        }
        get("/comment/show?contentId=" + contentId + "&page=1&pageSize=10", null);
        assertThat(redis.opsForList().size(rootsKey(contentId))).isEqualTo(3L);

        assertThat(post("/comment/add", java.util.Map.of("contentId", contentId, "message", "新主楼"), token)
                .getStatusCode().value()).isEqualTo(200);

        assertThat(redis.hasKey(rootsKey(contentId)))
                .as("提交 ⇒ INVALIDATE_ROOTS 生效 ⇒ roots key 被删（下次读懒重建）")
                .isFalse();
        assertThat(commentRows(contentId)).isEqualTo(4);
    }

    // ========================================================================
    // 窗口装载
    // ========================================================================

    @Test
    @DisplayName("★窗口装载只装被看的那一页（T10-A 的核心性质：页成本 ∝ 该页）")
    void 窗口装载只装被看的那一页() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "窗口装载内容");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(insertMainComment(contentId, authorId, "主楼 " + i));
        }

        JsonNode page1 = Envelope.data(
                get("/comment/show?contentId=" + contentId + "&page=1&pageSize=1", null));
        assertThat(page1.path("list").get(0).path("commentId").asLong()).isEqualTo(ids.get(0));
        assertThat(redis.opsForList().size(rootsKey(contentId)))
                .as("取第 1 页只应装 1 条主楼（不是全量 5 条）").isEqualTo(1L);

        JsonNode page3 = Envelope.data(
                get("/comment/show?contentId=" + contentId + "&page=3&pageSize=1", null));
        assertThat(page3.path("list").get(0).path("commentId").asLong()).isEqualTo(ids.get(2));
        assertThat(redis.opsForList().size(rootsKey(contentId)))
                .as("取第 3 页把水位推进到 3（增量装载，不重装）").isEqualTo(3L);

        // count key 在**首装**时写入，后续轮次不重复 COUNT
        assertThat(redis.opsForValue().get(rootCountKey(contentId))).isEqualTo("5");
        verify(commentDao, times(1)).countMainComments(contentId);
    }

    /**
     * ★ 性能契约（T21 = F-20 ①）：深翻抬高水位后，第 1 页只向 Redis 取**本页区间**。
     *
     * <p>⚠️ 断言作用域（与 T14 的性能契约同款说明）：这是 **RedisOps 参数级** 的
     * （spy 看的是"应用向 Redis 要了哪个区间"），不覆盖 Redis 侧实际传输量。
     * 病灶在"读侧全量读（{@code LRANGE 0 -1}）+ 内存切片"——多搬的元素在响应体上**看不见**
     * ⇒ 只能观测"向 Redis 要了多少"。设计级证据 = 一次性因果实验基线
     * （2 万主楼：p1 冷 → 深翻 p2000 → p1 再测；优化前 p1 热请求中位 51ms、每次反序列化 20000 条，
     * 优化后 8ms、只反序列化本页 10 条），见 {@code CommentCache#getRootPage} 的 T21 段。
     */
    @Test
    @DisplayName("★性能契约（T21/F-20①）：深翻抬高水位后，第 1 页只向 Redis 取本页区间（负载 ∝ 本页，不再 ∝ 水位）")
    void 性能契约_深翻后首页只取本页区间() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "区间读契约内容");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            ids.add(insertMainComment(contentId, authorId, "主楼 " + i));
        }

        // 深翻第 30 页（pageSize=1）⇒ 水位被抬到 30
        JsonNode deep = Envelope.data(
                get("/comment/show?contentId=" + contentId + "&page=30&pageSize=1", null));
        assertThat(deep.path("list").get(0).path("commentId").asLong()).isEqualTo(ids.get(29));
        assertThat(redis.opsForList().size(rootsKey(contentId)))
                .as("前置：深翻必须真把水位抬到 30 才有判别力").isEqualTo(30L);

        Mockito.reset(commentRedisOps);   // ⚠️ 深翻那次读取不计入（reset 在被测请求之前）

        JsonNode page1 = Envelope.data(
                get("/comment/show?contentId=" + contentId + "&page=1&pageSize=1", null));
        assertThat(page1.path("list").size()).isEqualTo(1);
        assertThat(page1.path("list").get(0).path("commentId").asLong()).isEqualTo(ids.get(0));
        assertThat(redis.opsForList().size(rootsKey(contentId)))
                .as("读首页不再推高水位（装载只在不足时发生）").isEqualTo(30L);

        // ★ 判别力：旧实现（LRANGE 0 -1 全量读 + 内存切片）在这一层看不见——
        //   它"要的"也是整段；注入回全量读 ⇒ 本条变红
        verify(commentRedisOps).readRootsInterval(eq(rootsKey(contentId)), eq(0L), eq(0L), any());
    }

    /**
     * ★ 越界页与 hit-empty 必须区分（T21 重构后最容易踩的坑）。
     *
     * <p>区间读之后，"本页区间为空"不再等于"没有评论"——旧实现靠"全量读为空"天然区分两者，
     * 新实现必须显式判"水位是否为 0"。若有人把判定简化成"区间空 ⇒ hit-empty"，
     * 有评论的内容会被写成空标记（60s 假空）+ total 归 0 ⇒ 本条变红。
     * 夹具带**双向对照**（AGENTS.md 纪律）：同一形态下，真无评论的内容必须写空标记。
     */
    @Test
    @DisplayName("越界页 ≠ 无评论：空 list + 真实 total（T8）且**不写空标记**；真无评论才写空标记（对照）")
    void 越界页不写空标记且保留真实total() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "越界页对照内容");
        for (int i = 0; i < 5; i++) {
            insertMainComment(contentId, authorId, "主楼 " + i);
        }

        JsonNode beyond = Envelope.data(
                get("/comment/show?contentId=" + contentId + "&page=99&pageSize=3", null));
        assertThat(beyond.path("list").size()).isZero();
        assertThat(beyond.path("total").asInt())
                .as("越界页保留真实主楼数（T8 契约）").isEqualTo(5);
        assertThat(redisKeys())
                .as("越界页 ≠ 无评论，不得写空标记（防把『有评论』固化成 60s 假空）")
                .doesNotContain(EMPTY_PREFIX + rootsKey(contentId));

        // 对照：真无评论的内容，同样的页参数必须写空标记（hit-empty）
        long emptyContentId = insertVideoContent(authorId, "真无评论对照内容");
        JsonNode empty = Envelope.data(
                get("/comment/show?contentId=" + emptyContentId + "&page=99&pageSize=3", null));
        assertThat(empty.path("list").size()).isZero();
        assertThat(empty.path("total").asInt()).isZero();
        assertThat(redisKeys())
                .as("真无评论（水位 0 且区间空）⇒ 空标记必须在")
                .contains(EMPTY_PREFIX + rootsKey(emptyContentId));
    }

    @Test
    @DisplayName("空标记：无评论的内容第二次读不再回源 DB（防穿透）")
    void 空标记让无评论的内容不再回源DB() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "无评论内容");

        JsonNode first = Envelope.data(
                get("/comment/show?contentId=" + contentId + "&page=1&pageSize=10", null));
        assertThat(first.path("total").asInt()).isZero();
        assertThat(redisKeys()).as("空标记必须落盘")
                .contains(EMPTY_PREFIX + rootsKey(contentId));

        JsonNode second = Envelope.data(
                get("/comment/show?contentId=" + contentId + "&page=1&pageSize=10", null));
        assertThat(second.path("total").asInt()).isZero();
        verify(commentDao, times(1)).getMainCommentsAfter(anyLong(), anyLong(), anyInt());
    }

    // ========================================================================
    // ★ 两种失败必须区分（G-4）
    // ========================================================================

    @Test
    @DisplayName("★Redis 失败降级直查 DB：接口仍 200 且结果正确，并且**不写回**（D4）")
    void Redis不可用时降级DB且不写回() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "降级内容");
        for (int i = 0; i < 3; i++) {
            insertMainComment(contentId, authorId, "主楼 " + i);
        }
        clearRedis();

        doThrow(new CacheUnavailableException("模拟 Redis 故障"))
                .when(commentRedisOps).emptyMarkerExists(org.mockito.ArgumentMatchers.anyString());

        JsonNode data = Envelope.data(
                get("/comment/show?contentId=" + contentId + "&page=1&pageSize=10", null));
        assertThat(data.path("total").asInt()).as("降级走 DB，结果仍正确").isEqualTo(3);
        assertThat(data.path("list").size()).isEqualTo(3);
        assertThat(redisKeys()).as("降级路径**不写回**（D4）").doesNotContain(rootsKey(contentId));
    }

    @Test
    @DisplayName("★DB 失败上抛（500），不伪装成『没有评论』")
    void DB失败上抛而不是返回空列表() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "DB 失败内容");
        clearRedis();

        doThrow(new DataAccessException("模拟 DB 故障") {
        }).when(commentDao).getMainCommentsAfter(anyLong(), anyLong(), anyInt());

        ResponseEntity<String> resp =
                get("/comment/show?contentId=" + contentId + "&page=1&pageSize=10", null);
        assertThat(resp.getStatusCode().value())
                .as("返回『没有评论』会把读不到伪装成没有；正解是 500：%s", resp.getBody())
                .isEqualTo(500);
        assertThat(redisKeys()).as("装载失败不得写空标记（不固化瞬时故障）")
                .doesNotContain(EMPTY_PREFIX + rootsKey(contentId));
    }

    // ========================================================================
    // 失效重映射的粒度
    // ========================================================================

    @Test
    @DisplayName("评论点赞后定向失效该主楼的 replies 字段（不整组失效）")
    void 评论点赞后定向失效该主楼的replies字段() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        String viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "点赞失效内容");
        long rootId = insertMainComment(contentId, authorId, "主楼");
        long replyId = insertComment(contentId, authorId, "回复", rootId, null);
        setReplyCount(rootId, 1);

        // 预热 replies field（分页读会 lazyLoad 并 HSET）
        get("/comment/show?contentId=" + contentId + "&page=1&pageSize=10", null);
        assertThat(redis.opsForHash().hasKey(repliesKey(contentId), String.valueOf(rootId)))
                .as("前置：replies field 应已被装载").isTrue();

        assertThat(postForm("/like/comment/add", query("commentId", String.valueOf(replyId)), viewerToken)
                .getStatusCode().value()).isEqualTo(200);

        assertThat(redis.opsForHash().hasKey(repliesKey(contentId), String.valueOf(rootId)))
                .as("点赞提交后 ⇒ 该主楼的 replies field 被 HDEL（下次读懒载刷新）").isFalse();
        assertThat(redis.hasKey(rootsKey(contentId)))
                .as("只失效 field，**不动**主楼序列（定向而非整组）").isTrue();
        // 反向对照：authorToken 未使用，仅用于保证 token 有效（避免"其实没登录"的假绿）
        assertThat(authorToken).isNotBlank();
    }

    @Test
    @DisplayName("增回复只定向失效所在主楼的 replies 字段（roots 保留）")
    void 增回复只定向失效所在主楼的replies字段() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "增回复内容");
        long rootId = insertMainComment(contentId, authorId, "主楼");
        insertComment(contentId, authorId, "回复", rootId, null);
        setReplyCount(rootId, 1);

        get("/comment/show?contentId=" + contentId + "&page=1&pageSize=10", null);
        assertThat(redis.opsForHash().hasKey(repliesKey(contentId), String.valueOf(rootId))).isTrue();

        assertThat(post("/comment/add",
                java.util.Map.of("contentId", contentId, "message", "新回复", "parentId", rootId), token)
                .getStatusCode().value()).isEqualTo(200);

        assertThat(redis.opsForHash().hasKey(repliesKey(contentId), String.valueOf(rootId)))
                .as("增回复 ⇒ 定向 HDEL 该主楼的 field").isFalse();
        assertThat(redis.hasKey(rootsKey(contentId)))
                .as("增回复**不应**失效主楼序列（增主楼才失效 roots）").isTrue();
    }
}
