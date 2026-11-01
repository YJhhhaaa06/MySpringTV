package io.github.yjhhhaaa06.videoweb.content;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 推荐流的**翻页不变量**（`R-28`；口径见
 * {@code architecture/domain/content/content-推荐流.md}）。
 *
 * <h2>为什么这些用例值钱</h2>
 * "随机序翻页"的坏实现有一大类**看起来正常**的失败：每页都有 20 条、每页都 200、点开都能播，
 * 只是**跨页重复 / 漏项**。它不会被"列表非空"之类的断言抓住，只能靠
 * 「**翻完全部页 == 池子全集，且无重复**」这一条独立 oracle —— 期望值来自直读索引 key 的 id 集合，
 * 与被测实现无关。
 *
 * <h2>三条判别力来自哪里</h2>
 * <ul>
 *   <li><b>去掉 {@code rank < lastRank} 过滤</b> ⇒ 第一页会被重复返回 ⇒ "无重复"必红；</li>
 *   <li><b>把 {@code nextCursor} 改成"本页不满才给 null"</b> ⇒ 恰好整除时最后一页会多一个空请求
 *       （无判别力），但"取 pageSize+1 判定"若退化成"取 pageSize"，整除时会把"还有下一页"错报成到底
 *       ⇒ {@link #翻完全部页等于池子全集()} 必红（漏掉一页）；</li>
 *   <li><b>游标改用下标而不是 id</b> ⇒ {@link #游标指向已删内容仍精确()} 必红。</li>
 * </ul>
 *
 * <h2>夹具纪律</h2>
 * 每个用例都由 {@code AbstractContentIntegrationTest#resetState} 清空内容与 Redis，
 * 所以"池子"就是本用例插的那几条 —— 不与其它用例串味。
 */
class RecommendFeedTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900003001";
    private static final String AUTHOR = "feed-author";

    // ========================================================================
    // 核心：不重不漏
    // ========================================================================

    @Test
    @DisplayName("★翻完全部页 == 池子全集，且无重复（独立 oracle：直读 content:index:-1:-1）")
    void 翻完全部页等于池子全集() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        Set<Long> inserted = new LinkedHashSet<>();
        for (int i = 1; i <= 7; i++) {
            inserted.add(insertVideoContent(authorId, "推荐页内容 " + i));
        }

        List<Long> collected = walkAllPages(3);

        assertThat(collected).as("跨页不得重复").doesNotHaveDuplicates();
        assertThat(new LinkedHashSet<>(collected))
                .as("漏项：翻完所有页应恰好覆盖池子")
                .isEqualTo(inserted);
        assertThat(idsInIndex()).as("池子（索引）本身应等于插入的内容").isEqualTo(inserted);
    }

    @Test
    @DisplayName("相邻两页交集为空（游标严格向后）")
    void 相邻两页无交集() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        for (int i = 1; i <= 12; i++) {
            insertVideoContent(authorId, "相邻页内容 " + i);
        }

        ResponseEntity<String> first = get("/api/v1/contents/recommended?pageSize=5", null);
        String cursor = cursorOf(first);
        assertThat(cursor).as("5 条 < 池子 12 条 ⇒ 必须有下一页").isNotNull();

        ResponseEntity<String> second = get("/api/v1/contents/recommended?pageSize=5&cursor=" + cursor, null);

        assertThat(idsOf(first)).hasSize(5);
        assertThat(idsOf(second)).hasSize(5).doesNotContainAnyElementsOf(idsOf(first));
    }

    // ========================================================================
    // 删除 / 脏 id
    // ========================================================================

    @Test
    @DisplayName("★游标指向已删内容仍精确：下一页不重不漏（游标锚在 id 上，不依赖它存在）")
    void 游标指向已删内容仍精确() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        for (int i = 1; i <= 6; i++) {
            insertVideoContent(authorId, "游标删除内容 " + i);
        }

        ResponseEntity<String> first = get("/api/v1/contents/recommended?pageSize=2", null);
        List<Long> page1 = idsOf(first);
        long lastOfPage1 = page1.get(page1.size() - 1);
        String cursor = cursorOf(first);

        // 直接删库：内容没了，但索引里的 id 还在（= 脏 id；比"正常删除"更苛刻）
        jdbcTemplate.update("DELETE FROM content WHERE id = ?", lastOfPage1);

        ResponseEntity<String> second = get("/api/v1/contents/recommended?pageSize=2&cursor=" + cursor, null);
        List<Long> page2 = idsOf(second);

        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(page2).as("游标指向的那条已不存在，仍应从它之后继续").hasSize(2);
        assertThat(page2).doesNotContain(lastOfPage1).doesNotContainAnyElementsOf(page1);
    }

    @Test
    @DisplayName("池中有脏 id（索引留着、内容已删）：跳过它，且本页仍填满")
    void 脏id被跳过且不占名额() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ids.add(insertVideoContent(authorId, "脏 id 内容 " + i));
        }
        long dirty = ids.get(2);
        jdbcTemplate.update("DELETE FROM content WHERE id = ?", dirty);

        List<Long> collected = walkAllPages(2);

        assertThat(collected).as("不得出现已删内容").doesNotContain(dirty);
        assertThat(collected).as("其余 4 条一条不少").hasSize(4).doesNotHaveDuplicates();
    }

    // ========================================================================
    // 参数 / 边界
    // ========================================================================

    @Test
    @DisplayName("空池：{list: [], nextCursor: null}，200")
    void 空池() {
        ResponseEntity<String> resp = get("/api/v1/contents/recommended?pageSize=10", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(idsOf(resp)).isEmpty();
        assertThat(cursorOf(resp)).isNull();
    }

    @Test
    @DisplayName("pageSize 归一：缺省 20、上限 50、非数字回落、显式值原样生效")
    void pageSize归一() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        for (int i = 1; i <= 51; i++) {
            insertVideoContent(authorId, "分页内容 " + i);
        }

        assertThat(idsOf(get("/api/v1/contents/recommended?pageSize=5", null))).hasSize(5);
        assertThat(idsOf(get("/api/v1/contents/recommended?pageSize=0", null)))
                .as("≤0 ⇒ 缺省 20").hasSize(20);
        assertThat(idsOf(get("/api/v1/contents/recommended?pageSize=abc", null)))
                .as("非数字 ⇒ 缺省 20").hasSize(20);
        assertThat(idsOf(get("/api/v1/contents/recommended?pageSize=999", null)))
                .as("超上限 ⇒ 截到 50").hasSize(50);
    }

    @Test
    @DisplayName("非法游标静默归一到第一页（不 400）")
    void 非法游标归一到第一页() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        Set<Long> inserted = new LinkedHashSet<>();
        for (int i = 1; i <= 5; i++) {
            inserted.add(insertVideoContent(authorId, "非法游标内容 " + i));
        }

        for (String bad : List.of("v1.bad", "abc", "v1.1", "v2.1.2", "v1.1.2.3")) {
            ResponseEntity<String> resp = get(
                    "/api/v1/contents/recommended?pageSize=10&cursor=" + bad, null);
            assertThat(resp.getStatusCode().value()).as("游标 %s 不应 400", bad).isEqualTo(200);
            assertThat(new LinkedHashSet<>(idsOf(resp))).as("游标 %s 应回第一页全集", bad).isEqualTo(inserted);
        }
    }

    // ========================================================================
    // 纯函数性 / 换一换
    // ========================================================================

    @Test
    @DisplayName("同一游标请求两次 ⇒ 结果逐字节一致（纯函数，重试安全）")
    void 同游标两次一致() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        for (int i = 1; i <= 8; i++) {
            insertVideoContent(authorId, "幂等内容 " + i);
        }

        String cursor = cursorOf(get("/api/v1/contents/recommended?pageSize=3", null));
        assertThat(cursor).isNotNull();

        String a = get("/api/v1/contents/recommended?pageSize=3&cursor=" + cursor, null).getBody();
        String b = get("/api/v1/contents/recommended?pageSize=3&cursor=" + cursor, null).getBody();

        assertThat(a).as("同游标 = 同一排列的同一段").isEqualTo(b);
    }

    @Test
    @DisplayName("不带游标 = 新会话（换一换）：同一池子的次序不同")
    void 不带游标换一换() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        for (int i = 1; i <= 12; i++) {
            insertVideoContent(authorId, "换一换内容 " + i);
        }

        List<Long> a = idsOf(get("/api/v1/contents/recommended?pageSize=12", null));
        List<Long> b = idsOf(get("/api/v1/contents/recommended?pageSize=12", null));

        assertThat(a).as("同一次会话内是同一批内容").containsExactlyInAnyOrderElementsOf(b);
        assertThat(a).as("新会话应换一个排列（12! 分之一才会相同）").isNotEqualTo(b);
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    /** 从第一页翻到底，返回按访问顺序收集的 id。 */
    private List<Long> walkAllPages(int pageSize) {
        List<Long> collected = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 100; guard++) {
            String path = "/api/v1/contents/recommended?pageSize=" + pageSize
                    + (cursor == null ? "" : "&cursor=" + cursor);
            ResponseEntity<String> resp = get(path, null);
            assertThat(resp.getStatusCode().value()).as("翻页请求应 200：%s", path).isEqualTo(200);
            collected.addAll(idsOf(resp));
            cursor = cursorOf(resp);
            if (cursor == null) {
                return collected;
            }
        }
        fail("翻页没有在 100 次内结束（疑似 nextCursor 永不置空）");
        return collected;
    }

    /** 独立 oracle：直读池子（索引 key）里的 id 集合。 */
    private Set<Long> idsInIndex() {
        List<String> raw = redis.opsForList().range(CONTENT_INDEX_PREFIX + "-1:-1", 0, -1);
        Set<Long> ids = new LinkedHashSet<>();
        if (raw != null) {
            for (String value : raw) {
                ids.add(Long.parseLong(value));
            }
        }
        return ids;
    }

    private static List<Long> idsOf(ResponseEntity<String> resp) {
        return idsOf(Envelope.data(resp));
    }

    /** 入参是 {@code data} 节点（不是 {@code data.list}）。 */
    private static List<Long> idsOf(JsonNode data) {
        JsonNode list = data.path("list");
        List<Long> ids = new ArrayList<>(list.size());
        list.forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    /** {@code nextCursor}：null 或缺省 ⇒ 到底。 */
    private static String cursorOf(ResponseEntity<String> resp) {
        JsonNode node = Envelope.data(resp).path("nextCursor");
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}
