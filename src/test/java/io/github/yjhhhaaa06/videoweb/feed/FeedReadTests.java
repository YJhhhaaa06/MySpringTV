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

    // ==================== 快照锚点（beforeContentId） ====================

    @Test
    @DisplayName("★快照锚点：首屏之后发新内容，第 2 页不重复上一页末条（对照：不带锚点会重复）")
    void 窗口路径_快照锚点冻结头部() {
        long viewerId = registerViewer();
        long authorId = registerAuthor();
        long c1 = insertVideoContent(authorId, "第1条");
        long c2 = insertVideoContent(authorId, "第2条");
        long c3 = insertVideoContent(authorId, "第3条");
        long c4 = insertVideoContent(authorId, "第4条");
        insertFollowRow(viewerId, authorId);
        for (long id : List.of(c1, c2, c3, c4)) {
            insertFeedInboxRow(viewerId, id);
        }
        markSynced(viewerId);
        String token = tokenOf(PHONE_VIEWER);

        JsonNode first = Envelope.data(getFeed(token, 1, 2));
        assertThat(idList(first.path("list"))).as("首屏 2 条，锚点 = 第一条 c4").containsExactly(c4, c3);
        assertThat(first.path("total").asInt()).isEqualTo(4);

        // 首屏之后又发了一条：直接插 DB + 删收件箱缓存（等价于 fanout 的"写后失效"）
        long c5 = insertVideoContent(authorId, "首屏之后发布");
        insertFeedInboxRow(viewerId, c5);
        redis.delete(FEED_INBOX_PREFIX + viewerId);

        JsonNode drift = Envelope.data(getFeed(token, 2, 2));
        assertThat(idList(drift.path("list")))
                .as("★对照：不带锚点时窗口整体后移，c3 在第 1 页已出现过 —— 这正是锚点要解决的问题")
                .containsExactly(c3, c2);

        JsonNode anchored = Envelope.data(getFeed(token, 2, 2, String.valueOf(c4)));
        assertThat(idList(anchored.path("list")))
                .as("带锚点 ⇒ 只在 c4（含）以下切页，第 2 页是 c2,c1，不重复")
                .containsExactly(c2, c1);
        assertThat(anchored.path("total").asInt())
                .as("total = 锚点内条数（不含首屏之后的新内容），与已冻结的列表一致")
                .isEqualTo(4);
        assertThat(anchored.path("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("锚点是包含的（id <= 锚点）：锚点本身仍在第 1 页，total 随之收窄")
    void 锚点包含自身() {
        long viewerId = registerViewer();
        long authorId = registerAuthor();
        long c1 = insertVideoContent(authorId, "a");
        long c2 = insertVideoContent(authorId, "b");
        long c3 = insertVideoContent(authorId, "c");
        insertFollowRow(viewerId, authorId);
        for (long id : List.of(c1, c2, c3)) {
            insertFeedInboxRow(viewerId, id);
        }
        markSynced(viewerId);

        JsonNode data = Envelope.data(getFeed(tokenOf(PHONE_VIEWER), 1, 2, String.valueOf(c3)));
        assertThat(idList(data.path("list"))).as("锚点 c3 本身可见（≤ 而非 <）").containsExactly(c3, c2);
        assertThat(data.path("total").asInt()).as("total 只算锚点内").isEqualTo(3);
    }

    @Test
    @DisplayName("锚点非法 / 非正 → 静默忽略（不 400、也不过滤成空页）")
    void 锚点非法静默忽略() {
        long viewerId = registerViewer();
        long authorId = registerAuthor();
        long c1 = insertVideoContent(authorId, "a");
        long c2 = insertVideoContent(authorId, "b");
        insertFollowRow(viewerId, authorId);
        insertFeedInboxRow(viewerId, c1);
        insertFeedInboxRow(viewerId, c2);
        markSynced(viewerId);
        String token = tokenOf(PHONE_VIEWER);

        for (String raw : List.of("abc", "0", "-3")) {
            ResponseEntity<String> resp = getFeed(token, 1, 2, raw);
            assertThat(resp.getStatusCode()).as("非法锚点 %s 不应变成 400", raw).isEqualTo(HttpStatus.OK);
            JsonNode data = Envelope.data(resp);
            assertThat(idList(data.path("list"))).as("非法锚点 %s 等价于不带", raw).containsExactly(c2, c1);
            assertThat(data.path("total").asInt()).isEqualTo(2);
        }
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
    @DisplayName("★未同步（纯拉）同样受锚点约束：计数与当页必须同条件")
    void 纯拉路径_快照锚点() {
        long viewerId = registerViewer();
        long authorId = registerAuthor();
        long c1 = insertVideoContent(authorId, "第1条");
        long c2 = insertVideoContent(authorId, "第2条");
        long c3 = insertVideoContent(authorId, "第3条");
        long c4 = insertVideoContent(authorId, "第4条");
        insertFollowRow(viewerId, authorId);
        // 故意不写 feed_inbox_sync ⇒ 走 purePull
        assertThat(isSynced(viewerId)).isFalse();
        String token = tokenOf(PHONE_VIEWER);

        JsonNode first = Envelope.data(getFeed(token, 1, 2));
        assertThat(idList(first.path("list"))).containsExactly(c4, c3);
        assertThat(first.path("total").asInt()).isEqualTo(4);

        insertVideoContent(authorId, "首屏之后发布");   // c5

        JsonNode drift = Envelope.data(getFeed(token, 2, 2));
        assertThat(idList(drift.path("list")))
                .as("★对照：纯拉不带锚点时第 2 页同样从 c3 开始（重复）")
                .containsExactly(c3, c2);

        JsonNode anchored = Envelope.data(getFeed(token, 2, 2, String.valueOf(c4)));
        assertThat(idList(anchored.path("list"))).containsExactly(c2, c1);
        assertThat(anchored.path("total").asInt())
                .as("计数 SQL 也必须带 id<=锚点，否则 total 会变成 5")
                .isEqualTo(4);
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
    @DisplayName("★R-31：图文条目带 imageUrls（按 sort 升序，oracle = content_media 行）；视频条目为 null")
    void 图文条目带图集且视频为null() {
        long viewerId = registerViewer();
        long authorId = registerAuthor();
        // ★ 故意**逆序插入**媒体行：sort 2 先插、sort 1 后插。
        //   若 buildContentMedia 丢了 sort（只靠 DAO 默认顺序），actual 会变成 2,1 ⇒ 红。
        long post = insertContent(authorId, CONTENT_TYPE_IMAGE, "feed 图文图集", "feed 图文图集 的简介", 2);
        insertMedia(post, "/upload/image/" + post + "_2.jpg", MEDIA_TYPE_IMAGE, 2);
        insertMedia(post, "/upload/image/" + post + "_1.jpg", MEDIA_TYPE_IMAGE, 1);
        insertMedia(post, "/upload/cover/" + post + ".png", MEDIA_TYPE_COVER, 1);
        long video = insertVideoContent(authorId, "feed 视频无图集");
        insertFollowRow(viewerId, authorId);
        insertFeedInboxRow(viewerId, post);
        insertFeedInboxRow(viewerId, video);
        markSynced(viewerId);

        JsonNode list = Envelope.data(getFeed(tokenOf(PHONE_VIEWER))).path("list");

        // 独立 oracle：直接读 content_media 的 type=2 行（sort 升序），不看接口回显
        List<String> expected = jdbcTemplate.queryForList(
                "SELECT url FROM content_media WHERE content_id = ? AND type = 2 ORDER BY sort",
                String.class, post);
        assertThat(expected).as("夹具：图文有两张图").hasSize(2);

        JsonNode postItem = itemOf(list, post);
        assertThat(postItem.path("imageUrls").isArray()).as("R-31：图文列表条目带图集").isTrue();
        List<String> actual = new ArrayList<>();
        postItem.path("imageUrls").forEach(node -> actual.add(node.asString()));
        assertThat(actual).as("顺序必须与 content_media.sort 一致").containsExactlyElementsOf(expected);

        JsonNode videoItem = itemOf(list, video);
        assertThat(videoItem.path("imageUrls").isNull())
                .as("视频条目的 imageUrls 为 null（不是省略、也不是空数组）").isTrue();
        // 反向边界：imageUrls 放开了，但 videoUrl 仍必须被 @JsonIgnore 挡住
        assertThat(postItem.has("videoUrl")).as("图文条目不带 videoUrl").isFalse();
        assertThat(videoItem.has("videoUrl")).as("视频条目也不带 videoUrl（播放地址属详情）").isFalse();
    }

    @Test
    @DisplayName("★GAP-01：动态卡片带 play/like/comment（= DB 列），coinCount 键不存在；interaction.coin 恒 null")
    void 条目带动态卡片计数且coin不存在() {
        long viewerId = registerViewer();
        long authorId = registerAuthor();
        long c1 = insertVideoContent(authorId, "feed 列表计数内容");
        insertFollowRow(viewerId, authorId);
        insertFeedInboxRow(viewerId, c1);
        markSynced(viewerId);
        // 前置：把三列钉到非零且互不相同 —— 否则"本面不查（null）"与"值就是 0"分不清
        jdbcTemplate.update("UPDATE content SET like_count = 3, comment_count = 5, play_count = 7, "
                + "coin_count = 2 WHERE id = ?", c1);

        JsonNode item = itemOf(Envelope.data(getFeed(tokenOf(PHONE_VIEWER))).path("list"), c1);

        // 独立 oracle：直接读 DB 列（不信接口回显）
        assertThat(item.path("playCount").asLong())
                .as("动态卡片嵌入块显示播放量；oracle = DB 列").isEqualTo(7L);
        assertThat(item.path("likeCount").asLong())
                .as("动态卡片操作栏显示点赞数；oracle = DB 列").isEqualTo(3L);
        assertThat(item.path("commentCount").asLong())
                .as("动态卡片操作栏显示评论总数；oracle = DB 列").isEqualTo(5L);
        assertThat(item.has("coinCount"))
                .as("★ 投币数没有动态卡片展示位 ⇒ 连字段都不加").isFalse();
        assertThat(item.has("favoriteCount"))
                .as("★ 收藏数没有动态卡片展示位 ⇒ 连字段都不加").isFalse();
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
        ResponseEntity<String> resp = post("/api/v1/sessions",
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
