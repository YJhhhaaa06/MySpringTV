package io.github.yjhhhaaa06.videoweb.feed;

import io.github.yjhhhaaa06.videoweb.favorite.service.FavoriteService;
import io.github.yjhhhaaa06.videoweb.follow.service.FollowService;
import io.github.yjhhhaaa06.videoweb.interaction.service.InteractionService;
import io.github.yjhhhaaa06.videoweb.like.service.LikeService;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@code GET /feed} 的**读路径契约**测试（S9）——覆盖 TV {@code FeedService} 的两种路径：
 * <b>两路读窗口（synced=true）</b> 与 <b>纯拉降级（synced=false）</b>。
 *
 * <h2>为什么两条路径都要测</h2>
 * "未同步 ⇒ 回退纯拉"是 S9 的核心裁决（读平面不依赖 MQ）：窗口不可信时**必须**能给出正确结果。
 * 只测窗口路径会漏掉这条降级保底。
 *
 * <h2>oracle 纪律</h2>
 * 关键计数（{@code total}）不信任接口回显——但 {@code total} 本身就是接口契约的一部分，
 * 故此处的独立 oracle 是"直查 {@code feed_inbox} 行数"与"直查 {@code content} 行数"，
 * 断言接口的 {@code total} 与它们**相符**（而非反向拿接口值当真）。
 */
class FeedReadTests extends AbstractFeedIntegrationTest {

    private static final String PHONE_VIEWER = "13900009001";
    private static final String PHONE_AUTHOR = "13900009002";

    /**
     * ★ T13：feed 的条目互动态改走**互动域**（F-16 兑现，本域不再直连 like）。
     * spy 它才能证明"走的是互动域 + 批量一趟"，而不只是"值对"（直连 like 也能得到对的值）。
     */
    @MockitoSpyBean
    private InteractionService interactionService;

    @MockitoSpyBean
    private LikeService likeService;

    @MockitoSpyBean
    private FavoriteService favoriteService;

    @MockitoSpyBean
    private FollowService followService;

    @BeforeEach
    void 重置互动域spy() {
        // spy 是单例 bean，调用记录会跨用例累积 ⇒ 每测清零（真实行为不受影响）
        Mockito.reset(interactionService, likeService, favoriteService, followService);
    }

    // ==================== 鉴权 ====================

