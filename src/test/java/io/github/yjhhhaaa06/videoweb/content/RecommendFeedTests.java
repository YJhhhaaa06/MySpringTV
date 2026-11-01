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
 * <h2>三条判别力，以及它们各自钉哪一行</h2>
 * <ul>
 *   <li><b>去掉 {@code rank < lastRank} 过滤</b> ⇒ {@link #翻完全部页等于池子全集()} /
 *       {@link #相邻两页无交集()} / {@link #脏id被跳过且不占名额()} /
 *       {@link #游标指向已删内容仍精确()} 四处变红；</li>
 *   <li><b>把 {@code pageSize + 1} 退化成 {@code pageSize}</b> ⇒ {@link #整除边界不多给空页()}
 *       变红（恰好整除时永远不会给出 {@code nextCursor}）；</li>
 *   <li><b>游标改用"位次"而不是 id</b> ⇒ {@link #游标指向已删内容仍精确()} 变红
 *       （末条从索引里也消失后，位次会整体位移、漏掉一条）。</li>
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

        List<Long> collected = walkAllPages(3, null);

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

    @Test
    @DisplayName("★整除边界：池子 6 条 / 每页 3 ⇒ 第 2 页恰好取满且 nextCursor=null（不多给一个空页）")
    void 整除边界不多给空页() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        Set<Long> inserted = new LinkedHashSet<>();
        for (int i = 1; i <= 6; i++) {
            inserted.add(insertVideoContent(authorId, "整除内容 " + i));
        }

        ResponseEntity<String> first = get("/api/v1/contents/recommended?pageSize=3", null);
        ResponseEntity<String> second = get("/api/v1/contents/recommended?pageSize=3&cursor=" + cursorOf(first), null);

        assertThat(idsOf(first)).hasSize(3);
        assertThat(cursorOf(first)).as("还有 3 条 ⇒ 必须有下一页").isNotNull();
        assertThat(idsOf(second)).as("第 2 页应恰好取满").hasSize(3);
        assertThat(cursorOf(second)).as("已取完 ⇒ 必须报到底，而不是再给一个空游标").isNull();
        Set<Long> all = new LinkedHashSet<>(idsOf(first));
        all.addAll(idsOf(second));
        assertThat(all).isEqualTo(inserted);
    }

    // ========================================================================
    // 删除 / 脏 id
    // ========================================================================

    @Test
    @DisplayName("★游标指向已删内容仍精确：走真实删除（索引也 LREM），剩余内容不重不漏")
    void 游标指向已删内容仍精确() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        Set<Long> inserted = new LinkedHashSet<>();
        for (int i = 1; i <= 6; i++) {
            inserted.add(insertVideoContent(authorId, "游标删除内容 " + i));
        }

        ResponseEntity<String> first = get("/api/v1/contents/recommended?pageSize=2", null);
        List<Long> page1 = idsOf(first);
        long lastOfPage1 = page1.get(page1.size() - 1);
        String cursor = cursorOf(first);

        // ★ 走真实删除：内容软删 + 索引 LREM（位次会整体位移；游标锚在 id 上则不受影响）
        assertThat(delete("/api/v1/contents/" + lastOfPage1, token).getStatusCode().value()).isEqualTo(204);

        List<Long> rest = walkAllPages(2, cursor);
        inserted.removeAll(page1);   // 第一页已经消费过的（含被删的末条）不在游标之后

        assertThat(rest).as("剩余内容不得重复").doesNotHaveDuplicates();
        assertThat(new LinkedHashSet<>(rest))
                .as("游标指向的那条已消失，仍应精确接上剩余内容")
                .isEqualTo(inserted);
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
        // ★ 先发一次请求：让索引真的把这 5 条写进去（否则删行后的懒重建会读不到它，"脏 id" 就不存在）
        assertThat(idsOf(get("/api/v1/contents/recommended?pageSize=5", null))).hasSize(5);

        long dirty = ids.get(2);
        jdbcTemplate.update("DELETE FROM content WHERE id = ?", dirty);
        // 模拟"删除时 LREM 失败"：索引保留脏 id；同时清掉内容缓存，让批量读真的拿不到它
        redis.delete(List.of(CONTENT_KEY_PREFIX + dirty, EMPTY_PREFIX + dirty));

        ResponseEntity<String> first = get("/api/v1/contents/recommended?pageSize=2", null);
        assertThat(idsOf(first)).as("脏 id 不占名额 ⇒ 本页仍应凑满 2 条").hasSize(2);

        List<Long> collected = new ArrayList<>(idsOf(first));
        collected.addAll(walkAllPages(2, cursorOf(first)));

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
    @DisplayName("pageSize 归一：缺省 20、上限 50、非数字回落、显式值生效；limit 不识别")
    void pageSize归一() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        for (int i = 1; i <= 51; i++) {
            insertVideoContent(authorId, "分页内容 " + i);
        }

        assertThat(idsOf(get("/api/v1/contents/recommended?pageSize=5", null))).hasSize(5);
        assertThat(idsOf(get("/api/v1/contents/recommended", null)))
                .as("不传 pageSize ⇒ 缺省 20").hasSize(20);
        assertThat(idsOf(get("/api/v1/contents/recommended?pageSize=0", null)))
                .as("≤0 ⇒ 缺省 20").hasSize(20);
        assertThat(idsOf(get("/api/v1/contents/recommended?pageSize=abc", null)))
                .as("非数字 ⇒ 缺省 20").hasSize(20);
        assertThat(idsOf(get("/api/v1/contents/recommended?pageSize=999", null)))
                .as("超上限 ⇒ 截到 50").hasSize(50);
        assertThat(idsOf(get("/api/v1/contents/recommended?limit=5", null)))
                .as("★ limit 不识别 ⇒ 仍按缺省 20（若被当成条数，这里会是 5）").hasSize(20);
        assertThat(idsOf(get("/api/v1/contents/recommended?limit=5&pageSize=6", null)))
                .as("pageSize 优先级更高").hasSize(6);
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

    /** 从第一页翻到底。 */
    private List<Long> walkAllPages(int pageSize, String startCursor) {
        List<Long> collected = new ArrayList<>();
        String cursor = startCursor;
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
