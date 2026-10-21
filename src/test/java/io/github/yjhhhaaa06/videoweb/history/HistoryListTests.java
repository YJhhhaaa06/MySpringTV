package io.github.yjhhhaaa06.videoweb.history;

import io.github.yjhhhaaa06.videoweb.content.AbstractContentIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 历史列表（{@code GET /history/list}）的**行为契约**（播放记录专项 T49-1）。
 *
 * <h2>期望值怎么来（盖住实现照样写得出来）</h2>
 * <ul>
 *   <li><b>顺序</b>：出自 {@code history-需求.md} §七「最后上报时间倒序；同一秒按内容 id 倒序」——
 *       再用**独立 oracle**（{@code SELECT content_id … ORDER BY update_time DESC, content_id DESC}）
 *       复算一遍比对，不复用被测实现；</li>
 *   <li><b>{@code total}</b>：独立 oracle = {@code SELECT COUNT(*) FROM watch_history WHERE user_id=?}
 *       —— **包含**失效行（历史不可删、占位不缩水）；</li>
 *   <li><b>占位三字段</b>：出自 {@code history.md} §三「失效行：title=内容已失效、
 *       coverUrl/authorName/durationSeconds=null、progress 保留」；</li>
 *   <li><b>失效判据</b>：装载契约"取不到"（软删 / 视频行缺失），**不是** {@code durationSeconds == null}、
 *       也**不是** {@code file_exists = 0}（{@code history.md} §八）。</li>
 * </ul>
 *
 * <h2>★ 夹具的插入顺序就是判别力（AGENTS.md）</h2>
 * 排序用例里三行内容的 {@code update_time} 与 {@code content_id} **反向**：内容 id 最小的排最前
 * ⇒ 把实现改成 {@code ORDER BY content_id DESC} 会立刻红；同秒用例里 **行 id 序与内容 id 序反向**
 * ⇒ 把 tie-breaker 改成行 id 也会红。
 *
 * <h2>反向验证（注入 ⇒ 必红，实测见任务回写）</h2>
 * ① 去掉 {@code content_id DESC} ⇒ 同秒用例红；
 * ② 占位分支不清作者（配合越界 join 取作者）⇒ 占位用例红；
 * ③ 失效行被跳过 ⇒ {@code total} / 页内条数断言红。
 */
class HistoryListTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900000101";
    private static final String AUTHOR = "history-list-author";
    private static final String VIEWER_PHONE = "13900000102";
    private static final String VIEWER = "history-list-viewer";
    private static final String OTHER_PHONE = "13900000103";
    private static final String OTHER = "history-list-viewer-2";

    /** 失效占位标题（{@code history.md} §三 的字面量，独立写死以免抄实现）。 */
    private static final String INVALID_TITLE = "内容已失效";

    private String viewerToken;
    private long viewerId;
    private long authorId;

    @BeforeEach
    void setUpUsers() {
        // 父类先清 users / content / content_media / Redis（JUnit5 父类 @BeforeEach 先跑）⇒ 这里重新注册。
        // watch_history 没有外键，父类不清 ⇒ 本类自己清（否则上一用例的行会跨用例残留）。
        jdbcTemplate.update("DELETE FROM watch_history");
        viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);
        viewerId = userIdOf(VIEWER_PHONE);
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        authorId = userIdOf(AUTHOR_PHONE);
    }

    // ========================================================================
    // 1. 空态 / 排序 / 时间语义
    // ========================================================================

    @Test
    @DisplayName("空历史：list=[]、total=0（**不 404**）；缺省信封 page=1 / pageSize=20")
    void 空历史返回空页() {
        ResponseEntity<String> resp = list(viewerToken, null, null);

        assertThat(resp.getStatusCode().value()).as("空历史是合法状态（不 404）").isEqualTo(200);
        JsonNode data = Envelope.data(resp);
        assertThat(data.path("list").isArray()).as("空态是**空数组**，不是 null").isTrue();
        assertThat(data.path("list").size()).isZero();
        assertThat(data.path("total").asLong()).isZero();
        assertThat(data.path("page").asLong()).isEqualTo(1L);
        assertThat(data.path("pageSize").asLong())
                .as("历史列表缺省 20 条（history-需求.md §二「分页」）").isEqualTo(20L);
        assertThat(data.path("totalPages").asLong()).isZero();
        assertThat(oracleCount(viewerId)).isZero();
    }

    @Test
    @DisplayName("★排序：update_time 倒序（夹具里时间序与内容 id 序**反向** ⇒ 改成按 id 排必红）")
    void 按最后上报时间倒序() {
        // ★ 内容 id 最小的给**最新**的 update_time，最大的给最旧的 ⇒
        //   "按 update_time DESC" = [A,B,C]、"按 content_id DESC" = [C,B,A]，两者可区分。
        long a = insertVideoContent(authorId, "最先看的");
        long b = insertVideoContent(authorId, "中间看的");
        long c = insertVideoContent(authorId, "最早看的");
        insertHistory(viewerId, a, 1, 11, "2020-01-01 00:00:00");
        insertHistory(viewerId, b, 2, 22, "2020-01-01 00:00:00");
        insertHistory(viewerId, c, 3, 33, "2020-01-01 00:00:00");

        List<Long> actual = contentIds(Envelope.data(list(viewerToken, null, null)).path("list"));

        assertThat(actual)
                .as("★ 最后观看时间倒序：a（1 天前）→ b（2 天前）→ c（3 天前）")
                .containsExactly(a, b, c);
        assertThat(actual).as("独立 oracle：同一排序口径在 DB 侧复算").isEqualTo(oracleOrder(viewerId));
        assertThat(jdbcTemplate.queryForList(
                "SELECT content_id FROM watch_history WHERE user_id = ? ORDER BY content_id DESC",
                Long.class, viewerId))
                .as("★ 判别力自检：id 倒序恰好是反的 ⇒ '按 id 排' 的实现会输出 [c,b,a]")
                .containsExactly(c, b, a);
    }

    @Test
    @DisplayName("★同一秒按 content_id DESC（夹具里行 id 序与内容 id 序反向 ⇒ 按行 id 排必红）")
    void 同秒按内容id倒序() {
        long small = insertVideoContent(authorId, "id 小的");
        long big = insertVideoContent(authorId, "id 大的");
        // 先插 big 再插 small ⇒ 行 id：big < small；内容 id：big > small。两种排序可区分。
        insertHistoryAtFixed(viewerId, big, "2026-10-01 00:00:00", 5);
        insertHistoryAtFixed(viewerId, small, "2026-10-01 00:00:00", 6);

        List<Long> actual = contentIds(Envelope.data(list(viewerToken, null, null)).path("list"));

        assertThat(actual)
                .as("同秒 ⇒ 内容 id 大的在前（history-需求.md §七）；按**行 id** DESC 会得到 [small,big]")
                .containsExactly(big, small);
        assertThat(actual).isEqualTo(oracleOrder(viewerId));
    }

    @Test
    @DisplayName("★上报旧行后它移到最前；watchedTime 是 update_time（**不是** create_time）")
    void 上报后移到最前且时间取更新时间() {
        long newest = insertVideoContent(authorId, "刚才看的");
        long middle = insertVideoContent(authorId, "昨天看的");
        long oldest = insertVideoContent(authorId, "很久以前看的");
        insertHistory(viewerId, newest, 1, 10, "2020-01-01 00:00:00");
        insertHistory(viewerId, middle, 2, 20, "2020-01-01 00:00:00");
        insertHistory(viewerId, oldest, 30, 30, "2020-01-01 00:00:00");

        ResponseEntity<String> report = post(
                "/history/report?contentId=" + oldest + "&progress=30", null, viewerToken);
        assertThat(report.getStatusCode().value()).as("上报应 200：%s", report.getBody()).isEqualTo(200);

        JsonNode data = Envelope.data(list(viewerToken, null, null));
        assertThat(contentIds(data.path("list")))
                .as("★ 上报过的旧行排到最前（update_time 是排序键）")
                .containsExactly(oldest, newest, middle);

        JsonNode moved = item(data.path("list"), oldest);
        assertThat(LocalDateTime.parse(moved.path("watchedTime").asString()).withNano(0))
                .as("★ watchedTime = update_time（刚上报的时间），不是 create_time（仍是 2020）")
                .isEqualTo(dbUpdateTime(viewerId, oldest).toLocalDateTime().withNano(0))
                .isAfter(LocalDateTime.of(2025, 1, 1, 0, 0));
        assertThat(dbCreateTime(viewerId, oldest).toLocalDateTime().getYear())
                .as("对照：create_time 仍是 2020 ⇒ 上一条断言的判别力来自'取 update_time'")
                .isEqualTo(2020);
    }

    // ========================================================================
    // 2. total / 分页
    // ========================================================================

    @Test
    @DisplayName("★total 用独立 oracle 复算（COUNT(*) 含失效行）")
    void total含失效行() {
        long alive = insertVideoContent(authorId, "正常的");
        long deleted = insertVideoContent(authorId, "已删的");
        long broken = insertBrokenVideoContent(authorId, "媒体坏了的");
        insertHistory(viewerId, alive, 1, 1, "2020-01-01 00:00:00");
        insertHistory(viewerId, deleted, 2, 2, "2020-01-01 00:00:00");
        insertHistory(viewerId, broken, 3, 3, "2020-01-01 00:00:00");
        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", deleted);

        JsonNode data = Envelope.data(list(viewerToken, null, null));

        assertThat(data.path("total").asLong())
                .as("★ 独立 oracle：total = COUNT(*)（失效行照常计入，不缩水）")
                .isEqualTo(oracleCount(viewerId))
                .isEqualTo(3L);
        assertThat(data.path("list").size()).as("页内条数也不缩水").isEqualTo(3);
    }

    @Test
    @DisplayName("分页：第二页内容正确（独立 oracle 切片）；page 极大 ⇒ 200 空页（offset 不溢出）")
    void 分页第二页与极大页码() {
        List<Long> inserted = new ArrayList<>();
        for (int daysAgo = 1; daysAgo <= 5; daysAgo++) {
            long contentId = insertVideoContent(authorId, "第 " + daysAgo + " 条");
            insertHistory(viewerId, contentId, daysAgo, daysAgo, "2020-01-01 00:00:00");
            inserted.add(contentId);
        }

        JsonNode second = Envelope.data(list(viewerToken, "2", "2"));
        List<Long> oracle = oracleOrder(viewerId);
        assertThat(contentIds(second.path("list")))
                .as("第二页 = 独立 oracle 的第 3、4 条")
                .containsExactlyElementsOf(oracle.subList(2, 4));
        assertThat(second.path("page").asLong()).isEqualTo(2L);
        assertThat(second.path("pageSize").asLong()).as("小信封原样回显").isEqualTo(2L);
        assertThat(second.path("total").asLong()).isEqualTo(5L);
        assertThat(second.path("totalPages").asLong()).isEqualTo(3L);

        ResponseEntity<String> huge = list(viewerToken, "2147483647", "100");
        assertThat(huge.getStatusCode().value())
                .as("★ page 极大不得 500（(page-1)*pageSize 用 long）").isEqualTo(200);
        assertThat(Envelope.data(huge).path("list").size()).isZero();
    }

    @Test
    @DisplayName("分页归一：page 缺省/非数字/≤0 → 1；pageSize 非数字 → 20、超上限 → 100")
    void 分页参数归一() {
        insertHistory(viewerId, insertVideoContent(authorId, "归一内容"), 1, 1, "2020-01-01 00:00:00");

        assertThat(Envelope.data(list(viewerToken, "0", null)).path("page").asLong())
                .as("page ≤ 0 → 1").isEqualTo(1L);
        assertThat(Envelope.data(list(viewerToken, "-7", null)).path("page").asLong())
                .as("★ 负页码同样归一（不是只有 0）").isEqualTo(1L);
        assertThat(Envelope.data(list(viewerToken, "abc", null)).path("page").asLong())
                .as("page 非数字 → 1（与全项目分页端点同款静默归一）").isEqualTo(1L);
        assertThat(Envelope.data(list(viewerToken, null, "abc")).path("pageSize").asLong())
                .as("pageSize 非数字 → 缺省 20").isEqualTo(20L);
        assertThat(Envelope.data(list(viewerToken, null, "0")).path("pageSize").asLong())
                .as("★ pageSize ≤ 0 → 缺省 20（否则会退化成 0 条的假空页）").isEqualTo(20L);
        assertThat(Envelope.data(list(viewerToken, null, "999")).path("pageSize").asLong())
                .as("超上限 → 100").isEqualTo(100L);
        assertThat(Envelope.data(list(viewerToken, null, "2")).path("pageSize").asLong())
                .as("小信封原样回显").isEqualTo(2L);
    }

    // ========================================================================
    // 3. 展示字段 / 失效占位
    // ========================================================================

    @Test
    @DisplayName("正常行：title / coverUrl / authorName / durationSeconds / progress 来自内容契约")
    void 正常行抄内容字段() {
        long contentId = insertVideoContent(authorId, "正常标题");
        setVideoDuration(contentId, 600);
        insertHistory(viewerId, contentId, 1, 123, "2020-01-01 00:00:00");

        JsonNode row = item(Envelope.data(list(viewerToken, null, null)).path("list"), contentId);

        assertThat(row.path("invalid").asBoolean()).isFalse();
        assertThat(row.path("title").asString()).isEqualTo("正常标题");
        assertThat(row.path("coverUrl").asString()).isEqualTo("/upload/cover/" + contentId + ".png");
        assertThat(row.path("authorName").asString()).isEqualTo(AUTHOR);
        assertThat(row.path("durationSeconds").asInt()).isEqualTo(600);
        assertThat(row.path("progress").asInt()).isEqualTo(123);
    }

    @Test
    @DisplayName("★失效判据只有「装载契约取不到」：时长未知 / file_exists=0 的**存活**视频仍是正常行")
    void 时长未知或未落盘不算失效() {
        // 夹具默认**不写** duration_seconds（= 存量老视频的形态）⇒ 时长恒 null，但它完全可播；
        // file_exists=0 是扫描标记缺失，也不等于不能播（history.md §八 明文禁止拿这两者判 404）。
        // 判据若被写成 `content == null || durationSeconds == null`（或加了 file_exists 过滤）⇒ 这里红。
        long noDuration = insertVideoContent(authorId, "时长未知的标题");
        long notOnDisk = insertVideoContent(authorId, "扫描标记缺失的标题");
        jdbcTemplate.update("UPDATE content_media SET file_exists = 0 WHERE content_id = ?", notOnDisk);
        insertHistory(viewerId, noDuration, 1, 21, "2020-01-01 00:00:00");
        insertHistory(viewerId, notOnDisk, 2, 22, "2020-01-01 00:00:00");

        JsonNode data = Envelope.data(list(viewerToken, null, null));
        assertThat(data.path("total").asLong()).isEqualTo(2L);

        JsonNode a = item(data.path("list"), noDuration);
        assertThat(a.path("invalid").asBoolean())
                .as("★ 时长 null ≠ 失效：解析失败的老视频仍可播").isFalse();
        assertThat(a.path("title").asString()).isEqualTo("时长未知的标题");
        assertThat(a.path("durationSeconds").isNull()).as("时长确实未知 ⇒ null（与失效无关）").isTrue();

        JsonNode b = item(data.path("list"), notOnDisk);
        assertThat(b.path("invalid").asBoolean())
                .as("★ file_exists=0 ≠ 失效（扫描标记不是可播判据）").isFalse();
        assertThat(b.path("title").asString()).isEqualTo("扫描标记缺失的标题");
    }

    @Test
    @DisplayName("★失效占位：invalid=true、标题占位、三字段 null、**progress 保留**、页内条数不缩水")
    void 失效占位脱敏且保留进度() {
        long alive = insertVideoContent(authorId, "存活标题");
        long deleted = insertVideoContent(authorId, "被删原标题");
        setVideoDuration(alive, 600);
        setVideoDuration(deleted, 900);
        insertHistory(viewerId, alive, 1, 10, "2020-01-01 00:00:00");
        insertHistory(viewerId, deleted, 2, 88, "2020-01-01 00:00:00");
        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", deleted);

        JsonNode data = Envelope.data(list(viewerToken, null, null));
        assertThat(data.path("list").size()).as("★ 失效行不跳过：整页仍是 2 条").isEqualTo(2);
        assertThat(data.path("total").asLong()).as("total 不缩水").isEqualTo(2L);

        JsonNode invalid = item(data.path("list"), deleted);
        assertThat(invalid.path("invalid").asBoolean()).as("失效 ⇒ invalid=true").isTrue();
        assertThat(invalid.path("title").asString()).as("标题位写「内容已失效」").isEqualTo(INVALID_TITLE);
        assertThat(invalid.path("title").asString()).as("不得带原标题").isNotEqualTo("被删原标题");
        assertThat(invalid.path("coverUrl").isNull()).as("封面为 null").isTrue();
        assertThat(invalid.path("authorName").isNull())
                .as("★ 作者为 null —— 只换标题不清作者会让这条红").isTrue();
        assertThat(invalid.path("durationSeconds").isNull())
                .as("失效行时长取不到 ⇒ null（正常行有 600/900 作对照）").isTrue();
        assertThat(invalid.path("progress").asInt())
                .as("★ progress 保留：历史数据是真的（history.md §三）").isEqualTo(88);
        assertThat(invalid.path("contentId").asLong()).isEqualTo(deleted);

        JsonNode valid = item(data.path("list"), alive);
        assertThat(valid.path("invalid").asBoolean()).isFalse();
        assertThat(valid.path("authorName").asString()).as("正常行作者照常返回").isEqualTo(AUTHOR);
        assertThat(valid.path("durationSeconds").asInt()).isEqualTo(600);
    }

    @Test
    @DisplayName("★一整页失效也整页返回（pageSize=2 两条都失效 ⇒ 仍是 2 条占位，不补位、不跳过）")
    void 整页失效也整页返回() {
        long first = insertVideoContent(authorId, "失效一");
        long second = insertVideoContent(authorId, "失效二");
        insertHistory(viewerId, first, 1, 7, "2020-01-01 00:00:00");
        insertHistory(viewerId, second, 2, 9, "2020-01-01 00:00:00");
        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id IN (?, ?)", first, second);

        JsonNode data = Envelope.data(list(viewerToken, "1", "2"));

        assertThat(data.path("total").asLong()).as("total 仍含两条失效行").isEqualTo(2L);
        assertThat(data.path("pageSize").asLong()).isEqualTo(2L);
        assertThat(data.path("list").size())
                .as("★ 整页失效也照常返回满页（跳过 ⇒ 用户永远看不到，也就没法处理）").isEqualTo(2);
        assertThat(contentIds(data.path("list"))).containsExactly(first, second);
        for (JsonNode row : data.path("list")) {
            assertThat(row.path("invalid").asBoolean()).isTrue();
            assertThat(row.path("title").asString()).isEqualTo(INVALID_TITLE);
            assertThat(row.path("coverUrl").isNull()).isTrue();
            assertThat(row.path("authorName").isNull()).isTrue();
            assertThat(row.path("durationSeconds").isNull()).isTrue();
        }
        assertThat(item(data.path("list"), first).path("progress").asInt()).isEqualTo(7);
        assertThat(item(data.path("list"), second).path("progress").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("★装载契约取不到也占位：type=1 但缺视频行（失效判据不是 is_deleted）")
    void 装载不出来的内容也占位() {
        long broken = insertBrokenVideoContent(authorId, "视频行缺失的标题");
        insertHistory(viewerId, broken, 1, 45, "2020-01-01 00:00:00");

        JsonNode data = Envelope.data(list(viewerToken, null, null));
        JsonNode row = item(data.path("list"), broken);

        assertThat(row.path("invalid").asBoolean())
                .as("★ 判据 = loadContentVOs 取不到（不是 is_deleted=1：本行 is_deleted 仍是 0）")
                .isTrue();
        assertThat(row.path("title").asString()).isEqualTo(INVALID_TITLE);
        assertThat(row.path("coverUrl").isNull()).isTrue();
        assertThat(row.path("authorName").isNull()).isTrue();
        assertThat(row.path("durationSeconds").isNull()).isTrue();
        assertThat(row.path("progress").asInt()).isEqualTo(45);
        assertThat(data.path("total").asLong()).isEqualTo(1L);
    }

    // ========================================================================
    // 4. 隔离 / 形状 / 鉴权
    // ========================================================================

    @Test
    @DisplayName("★跨用户隔离：看不到别人的历史（列表与 total 都按 user_id 过滤）")
    void 跨用户隔离() {
        String otherToken = registerAndGetToken(OTHER_PHONE, OTHER);
        long otherId = userIdOf(OTHER_PHONE);
        long mine = insertVideoContent(authorId, "我看的");
        long theirs = insertVideoContent(authorId, "他看的");
        insertHistory(viewerId, mine, 1, 1, "2020-01-01 00:00:00");
        insertHistory(otherId, theirs, 1, 1, "2020-01-01 00:00:00");

        JsonNode myData = Envelope.data(list(viewerToken, null, null));
        assertThat(contentIds(myData.path("list"))).containsExactly(mine);
        assertThat(myData.path("total").asLong()).isEqualTo(1L);

        JsonNode theirData = Envelope.data(list(otherToken, null, null));
        assertThat(contentIds(theirData.path("list")))
                .as("★ 乙的列表里没有甲的行（WHERE user_id 过滤）").containsExactly(theirs);
        assertThat(theirData.path("total").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("★形状契约：信封 5 键；条目恰好 8 键（键名与设计 §三 的 JSON 逐字一致）")
    void 形状契约() {
        long contentId = insertVideoContent(authorId, "形状内容");
        insertHistory(viewerId, contentId, 1, 1, "2020-01-01 00:00:00");

        JsonNode data = Envelope.data(list(viewerToken, null, null));
        assertThat(data.propertyNames())
                .as("分页信封字段集冻结（PageResult 是跨域共享信封）")
                .containsExactlyInAnyOrder("list", "total", "page", "pageSize", "totalPages");
        assertThat(data.path("list").get(0).propertyNames())
                .as("★ 条目键集：逐字对齐 history.md §三 的 JSON 形状")
                .containsExactlyInAnyOrder("contentId", "watchedTime", "invalid", "title",
                        "coverUrl", "authorName", "progress", "durationSeconds");
        // ⚠️ 如实声明：`Boolean invalid`（→ getInvalid）与 `boolean invalid`（→ isInvalid）在 Jackson
        //    下**都**序列化成 "invalid" ⇒ 这条键集断言**测不到**包装类型与否。保留包装是沿用
        //    WatchLaterItemVO 的形态（可表达 null），不是靠这条断言守的。
    }

    @Test
    @DisplayName("鉴权：未登录 / 坏 token ⇒ 401（⚠️ HTTP 侧对 @RequiresLogin 是**假绿**）")
    void 需登录() {
        insertHistory(viewerId, insertVideoContent(authorId, "鉴权内容"), 1, 1, "2020-01-01 00:00:00");

        // ⚠️ 假绿声明：401 由 @CurrentUserId 参数解析器兜出 ⇒ 去掉类级 @RequiresLogin 这两条**仍然绿**。
        //    注解是否在位只由 SecurityContractTests 求值（钉路由，不证明注解）。
        assertThat(list(null, null, null).getStatusCode().value()).as("未登录 ⇒ 401").isEqualTo(401);
        assertThat(list("not-a-jwt", null, null).getStatusCode().value()).as("坏 token ⇒ 401").isEqualTo(401);
        assertThat(list(viewerToken, null, null).getStatusCode().value()).isEqualTo(200);
    }

    // ========================================================================
    // HTTP / 夹具 / 独立 oracle
    // ========================================================================

    private ResponseEntity<String> list(String token, String page, String pageSize) {
        StringBuilder url = new StringBuilder("/history/list");
        boolean first = true;
        if (page != null) {
            url.append("?page=").append(page);
            first = false;
        }
        if (pageSize != null) {
            url.append(first ? "?" : "&").append("pageSize=").append(pageSize);
        }
        return get(url.toString(), token);
    }

    private static List<Long> contentIds(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : list) {
            ids.add(item.path("contentId").asLong());
        }
        return ids;
    }

    private static JsonNode item(JsonNode list, long contentId) {
        for (JsonNode node : list) {
            if (node.path("contentId").asLong() == contentId) {
                return node;
            }
        }
        throw new AssertionError("列表里应当有 contentId=" + contentId + " 的条目，实际只有：" + contentIds(list));
    }

    /** ★ 独立 oracle：该用户历史行数（**含**失效行）。 */
    private long oracleCount(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_history WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }

    /** ★ 独立 oracle：按设计口径（{@code update_time DESC, content_id DESC}）在 DB 侧复算顺序。 */
    private List<Long> oracleOrder(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT content_id FROM watch_history WHERE user_id = ? "
                        + "ORDER BY update_time DESC, content_id DESC", Long.class, userId);
    }

    /** 直插一条历史：{@code daysAgo} 天前上报（时间用 **DB 时钟**造，避免测试机时钟漂移）。 */
    private void insertHistory(long userId, long contentId, int daysAgo, int progress, String createTime) {
        jdbcTemplate.update("INSERT INTO watch_history "
                        + "(user_id, content_id, progress_seconds, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?, NOW() - INTERVAL ? DAY)",
                userId, contentId, progress, createTime, daysAgo);
    }

    /** 直插固定 {@code update_time}（同秒并列用例用）。 */
    private void insertHistoryAtFixed(long userId, long contentId, String updateTime, int progress) {
        jdbcTemplate.update("INSERT INTO watch_history "
                        + "(user_id, content_id, progress_seconds, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?, ?)",
                userId, contentId, progress, "2020-01-01 00:00:00", updateTime);
    }

    private Timestamp dbUpdateTime(long userId, long contentId) {
        return jdbcTemplate.queryForObject(
                "SELECT update_time FROM watch_history WHERE user_id = ? AND content_id = ?",
                Timestamp.class, userId, contentId);
    }

    private Timestamp dbCreateTime(long userId, long contentId) {
        return jdbcTemplate.queryForObject(
                "SELECT create_time FROM watch_history WHERE user_id = ? AND content_id = ?",
                Timestamp.class, userId, contentId);
    }

    private void setVideoDuration(long contentId, int seconds) {
        jdbcTemplate.update("UPDATE content_media SET duration_seconds = ? WHERE content_id = ? AND type = 1",
                seconds, contentId);
    }
}
