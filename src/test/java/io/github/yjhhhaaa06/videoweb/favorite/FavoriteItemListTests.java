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
 * 收藏一期 <b>T6：夹内列表分页（含失效占位）</b> 的契约测试（{@code 能力 一期-7}，R-03 / R-07 / G12）。
 *
 * <h2>覆盖什么（期望值全部出自业务口径，盖住实现照样写得出来）</h2>
 * <ul>
 *   <li><b>顺序</b>：收藏时间倒序（需求篇 §三）—— 最近收藏的排最前；同秒并用 {@code id} 兜底；</li>
 *   <li>★ <b>分页单位是收藏记录行</b>（G12）：{@code total} <b>含</b>失效条目（R-07），
 *       页与页之间**不重不漏**（并集 = 全量、交集 = 空）；</li>
 *   <li>★ <b>失效条目脱敏</b>（R-03）：{@code invalid=true}、标题是「内容已失效」、
 *       {@code coverUrl} / {@code authorName} 恒为 {@code null} ——
 *       <b>不含原标题 / 封面 / 作者</b>；且失效条目**保留 {@code contentId}**（移出要它）；</li>
 *   <li>★ <b>一整页都是失效条目也返回满页</b>（G12）：跳过 ⇒ 用户看不到 ⇒ 也就永远清不掉；</li>
 *   <li>★ <b>失效条目能移出</b>（与 T4 联动，R-07）：占位能看见 ⇒ 就该删得掉；</li>
 *   <li>归属与鉴权：他人的夹 403 / 不存在的夹 404 / 缺 {@code folderId} 400 / 未登录 401；</li>
 *   <li>形状契约：信封 5 键、条目 7 键（多一个 = 把别处信息带出来；少一个 = 破坏渲染或移出）；</li>
 *   <li>分页归一：{@code page} 非数字 → 1、{@code pageSize} 非数字 → 100、超大 → 上限 100。</li>
 * </ul>
 *
 * <h2>★ 期望值怎么来的（规避"迎合型伪测试"）</h2>
 * "倒序"出自需求篇 §三「按收藏时间倒序」；"total 含失效"出自 R-07（B 站实测：失效视频算进
 * 收藏夹视频数）；"脱敏"出自 R-03（只给占位，**不返回原标题 / 封面 / 作者**）；
 * "整页失效也满页"出自 G12 与 R-07 的"可移出"理由（看不见就清不掉）；
 * "失效能移出"出自 G11（{@code remove} 不校验内容存在性）；
 * 归属与鉴权口径出自 {@code FavoriteService} 类注释的表与 T2~T5 一贯形态。
 * 盖住实现，这些断言照样写得出来。
 *
 * <h2>★ 反向验证（逐条注入 → 确认变红 → 还原；实测记录见 T6 回写，T8 收口复核）</h2>
 * <ul>
 *   <li>去掉 {@code FavoriteService.maskIfInvalid} 的失效分支 ⇒ 脱敏用例变红（原标题 / 封面 / 作者泄漏）；</li>
 *   <li>{@code findPageByFolderId} 的 {@code LEFT JOIN} 改成 {@code INNER JOIN} ⇒
 *       失效条目从页里消失 ⇒ 脱敏 / total / 满页三条用例变红（**分页出空洞**）；</li>
 *   <li>{@code ORDER BY} 去掉 {@code i.create_time DESC}（改成 {@code i.id DESC}）⇒ 倒序用例变红；</li>
 *   <li>{@code countByFolderId} 加 {@code JOIN content ... is_deleted = 0} ⇒ total 变小 ⇒
 *       "total 含失效"与分页并集用例变红。</li>
 * </ul>
 * ⚠️ 其中"同秒 tie-breaker"那条钉的是**契约**，其判别力依赖优化器在 {@code create_time} 并列时
 * 恰好怎么回行 —— 反向验证时以实测为准（若去掉 {@code , i.id DESC} 不变红，说明该顺序由索引
 * 结构巧合保证，本条降级为"钉契约"，不算判别力）。
 *
 * <h2>独立性</h2>
 * 关键计数一律用**独立 oracle** 直查 {@code favorite_item} 复算（{@code COUNT(*)}），不信接口回显；
 * 时间窗用 **DB 时钟**（{@code NOW() - INTERVAL n DAY}）造，不绑 JVM 时间（容器是 UTC）。
 */
class FavoriteItemListTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    /** 失效占位标题（R-03；与 {@code FavoriteService.INVALID_CONTENT_TITLE} 同源，此处独立写死以免抄实现）。 */
    private static final String INVALID_TITLE = "内容已失效";

    /** 刻意**不**在 content 表插行的"幽灵内容 id"（内容行真没了，R-07 失效形态之一）。 */
    private static final long GHOST_CONTENT_ID = 990601L;

    @BeforeEach
    void 清空本域相关表() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        jdbcTemplate.update("DELETE FROM content_media");
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ========================================================================
    // 1. 空态
    // ========================================================================

    @Test
    @DisplayName("空夹：list=[]、total=0、totalPages=0（**不 404**）；信封是分页形状")
    void 空夹返回空页而非404() {
        TestUser me = register("13800004001", "空夹用户");
        long folder = insertFolder(me.id, "空夹");

        ResponseEntity<String> resp = list(me.token, folder, null, null);
        assertThat(resp.getStatusCode().value()).as("空夹是合法状态（R-01 懒建同款口径）").isEqualTo(200);
        JsonNode data = Envelope.data(resp);
        assertThat(data.path("list").isArray()).as("★ 空态是**空数组**，不是 null / 不是缺键").isTrue();
        assertThat(data.path("list").size()).isZero();
        assertThat(data.path("total").asLong()).isZero();
        assertThat(data.path("page").asLong()).isEqualTo(1L);
        assertThat(data.path("pageSize").asLong()).as("缺省信封 = 100").isEqualTo(100L);
        assertThat(data.path("totalPages").asLong()).as("total=0 ⇒ 0 页").isZero();
        assertThat(oracleItemCount(folder)).isZero();
    }

    // ========================================================================
    // 2. ★ 顺序（收藏时间倒序）
    // ========================================================================

    @Test
    @DisplayName("★收藏时间倒序：最近收藏的排最前；favoriteTime 逐条严格递减")
    void 按收藏时间倒序() {
        TestUser me = register("13800004002", "顺序用户");
        long folder = insertFolder(me.id, "顺序夹");
        long oldest = insertContent(me.id, "最早收的", 0);
        long middle = insertContent(me.id, "中间收的", 0);
        long newest = insertContent(me.id, "最近收的", 0);
        // ★ 刻意**倒着**插收藏记录：让"最近收的"拿到**最小**的 favorite_item.id。
        //   否则 id 序与收藏时序同向 ⇒ 实现改成 `ORDER BY i.id DESC` 也照样绿 ——
        //   本用例就分不出"按收藏时间排"和"按 id 排"（T6 反向验证注入⑤实测到的假绿，已修）。
        insertItem(folder, me.id, newest, 1);
        insertItem(folder, me.id, middle, 2);
        insertItem(folder, me.id, oldest, 3);

        JsonNode data = Envelope.data(list(me.token, folder, null, null));
        assertThat(data.path("total").asLong()).isEqualTo(3L);
        assertThat(contentIds(data.path("list")))
                .as("★ 倒序：最近收藏（1 天前）排最前（需求篇 §三）")
                .containsExactly(newest, middle, oldest);
        assertThat(titles(data.path("list"))).containsExactly("最近收的", "中间收的", "最早收的");

        List<String> times = favoriteTimes(data.path("list"));
        assertThat(times).hasSize(3);
        for (int i = 1; i < times.size(); i++) {
            assertThat(times.get(i - 1).compareTo(times.get(i)))
                    .as("favoriteTime 必须严格递减（第 %d 条 vs 第 %d 条）", i, i + 1)
                    .isPositive();
        }
    }

    @Test
    @DisplayName("同秒收藏的 tie-breaker：create_time 并列时按 id DESC（否则页间顺序不确定 ⇒ 分页重漏）")
    void 同秒收藏按id倒序() {
        TestUser me = register("13800004003", "同秒用户");
        long folder = insertFolder(me.id, "同秒夹");
        long first = insertContent(me.id, "先收的", 0);
        long second = insertContent(me.id, "后收的", 0);
        // 两条钉到同一个 datetime（秒精度 ⇒ 必然并列）
        insertItemAt(folder, me.id, first, "2026-10-01 00:00:00");
        insertItemAt(folder, me.id, second, "2026-10-01 00:00:00");

        assertThat(contentIds(Envelope.data(list(me.token, folder, null, null)).path("list")))
                .as("同秒 ⇒ 后插入（id 更大）的在前，与 'id DESC' tie-breaker 一致")
                .containsExactly(second, first);
    }

    // ========================================================================
    // 3. ★ 分页边界（不重不漏）
    // ========================================================================

    @Test
    @DisplayName("★分页不重不漏：5 条分 pageSize=2 三页，逐页顺序正确、并集=全量、交集为空")
    void 分页不重不漏() {
        TestUser me = register("13800004004", "分页用户");
        long folder = insertFolder(me.id, "分页夹");
        // ★ 从**新到旧**插（i 天前，i 越小越新）：于是 favorite_item.id 也是"新→旧"递增，
        //   id 序与收藏时序**同向**。这样才与下一条用例形成对照 ——
        //   见"倒序"用例里倒着插的那条注释（两处合起来才能分出"按时间排"与"按 id 排"）。
        List<Long> expectedNewestFirst = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            long content = insertContent(me.id, "内容" + i, 0);
            insertItem(folder, me.id, content, i);
            expectedNewestFirst.add(content);
        }
        assertThat(oracleItemCount(folder)).isEqualTo(5L);

        JsonNode page1 = Envelope.data(list(me.token, folder, "1", "2"));
        assertThat(page1.path("total").asLong()).as("total 与 oracle 一致（5 条记录）").isEqualTo(5L);
        assertThat(page1.path("totalPages").asLong()).as("ceil(5/2)=3").isEqualTo(3L);
        List<Long> p1 = contentIds(page1.path("list"));
        List<Long> p2 = contentIds(Envelope.data(list(me.token, folder, "2", "2")).path("list"));
        List<Long> p3 = contentIds(Envelope.data(list(me.token, folder, "3", "2")).path("list"));

        assertThat(p1).as("第 1 页 = 最新两条").isEqualTo(expectedNewestFirst.subList(0, 2));
        assertThat(p2).isEqualTo(expectedNewestFirst.subList(2, 4));
        assertThat(p3).as("第 3 页只剩 1 条（不补位）").isEqualTo(expectedNewestFirst.subList(4, 5));

        List<Long> union = new ArrayList<>(p1);
        union.addAll(p2);
        union.addAll(p3);
        assertThat(union).as("★ 并集 = 全量且**无重复**（不重不漏）")
                .containsExactlyElementsOf(expectedNewestFirst);
    }

    @Test
    @DisplayName("分页归一：page=0/abc → 1；pageSize=0/abc → 100；pageSize=999 → 上限 100；pageSize=3 → 回显 3")
    void 分页参数归一() {
        TestUser me = register("13800004005", "归一用户");
        long folder = insertFolder(me.id, "归一夹");
        for (int i = 3; i >= 1; i--) {
            insertItem(folder, me.id, insertContent(me.id, "内容" + i, 0), i);
        }

        assertThat(Envelope.data(list(me.token, folder, "0", null)).path("page").asLong())
                .as("page ≤ 0 → 1").isEqualTo(1L);
        assertThat(Envelope.data(list(me.token, folder, "abc", null)).path("page").asLong())
                .as("page 非数字 → 1（与全项目分页端点同款静默归一）").isEqualTo(1L);
        assertThat(Envelope.data(list(me.token, folder, null, "abc")).path("pageSize").asLong())
                .as("pageSize 非数字 → 缺省 100").isEqualTo(100L);
        assertThat(Envelope.data(list(me.token, folder, null, "999")).path("pageSize").asLong())
                .as("超上限 → 100（域级上限）").isEqualTo(100L);
        assertThat(Envelope.data(list(me.token, folder, null, "2")).path("pageSize").asLong())
                .as("小信封原样回显（否则没法用小信封跨页验证）").isEqualTo(2L);
        assertThat(Envelope.data(list(me.token, folder, null, "2")).path("list").size())
                .as("pageSize=2 ⇒ 第 1 页 2 条").isEqualTo(2);
    }

    // ========================================================================
    // 4. ★ 失效条目：脱敏 + 计入 + 满页 + 能移出
    // ========================================================================

    @Test
    @DisplayName("★失效条目脱敏：invalid=true、标题=「内容已失效」、**不含原标题/封面/作者**；正常条目三者齐全")
    void 失效条目脱敏不含原标题封面作者() {
        TestUser me = register("13800004006", "脱敏用户");
        long folder = insertFolder(me.id, "含失效的夹");
        long alive = insertContent(me.id, "正常内容的标题", 0);
        long softDeleted = insertContent(me.id, "作者已删的标题", 1);   // is_deleted = 1
        insertItem(folder, me.id, alive, 2);
        insertItem(folder, me.id, softDeleted, 1);
        insertItem(folder, me.id, GHOST_CONTENT_ID, 0);                 // 内容行真没了

        JsonNode data = Envelope.data(list(me.token, folder, null, null));
        assertThat(data.path("total").asLong()).as("三条记录都在（含失效）").isEqualTo(3L);

        JsonNode valid = requireItem(data.path("list"), alive);
        assertThat(valid.path("invalid").asBoolean())
                .as("★ 正向断 false —— 缺失键时 asBoolean() 恒 false，只有 true/false 成对才能证明键名")
                .isFalse();
        assertThat(valid.path("title").asString()).isEqualTo("正常内容的标题");
        assertThat(valid.path("coverUrl").asString())
                .as("★ 正常条目**有**封面（否则'失效无封面'那条断言就是假绿：恒 null）").isNotBlank();
        assertThat(valid.path("authorName").asString())
                .as("★ 正常条目**有**作者（同理，否则'失效无作者'恒 null ⇒ 假绿）").isEqualTo("脱敏用户");

        for (long invalidContent : new long[]{softDeleted, GHOST_CONTENT_ID}) {
            JsonNode item = requireItem(data.path("list"), invalidContent);
            assertThat(item.path("invalid").asBoolean())
                    .as("失效（is_deleted=%d / 行没了）⇒ invalid=true", invalidContent == softDeleted ? 1 : -1)
                    .isTrue();
            assertThat(item.path("title").asString()).as("标题位写「内容已失效」").isEqualTo(INVALID_TITLE);
            assertThat(item.path("title").asString())
                    .as("★ 不得是原标题（R-03：删了就不该再被检索到）").isNotEqualTo("作者已删的标题");
            assertThat(item.path("coverUrl").isNull())
                    .as("★ 封面为 null —— isNull() 区分'键存在且为 null'与'键缺失'").isTrue();
            assertThat(item.path("authorName").isNull()).as("★ 作者为 null").isTrue();
            assertThat(item.path("contentId").asLong())
                    .as("★ 保留 contentId（移出要它：失效条目必须删得掉）").isEqualTo(invalidContent);
            assertThat(item.path("favoriteTime").isMissingNode()).as("收藏时间仍是事实，照常返回").isFalse();
        }
    }

    @Test
    @DisplayName("★total 含失效条目：2 正常 + 2 失效 ⇒ total=4（与独立 oracle 的 COUNT(*) 一致）")
    void total包含失效条目() {
        TestUser me = register("13800004007", "计数用户");
        long folder = insertFolder(me.id, "混杂夹");
        long normal1 = insertContent(me.id, "正常1", 0);
        long normal2 = insertContent(me.id, "正常2", 0);
        long deleted = insertContent(me.id, "作者删除", 1);   // is_deleted = 1
        long hidden = insertContent(me.id, "管理员下架", 2);  // is_deleted = 2
        insertItem(folder, me.id, normal1, 4);
        insertItem(folder, me.id, normal2, 3);
        insertItem(folder, me.id, deleted, 2);
        insertItem(folder, me.id, hidden, 1);

        JsonNode data = Envelope.data(list(me.token, folder, null, null));
        assertThat(oracleItemCount(folder)).as("独立 oracle：4 条收藏记录").isEqualTo(4L);
        assertThat(data.path("total").asLong())
                .as("★ total 必须等于收藏**记录**数（含失效）—— 与 /folder/list 的 itemCount 同一口径")
                .isEqualTo(oracleItemCount(folder));
        assertThat(data.path("list").size()).as("一页装得下 4 条 ⇒ 满页返回（不跳过失效）").isEqualTo(4);

        // ⚠️ 按 contentId 定位而不是按下标 —— 列表是**收藏时间倒序**，下标与插入序无关
        //   （本例下标 0 恰恰是最晚收藏的"管理员下架"那条）。
        assertThat(requireItem(data.path("list"), deleted).path("invalid").asBoolean())
                .as("is_deleted=1（作者删除）⇒ 失效").isTrue();
        assertThat(requireItem(data.path("list"), hidden).path("invalid").asBoolean())
                .as("★ is_deleted=2（管理员下架）也算失效 ⇒ 判据必须覆盖三态，别写成 = 1").isTrue();
        assertThat(requireItem(data.path("list"), normal1).path("invalid").asBoolean()).isFalse();
        assertThat(requireItem(data.path("list"), normal2).path("invalid").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★一整页都是失效条目也返回满页（G12）：3 条全失效、pageSize=2 ⇒ 第 1 页仍 2 条")
    void 整页失效也返回满页() {
        TestUser me = register("13800004008", "全失效用户");
        long folder = insertFolder(me.id, "全失效夹");
        insertItem(folder, me.id, GHOST_CONTENT_ID, 3);
        insertItem(folder, me.id, GHOST_CONTENT_ID + 1, 2);
        insertItem(folder, me.id, insertContent(me.id, "被下架的", 2), 1);

        JsonNode data = Envelope.data(list(me.token, folder, "1", "2"));
        assertThat(data.path("total").asLong()).isEqualTo(3L);
        assertThat(data.path("list").size())
                .as("★ 跳过 ⇒ 用户看不到 ⇒ 也就永远清不掉（R-07 的'可移出'落空）").isEqualTo(2);
        for (JsonNode item : data.path("list")) {
            assertThat(item.path("invalid").asBoolean()).isTrue();
            assertThat(item.path("title").asString()).isEqualTo(INVALID_TITLE);
        }
    }

    @Test
    @DisplayName("★失效条目能移出（与 T4 联动）：占位删掉后从列表消失、total-1（G11）")
    void 失效条目能移出() {
        TestUser me = register("13800004009", "清理占位用户");
        long folder = insertFolder(me.id, "待清理夹");
        long alive = insertContent(me.id, "正常的", 0);
        long softDeleted = insertContent(me.id, "已删的", 1);
        insertItem(folder, me.id, alive, 2);
        insertItem(folder, me.id, softDeleted, 1);

        JsonNode before = Envelope.data(list(me.token, folder, null, null));
        assertThat(before.path("total").asLong()).isEqualTo(2L);
        assertThat(itemByContentId(before.path("list"), softDeleted)).isNotNull();

        // /favorite/remove 刻意**不校验内容存在性**（G11）⇒ 失效条目必须删得掉
        ResponseEntity<String> removed = remove(me.token, folder, softDeleted);
        assertThat(removed.getStatusCode().value()).as("失效条目移出 ⇒ 200（不是 404）").isEqualTo(200);

        JsonNode after = Envelope.data(list(me.token, folder, null, null));
        assertThat(after.path("total").asLong()).as("total 减 1").isEqualTo(1L);
        assertThat(oracleItemCount(folder)).as("独立 oracle：DB 里只剩 1 条").isEqualTo(1L);
        assertThat(contentIds(after.path("list"))).containsExactly(alive);
        assertThat(itemByContentId(after.path("list"), softDeleted)).as("占位已从列表消失").isNull();
    }

    // ========================================================================
    // 5. 归属 / 鉴权 / 参数
    // ========================================================================

    @Test
    @DisplayName("归属与参数：他人的夹 403、不存在的夹 404、缺 folderId 400（缺参由 Spring 参数绑定拦）")
    void 归属与参数错误线() {
        TestUser me = register("13800004010", "甲");
        TestUser other = register("13800004011", "乙");
        long mine = insertFolder(me.id, "甲的夹");
        long theirs = insertFolder(other.id, "乙的夹");
        insertItem(mine, me.id, insertContent(me.id, "甲的内容", 0), 1);

        assertThat(list(me.token, theirs, null, null).getStatusCode().value())
                .as("别人的夹 ⇒ 403（与改名/删除/移出同一口径）").isEqualTo(403);
        assertThat(list(me.token, 999999999L, null, null).getStatusCode().value())
                .as("不存在的夹 ⇒ 404").isEqualTo(404);
        assertThat(get("/favorite/list", me.token).getStatusCode().value())
                .as("缺 folderId ⇒ 400").isEqualTo(400);
        assertThat(list(me.token, mine, null, null).getStatusCode().value())
                .as("自己的夹 ⇒ 200").isEqualTo(200);
    }

    @Test
    @DisplayName("鉴权：未登录 → 401；坏 token → 401（夹内列表是'我的'私有视角）")
    void 夹内列表需登录() {
        TestUser me = register("13800004012", "鉴权用户");
        long folder = insertFolder(me.id, "夹");
        insertItem(folder, me.id, insertContent(me.id, "内容", 0), 1);

        assertThat(list(null, folder, null, null).getStatusCode().value())
                .as("未登录不得看别人的/我的收藏夹内容 ⇒ 401").isEqualTo(401);
        assertThat(list("not-a-jwt", folder, null, null).getStatusCode().value())
                .as("坏 token ⇒ 401").isEqualTo(401);
        assertThat(list(me.token, folder, null, null).getStatusCode().value()).isEqualTo(200);
    }

    // ========================================================================
    // 6. 形状契约
    // ========================================================================

    @Test
    @DisplayName("★形状契约：信封恰好 5 键；条目恰好 {id, contentId, favoriteTime, invalid, title, coverUrl, authorName}")
    void 形状契约() {
        TestUser me = register("13800004013", "形状用户");
        long folder = insertFolder(me.id, "唯一夹");
        insertItem(folder, me.id, insertContent(me.id, "内容", 0), 1);

        JsonNode data = Envelope.data(list(me.token, folder, null, null));
        assertThat(data.propertyNames())
                .as("分页信封字段集一次性冻结（PageResult 是跨域共享信封）")
                .containsExactlyInAnyOrder("list", "total", "page", "pageSize", "totalPages");
        assertThat(data.path("list").size()).isEqualTo(1);
        assertThat(data.path("list").get(0).propertyNames())
                .as("条目只给'渲染一张卡片 + 移出'所必需的字段（多一个 = 把别处信息带出来）")
                .containsExactlyInAnyOrder("id", "contentId", "favoriteTime", "invalid",
                        "title", "coverUrl", "authorName");
    }

    // ========================================================================
    // HTTP
    // ========================================================================

    private ResponseEntity<String> list(String token, long folderId, String page, String pageSize) {
        StringBuilder url = new StringBuilder("/favorite/list?folderId=").append(folderId);
        if (page != null) {
            url.append("&page=").append(page);
        }
        if (pageSize != null) {
            url.append("&pageSize=").append(pageSize);
        }
        return get(url.toString(), token);
    }

    private ResponseEntity<String> remove(String token, long folderId, long... contentIds) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("folderId", String.valueOf(folderId));
        StringBuilder joined = new StringBuilder();
        for (long id : contentIds) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(id);
        }
        form.add("contentIds", joined.toString());
        return postForm("/favorite/remove", form, token);
    }

    /** POST + {@code x-www-form-urlencoded}（同域 T2/T4 测试的同款实现：收藏写端点收 form）。 */
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

    private static List<String> titles(JsonNode list) {
        List<String> titles = new ArrayList<>();
        for (JsonNode item : list) {
            titles.add(item.path("title").asString());
        }
        return titles;
    }

    private static List<String> favoriteTimes(JsonNode list) {
        List<String> times = new ArrayList<>();
        for (JsonNode item : list) {
            times.add(item.path("favoriteTime").asString());
        }
        return times;
    }

    private static JsonNode itemByContentId(JsonNode list, long contentId) {
        for (JsonNode item : list) {
            if (item.path("contentId").asLong() == contentId) {
                return item;
            }
        }
        return null;
    }

    /**
     * 取条目，**必须在**（找不到 ⇒ 报出"缺了哪条"，而不是让下游 NPE）。
     *
     * <p>★ 为什么不用 {@code assertThat(...).isNotNull()}：注入 INNER JOIN 这类缺陷时，
     * 条目是**整行消失**，下游直接 NPE —— 失败信息里看不到"少了哪一条"。
     * 这里把"应当在"显式成一条断言，反向验证时失败原因一眼可见。
     */
    private static JsonNode requireItem(JsonNode list, long contentId) {
        JsonNode item = itemByContentId(list, contentId);
        if (item == null) {
            throw new AssertionError("列表里应当有 contentId=" + contentId
                    + " 的条目，实际只有：" + contentIds(list));
        }
        return item;
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

    /** ★ 独立 oracle：夹内收藏记录数（数的是**记录行**，含失效）。 */
    private long oracleItemCount(long folderId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_item WHERE folder_id = ?", Long.class, folderId);
        return n == null ? 0L : n;
    }

    /** 插入一条内容（含一张封面媒体行，使"正常条目有封面"这条断言具备判别力）。 */
    private long insertContent(long authorId, String title, int isDeleted) {
        long contentId = insertContentRow(authorId, title, isDeleted);
        jdbcTemplate.update(
                "INSERT INTO content_media (content_id, url, type, sort) VALUES (?, ?, 3, 0)",
                contentId, "/upload/cover/" + contentId + ".png");
        return contentId;
    }

    private long insertContentRow(long authorId, String title, int isDeleted) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description, is_deleted) VALUES (?, 1, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setString(2, title);
            ps.setString(3, "T6 测试描述");
            ps.setInt(4, isDeleted);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    /**
     * 插入一条收藏记录，收藏时间用 **DB 时钟**造（{@code NOW() - INTERVAL n DAY}）。
     *
     * <p>⚠️ 不用 JVM 时间：容器 MySQL 是 UTC、JVM 是 +08，两者混用会让"倒序"用例
     * 在边界上（相差 8 小时内）出现**看似正确实则错位**的结果。
     */
    private void insertItem(long folderId, long userId, long contentId, int daysAgo) {
        jdbcTemplate.update(
                "INSERT INTO favorite_item (folder_id, user_id, content_id, create_time) "
                        + "VALUES (?, ?, ?, NOW() - INTERVAL ? DAY)",
                folderId, userId, contentId, daysAgo);
    }

    /** 把收藏时间钉到**固定的** datetime（同秒并列用例用；字面量同样是 DB 侧的值，不经 JVM）。 */
    private void insertItemAt(long folderId, long userId, long contentId, String createTime) {
        jdbcTemplate.update(
                "INSERT INTO favorite_item (folder_id, user_id, content_id, create_time) VALUES (?, ?, ?, ?)",
                folderId, userId, contentId, createTime);
    }

    private long insertFolder(long userId, String name) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO favorite_folder (user_id, name) VALUES (?, ?)", new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, name);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("favorite_folder 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }
}
