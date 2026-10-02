package io.github.yjhhhaaa06.videoweb.comment;

import io.github.yjhhhaaa06.videoweb.content.AbstractContentIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5：comment **读路径**的 HTTP 契约测试（{@code /comment/show}、{@code /comment/replies}）。
 *
 * <h2>钉的是旧 pytest {@code test_comment_paging.py} 的全部契约</h2>
 * <ul>
 *   <li><b>缺省（不传 page/pageSize）→ {@code data} 是全量数组</b>（children 全量随行 + {@code replyCount}）；
 *       传任一 → 分页信封，{@code total} = **主楼条数**；</li>
 *   <li>分页归一：{@code page} 缺省 → 1；{@code pageSize} 缺省 → **200**（评论域信封）、上限 **500**；</li>
 *   <li>每主楼 children ≤ **K=2**（预览）且带 {@code replyCount}（该主楼回复总数）；展开走
 *       {@code /comment/replies}，其 {@code total} 与 {@code replyCount} **同口径**；</li>
 *   <li>越界页 → 空 list 但 **total 保留**（前端据此判末页）。</li>
 * </ul>
 *
 * <h2>★ 两类"看似 bug、实为规格"的行为（必须有测试钉住）</h2>
 * <ol>
 *   <li><b>内容不存在 / 评论区关闭 → 200 空</b>，不是 404/409。读评论不该因内容不可见而报错。</li>
 *   <li><b>缺省路径的 children 是"全量"、分页路径是"前 2 条"</b>——两条路径的 children 口径
 *       **刻意不同**（T8/T10-B）。若有人"统一"成同一个口径，两者之一必错。</li>
 * </ol>
 */
class CommentReadTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900003001";
    private static final String AUTHOR = "comment-author";
    private static final String VIEWER_PHONE = "13900003002";
    private static final String VIEWER = "comment-viewer";

    /** 专用内容：5 条主楼，第 1 条主楼下 3 条回复，另有独立评论用于边界用例。 */
    private long contentId;
    private long authorId;
    private String authorToken;
    private final List<Long> mainIds = new ArrayList<>();
    private long firstRootWithReplies;

    private void seed() {
        authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        authorId = userIdOf(AUTHOR_PHONE);
        contentId = insertVideoContent(authorId, "评论读路径内容");
        for (int i = 0; i < 5; i++) {
            mainIds.add(insertMainComment(contentId, authorId, "主楼 " + i));
        }
        firstRootWithReplies = mainIds.get(0);
        for (int i = 0; i < 3; i++) {
            insertComment(contentId, authorId, "回复 " + i, firstRootWithReplies, null);
        }
        setReplyCount(firstRootWithReplies, 3);
    }

    // ========================================================================
    // /comment/show
    // ========================================================================

    @Test
    @DisplayName("缺省：返回**全量数组**（children 全量随行 + replyCount）")
    void 缺省返回全量数组() {
        seed();
        JsonNode data = Envelope.data(get("/comment/show?contentId=" + contentId, null));
        assertThat(data.isArray()).as("不传分页参数应保持全量数组（缺省兼容）").isTrue();
        assertThat(data.size()).isEqualTo(5);
        assertThat(rootIds(data)).isEqualTo(mainIds);
        JsonNode root0 = data.get(0);
        assertThat(root0.path("replyCount").asInt()).as("缺省数组也带 replyCount（T10-B）").isEqualTo(3);
        assertThat(root0.path("children").size()).as("★缺省路径 children 是**全量**，不截断").isEqualTo(3);
    }

    @Test
    @DisplayName("分页：信封 total = 主楼条数；每主楼 children ≤ K=2 且带 replyCount")
    void 分页信封与预览K() {
        seed();
        JsonNode data = Envelope.data(get("/comment/show?contentId=" + contentId + "&page=1&pageSize=3", null));
        assertThat(data.path("total").asInt()).as("total 是**主楼**条数，不是评论总数").isEqualTo(5);
        assertThat(data.path("page").asInt()).isEqualTo(1);
        assertThat(data.path("pageSize").asInt()).isEqualTo(3);
        assertThat(data.path("totalPages").asInt()).isEqualTo(2);
        assertThat(rootIds(data.path("list"))).isEqualTo(mainIds.subList(0, 3));

        JsonNode root0 = data.path("list").get(0);
        assertThat(root0.path("children").size()).as("★分页路径只带前 K=2 条").isEqualTo(2);
        assertThat(root0.path("replyCount").asInt()).as("replyCount 是总数（不受截断影响）").isEqualTo(3);
    }

    @Test
    @DisplayName("分页：半参数也算分页请求；pageSize 缺省取评论域信封 200、上限 500")
    void 分页半参数与域级常量() {
        seed();
        JsonNode bySize = Envelope.data(get("/comment/show?contentId=" + contentId + "&pageSize=3", null));
        assertThat(bySize.path("page").asInt()).as("只传 pageSize 也算分页").isEqualTo(1);
        assertThat(rootIds(bySize.path("list"))).isEqualTo(mainIds.subList(0, 3));

        JsonNode byPage = Envelope.data(get("/comment/show?contentId=" + contentId + "&page=1", null));
        assertThat(byPage.path("pageSize").asInt()).as("comments 域信封是 200（不是 100）").isEqualTo(200);
        assertThat(rootIds(byPage.path("list"))).isEqualTo(mainIds);

        JsonNode capped = Envelope.data(
                get("/comment/show?contentId=" + contentId + "&page=1&pageSize=999", null));
        assertThat(capped.path("pageSize").asInt()).as("上限 500（与 search/profile 的 100 不同）").isEqualTo(500);
    }

    @Test
    @DisplayName("分页：越界页空 list 但 total 保留；pageSize=1 逐页不重不漏")
    void 分页越界与逐页() {
        seed();
        JsonNode beyond = Envelope.data(
                get("/comment/show?contentId=" + contentId + "&page=99&pageSize=3", null));
        assertThat(beyond.path("list").size()).isZero();
        assertThat(beyond.path("total").asInt()).as("越界页仍保留真实 total").isEqualTo(5);

        List<Long> collected = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            JsonNode one = Envelope.data(
                    get("/comment/show?contentId=" + contentId + "&page=" + page + "&pageSize=1", null));
            assertThat(one.path("list").size()).as("pageSize=1 每页应 1 条").isEqualTo(1);
            collected.add(one.path("list").get(0).path("commentId").asLong());
        }
        assertThat(collected).as("页间不重不漏、顺序与全量一致").isEqualTo(mainIds);
    }

    @Test
    @DisplayName("门禁：内容不存在 → 200 + 空；开关关闭 → 200 + 空（不是 404/409）")
    void 门禁返回空而不是报错() {
        seed();
        ResponseEntity<String> missing = get("/comment/show?contentId=987654321", null);
        assertThat(missing.getStatusCode().value()).as("内容不存在 ⇒ 200").isEqualTo(200);
        assertThat(Envelope.data(missing).size()).isZero();

        assertThat(Envelope.data(get("/comment/show?contentId=" + contentId, null)).size())
                .as("前置：开启状态下 5 条主楼可见").isEqualTo(5);

        // ★ 必须走**真实端点**关闭——直改 JDBC 会绕过缓存失效，测出的只是"缓存陈旧"而不是"门禁生效"。
        //   这条链路同时是 S2 那笔账的闭环证明：CM-3 说"S5 搬 /content/commentEnabled 后即可闭环"。
        assertThat(postQuery("/content/commentEnabled",
                query("contentId", String.valueOf(contentId), "enabled", "0"), authorToken)
                .getStatusCode().value()).isEqualTo(200);

        JsonNode closed = Envelope.data(get("/comment/show?contentId=" + contentId, null));
        assertThat(closed.size()).as("关闭后评论整体不可见").isZero();
        JsonNode closedPaged = Envelope.data(
                get("/comment/show?contentId=" + contentId + "&page=1&pageSize=10", null));
        assertThat(closedPaged.path("total").asInt()).as("关闭后分页信封 total=0").isZero();

        assertThat(postQuery("/content/commentEnabled",
                query("contentId", String.valueOf(contentId), "enabled", "1"), authorToken)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.data(get("/comment/show?contentId=" + contentId, null)).size())
                .as("重新开启即恢复（评论数据从未被删）").isEqualTo(5);
    }

    @Test
    @DisplayName("门禁：缺 contentId → 400")
    void 缺contentId() {
        assertThat(get("/comment/show", null).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("isLiked：匿名全 false；登录后仅「已点赞的那条」为 true")
    void 点赞态标注() {
        seed();
        String viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);
        long viewerId = userIdOf(VIEWER_PHONE);
        insertCommentLike(viewerId, mainIds.get(1));

        JsonNode anonymous = Envelope.data(get("/comment/show?contentId=" + contentId, null));
        for (JsonNode node : anonymous) {
            assertThat(node.path("isLiked").asBoolean()).isFalse();
        }

        JsonNode loggedIn = Envelope.data(get("/comment/show?contentId=" + contentId, viewerToken));
        assertThat(loggedIn.get(0).path("isLiked").asBoolean()).isFalse();
        assertThat(loggedIn.get(1).path("isLiked").asBoolean()).as("只有第 2 条被点赞").isTrue();
        assertThat(loggedIn.get(2).path("isLiked").asBoolean()).isFalse();
    }

    // ========================================================================
    // /comment/replies
    // ========================================================================

    @Test
    @DisplayName("展开：信封 total = 主楼 reply_count；升序；越界保留 total")
    void 展开回复() {
        seed();
        JsonNode data = Envelope.data(
                get("/comment/replies?rootId=" + firstRootWithReplies + "&page=1&pageSize=50", null));
        assertThat(data.path("total").asInt()).isEqualTo(3);
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : data.path("list")) {
            ids.add(node.path("commentId").asLong());
        }
        assertThat(ids).as("comment_id 升序").isSorted();
        assertThat(ids.size()).isEqualTo(3);

        JsonNode second = Envelope.data(
                get("/comment/replies?rootId=" + firstRootWithReplies + "&page=2&pageSize=2", null));
        assertThat(second.path("list").size()).isEqualTo(1);
        assertThat(second.path("total").asInt()).isEqualTo(3);

        JsonNode beyond = Envelope.data(
                get("/comment/replies?rootId=" + firstRootWithReplies + "&page=99&pageSize=2", null));
        assertThat(beyond.path("list").size()).isZero();
        assertThat(beyond.path("total").asInt()).as("越界页保留真实 total").isEqualTo(3);

        JsonNode byPageOnly = Envelope.data(
                get("/comment/replies?rootId=" + firstRootWithReplies + "&page=1", null));
        assertThat(byPageOnly.path("pageSize").asInt()).as("缺省信封 200（T11-B）").isEqualTo(200);
    }

    @Test
    @DisplayName("展开：主楼不存在或已删 → 404；缺 rootId → 400")
    void 展开边界() {
        seed();
        assertThat(get("/comment/replies", null).getStatusCode().value()).isEqualTo(400);
        assertThat(get("/comment/replies?rootId=987654321", null).getStatusCode().value()).isEqualTo(404);

        softDeleteComment(firstRootWithReplies);
        ResponseEntity<String> deleted = get("/comment/replies?rootId=" + firstRootWithReplies, null);
        assertThat(deleted.getStatusCode().value()).as("被删主楼展开应失败").isEqualTo(404);
    }

    @Test
    @DisplayName("展开：点赞态只对该页回复批量查询并正确标注")
    void 展开点赞态() {
        seed();
        String viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);
        long viewerId = userIdOf(VIEWER_PHONE);
        JsonNode all = Envelope.data(
                get("/comment/replies?rootId=" + firstRootWithReplies + "&page=1&pageSize=50", null));
        long secondReplyId = all.path("list").get(1).path("commentId").asLong();
        insertCommentLike(viewerId, secondReplyId);

        JsonNode data = Envelope.data(get("/comment/replies?rootId=" + firstRootWithReplies
                + "&page=1&pageSize=50", viewerToken));
        assertThat(data.path("list").get(0).path("isLiked").asBoolean()).isFalse();
        assertThat(data.path("list").get(1).path("isLiked").asBoolean()).isTrue();
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    private static List<Long> rootIds(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : list) {
            ids.add(node.path("commentId").asLong());
        }
        return ids;
    }
}
