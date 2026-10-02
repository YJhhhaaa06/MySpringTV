package io.github.yjhhhaaa06.videoweb.content;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * S5：{@code /profile} 的 HTTP 契约 + 计数缓存语义（决策表 G-5 / G-8）。
 *
 * <h2>信封位置是契约的一部分</h2>
 * 分页信封嵌在 {@code data.contentPage}（不是 {@code data}）——旧 pytest 的断言路径就是这个，
 * 属冻结契约。本类第一组用例专门钉住它。
 *
 * <h2>★ G-8 的机制证明</h2>
 * 关注/粉丝计数走 {@code FollowCache} 的计数缓存（S4 去掉、S5 补回）。
 * 证明手法：spy {@code UserDao}，断言"读两次只打一次 DB"（第二次命中缓存）——
 * 只断言"计数正确"是不够的，直读 DB 也能得到正确值。
 */
class ProfileReadTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900005001";
    private static final String AUTHOR = "profile-author";
    private static final String VIEWER_PHONE = "13900005002";
    private static final String VIEWER = "profile-viewer";

    @MockitoSpyBean
    private UserDao userDao;

    @BeforeEach
    void resetStubs() {
        Mockito.reset(userDao);
    }

    // ========================================================================
    // 形状与分页
    // ========================================================================

    @Test
    @DisplayName("形状：信封嵌在 data.contentPage，用户维度字段齐全")
    void 形状与信封位置() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        insertVideoContent(authorId, "主页内容");

        ResponseEntity<String> resp = get("/profile?userId=" + authorId, null);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        JsonNode data = Envelope.data(resp);
        assertThat(data.path("userId").asLong()).isEqualTo(authorId);
        assertThat(data.path("username").asString()).isEqualTo(AUTHOR);
        assertThat(data.has("followerCount")).isTrue();
        assertThat(data.has("followCount")).isTrue();
        assertThat(data.has("isFollowed")).as("匿名也输出 isFollowed（值为 null）").isTrue();
        assertThat(data.path("isFollowed").isNull()).as("匿名 ⇒ null（不是 false）").isTrue();

        JsonNode page = data.path("contentPage");
        List<String> keys = new ArrayList<>();
        page.propertyNames().forEach(keys::add);
        assertThat(keys).as("信封必须嵌在 contentPage 里").containsExactlyInAnyOrder(
                "list", "total", "page", "pageSize", "totalPages");
        assertThat(page.path("total").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("分页：域级 100/100；51 原样；缺省 == 显式首页逐字节一致")
    void 分页口径() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        insertVideoContent(authorId, "分页内容");

        JsonNode defaultPage = Envelope.data(get("/profile?userId=" + authorId, null)).path("contentPage");
        assertThat(defaultPage.path("page").asInt()).isEqualTo(1);
        assertThat(defaultPage.path("pageSize").asInt()).isEqualTo(PAGE_SIZE_MAX);

        assertThat(Envelope.data(get("/profile?userId=" + authorId + "&pageSize=999", null))
                .path("contentPage").path("pageSize").asInt()).as("超上限截到 100").isEqualTo(100);
        assertThat(Envelope.data(get("/profile?userId=" + authorId + "&pageSize=51", null))
                .path("contentPage").path("pageSize").asInt()).as("51 原样回显").isEqualTo(51);
        assertThat(Envelope.data(get("/profile?userId=" + authorId + "&pageSize=abc", null))
                .path("contentPage").path("pageSize").asInt()).as("非法值回落信封").isEqualTo(100);

        JsonNode explicitPage = Envelope.data(
                get("/profile?userId=" + authorId + "&page=1&pageSize=100", null)).path("contentPage");
        assertThat(defaultPage.path("list").size()).as("需非空列表才有判别力").isEqualTo(1);
        assertThat(defaultPage.toString()).isEqualTo(explicitPage.toString());
    }

    @Test
    @DisplayName("★顺序：按 contentId 降序；pageSize=1 逐页不重不漏")
    void 顺序为内容id降序() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(insertVideoContent(authorId, "主页分页 " + i));
        }

        List<Long> paged = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            JsonNode envelope = Envelope.data(
                    get("/profile?userId=" + authorId + "&page=" + page + "&pageSize=1", null))
                    .path("contentPage");
            assertThat(envelope.path("total").asInt()).as("total 恒为 3（越界页也保留）").isEqualTo(3);
            assertThat(envelope.path("list").size()).isEqualTo(1);
            paged.add(envelope.path("list").get(0).path("id").asLong());
        }
        List<Long> expected = new ArrayList<>(ids);
        expected.sort(java.util.Comparator.reverseOrder());
        assertThat(paged).as("跨页不重不漏、整体降序").isEqualTo(expected);
    }

    @Test
    @DisplayName("边界：缺 userId → 400（文案），非法 → 400，用户不存在 → 404")
    void 边界() {
        ResponseEntity<String> missing = get("/profile", null);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(missing)).as("TV 文案逐字保留").isEqualTo("缺少 userId");

        ResponseEntity<String> malformed = get("/profile?userId=abc", null);
        assertThat(malformed.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(malformed)).isEqualTo("userId 格式错误");

        ResponseEntity<String> notFound = get("/profile?userId=987654321", null);
        assertThat(notFound.getStatusCode().value()).isEqualTo(404);
        assertThat(Envelope.msg(notFound)).isEqualTo("用户不存在");
    }

    // ========================================================================
    // 关注态
    // ========================================================================

    @Test
    @DisplayName("关注态：登录且非自己 → 真实值；看自己 → null；匿名 → null")
    void 关注态三态() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        String viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);
        long authorId = userIdOf(AUTHOR_PHONE);

        assertThat(Envelope.data(get("/profile?userId=" + authorId, viewerToken))
                .path("isFollowed").asBoolean()).as("未关注 ⇒ false").isFalse();

        assertThat(postForm("/follow/add", query("followedUserId", String.valueOf(authorId)), viewerToken)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.data(get("/profile?userId=" + authorId, viewerToken))
                .path("isFollowed").asBoolean()).as("已关注 ⇒ true").isTrue();

        assertThat(Envelope.data(get("/profile?userId=" + authorId, authorToken))
                .path("isFollowed").isNull()).as("看自己 ⇒ null（无意义的问题）").isTrue();
    }

    // ========================================================================
    // ★ G-8：计数缓存
    // ========================================================================

    @Test
    @DisplayName("★计数走缓存：连读两次只打一次 DB（第二次命中计数缓存）")
    void 计数缓存命中() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        insertVideoContent(authorId, "计数内容");

        get("/profile?userId=" + authorId, null);
        verify(userDao, times(1)).getFollowCountById(authorId);
        verify(userDao, times(1)).getFollowerCountById(authorId);

        get("/profile?userId=" + authorId, null);
        verify(userDao, times(1)).getFollowCountById(authorId);
        verify(userDao, times(1)).getFollowerCountById(authorId);

        // 计数 key 的形状也是契约（key 改名 = 缓存静默失联）
        assertThat(redisKeys()).contains(
                "user:followCount:" + authorId, "user:followerCount:" + authorId);
    }

    @Test
    @DisplayName("★关注后计数缓存被增量更新；而**未预热的一侧**不动（条件写边界）")
    void 关注后计数缓存被更新() {
        String viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long viewerId = userIdOf(VIEWER_PHONE);

        // 只预热**被关注者**的计数（读他的主页）；关注者本人的计数保持"未加载"
        JsonNode before = Envelope.data(get("/profile?userId=" + authorId, viewerToken));
        assertThat(before.path("followerCount").asInt()).isZero();
        assertThat(redis.opsForValue().get("user:followerCount:" + authorId))
                .as("前置：被关注者的粉丝计数已加载为 0").isEqualTo("0");
        assertThat(redis.opsForValue().get("user:followCount:" + viewerId))
                .as("前置：关注者本人的关注计数**未加载**（本用例的关键前提）").isNull();

        assertThat(postForm("/follow/add", query("followedUserId", String.valueOf(authorId)), viewerToken)
                .getStatusCode().value()).isEqualTo(200);

        // ① 已加载的一侧：提交后条件 INCRBY ⇒ 立刻是 1
        assertThat(redis.opsForValue().get("user:followerCount:" + authorId))
                .as("已加载 ⇒ 条件写生效").isEqualTo("1");
        // ② 未加载的一侧：条件写**什么都不做**（写个 1 会被读路径当权威值，而真值可能是 37）
        assertThat(redis.opsForValue().get("user:followCount:" + viewerId))
                .as("冷 key ⇒ 不创建半套计数").isNull();

        // ③ 读关注者的主页 ⇒ 回源 DB 得到真值并回填
        JsonNode viewerProfile = Envelope.data(get("/profile?userId=" + viewerId, viewerToken));
        assertThat(viewerProfile.path("followCount").asInt()).as("回源 DB 得到真值").isEqualTo(1);
        assertThat(redis.opsForValue().get("user:followCount:" + viewerId))
                .as("回填后 key 建立").isEqualTo("1");

        // ④ 被关注者侧：接口读数与 DB 列一致（独立 oracle）
        JsonNode after = Envelope.data(get("/profile?userId=" + authorId, viewerToken));
        assertThat(after.path("followerCount").asInt()).as("接口读数与缓存一致").isEqualTo(1);
        assertThat(followerCountColumn(authorId)).as("独立 oracle：DB 列也一致").isEqualTo(1);
    }

    @Test
    @DisplayName("★缓存缺失时不创建半套计数（冷 key 条件写不动），读路径回源 DB 回填")
    void 冷key不创建半套计数() {
        String viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);

        // 不预热计数缓存，直接关注：条件写发现 key 不存在 ⇒ 什么都不做
        assertThat(postForm("/follow/add", query("followedUserId", String.valueOf(authorId)), viewerToken)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(redis.opsForValue().get("user:followerCount:" + authorId))
                .as("冷 key 不得被写成 1（真实值可能是 37）").isNull();

        // 读路径回源 DB ⇒ 得到真值 1，并回填
        JsonNode profile = Envelope.data(get("/profile?userId=" + authorId, viewerToken));
        assertThat(profile.path("followerCount").asInt()).as("回源 DB 得到真值").isEqualTo(1);
        assertThat(redis.opsForValue().get("user:followerCount:" + authorId)).isEqualTo("1");
    }

    private int followerCountColumn(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT follower_count FROM users WHERE id = ?", Integer.class, userId);
        return n == null ? -1 : n;
    }
}
