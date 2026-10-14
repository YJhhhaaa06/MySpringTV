package io.github.yjhhhaaa06.videoweb.comment;

import io.github.yjhhhaaa06.videoweb.comment.dao.CommentDao;
import io.github.yjhhhaaa06.videoweb.content.AbstractContentIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

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
 * <h2>★ T14（F-20 ②）：{@code /comment/replies} 由 offset 分页改**游标（keyset）分页**</h2>
 * 上方那几条 offset 契约**除外**于 {@code /comment/replies}：它现在入参 {@code cursor}、
 * 出参加 {@code nextCursor}（{@code null} = 到底）。改的理由 = offset 的
 * {@code LIMIT page*pageSize} 让**负载 ∝ 页码**；游标只取本页。本类的
 * {@code 性能契约_本页DB取行数不随游标深度增长} 用 spy 钉住该不变量的**判别力**；
 * {@code 游标翻页不重不漏} 用独立 oracle 钉住"页间不重不漏"（不因改实现而变化）。
 * <p>⚠️ 旧的 {@code page} 参数不再被识别（传了退化为第 1 页）；前端 {@code detail.js} 已同批改游标。
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

    /**
     * ★ T14：钉"**本页向 DB 取的行数只由 pageSize 决定、与游标深度无关**"（性能契约的观测手段）。
     *
     * <p>为什么 spy **DAO** 而不是断言响应体：负载 ∝ 页码 的病灶在"**DB 返回了 page*pageSize 行**"，
     * 这在响应体上**看不见**（多取的行被内存切片丢掉了）⇒ 只能观测"向 DB 要了多少行"。
     * 先例 = `ContentReadTests` 用 spy 数下游被调次数（T13 的批读趟数契约）。
     */
    @MockitoSpyBean
    private CommentDao commentDao;

    @BeforeEach
    void 重置spy() {
        // spy 是单例 bean，调用记录跨用例累积 ⇒ 每测清零（真实行为不受影响）。
        // ⚠️ reset 必须在"被测请求"之前：写在之后会把待验证的调用记录一起清掉（T13 实测踩到）。
        Mockito.reset(commentDao);
    }

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
    @DisplayName("展开：游标信封 {list,total,pageSize,nextCursor}；升序；nextCursor=null 即到底；越界空页保留 total")
    void 展开回复() {
        seed();
        // 一页装下 ⇒ 直接到底
        JsonNode onePage = Envelope.data(
                get("/comment/replies?rootId=" + firstRootWithReplies + "&pageSize=50", null));
        assertThat(onePage.path("total").asInt()).isEqualTo(3);
        assertThat(onePage.path("pageSize").asInt()).isEqualTo(50);
        assertThat(rootIds(onePage.path("list"))).as("comment_id 升序").isSorted().hasSize(3);
        assertThat(onePage.path("nextCursor").isNull()).as("一页装下 3 条 ⇒ 到底").isTrue();

        // 两页：nextCursor 逐页推进，末页为 null
        JsonNode p1 = Envelope.data(
                get("/comment/replies?rootId=" + firstRootWithReplies + "&pageSize=2", null));
        List<Long> firstIds = rootIds(p1.path("list"));
        assertThat(firstIds).hasSize(2);
        long cursor2 = p1.path("nextCursor").asLong();
        assertThat(cursor2).as("nextCursor = 本页末条 commentId").isEqualTo(firstIds.get(1));

        JsonNode p2 = Envelope.data(
                get("/comment/replies?rootId=" + firstRootWithReplies + "&cursor=" + cursor2 + "&pageSize=2", null));
        assertThat(p2.path("list").size()).isEqualTo(1);
        assertThat(p2.path("total").asInt()).isEqualTo(3);
        assertThat(p2.path("nextCursor").isNull()).as("末页 ⇒ nextCursor=null").isTrue();

        // 缺省 pageSize = 评论域信封 200（本条语义未变）
        JsonNode byDefault = Envelope.data(get("/comment/replies?rootId=" + firstRootWithReplies, null));
        assertThat(byDefault.path("pageSize").asInt()).as("缺省信封 200（T11-B）").isEqualTo(200);

        // 越界游标：空 list + nextCursor=null，但 total 仍为真值（与 offset 分页"越界页"同构）
        JsonNode beyond = Envelope.data(
                get("/comment/replies?rootId=" + firstRootWithReplies + "&cursor=999999999&pageSize=2", null));
        assertThat(beyond.path("list").size()).isZero();
        assertThat(beyond.path("total").asInt()).as("越界游标保留真实 total").isEqualTo(3);
        assertThat(beyond.path("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("★ 独立 oracle：游标逐条翻页 → 页间不重不漏、顺序与 DB 行集一致（含二级间接回复）")
    void 游标翻页不重不漏() {
        seed();
        // 造一条二级间接回复（挂到首条直接回复下）—— 覆盖 SQL 的"直接回复 + 二级间接回复"谓词
        Long direct1 = jdbcTemplate.queryForObject(
                "SELECT MIN(comment_id) FROM comment WHERE parent_id = ?", Long.class, firstRootWithReplies);
        insertComment(contentId, authorId, "二级间接回复", direct1, authorId);
        setReplyCount(firstRootWithReplies, 4);

        // ★ 夹具带"对照"（仓库根 AGENTS.md「测试纪律」）：造"应被过滤掉"的干扰行，否则去掉该过滤仍全绿。
        //   ① 另一主楼（mainIds[1]）下的回复 —— 验证 `parent_id = rootId OR parent_id IN (…)` 谓词；
        //   ② 本主楼下一**软删**回复 —— 验证 `is_deleted = 0`。
        //   ⚠️ `content_id = ?` 未带对照：其干扰行（parent 指向本楼、却属另一 content）在生产不可达
        //      —— `addComment` 校验"被回复评论属于该内容"，故不造这种假数据。
        for (int i = 0; i < 2; i++) {
            insertComment(contentId, authorId, "他楼回复 " + i, mainIds.get(1), null);
        }
        long deletedReply = insertComment(contentId, authorId, "已删回复", firstRootWithReplies, null);
        jdbcTemplate.update("UPDATE comment SET is_deleted = 1 WHERE comment_id = ?", deletedReply);

        // 独立 oracle：从**原始行集**在 Java 侧重建"该主楼的回复集"（不照抄被测 SQL 的两层谓词）
        Map<Long, List<Long>> childrenOf = new LinkedHashMap<>();
        TreeSet<Long> direct = new TreeSet<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(
                "SELECT comment_id, parent_id FROM comment WHERE content_id = ? AND is_deleted = 0", contentId)) {
            Object parentObj = r.get("parent_id");
            if (parentObj == null) {
                continue;
            }
            long id = ((Number) r.get("comment_id")).longValue();
            long parent = ((Number) parentObj).longValue();
            childrenOf.computeIfAbsent(parent, k -> new ArrayList<>()).add(id);
            if (parent == firstRootWithReplies) {
                direct.add(id);
            }
        }
        TreeSet<Long> expected = new TreeSet<>(direct);
        for (Long d : direct) {
            expected.addAll(childrenOf.getOrDefault(d, List.of()));
        }
        assertThat(expected).as("前置：该主楼共 4 条回复（3 直接 + 1 二级间接）").hasSize(4);

        // pageSize=1 逐条翻页 —— 最严格地暴露"重 / 漏 / 错序"
        List<Long> walked = new ArrayList<>();
        long cursor = 0;
        for (int guard = 0; guard < 100; guard++) {
            JsonNode data = Envelope.data(get("/comment/replies?rootId=" + firstRootWithReplies
                    + "&cursor=" + cursor + "&pageSize=1", null));
            assertThat(data.path("total").asInt()).as("total 每页恒为 reply_count").isEqualTo(4);
            for (JsonNode node : data.path("list")) {
                walked.add(node.path("commentId").asLong());
            }
            if (data.path("nextCursor").isNull()) {
                break;
            }
            long next = data.path("nextCursor").asLong();
            assertThat(next).as("游标必须严格前进").isGreaterThan(cursor);
            cursor = next;
        }
        assertThat(walked).as("页间不重不漏、升序、与独立 oracle 行集一致")
                .isEqualTo(new ArrayList<>(expected)).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("兜底：缺省 / 非数字 / 非正 cursor 静默归一到楼头（第 1 页），不报错")
    void 非法游标兜底() {
        seed();
        for (String c : new String[]{"", "abc", "-5", "0"}) {
            JsonNode data = Envelope.data(get("/comment/replies?rootId=" + firstRootWithReplies
                    + "&cursor=" + c + "&pageSize=50", null));
            assertThat(data.path("list").size()).as("cursor=[%s] 应回到第 1 页", c).isEqualTo(3);
            assertThat(data.path("nextCursor").isNull()).isTrue();
        }
    }

    /**
     * ★ 性能契约（T14）：钉"**本页向 DB 取的行数只由 pageSize 决定、与游标深度无关**"。
     *
     * <p>⚠️ **这条断言的作用域要说清**：它是 **DAO 参数级** 的（`@MockitoSpyBean` 看的是"服务向
     * DAO 要了多少行"），**不覆盖** SQL 侧 / 执行计划侧（"MySQL 实际只扫了本页"）。
     * "负载 ∝ 本页"的**设计级证据**是一次性基线与改造后对照（见 `收藏功能-编号索引.md` **A.1 T14**：
     * 优化前 DB 返 2 / 20000 行、HTTP 21ms / 68ms；改造后 ≤ pageSize+1 行、HTTP 20ms / 8ms）。
     */
    @Test
    @DisplayName("★ 性能契约：向 DB 取的行数 = pageSize+1，且与游标深度无关（负载 ∝ 本页，不再 ∝ 页码）")
    void 性能契约_本页DB取行数不随游标深度增长() {
        seed();
        // 该主楼再补 25 条直接回复（seed 已有 3 条）⇒ 共 28 条，令 pageSize=10 时可翻 3 页
        for (int i = 0; i < 25; i++) {
            insertComment(contentId, authorId, "补 " + i, firstRootWithReplies, null);
        }
        setReplyCount(firstRootWithReplies, 28);
        Mockito.reset(commentDao);   // ⚠️ 前置夹具（建内容 / 建回复）的 DAO 调用不计入

        JsonNode p1 = Envelope.data(
                get("/comment/replies?rootId=" + firstRootWithReplies + "&pageSize=10", null));
        assertThat(p1.path("list").size()).isEqualTo(10);
        long c2 = p1.path("nextCursor").asLong();

        JsonNode p2 = Envelope.data(get("/comment/replies?rootId=" + firstRootWithReplies
                + "&cursor=" + c2 + "&pageSize=10", null));
        assertThat(p2.path("list").size()).isEqualTo(10);
        long c3 = p2.path("nextCursor").asLong();

        JsonNode p3 = Envelope.data(get("/comment/replies?rootId=" + firstRootWithReplies
                + "&cursor=" + c3 + "&pageSize=10", null));
        assertThat(p3.path("list").size()).isEqualTo(8);
        assertThat(p3.path("nextCursor").isNull()).as("28 条 / 每页 10 ⇒ 第 3 页到底").isTrue();

        // 三次请求都只向 DB 取 pageSize+1 = 11 行；旧实现（LIMIT page*pageSize）会随页码增长 ⇒ 变红
        verify(commentDao).getRepliesInTreeByRoot(anyLong(), eq(firstRootWithReplies), eq(0L), eq(11));
        verify(commentDao).getRepliesInTreeByRoot(anyLong(), eq(firstRootWithReplies), eq(c2), eq(11));
        verify(commentDao).getRepliesInTreeByRoot(anyLong(), eq(firstRootWithReplies), eq(c3), eq(11));
    }

    @Test
    @DisplayName("契约破面：旧 page 参数不再被识别（静默退化为第 1 页，不报错）；信封不再有 page/totalPages")
    void 旧page参数不再被识别() {
        seed();
        JsonNode withPage = Envelope.data(
                get("/comment/replies?rootId=" + firstRootWithReplies + "&page=99&pageSize=2", null));
        assertThat(withPage.path("list").size()).as("page=99 被忽略 ⇒ 仍返回第 1 页 2 条").isEqualTo(2);
        assertThat(withPage.path("list").get(0).path("commentId").asLong())
                .as("第 1 条 = 楼内最小 id")
                .isEqualTo(jdbcTemplate.queryForObject(
                        "SELECT MIN(comment_id) FROM comment WHERE parent_id = ?",
                        Long.class, firstRootWithReplies));
        assertThat(withPage.path("page").isMissingNode()).as("信封不再有 page 字段").isTrue();
        assertThat(withPage.path("totalPages").isMissingNode()).as("信封不再有 totalPages 字段").isTrue();
        assertThat(withPage.path("nextCursor").isNumber()).as("信封有 nextCursor（还有下一页）").isTrue();
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
                get("/comment/replies?rootId=" + firstRootWithReplies + "&pageSize=50", null));
        long secondReplyId = all.path("list").get(1).path("commentId").asLong();
        insertCommentLike(viewerId, secondReplyId);

        JsonNode data = Envelope.data(get("/comment/replies?rootId=" + firstRootWithReplies
                + "&pageSize=50", viewerToken));
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
