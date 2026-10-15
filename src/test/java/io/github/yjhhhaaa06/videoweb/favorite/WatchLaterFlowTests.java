package io.github.yjhhhaaa06.videoweb.favorite;

import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收藏三期 <b>T17：稍后再看</b> 的契约测试（{@code 能力 收藏三期-稍后再看}）。
 *
 * <h2>覆盖什么（期望值全部出自业务口径，盖住实现照样写得出来）</h2>
 * <ul>
 *   <li><b>空池</b>：{@code list=[]} + {@code total=0}（不 404）；</li>
 *   <li>★ <b>add / list / remove 往返一致</b>：独立 oracle 直查 {@code watch_later} 复算；</li>
 *   <li>★ <b>重复 add ⇒ 移到最前</b>（favorite-需求.md §六，B 站实测）：**不新增行、不改 id、只刷 create_time**
 *       —— oracle 断"行数不变 + id 不变 + 时间刷新 + 顺序到最前"；</li>
 *   <li>★ <b>只能加视频</b>（favorite-需求.md §六）：非视频 ⇒ 400、不存在/已软删 ⇒ 404；</li>
 *   <li>★ <b>上限 100</b>：加**新**内容且已满 ⇒ 400；已满时"已在池中"重复 add ⇒ **仍成功**；</li>
 *   <li>★ <b>remove 不校验内容存在性</b>（G11）：失效条目能移出；幂等（0 行不算错）；空集 ⇒ 400；</li>
 *   <li>★ <b>失效占位脱敏</b>（R-03 / R-11）：{@code invalid=true}、标题「内容已失效」、封面/作者 null、
 *       保留 {@code contentId}；</li>
 *   <li>分页不重不漏；{@code page} 极大不得 500；形状契约（信封 5 键 / 条目 6 键）；未登录/坏 token 401。</li>
 * </ul>
 *
 * <h2>★ 期望值怎么来的（规避"迎合型伪测试"）</h2>
 * 三条实测语义出自favorite-需求.md §六（用户 2026-10-14 现场实测）；"只能加视频"来自 favorite-需求.md §五/§六；
 * 占位脱敏出自 R-03 / R-11；"失效能移出、不校验存在性"出自 G11；上限 100 出自favorite.md §九 / 决策。
 * 盖住实现，这些断言照样写得出来。
 *
 * <h2>独立性</h2>
 * 计数/行一律用**独立 oracle** 直查 {@code watch_later}（{@code COUNT(*)} / {@code id} / {@code create_time}），
 * 不信接口回显；时间窗用 **DB 时钟**造（{@code NOW() - INTERVAL n DAY}）。
 */
class WatchLaterFlowTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    /** 失效占位标题（R-03；与 {@code WatchLaterService.INVALID_CONTENT_TITLE} 同源，此处独立写死以免抄实现）。 */
    private static final String INVALID_TITLE = "内容已失效";

    /** favorite.md §九 / 决策定的稍后再看上限（与 {@code video.favorite.watch-later-max-items} 一致）。 */
    private static final int LIMIT = 100;

    @BeforeEach
    void 清空本域相关表() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        jdbcTemplate.update("DELETE FROM watch_later");
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content_media");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ========================================================================
    // 1. 空态
    // ========================================================================

    @Test
    @DisplayName("空池：list=[]、total=0（**不 404**）；信封是分页形状")
    void 空池返回空页而非404() {
        TestUser me = register("13800005001", "空池用户");

        ResponseEntity<String> resp = list(me.token, null, null);
        assertThat(resp.getStatusCode().value()).as("空态是合法状态（不 404）").isEqualTo(200);
        JsonNode data = Envelope.data(resp);
        assertThat(data.path("list").isArray()).as("空态是**空数组**，不是 null / 不是缺键").isTrue();
        assertThat(data.path("list").size()).isZero();
        assertThat(data.path("total").asLong()).isZero();
        assertThat(data.path("page").asLong()).isEqualTo(1L);
        assertThat(data.path("pageSize").asLong()).as("缺省信封 = 100").isEqualTo(100L);
        assertThat(data.path("totalPages").asLong()).as("total=0 ⇒ 0 页").isZero();
        assertThat(oracleCount(me.id)).isZero();
    }

    // ========================================================================
    // 2. add / list / remove 往返一致
    // ========================================================================

    @Test
    @DisplayName("★add → list → remove 往返一致（独立 oracle 直查 watch_later 复算）")
    void 往返一致() {
        TestUser me = register("13800005002", "往返用户");
        long video = insertVideoContent(me.id, "往返视频");

        assertThat(add(me.token, video).getStatusCode().value()).isEqualTo(200);
        assertThat(oracleCount(me.id)).as("独立 oracle：池里 1 行").isEqualTo(1L);

        JsonNode data = Envelope.data(list(me.token, null, null));
        assertThat(data.path("total").asLong()).isEqualTo(1L);
        assertThat(contentIds(data.path("list"))).containsExactly(video);
        assertThat(item(data.path("list"), video).path("invalid").asBoolean()).isFalse();
        assertThat(item(data.path("list"), video).path("title").asString()).isEqualTo("往返视频");

        assertThat(remove(me.token, video).getStatusCode().value()).isEqualTo(200);
        assertThat(oracleCount(me.id)).as("移出后池空").isZero();
        assertThat(Envelope.data(list(me.token, null, null)).path("list").size()).isZero();

        // 幂等：再移出一次（本就不在池中）也照常 200（陈旧页面 / 双击）
        assertThat(remove(me.token, video).getStatusCode().value())
                .as("remove 幂等：0 行不算错").isEqualTo(200);
    }

    // ========================================================================
    // 3. ★ 重复 add ⇒ 移到最前（不新增行、不改 id、只刷时间）
    // ========================================================================

    @Test
    @DisplayName("★重复 add ⇒ 移到最前：行数不变、id 不变、create_time 刷新、顺序到最前")
    void 重复加入移到最前() {
        TestUser me = register("13800005003", "移到最前用户");
        long older = insertVideoContent(me.id, "早加的");
        long newer = insertVideoContent(me.id, "晚加的");
        // 直插两条（早/晚），让"早加的"在池中排最后
        insertWatchLaterAt(me.id, older, 5);
        insertWatchLaterAt(me.id, newer, 1);
        long olderRowId = rowId(me.id, older);
        long newerRowId = rowId(me.id, newer);

        // ★ 走真实写路径：再次 add "早加的"
        assertThat(add(me.token, older).getStatusCode().value()).isEqualTo(200);

        assertThat(oracleCount(me.id)).as("★ 不新增行：仍是 2 条（不是 3 条）").isEqualTo(2L);
        assertThat(rowId(me.id, older)).as("★ 不改 id：'早加的'仍是原来那一行").isEqualTo(olderRowId);
        assertThat(rowId(me.id, newer)).as("另一条不受影响").isEqualTo(newerRowId);

        // 顺序到最前：older 的 create_time 已被刷成"现在" ⇒ 排在 newer 之前
        assertThat(contentIds(Envelope.data(list(me.token, null, null)).path("list")))
                .as("★ 再次 add 的'早加的'排最前（favorite-需求.md §六，B 站实测）")
                .containsExactly(older, newer);
        assertThat(createTime(me.id, older).getTime())
                .as("★ create_time 被刷新到 now，晚于另一条")
                .isGreaterThan(createTime(me.id, newer).getTime());
    }

    @Test
    @DisplayName("同秒并列的 tie-breaker：create_time 并列时按 id DESC（否则页间顺序不确定）")
    void 同秒按id倒序() {
        TestUser me = register("13800005004", "同秒用户");
        long first = insertVideoContent(me.id, "先加的");
        long second = insertVideoContent(me.id, "后加的");
        insertWatchLaterAtFixed(me.id, first, "2026-10-01 00:00:00");
        insertWatchLaterAtFixed(me.id, second, "2026-10-01 00:00:00");

        assertThat(contentIds(Envelope.data(list(me.token, null, null)).path("list")))
                .as("同秒 ⇒ 后插入（id 更大）的在前，与 'id DESC' tie-breaker 一致")
                .containsExactly(second, first);
    }

    // ========================================================================
    // 4. ★ 只能加视频
    // ========================================================================

    @Test
    @DisplayName("★只能加视频：图文（type=2）⇒ 400；不存在 / 已软删 ⇒ 404（池里 0 行）")
    void 非视频内容不能加入() {
        TestUser me = register("13800005005", "只视频用户");
        long text = insertTextContent(me.id, "一份图文帖子");
        long deletedVideo = insertVideoContent(me.id, "已删视频");
        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", deletedVideo);

        assertThat(add(me.token, text).getStatusCode().value())
                .as("★ 图文不能进稍后再看（favorite-需求.md §六：只能加视频）")
                .isEqualTo(400);
        assertThat(add(me.token, deletedVideo).getStatusCode().value())
                .as("对已软删视频：走 type 判据前的'未软删'条件 ⇒ 404（同 /favorite/add 的失效口径）")
                .isEqualTo(404);
        assertThat(add(me.token, 999999999L).getStatusCode().value())
                .as("不存在的内容 ⇒ 404").isEqualTo(404);
        assertThat(oracleCount(me.id)).as("三次都被拒 ⇒ 池里一行都没有").isZero();
    }

    // ========================================================================
    // 5. ★ 上限 100（加新内容 400；已满时"重复 add"仍成功）
    // ========================================================================

    @Test
    @DisplayName("★上限 100：已满时加新内容 ⇒ 400；已满时'已在池中'重复 add ⇒ 仍成功（移到最前）")
    void 上限溢出与重复加入() {
        TestUser me = register("13800005006", "满池用户");
        long[] fillers = new long[LIMIT];
        for (int i = 0; i < LIMIT; i++) {
            fillers[i] = insertVideoContent(me.id, "填充视频" + i);
            insertWatchLaterAt(me.id, fillers[i], 1);
        }
        long extra = insertVideoContent(me.id, "第 101 条");

        assertThat(add(me.token, extra).getStatusCode().value())
                .as("★ 已满（100）⇒ 加**新**内容 400（favorite.md §九）").isEqualTo(400);
        assertThat(oracleCount(me.id)).as("被拒 ⇒ 仍是 100 行").isEqualTo(LIMIT);

        assertThat(add(me.token, fillers[0]).getStatusCode().value())
                .as("★ 已满时'已在池中的内容'再次 add **仍成功**（判据排除自身，favorite-需求.md §六）")
                .isEqualTo(200);
        assertThat(oracleCount(me.id)).as("★ 不新增行 ⇒ 仍是 100 行").isEqualTo(LIMIT);
        assertThat(contentIds(Envelope.data(list(me.token, null, null)).path("list")).get(0))
                .as("再次 add 的那条排到最前").isEqualTo(fillers[0]);
    }

    // ========================================================================
    // 6. remove / 失效条目
    // ========================================================================

    @Test
    @DisplayName("★批量移出 + 失效条目能移出（G11：不校验内容存在性）")
    void 批量移出与失效条目() {
        TestUser me = register("13800005007", "移出用户");
        long alive = insertVideoContent(me.id, "正常的");
        long softDeleted = insertVideoContent(me.id, "已删的");
        add(me.token, alive);
        add(me.token, softDeleted);
        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", softDeleted);
        assertThat(oracleCount(me.id)).isEqualTo(2L);

        assertThat(remove(me.token, alive, softDeleted).getStatusCode().value())
                .as("★ 失效条目照常删得掉（不校验内容存在性，G11）").isEqualTo(200);
        assertThat(oracleCount(me.id)).as("两条都移出 ⇒ 池空").isZero();
    }

    @Test
    @DisplayName("★清单失效占位脱敏：invalid=true、标题「内容已失效」、封面/作者 null、保留 contentId")
    void 失效占位脱敏() {
        TestUser me = register("13800005008", "占位用户");
        long alive = insertVideoContent(me.id, "正常视频标题");
        long softDeleted = insertVideoContent(me.id, "作者已删的标题");
        add(me.token, alive);
        add(me.token, softDeleted);
        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", softDeleted);

        JsonNode data = Envelope.data(list(me.token, null, null));
        assertThat(data.path("total").asLong()).as("失效条目照常计入/占位").isEqualTo(2L);

        JsonNode valid = item(data.path("list"), alive);
        assertThat(valid.path("invalid").asBoolean()).as("正常条目 false + 有标题/封面/作者").isFalse();
        assertThat(valid.path("title").asString()).isEqualTo("正常视频标题");
        assertThat(valid.path("coverUrl").asString()).isEqualTo("/upload/cover/" + alive + ".png");
        assertThat(valid.path("authorName").asString()).isEqualTo("占位用户");

        JsonNode invalid = item(data.path("list"), softDeleted);
        assertThat(invalid.path("invalid").asBoolean()).as("失效 ⇒ invalid=true").isTrue();
        assertThat(invalid.path("title").asString()).as("标题位写「内容已失效」").isEqualTo(INVALID_TITLE);
        assertThat(invalid.path("title").asString()).as("不得带原标题").isNotEqualTo("作者已删的标题");
        assertThat(invalid.path("coverUrl").isNull()).as("封面为 null").isTrue();
        assertThat(invalid.path("authorName").isNull()).as("作者为 null").isTrue();
        assertThat(invalid.path("contentId").asLong()).as("★ 保留 contentId（移出要它）").isEqualTo(softDeleted);
    }

    @Test
    @DisplayName("★装载不出来的内容也占位（R-11 看不了≈失效）：type=1 但缺视频行 ⇒ 占位、不带原字段")
    void 装载不出来的内容也占位() {
        TestUser me = register("13800005016", "损坏态用户");
        long missingVideo = insertUnbuildableContent(me.id, "视频行缺失的标题");
        // add 只判 is_deleted + type=1 ⇒ 收得进（数据损坏态无 API 可达，属 R-11 已登记取舍）
        assertThat(add(me.token, missingVideo).getStatusCode().value()).isEqualTo(200);

        JsonNode data = Envelope.data(list(me.token, null, null));
        assertThat(data.path("total").asLong()).isEqualTo(1L);
        JsonNode item = item(data.path("list"), missingVideo);
        assertThat(item.path("invalid").asBoolean())
                .as("★ R-11：'复用 loadContentVOs' 的落点 —— 装不出来照样占位（旧'仅判 is_deleted'的实现下本断言必绿）")
                .isTrue();
        assertThat(item.path("title").asString()).isEqualTo(INVALID_TITLE);
        assertThat(item.path("coverUrl").isNull()).isTrue();
        assertThat(item.path("authorName").isNull()).isTrue();
    }

    // ========================================================================
    // 7. 分页 / 形状 / 鉴权 / 参数
    // ========================================================================

    @Test
    @DisplayName("分页不重不漏：5 条分 pageSize=2 三页，并集=全量、交集为空；page 极大不得 500")
    void 分页不重不漏() {
        TestUser me = register("13800005009", "分页用户");
        // ★ 从**新到旧**直插（先插 i=1 最新的、最后插 i=5 最旧的）⇒ 最新那条拿**最小** id，
        //   即"记录 id 序"与"加入时序"**反向**。这样"按 create_time 排"与"按 id 排"才**可区分**
        //   （若同向，实现把 ORDER BY 改成 `w.id DESC` 也照样绿 —— 正是 AGENTS「断言顺序必须让排序键序与 id 序反向」点名的假绿）。
        List<Long> expected = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            long content = insertVideoContent(me.id, "内容" + i);
            insertWatchLaterAt(me.id, content, i);
            expected.add(content);   // 插入序 = 最新在前（i=1 最新）
        }

        List<Long> p1 = contentIds(Envelope.data(list(me.token, "1", "2")).path("list"));
        List<Long> p2 = contentIds(Envelope.data(list(me.token, "2", "2")).path("list"));
        List<Long> p3 = contentIds(Envelope.data(list(me.token, "3", "2")).path("list"));
        assertThat(p1).isEqualTo(expected.subList(0, 2));
        assertThat(p2).isEqualTo(expected.subList(2, 4));
        assertThat(p3).as("第 3 页只剩 1 条（不补位）").isEqualTo(expected.subList(4, 5));
        List<Long> union = new ArrayList<>(p1);
        union.addAll(p2);
        union.addAll(p3);
        assertThat(union).as("★ 并集 = 全量且无重复（不重不漏）").containsExactlyElementsOf(expected);

        ResponseEntity<String> huge = list(me.token, "2147483647", "100");
        assertThat(huge.getStatusCode().value())
                .as("★ page 极大不得 500（offset 用 long 防溢出）").isEqualTo(200);
        assertThat(Envelope.data(huge).path("list").size()).isZero();
    }

    @Test
    @DisplayName("分页归一：page=0/abc → 1；pageSize=abc → 100；pageSize=999 → 上限 100；pageSize=2 原样回显")
    void 分页参数归一() {
        TestUser me = register("13800005015", "归一用户");
        for (int i = 1; i <= 3; i++) {
            add(me.token, insertVideoContent(me.id, "内容" + i));
        }
        assertThat(Envelope.data(list(me.token, "0", null)).path("page").asLong())
                .as("page ≤ 0 → 1").isEqualTo(1L);
        assertThat(Envelope.data(list(me.token, "abc", null)).path("page").asLong())
                .as("page 非数字 → 1（与全项目分页端点同款静默归一）").isEqualTo(1L);
        assertThat(Envelope.data(list(me.token, null, "abc")).path("pageSize").asLong())
                .as("pageSize 非数字 → 缺省 100").isEqualTo(100L);
        assertThat(Envelope.data(list(me.token, null, "999")).path("pageSize").asLong())
                .as("超上限 → 100（域级上限）").isEqualTo(100L);
        assertThat(Envelope.data(list(me.token, null, "2")).path("pageSize").asLong())
                .as("小信封原样回显（否则没法用小信封跨页验证）").isEqualTo(2L);
    }

    @Test
    @DisplayName("★形状契约：信封恰好 5 键；条目恰好 {contentId,addedTime,invalid,title,coverUrl,authorName}（不含 id）")
    void 形状契约() {
        TestUser me = register("13800005010", "形状用户");
        long video = insertVideoContent(me.id, "形状内容");
        add(me.token, video);

        JsonNode data = Envelope.data(list(me.token, null, null));
        assertThat(data.propertyNames())
                .as("分页信封字段集冻结（PageResult 是跨域共享信封）")
                .containsExactlyInAnyOrder("list", "total", "page", "pageSize", "totalPages");
        assertThat(data.path("list").get(0).propertyNames())
                .as("★ 条目键集：用 addedTime（不是 favoriteTime）、**不含 id**（决策 D3）"
                        + " —— 多一个键就是契约扩大")
                .containsExactlyInAnyOrder("contentId", "addedTime", "invalid", "title", "coverUrl", "authorName");
    }

    @Test
    @DisplayName("★跨用户隔离：别人的稍后再看不出现（列表与总数都按 user_id 过滤）")
    void 跨用户隔离() {
        TestUser me = register("13800005013", "甲");
        TestUser other = register("13800005014", "乙");
        long video = insertVideoContent(me.id, "我的视频");
        add(me.token, video);

        JsonNode mine = Envelope.data(list(me.token, null, null));
        assertThat(mine.path("total").asLong()).as("甲自己的").isEqualTo(1L);
        assertThat(contentIds(mine.path("list"))).containsExactly(video);

        JsonNode theirs = Envelope.data(list(other.token, null, null));
        assertThat(theirs.path("total").asLong()).as("★ 乙看不到甲的稍后再看（列表/总数都按 user_id 过滤）").isZero();
        assertThat(theirs.path("list").size()).isZero();
    }

    @Test
    @DisplayName("鉴权：未登录 / 坏 token ⇒ 401（四端点都是'我的私有池'）")
    void 需登录() {
        TestUser me = register("13800005011", "鉴权用户");
        long video = insertVideoContent(me.id, "内容");
        add(me.token, video);

        assertThat(list(null, null, null).getStatusCode().value()).as("未登录 list ⇒ 401").isEqualTo(401);
        assertThat(list("not-a-jwt", null, null).getStatusCode().value()).as("坏 token ⇒ 401").isEqualTo(401);
        assertThat(add(null, video).getStatusCode().value()).as("未登录 add ⇒ 401").isEqualTo(401);
        assertThat(remove(null, video).getStatusCode().value()).as("未登录 remove ⇒ 401").isEqualTo(401);
        assertThat(move(null, video, null).getStatusCode().value()).as("未登录 move ⇒ 401").isEqualTo(401);
        assertThat(list(me.token, null, null).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("参数：remove 空 / 缺 contentIds ⇒ 400；add 缺 contentId ⇒ 400")
    void 参数错误线() {
        TestUser me = register("13800005012", "参数用户");

        MultiValueMap<String, String> emptyIds = new LinkedMultiValueMap<>();
        emptyIds.add("contentIds", "");
        assertThat(postForm("/watchlater/remove", emptyIds, me.token).getStatusCode().value())
                .as("空的 contentIds ⇒ 400（一个 id 都不给多半是调用方 bug）").isEqualTo(400);
        assertThat(postForm("/watchlater/remove", new LinkedMultiValueMap<>(), me.token).getStatusCode().value())
                .as("缺 contentIds ⇒ 400").isEqualTo(400);
        assertThat(postForm("/watchlater/add", new LinkedMultiValueMap<>(), me.token).getStatusCode().value())
                .as("缺 contentId ⇒ 400").isEqualTo(400);
    }

    // ========================================================================
    // HTTP
    // ========================================================================

    private ResponseEntity<String> list(String token, String page, String pageSize) {
        StringBuilder url = new StringBuilder("/watchlater/list");
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

    private ResponseEntity<String> add(String token, long contentId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("contentId", String.valueOf(contentId));
        return postForm("/watchlater/add", form, token);
    }

    private ResponseEntity<String> remove(String token, long... contentIds) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        StringBuilder joined = new StringBuilder();
        for (long id : contentIds) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(id);
        }
        form.add("contentIds", joined.toString());
        return postForm("/watchlater/remove", form, token);
    }

    private ResponseEntity<String> move(String token, long contentId, Long folderId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("contentId", String.valueOf(contentId));
        if (folderId != null) {
            form.add("folderId", String.valueOf(folderId));
        }
        return postForm("/watchlater/move", form, token);
    }

    private ResponseEntity<String> postForm(String path, MultiValueMap<String, String> form, String token) {
        return send(spec -> {
            RestClient.RequestBodySpec request = spec.uri(path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED);
            if (token != null) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return request.body(form);
        });
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

    // ========================================================================
    // 夹具与独立 oracle（都直连 DB，不经接口）
    // ========================================================================

    private TestUser register(String phone, String username) {
        String token = registerAndGetToken(phone, username, PASSWORD);
        return new TestUser(userIdOf(phone), token);
    }

    private record TestUser(long id, String token) {
    }

    /** ★ 独立 oracle：该用户稍后再看的行数。 */
    private long oracleCount(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_later WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }

    /** 独立 oracle：某 (user, content) 在稍后再看里的行 id（不存在 ⇒ 断言失败）。 */
    private long rowId(long userId, long contentId) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM watch_later WHERE user_id = ? AND content_id = ?", Long.class, userId, contentId);
        if (id == null) {
            throw new AssertionError("watch_later 里应有 (user=" + userId + ", content=" + contentId + ") 的行");
        }
        return id;
    }

    private java.sql.Timestamp createTime(long userId, long contentId) {
        return jdbcTemplate.queryForObject(
                "SELECT create_time FROM watch_later WHERE user_id = ? AND content_id = ?",
                java.sql.Timestamp.class, userId, contentId);
    }

    /** 直插一条稍后再看记录，时间用 **DB 时钟**造（{@code NOW() - INTERVAL n DAY}）。 */
    private void insertWatchLaterAt(long userId, long contentId, int daysAgo) {
        jdbcTemplate.update(
                "INSERT INTO watch_later (user_id, content_id, create_time) VALUES (?, ?, NOW() - INTERVAL ? DAY)",
                userId, contentId, daysAgo);
    }

    /** 把稍后再看时间钉到固定 datetime（同秒并列用例用；字面量同样是 DB 侧的值）。 */
    private void insertWatchLaterAtFixed(long userId, long contentId, String createTime) {
        jdbcTemplate.update(
                "INSERT INTO watch_later (user_id, content_id, create_time) VALUES (?, ?, ?)",
                userId, contentId, createTime);
    }

    /** 插入一条**可装配**的视频内容（content type=1 + 视频行 + 封面行）——列表里是正常卡片。 */
    private long insertVideoContent(long authorId, String title) {
        long contentId = insertContentRow(authorId, title, 1, 0);
        insertMedia(contentId, "/upload/video/" + contentId + ".mp4", 1, 1);
        insertMedia(contentId, "/upload/cover/" + contentId + ".png", 3, 0);
        return contentId;
    }

    /** 插入一条"装不出来"的内容：type=1 但**缺视频行**（R-11 钉子用；is_deleted=0 ⇒ add 收得进）。 */
    private long insertUnbuildableContent(long authorId, String title) {
        long contentId = insertContentRow(authorId, title, 1, 0);
        insertMedia(contentId, "/upload/cover/" + contentId + ".png", 3, 0);
        return contentId;
    }

    /** 插入一条**图文**内容（content type=2 + 封面行）——"只能加视频"的对照。 */
    private long insertTextContent(long authorId, String title) {
        long contentId = insertContentRow(authorId, title, 2, 0);
        insertMedia(contentId, "/upload/cover/" + contentId + ".png", 3, 0);
        return contentId;
    }

    private long insertContentRow(long authorId, String title, int type, int isDeleted) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description, is_deleted) VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setInt(2, type);
            ps.setString(3, title);
            ps.setString(4, "T17 测试描述");
            ps.setInt(5, isDeleted);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    private void insertMedia(long contentId, String url, int type, int sort) {
        jdbcTemplate.update(
                "INSERT INTO content_media (content_id, url, type, sort) VALUES (?, ?, ?, ?)",
                contentId, url, type, sort);
    }
}
