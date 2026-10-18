package io.github.yjhhhaaa06.videoweb.ratelimit;

import io.github.yjhhhaaa06.videoweb.comment.cache.CommentCache;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheMissSignal;
import io.github.yjhhhaaa06.videoweb.content.cache.ContentCache;
import io.github.yjhhhaaa06.videoweb.favorite.cache.FavoriteCache;
import io.github.yjhhhaaa06.videoweb.feed.cache.FeedCache;
import io.github.yjhhhaaa06.videoweb.follow.cache.FollowCache;
import io.github.yjhhhaaa06.videoweb.like.cache.LikeCache;
import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 信号②的**接线面**证明：六个缓存类的"冷读（要回源 DB）"必须标记、命中（含空标记命中）必须不标记
 * （限流专项 T26；口径见 {@code architecture/tech/rate-limit/限流.md} §四）。
 *
 * <h2>★ 为什么本类不在 {@code common.cache}（信号类的同包）</h2>
 * 本测试要 import 六个业务域的 {@code *Cache}（接线面横跨全域），而
 * {@code ArchitectureTests} 规则 1 约束 {@code ..videoweb.common..} 面——把跨域 import 的测试
 * 放进该包有踩线之嫌；跨切面测试的**既有先例**是独立测试包（{@code observability} /
 * {@code resilience}，均不在 {@code DOMAINS} 内、不受五条规则约束）。
 *
 * <h2>夹具口径</h2>
 * 六个用例全部使用**不存在**的 id（990_000_000 段）：对"是否回源"没有影响（照样打一次空结果的
 * DB 查询 ⇒ 标记），但省去全部表夹具、并让**第二次读**必然走"空标记命中 / 数据键命中"的
 * **不标记**一侧——两侧都在本类里被证到。每测前清空本类涉及的缓存键（进程级共享容器）。
 *
 * <h2>★ 反向验证指向</h2>
 * 把 {@code CacheMissSignal.mark()} 改成空实现（或任一缓存类的回源点删掉 mark）⇒ 对应用例必红；
 * 本仓六个域各抽一条主读路径，**不追求逐点覆盖全部 40 个回源点**（逐点清单见实现注释；
 * 缺一处 = 该路径在信号②里静默消失，属保守方向的漏记）。
 */
class CacheMissSignalWiringTests extends AbstractIntegrationTest {

    /** 本类专用 id 段（远高于自增水位；不改自增、不建夹具行）。 */
    private static final long USER_ID = 990_261_001L;
    private static final long CONTENT_ID = 990_261_101L;
    private static final long COMMENT_CONTENT_ID = 990_261_102L;

    @Autowired
    private ContentCache contentCache;
    @Autowired
    private LikeCache likeCache;
    @Autowired
    private FollowCache followCache;
    @Autowired
    private FavoriteCache favoriteCache;
    @Autowired
    private FeedCache feedCache;
    @Autowired
    private CommentCache commentCache;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void 清本类涉及的键与标记() {
        redis.delete(List.of(
                CacheKeys.content(CONTENT_ID),
                CacheKeys.empty(CacheKeys.content(CONTENT_ID)),
                CacheKeys.userLikeSet(USER_ID),
                CacheKeys.empty(CacheKeys.userLikeSet(USER_ID)),
                CacheKeys.userFollowing(USER_ID),
                CacheKeys.empty(CacheKeys.userFollowing(USER_ID)),
                CacheKeys.partial(CacheKeys.userFollowing(USER_ID)),
                CacheKeys.userFavoriteSet(USER_ID),
                CacheKeys.empty(CacheKeys.userFavoriteSet(USER_ID)),
                CacheKeys.contentCommentRoots(COMMENT_CONTENT_ID),
                CacheKeys.empty(CacheKeys.contentCommentRoots(COMMENT_CONTENT_ID)),
                CacheKeys.contentCommentReplies(COMMENT_CONTENT_ID),
                CacheKeys.contentCommentRootCount(COMMENT_CONTENT_ID),
                CacheKeys.feedInbox(USER_ID),
                CacheKeys.empty(CacheKeys.feedInbox(USER_ID))));
        CacheMissSignal.clear();
    }

    @Test
    @DisplayName("content：冷读 miss 标记（CacheAside 回源）／空标记命中不标记")
    void content_冷读标记_命中不标记() {
        assertThat(contentCache.getContent(CONTENT_ID)).isNull();       // miss → DB 空 → 空标记
        assertThat(CacheMissSignal.consume()).as("冷读回源必须标记").isTrue();

        assertThat(contentCache.getContent(CONTENT_ID)).isNull();       // 空标记命中
        assertThat(CacheMissSignal.consume()).as("空标记命中不得标记").isFalse();
    }

    @Test
    @DisplayName("like：冷读 miss 标记／空标记命中不标记")
    void like_冷读标记_命中不标记() {
        assertThat(likeCache.isContentLiked(USER_ID, CONTENT_ID)).isFalse();
        assertThat(CacheMissSignal.consume()).as("冷读回源必须标记").isTrue();

        assertThat(likeCache.isContentLiked(USER_ID, CONTENT_ID)).isFalse();
        assertThat(CacheMissSignal.consume()).as("空标记命中不得标记").isFalse();
    }

    @Test
    @DisplayName("follow：冷读 miss 标记／空标记命中不标记")
    void follow_冷读标记_命中不标记() {
        assertThat(followCache.isFollowing(USER_ID, 1L)).isFalse();
        assertThat(CacheMissSignal.consume()).as("冷读回源必须标记").isTrue();

        assertThat(followCache.isFollowing(USER_ID, 1L)).isFalse();
        assertThat(CacheMissSignal.consume()).as("空标记命中不得标记").isFalse();
    }

    @Test
    @DisplayName("favorite：冷读 miss 标记／空标记命中不标记")
    void favorite_冷读标记_命中不标记() {
        assertThat(favoriteCache.isFavorited(USER_ID, CONTENT_ID)).isFalse();
        assertThat(CacheMissSignal.consume()).as("冷读回源必须标记").isTrue();

        assertThat(favoriteCache.isFavorited(USER_ID, CONTENT_ID)).isFalse();
        assertThat(CacheMissSignal.consume()).as("空标记命中不得标记").isFalse();
    }

    @Test
    @DisplayName("feed：收件箱冷读 miss 标记／空标记命中不标记")
    void feed_冷读标记_命中不标记() {
        feedCache.readInbox(USER_ID, List::of);
        assertThat(CacheMissSignal.consume()).as("冷读回源必须标记").isTrue();

        feedCache.readInbox(USER_ID, List::of);
        assertThat(CacheMissSignal.consume()).as("空标记命中不得标记").isFalse();
    }

    @Test
    @DisplayName("comment：主楼窗口冷读标记（两键组首次装载）／空标记命中不标记")
    void comment_冷读标记_命中不标记() {
        commentCache.getRootPage(COMMENT_CONTENT_ID, 1, 10);
        assertThat(CacheMissSignal.consume()).as("冷读窗口装载必须标记").isTrue();

        commentCache.getRootPage(COMMENT_CONTENT_ID, 1, 10);
        assertThat(CacheMissSignal.consume()).as("空标记命中不得标记").isFalse();
    }
}
