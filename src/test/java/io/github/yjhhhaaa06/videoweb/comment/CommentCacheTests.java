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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