    @Test
    @DisplayName("未登录 → 401（TV 的 /feed 是 PROTECTED_EXACT 精确保护项）")
    void 未登录401() {
        ResponseEntity<String> resp = getFeed(null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ==================== 窗口路径 ====================

    @Test
    @DisplayName("★已同步：返回归并窗口（contentId 降序），total = 窗口实际条数")
    void 已同步_按窗口返回降序() {
        long viewerId = registerViewer();
        long authorId = registerAuthor();

        long c1 = insertVideoContent(authorId, "第一条");
        long c2 = insertVideoContent(authorId, "第二条");
        long c3 = insertVideoContent(authorId, "第三条");
        insertFollowRow(viewerId, authorId);
        // 窗口里只放 c1,c3（模拟"重建裁掉了 c2"或"c2 超出窗口"）——读侧应只回窗口内的
        insertFeedInboxRow(viewerId, c1);
        insertFeedInboxRow(viewerId, c3);
        markSynced(viewerId);

        ResponseEntity<String> resp = getFeed(tokenOf(PHONE_VIEWER));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = Envelope.data(resp);
        assertThat(idList(data.path("list")))
                .as("窗口内容必须按 contentId 降序（c3 > c1）")
                .containsExactly(c3, c1);
        assertThat(data.path("total").asInt()).as("total = 窗口实际条数（直查 feed_inbox 行数佐证）")
                .isEqualTo((int) countInboxRows(viewerId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("深翻越过窗口 → 空 list 但 total 保持窗口条数（前端据此自然停止翻页）")
    void 深翻越界返回空页() {
        long viewerId = registerViewer();
        long authorId = registerAuthor();
        long c1 = insertVideoContent(authorId, "唯一一条");
        insertFollowRow(viewerId, authorId);
        insertFeedInboxRow(viewerId, c1);
        markSynced(viewerId);

        ResponseEntity<String> resp = getFeed(tokenOf(PHONE_VIEWER), 3, 100);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = Envelope.data(resp);
        assertThat(data.path("list")).isEmpty();
        assertThat(data.path("total").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("分页归一：缺省 → 1 / 100；pageSize=51 原样回显；超上限 → 100")
    void 分页归一() {
        long viewerId = registerViewer();
        long authorId = registerAuthor();
        long c1 = insertVideoContent(authorId, "x");
        insertFollowRow(viewerId, authorId);
        insertFeedInboxRow(viewerId, c1);
        markSynced(viewerId);
        String token = tokenOf(PHONE_VIEWER);

        JsonNode defaulted = Envelope.data(getFeed(token));
        assertThat(defaulted.path("page").asInt()).isEqualTo(1);
        assertThat(defaulted.path("pageSize").asInt()).isEqualTo(FEED_PAGE_SIZE_DEFAULT);

        JsonNode echo = Envelope.data(getFeed(token, 1, 51));
        assertThat(echo.path("pageSize").asInt()).as("未超上限必须原样回显（保留小信封跨页验证能力）").isEqualTo(51);

        JsonNode capped = Envelope.data(getFeed(token, 1, 999));
        assertThat(capped.path("pageSize").asInt()).as("超上限归一到域级上限").isEqualTo(FEED_PAGE_SIZE_MAX);
    }

    @Test
    @DisplayName("窗口内已删 / 媒体损坏内容被跳过：list 短一条，但 total 仍是窗口条数")
    void 窗口内跳过不可用内容() {
        long viewerId = registerViewer();
        long authorId = registerAuthor();
        long ok = insertVideoContent(authorId, "可用");
        long deleted = insertVideoContent(authorId, "将删除");
        long broken = insertBrokenVideoContent(authorId, "媒体损坏");
        insertFollowRow(viewerId, authorId);
        insertFeedInboxRow(viewerId, ok);
        insertFeedInboxRow(viewerId, deleted);
        insertFeedInboxRow(viewerId, broken);
        markSynced(viewerId);
        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", deleted);

        JsonNode data = Envelope.data(getFeed(tokenOf(PHONE_VIEWER)));
        assertThat(idList(data.path("list"))).as("只应剩下可用的一条").containsExactly(ok);
        assertThat(data.path("total").asInt()).as("total 不因跳过而缩水——窗口条数").isEqualTo(3);
    }

    // ==================== 纯拉降级路径 ====================

    @Test
    @DisplayName("★未同步（无 feed_inbox_sync 行）→ 回退纯拉：全量可见，不受窗口约束")
    void 未同步_回退纯拉() {
        long viewerId = registerViewer();
        long authorId = registerAuthor();
        long c1 = insertVideoContent(authorId, "a");
        long c2 = insertVideoContent(authorId, "b");
        insertFollowRow(viewerId, authorId);
        // 故意不写 feed_inbox_sync；窗口里也故意留空（模拟"从未重建"的场景）
        assertThat(isSynced(viewerId)).isFalse();

        JsonNode data = Envelope.data(getFeed(tokenOf(PHONE_VIEWER)));
        assertThat(idList(data.path("list")))
                .as("纯拉应看到关注作者的全部未删内容（c2 比 c1 新 ⇒ 降序）")
                .containsExactly(c2, c1);
        assertThat(data.path("total").asInt())
                .as("total = 关注作者内容总数（独立 oracle 直查 content 行数）")
                .isEqualTo(countContentRows());
    }

    @Test
    @DisplayName("未同步 + 无关注 → 空页 total=0（两个早退分支之一）")
    void 未同步无关注返回空页() {
        registerViewer();
        JsonNode data = Envelope.data(getFeed(tokenOf(PHONE_VIEWER)));
        assertThat(data.path("list")).isEmpty();
        assertThat(data.path("total").asInt()).isZero();
    }

    @Test
    @DisplayName("已同步 + 无关注 → 空窗（另一早退分支）")
    void 已同步无关注返回空窗() {
        long viewerId = registerViewer();
        markSynced(viewerId);
        JsonNode data = Envelope.data(getFeed(tokenOf(PHONE_VIEWER)));
        assertThat(data.path("list")).isEmpty();
        assertThat(data.path("total").asInt()).isZero();
    }

    @Test
    @DisplayName("★T13（R-13）：条目内嵌 interaction 且只带赞维；关注维不再是恒 false 的谎言")
    void 条目互动态_只带赞维() {
        long viewerId = registerViewer();
        long authorId = registerAuthor();
        long liked = insertVideoContent(authorId, "feed 已赞内容");
        long untouched = insertVideoContent(authorId, "feed 未赞内容");
        insertFollowRow(viewerId, authorId);
        insertFeedInboxRow(viewerId, liked);
        insertFeedInboxRow(viewerId, untouched);
        markSynced(viewerId);
        insertContentLike(viewerId, liked);            // ★ 对照：只有 liked 被赞

        JsonNode list = Envelope.data(getFeed(tokenOf(PHONE_VIEWER))).path("list");
        JsonNode likedItem = itemOf(list, liked);
        JsonNode likedView = likedItem.path("interaction");
        assertThat(likedView.isObject()).as("feed 必登录 ⇒ interaction 是对象（不会是 null）").isTrue();
        assertThat(likedView.path("isLiked").asBoolean()).as("已赞 ⇒ true").isTrue();
        assertThat(likedView.path("isFavorited").isNull()).as("feed 不带收藏维 ⇒ null").isTrue();
        assertThat(likedView.path("isFollowed").isNull())
                .as("★ feed 不带关注维 ⇒ null —— 旧实现这里是**恒 false**（viewer 明明关注了作者）").isTrue();

        // ★ 对照：未赞的那条 ⇒ isLiked 是 **false**（不是 null）——"不查"与"查了是否"必须可区分
        JsonNode untouchedView = itemOf(list, untouched).path("interaction");
        assertThat(untouchedView.path("isLiked").asBoolean()).as("未赞 ⇒ false（不是 null）").isFalse();
        assertThat(untouchedView.path("isFavorited").isNull()).isTrue();
        assertThat(untouchedView.path("isFollowed").isNull()).isTrue();
    }

    @Test
    @DisplayName("★T40：条目带公共计数 coinCount（与 content.coin_count 一致）；interaction.coin 恒 null")
    void 条目公共计数_coinCount与DB一致且coin恒null() {
        long viewerId = registerViewer();
        long authorId = registerAuthor();
        long c1 = insertVideoContent(authorId, "feed coinCount 内容");
        insertFollowRow(viewerId, authorId);
        insertFeedInboxRow(viewerId, c1);
        markSynced(viewerId);
        // 独立 oracle 的前置：直接把公共计数钉到 2（本用例只钉 feed 的组装，不走投币写路径）
        jdbcTemplate.update("UPDATE content SET coin_count = 2 WHERE id = ?", c1);

        JsonNode item = itemOf(Envelope.data(getFeed(tokenOf(PHONE_VIEWER))).path("list"), c1);

        Integer countInDb = jdbcTemplate.queryForObject(
                "SELECT coin_count FROM content WHERE id = ?", Integer.class, c1);
        assertThat(countInDb).as("前置：DB 里的公共计数为 2").isEqualTo(2);
        assertThat(item.path("coinCount").asLong())
                .as("★ feed 的 coinCount 由 renderPage 一趟批量填充，与 content.coin_count 一致")
                .isEqualTo(countInDb.longValue());
        assertThat(item.path("interaction").path("coin").isNull())
                .as("★ 列表不查投币维 ⇒ coin 恒 null（契约，不是漏填）").isTrue();
    }

    @Test
    @DisplayName("★T13：feed 改走互动域后仍是一趟批量（赞 1 趟、藏/关注 0 趟）")
    void 条目互动态_一趟批量不退化() {
        long viewerId = registerViewer();
        long authorId = registerAuthor();
        long c1 = insertVideoContent(authorId, "feed 批量内容");
        insertFollowRow(viewerId, authorId);
        insertFeedInboxRow(viewerId, c1);
        markSynced(viewerId);

        Mockito.reset(interactionService, likeService, favoriteService, followService);
        assertThat(idList(Envelope.data(getFeed(tokenOf(PHONE_VIEWER))).path("list")))
                .as("本用例需非空列表才有判别力").containsExactly(c1);

        // ★ 判别力①：把 FeedService 改回"逐条调 InteractionService#getStatus" ⇒ 下面两条立即变红
        verify(interactionService, times(1)).batchGetStatus(eq(viewerId), anyList(), anySet());
        verify(interactionService, never()).getStatus(any(), anyLong(), anyLong(), anyInt());
        // ★ 判别力②：把 FEED_DIMENSIONS 改回全量三维 ⇒ 下面两条立即变红
        verify(likeService, times(1)).batchIsContentLiked(eq(viewerId), anyList());
        verify(favoriteService, never()).batchIsFavorited(anyLong(), anyList());
        verify(followService, never()).batchIsFollowing(anyLong(), anyList());
    }

    // ==================== helpers ====================

    private long registerViewer() {
        registerAndGetToken(PHONE_VIEWER, "viewer", PASSWORD);
        return userIdOf(PHONE_VIEWER);
    }

    private long registerAuthor() {
        registerAndGetToken(PHONE_AUTHOR, "author", PASSWORD);
        return userIdOf(PHONE_AUTHOR);
    }

    private String tokenOf(String phone) {
        // 重新登录取 token（registerAndGetToken 已回填用户；token 直接复用注册时返回的更简单，
        // 但两个 helper 都会注册 → 这里用登录保证拿到 viewer 的 token）。
        ResponseEntity<String> resp = post("/user/login",
                java.util.Map.of("phone", phone, "password", PASSWORD), null);
        String token = Envelope.str(resp, "token");
        assertThat(token).as("登录必须返回 token").isNotBlank();
        return token;
    }

    private static List<Long> idList(JsonNode arrayNode) {
        List<Long> ids = new ArrayList<>();
        arrayNode.forEach(node -> ids.add(node.path("id").asLong()));
        return ids;
    }

    /** 从列表里取指定 id 的条目（找不到直接失败——防"夹具没造上/被过滤掉"的假绿）。 */
    private static JsonNode itemOf(JsonNode list, long contentId) {
        for (JsonNode node : list) {
            if (node.path("id").asLong() == contentId) {
                return node;
            }
        }
        throw new AssertionError("列表里应包含内容 " + contentId + "，实际：" + list);
    }
}
