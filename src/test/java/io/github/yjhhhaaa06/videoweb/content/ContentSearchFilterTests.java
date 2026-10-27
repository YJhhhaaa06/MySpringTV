package io.github.yjhhhaaa06.videoweb.content;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GAP-10：{@code GET /api/v1/contents} 的**类型筛选**与**排序**（2026-10-27）。
 *
 * <h2>这些用例钉什么</h2>
 * 加参数前该端点只有 `keyword/page/pageSize`，顺序写死 {@code create_time DESC, id DESC}；
 * 本次加的两个参数都只影响 SQL，故**判别力全在"结果集合与次序"上**：
 * <ol>
 *   <li><b>筛选</b>：{@code type=VIDEO|POST} 只回该类型，且 <b>total 与列表同源</b>
 *       （单独把 count 漏掉时，列表对、总数不对 ⇒ 页码多出来）；</li>
 *   <li><b>排序</b>：{@code order=PLAY} 按 {@code play_count DESC, id DESC}，
 *       且**逐页**也对（若实现退化成"内存排序"，跨页就会重/漏）；</li>
 *   <li><b>缺省 = 加参数前</b>：不传 / 传空串 / 显式 {@code LATEST} 三者等价；</li>
 *   <li><b>非法值响亮地 400</b>：{@code errors[].field} 能定位到 {@code type} / {@code order}
 *       （静默回落会让用户以为"没有这类内容"）。</li>
 * </ol>
 *
 * <h2>★ 两条"防假绿"的夹具纪律</h2>
 * <ul>
 *   <li><b>排序键序必须与 id 序相反</b>：夹具按 id 升序插入，若把播放量也设成升序，
 *       "删掉 ORDER BY 也不会红"（id DESC 恰好与 play DESC 同序）。本类里
 *       A(id 最小)=100、B=50、C(id 最大)=50 ⇒ 播放序 {@code [A, C, B]} 与默认序
 *       {@code [C, B, A]} **不同**，注入"删掉 play 分支"必红。</li>
 *   <li><b>单字符 / 多字符两条 SQL 分支都测</b>：关键词长度 1 走
 *       {@code title LIKE '%x%'}、长度 ≥2 走 {@code MATCH … AGAINST}；两条分支各有自己的
 *       WHERE / ORDER BY 位置，只测一条就等于只覆盖半个 mapper。</li>
 * </ul>
 *
 * <h2>⚠️ 已知退化（不是缺陷，见 {@code SearchController#keywordSearch} 的说明）</h2>
 * 图文的 {@code content.play_count} 结构性恒 0（{@code /play} 只收视频）⇒
 * {@code type=POST&order=PLAY} 实际等价于按 id 倒序。后端故意**不设特例**，由前端不渲染该按钮。
 */
class ContentSearchFilterTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900001070";
    private static final String AUTHOR = "gap10作者";

    /** 多字符关键词（→ {@code MATCH … AGAINST} 分支）。 */
    private static final String KW_MULTI = "qzg";

    /** 单字符关键词（→ {@code title LIKE '%q%'} 分支；夹具标题都含 {@code qzg}，故同样命中）。 */
    private static final String KW_SINGLE = "q";

    /** 本类共用的作者 id：在 {@code resetState()} 清库之后注册一次（每用例只注册一次）。 */
    private long authorId;

    // ========================================================================
    // 类型筛选
    // ========================================================================

    @Test
    @DisplayName("GAP-10 筛选：type=VIDEO/POST 只回该类型，且 total 与列表**同源**")
    void 类型筛选_列表与总数同源() {
        long videoId = insertVideoContent(authorId, KW_MULTI + " 视频甲");
        long postId = insertPostContent(authorId, KW_MULTI + " 图文乙");

        // 不筛：两类都在（总数 2 是夹具事实，不是从 list.size 反推）
        assertThat(total("/api/v1/contents?keyword=" + KW_MULTI)).isEqualTo(2);
        assertThat(ids("/api/v1/contents?keyword=" + KW_MULTI))
                .as("缺省顺序 = create_time DESC, id DESC ⇒ 后插入的图文在前")
                .containsExactly(postId, videoId);

        // 只留视频：total 必须是 1 —— 若 count 的 SQL 漏了 type 过滤，这里会红（列表却是对的）
        assertThat(total("/api/v1/contents?keyword=" + KW_MULTI + "&type=VIDEO")).isEqualTo(1);
        assertThat(ids("/api/v1/contents?keyword=" + KW_MULTI + "&type=VIDEO"))
                .containsExactly(videoId);

        // 只留图文
        assertThat(total("/api/v1/contents?keyword=" + KW_MULTI + "&type=POST")).isEqualTo(1);
        assertThat(ids("/api/v1/contents?keyword=" + KW_MULTI + "&type=POST"))
                .containsExactly(postId);
    }

    @Test
    @DisplayName("GAP-10 筛选：单字符 LIKE 分支同样带类型过滤（两条 SQL 分支都要覆盖）")
    void 类型筛选_单字符分支() {
        long videoId = insertVideoContent(authorId, KW_MULTI + " 视频甲");
        long postId = insertPostContent(authorId, KW_MULTI + " 图文乙");

        assertThat(total("/api/v1/contents?keyword=" + KW_SINGLE)).isEqualTo(2);
        assertThat(total("/api/v1/contents?keyword=" + KW_SINGLE + "&type=VIDEO")).isEqualTo(1);
        assertThat(ids("/api/v1/contents?keyword=" + KW_SINGLE + "&type=VIDEO"))
                .containsExactly(videoId);
        assertThat(ids("/api/v1/contents?keyword=" + KW_SINGLE + "&type=POST"))
                .containsExactly(postId);
    }

    // ========================================================================
    // 排序
    // ========================================================================

    @Test
    @DisplayName("GAP-10 排序：order=PLAY 按播放量倒序（与 id 序相反）+ 逐页不重不漏")
    void 排序_播放量倒序且逐页一致() {
        long idA = insertVideoContent(authorId, KW_MULTI + " 视频A");
        long idB = insertVideoContent(authorId, KW_MULTI + " 视频B");
        long idC = insertVideoContent(authorId, KW_MULTI + " 视频C");
        // ★ 播放量序 [A, C, B] 与 id 序 [C, B, A] **不同** —— 否则"排序没生效"看不出来。
        //   B/C 同分 ⇒ 也顺带钉住 tie-breaker：必须 C 在 B 前（id 降序）。
        setPlayCount(idA, 100);
        setPlayCount(idB, 50);
        setPlayCount(idC, 50);

        assertThat(ids("/api/v1/contents?keyword=" + KW_MULTI))
                .as("默认序（create_time DESC, id DESC）")
                .containsExactly(idC, idB, idA);
        assertThat(ids("/api/v1/contents?keyword=" + KW_MULTI + "&order=PLAY"))
                .as("播放量降序；同分按 id 降序")
                .containsExactly(idA, idC, idB);

        // 排序发生在 SQL ⇒ pageSize=1 逐页也必须与整页同序（内存排序会在这里露馅）
        List<Long> paged = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            paged.addAll(ids("/api/v1/contents?keyword=" + KW_MULTI
                    + "&order=PLAY&pageSize=1&page=" + page));
        }
        assertThat(paged).as("页间不重不漏且顺序一致").containsExactly(idA, idC, idB);

        // 筛选 + 排序**同时**生效（两个 include 相邻，顺序写错会直接是 SQL 语法错）
        assertThat(ids("/api/v1/contents?keyword=" + KW_MULTI + "&type=VIDEO&order=PLAY&pageSize=1"))
                .containsExactly(idA);

        // 排序依据与展示值同源：PLAY 排序用的就是 playCount 那一列
        assertThat(Envelope.data(get("/api/v1/contents?keyword=" + KW_MULTI + "&order=PLAY", null))
                .path("list").get(0).path("playCount").asLong())
                .as("排序键与列表回显的 playCount 必须同源")
                .isEqualTo(100L);
    }

    @Test
    @DisplayName("GAP-10 排序：单字符 LIKE 分支同样支持 order=PLAY")
    void 排序_单字符分支() {
        long idA = insertVideoContent(authorId, KW_MULTI + " 视频A");
        long idB = insertVideoContent(authorId, KW_MULTI + " 视频B");
        // ★ 播放序 [A, B] 必须与 id 序 [B, A] **相反** —— 否则"排序没生效"这条用例照样绿
        //   （2026-10-27 反向验证实测：初版设成 A=10 / B=99，注入"PLAY 改回 create_time"仍是绿的）。
        setPlayCount(idA, 99);
        setPlayCount(idB, 10);

        assertThat(ids("/api/v1/contents?keyword=" + KW_SINGLE + "&order=PLAY"))
                .containsExactly(idA, idB);
    }

    // ========================================================================
    // 缺省等价 / 边界
    // ========================================================================

    @Test
    @DisplayName("GAP-10 缺省：不传 order == 显式 order=LATEST（逐字节一致）")
    void 缺省排序等价显式LATEST() {
        insertVideoContent(authorId, KW_MULTI + " 视频甲");
        insertPostContent(authorId, KW_MULTI + " 图文乙");

        JsonNode omitted = Envelope.data(get("/api/v1/contents?keyword=" + KW_MULTI, null));
        JsonNode explicit = Envelope.data(get("/api/v1/contents?keyword=" + KW_MULTI + "&order=LATEST", null));
        assertThat(omitted.path("list").size()).as("本用例需非空列表才有判别力").isEqualTo(2);
        assertThat(explicit.toString()).isEqualTo(omitted.toString());
    }

    @Test
    @DisplayName("GAP-10 边界：type= / order= 的**空串**视为不传（不是 400、更不是\"筛空\"）")
    void 空串参数视为不传() {
        insertVideoContent(authorId, KW_MULTI + " 视频甲");
        insertPostContent(authorId, KW_MULTI + " 图文乙");

        ResponseEntity<String> resp = getQuery("/api/v1/contents",
                query("keyword", KW_MULTI, "type", "", "order", ""), null);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        assertThat(Envelope.data(resp).path("total").asInt()).isEqualTo(2);
    }

    // ========================================================================
    // 非法值 → 400（字段级定位，不是静默回落）
    // ========================================================================

    @Test
    @DisplayName("GAP-10 非法值：type=video（小写）/ order=hot ⇒ 400 + errors[].field 可定位")
    void 非法参数_400并定位字段() {
        insertVideoContent(authorId, KW_MULTI + " 视频甲");

        ResponseEntity<String> badType = get("/api/v1/contents?keyword=" + KW_MULTI + "&type=video", null);
        assertThat(badType.getStatusCode().value()).as("%s", badType.getBody()).isEqualTo(400);
        assertThat(Envelope.code(badType)).isEqualTo("PARAM_INVALID");
        assertThat(Envelope.errors(badType).isArray()).isTrue();
        assertThat(Envelope.errors(badType).get(0).path("field").asString()).isEqualTo("type");

        ResponseEntity<String> badOrder = get("/api/v1/contents?keyword=" + KW_MULTI + "&order=hot", null);
        assertThat(badOrder.getStatusCode().value()).as("%s", badOrder.getBody()).isEqualTo(400);
        assertThat(Envelope.code(badOrder)).isEqualTo("PARAM_INVALID");
        assertThat(Envelope.errors(badOrder).get(0).path("field").asString()).isEqualTo("order");
    }

    // ========================================================================
    // 夹具 / 取值辅助
    // ========================================================================

    @BeforeEach
    void registerAuthor() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        authorId = userIdOf(AUTHOR_PHONE);
    }

    /** 直接改列（不调 {@code /play}）：本类测的是**读**路径的排序，不需要造观看记录。 */
    private void setPlayCount(long contentId, int value) {
        jdbcTemplate.update("UPDATE content SET play_count = ? WHERE id = ?", value, contentId);
    }

    private List<Long> ids(String path) {
        ResponseEntity<String> resp = get(path, null);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        List<Long> ids = new ArrayList<>();
        Envelope.data(resp).path("list").forEach(node -> ids.add(node.path("id").asLong()));
        return ids;
    }

    private int total(String path) {
        ResponseEntity<String> resp = get(path, null);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        return Envelope.data(resp).path("total").asInt();
    }
}
