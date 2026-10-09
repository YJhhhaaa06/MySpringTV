package io.github.yjhhhaaa06.videoweb.favorite;

import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import tools.jackson.databind.JsonNode;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收藏一期 <b>T5：收藏状态 + 收藏数</b> 的契约测试（{@code 能力 一期-5 / 一期-6}，R-06 / R-07）。
 *
 * <h2>覆盖什么（期望值全部出自业务口径，盖住实现照样写得出来）</h2>
 * <ul>
 *   <li><b>状态</b>（{@code GET /favorite/status}）：未收藏 ⇒ {@code {isFavorited:false, folders:[]}}；
 *       已收藏 ⇒ {@code folders} 列出**收在哪些夹**（默认夹置顶 + 创建序），{@code isFavorited=true}；</li>
 *   <li>★ <b>计数按人去重</b>（需求篇 §六 / R-06）：同一人把同一内容收进**两个夹** ⇒
 *       {@code count} 仍是 <b>1</b>（而 DB 里是 2 条记录 —— 本类专门把这两个数都断言出来，
 *       让"去掉 DISTINCT"这类错**必然**变红）；另一人再收 ⇒ 2；全移出 ⇒ 0；</li>
 *   <li>★ <b>失效内容</b>（R-07③）：{@code is_deleted != 0} / 内容行真没了的记录**仍计入收藏数**、
 *       且状态**仍显示它所在的夹**（记录还在；脱敏是 T6 在内容侧的事）；</li>
 *   <li>★ <b>鉴权口径</b>：{@code status} 需登录（401）、{@code count} **匿名可访问**（200）——
 *       收藏数是公开展示数（需求篇 §三），与 {@code /like/content/count} 的"需登录"刻意不同；</li>
 *   <li>跨用户隔离：别人的收藏不影响我的 status、也不改变 count 的"人"语义（换个用户就 +1）；</li>
 *   <li>形状契约：{@code data} 恰好 {@code isFavorited + folders} 两键、{@code folders} 元素恰好
 *       {@code id + name} 两键（多一个键 = 把别处信息带出来；少一个 = 破坏"知道收在哪些夹"）。</li>
 * </ul>
 *
 * <h2>★ 期望值怎么来的（规避"迎合型伪测试"）</h2>
 * "按人去重"出自需求篇 §六与 R-06（"同一人收进多个夹只算 1"）；"失效仍计"出自 R-07③；
 * "status 含所在夹"出自需求篇 §三；两条端点的鉴权差异出自 R-06 的"公开展示数"定位与
 * T5 的端点设计；空态合法（不 404）出自 T3 的"不给存在性开探测口"同款口径。盖住实现，
 * 这些断言照样写得出来。
 *
 * <h2>★ 反向验证（逐条注入 → 确认变红 → 还原；实测记录见 T5 回写，T8 收口复核）</h2>
 * <ul>
 *   <li>把 {@code countDistinctUserByContentId} 的 {@code COUNT(DISTINCT user_id)} 改成
 *       {@code COUNT(*)} ⇒ {@code 同一人进两夹…计数仍为一} 变红（1 → 2）；</li>
 *   <li>把 {@code findFoldersByUserAndContent} 的 {@code i.user_id = ?} 去掉 ⇒
 *       {@code 两人收藏…状态互不影响} 变红（别人的夹混进我的状态）；</li>
 *   <li>给 {@code /favorite/count} 误加 {@code @RequiresLogin} ⇒ 本类匿名用例 + {@code
 *       SecurityContractTests.favorite状态与计数鉴权} 变红。</li>
 * </ul>
 *
 * <h2>独立性</h2>
 * 关键计数一律用**独立 oracle** 直查 {@code favorite_item} 复算（{@code COUNT(DISTINCT user_id)}），
 * 不信接口回显；且同时算出 {@code COUNT(*)} 对照 —— 两者之差正是"去重"这项契约的判别力所在。
 */
class FavoriteStatusAndCountTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    /** 刻意**不**在 content 表插行的"幽灵内容 id"（内容行真没了，R-07 失效形态之一）。 */
    private static final long GHOST_CONTENT_ID = 990201L;

    @BeforeEach
    void 清空本域相关表() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ========================================================================
    // 1. 未收藏 / 空态
    // ========================================================================

    @Test
    @DisplayName("★未收藏：status = {isFavorited:false, folders:[]}（两键都在，不是缺 data）；count = 0")
    void 未收藏时状态为空计数为零() {
        TestUser me = register("13800003001", "从没收藏的人");
        long content = insertContent(me.id);

        ResponseEntity<String> statusResp = status(me.token, content);
        assertThat(statusResp.getStatusCode().value()).isEqualTo(200);
        JsonNode data = Envelope.data(statusResp);
        assertThat(data.path("isFavorited").asBoolean()).as("未收藏").isFalse();
        assertThat(data.path("folders").isArray()).as("★ 空态是**空数组**，不是 null / 不是缺键").isTrue();
        assertThat(data.path("folders").size()).isZero();

        ResponseEntity<String> countResp = count(me.token, content);
        assertThat(countResp.getStatusCode().value()).isEqualTo(200);
        assertThat(countOf(countResp)).as("独立 oracle：DB 里 0 条").isEqualTo(oracleDistinctUserCount(content));
        assertThat(countOf(countResp)).as("无人收藏 ⇒ 0").isZero();

        // 内容不存在同样是"未收藏 / 0"（不 404）—— 见下一条用例的对称覆盖
        assertThat(oracleRecordCount(content)).isZero();
    }

    // ========================================================================
    // 2. ★ 按人去重（本任务的核心口径）
    // ========================================================================

    @Test
    @DisplayName("★同一人进两夹：status.folders 含两夹（默认夹置顶）、isFavorited=true；count 仍为 **1**（按人去重）")
    void 同一人进两夹状态含两夹计数仍为一() {
        TestUser me = register("13800003002", "多夹用户");
        long content = insertContent(me.id);
        // 刻意先建**自建夹**、后建**默认夹**（默认夹 id 更大）—— 若排序按 id 而非 is_default，顺序会错
        long namedFolder = insertFolder(me.id, "稍后再看");
        long defaultFolder = insertDefaultFolder(me.id, "默认收藏夹");
        insertItem(namedFolder, me.id, content);
        insertItem(defaultFolder, me.id, content);

        // ---- status ----
        JsonNode data = Envelope.data(status(me.token, content));
        assertThat(data.path("isFavorited").asBoolean())
                .as("★ 正向断 true —— path().asBoolean() 对**缺失键**恒 false，只有 true 能证明 JSON 键名"
                        + "逐字是 isFavorited（映射链 resultMap/getter → Jackson 整条通）")
                .isTrue();
        List<String> names = names(data.path("folders"));
        assertThat(names)
                .as("顺序契约：默认夹置顶 + 创建序（两处列表复用同一顺序）")
                .containsExactly("默认收藏夹", "稍后再看");
        List<Long> ids = ids(data.path("folders"));
        assertThat(ids).as("id 要对得上（前端按 folderId 做移出 / 移动定位）")
                .containsExactly(defaultFolder, namedFolder);

        // ---- count：★ 去重的判别力就在这两个数的差 ----
        long apiCount = countOf(count(me.token, content));
        assertThat(oracleRecordCount(content)).as("DB 里是 2 条记录（一人进两夹）").isEqualTo(2L);
        assertThat(oracleDistinctUserCount(content)).as("独立 oracle：按人去重后是 1 人").isEqualTo(1L);
        assertThat(apiCount).as("★ 接口必须与 oracle 的 DISTINCT 口径一致（=1，不是 2）").isEqualTo(1L);
        assertThat(apiCount)
                .as("★ 显式对照 COUNT(*)：若实现漏了 DISTINCT，这里会是 2 —— 这条断言即注入点的判别力")
                .isEqualTo(oracleDistinctUserCount(content))
                .isNotEqualTo(oracleRecordCount(content));
    }

    @Test
    @DisplayName("★两人各收藏同一内容：count = 2；各自 status 只显示**自己的**夹（跨用户隔离）")
    void 两人收藏计数加一且状态互不影响() {
        TestUser a = register("13800003003", "甲");
        TestUser b = register("13800003004", "乙");
        TestUser c = register("13800003005", "丙没收藏");
        long content = insertContent(a.id);
        long folderA = insertFolder(a.id, "甲的夹");
        long folderB = insertFolder(b.id, "乙的夹");
        insertItem(folderA, a.id, content);
        insertItem(folderB, b.id, content);

        assertThat(countOf(count(a.token, content))).as("两个不同的人 ⇒ 2").isEqualTo(2L);
        assertThat(countOf(count(a.token, content))).isEqualTo(oracleDistinctUserCount(content));

        // ★ 隔离：甲的状态只含甲的夹，乙的只含乙的，丙的空
        assertThat(names(Envelope.data(status(a.token, content)).path("folders")))
                .as("★ 状态判据是 (当前用户, 内容)；去掉 user_id 条件会把乙的夹混进来")
                .containsExactly("甲的夹");
        assertThat(names(Envelope.data(status(b.token, content)).path("folders")))
                .containsExactly("乙的夹");
        assertThat(Envelope.data(status(c.token, content)).path("folders").size())
                .as("丙没收藏 ⇒ 空（别人的收藏不算我的）").isZero();
    }

    // ========================================================================
    // 3. 移出后回退
    // ========================================================================

    @Test
    @DisplayName("★依次移出：移出一个夹后仍显示另一个夹、count 不变；全移出后 count 回 0、status 回 {false,[]}")
    void 全部移出后回到未收藏() {
        TestUser me = register("13800003006", "清理者");
        long content = insertContent(me.id);
        long folderA = insertFolder(me.id, "夹A");
        long folderB = insertFolder(me.id, "夹B");
        insertItem(folderA, me.id, content);
        insertItem(folderB, me.id, content);

        // 移出 A：B 里还有 ⇒ 仍"已收藏"，且 count 仍是 1（同一人还没绝迹）
        jdbcTemplate.update("DELETE FROM favorite_item WHERE folder_id = ? AND content_id = ?", folderA, content);
        JsonNode afterA = Envelope.data(status(me.token, content));
        assertThat(afterA.path("isFavorited").asBoolean()).isTrue();
        assertThat(names(afterA.path("folders"))).containsExactly("夹B");
        assertThat(countOf(count(me.token, content)))
                .as("同一人还在（别的夹里）⇒ 收藏数不变，仍是 1").isEqualTo(1L);

        // 全移出：回到未收藏
        jdbcTemplate.update("DELETE FROM favorite_item WHERE folder_id = ? AND content_id = ?", folderB, content);
        JsonNode empty = Envelope.data(status(me.token, content));
        assertThat(empty.path("isFavorited").asBoolean()).isFalse();
        assertThat(empty.path("folders").size()).isZero();
        assertThat(countOf(count(me.token, content))).as("全移出后回 0").isZero();
        assertThat(oracleDistinctUserCount(content)).isZero();
    }

    // ========================================================================
    // 4. 失效内容（R-07③）
    // ========================================================================

    @Test
    @DisplayName("★失效内容：收藏数**仍计入**（R-07③）；status 仍显示它所在的夹（记录还在，脱敏归 T6）")
    void 失效内容计数仍算状态仍显示() {
        TestUser me = register("13800003007", "收着失效内容的人");
        long softDeleted = insertContentWithState(me.id, 1);   // 1 = 作者删除
        long folder = insertFolder(me.id, "含失效的夹");
        insertItem(folder, me.id, softDeleted);                 // 软删内容
        insertItem(folder, me.id, GHOST_CONTENT_ID);            // 内容行真没了

        assertThat(oracleDistinctUserCount(softDeleted))
                .as("独立 oracle：软删内容的记录仍在表里").isEqualTo(1L);
        assertThat(countOf(count(me.token, softDeleted)))
                .as("★ 失效内容的收藏数仍算（R-07③）—— 收藏数不感知内容有效性").isEqualTo(1L);
        assertThat(countOf(count(me.token, softDeleted))).isEqualTo(oracleDistinctUserCount(softDeleted));

        JsonNode data = Envelope.data(status(me.token, softDeleted));
        assertThat(data.path("isFavorited").asBoolean())
                .as("★ 失效内容我仍'收着它'（记录没删，R-03 不自动清理）").isTrue();
        assertThat(names(data.path("folders")))
                .as("状态只答'收在哪些夹'，不判内容有效性（T6 才在内容侧渲染脱敏占位）")
                .containsExactly("含失效的夹");

        // 幽灵内容同理
        assertThat(countOf(count(me.token, GHOST_CONTENT_ID)))
                .as("幽灵 id（内容行不存在）的记录同样计入").isEqualTo(oracleDistinctUserCount(GHOST_CONTENT_ID));
    }

    // ========================================================================
    // 5. 鉴权口径（两类端点、两个信任边界）
    // ========================================================================

    @Test
    @DisplayName("★鉴权：status 未登录→401（坏 token 也 401）；count 匿名 / 坏 token 均 200（公开展示数）")
    void 状态需登录计数匿名可访问() {
        TestUser me = register("13800003008", "鉴权用户");
        long content = insertContent(me.id);
        long folder = insertFolder(me.id, "夹");
        insertItem(folder, me.id, content);

        // status：私有状态，必须登录
        assertThat(status(null, content).getStatusCode().value())
                .as("未登录不得看到'我收在哪些夹'").isEqualTo(401);
        assertThat(get("/favorite/status?contentId=" + content, "not-a-jwt").getStatusCode().value())
                .as("坏 token 对需登录端点 ⇒ 401").isEqualTo(401);
        assertThat(status(me.token, content).getStatusCode().value()).as("合法登录 ⇒ 200").isEqualTo(200);

        // count：公开展示数，匿名可访问（★ 与 /like/content/count 的"需登录"刻意不同）
        ResponseEntity<String> anon = count(null, content);
        assertThat(anon.getStatusCode().value())
                .as("收藏数是公开展示数（需求篇 §三），内容页匿名可看 ⇒ 匿名必须 200").isEqualTo(200);
        assertThat(countOf(anon)).as("匿名拿到的值要和登录用户一致").isEqualTo(oracleDistinctUserCount(content));
        assertThat(get("/favorite/count?contentId=" + content, "not-a-jwt").getStatusCode().value())
                .as("公开端点收到坏 token ⇒ 按匿名放行（见 JwtAuthFilter）").isEqualTo(200);
    }

    // ========================================================================
    // 6. 形状与参数契约
    // ========================================================================

    @Test
    @DisplayName("★形状契约：status.data 恰好 {isFavorited, folders}；folders 元素恰好 {id, name}")
    void 状态形状契约() {
        TestUser me = register("13800003009", "形状用户");
        long content = insertContent(me.id);
        long folder = insertFolder(me.id, "唯一夹");
        insertItem(folder, me.id, content);

        JsonNode data = Envelope.data(status(me.token, content));
        assertThat(data.propertyNames())
                .as("status 载荷字段集一次性冻结（多一个键 = 把无关信息带出去）")
                .containsExactlyInAnyOrder("isFavorited", "folders");
        assertThat(data.path("folders").size()).isEqualTo(1);
        assertThat(data.path("folders").get(0).propertyNames())
                .as("条目只给 id（操作定位）+ name（显示）；不含 itemCount / isPrivate / isDefault")
                .containsExactlyInAnyOrder("id", "name");
        assertThat(data.path("folders").get(0).path("name").asString()).isEqualTo("唯一夹");
    }

    @Test
    @DisplayName("参数：缺 contentId → 400（两个端点）；contentId 不存在 ⇒ status 未收藏 / count 0（**不 404**）")
    void 参数缺失400与不存在内容不404() {
        TestUser me = register("13800003010", "参数用户");

        // 缺参：status 需登录，故必须带 token 才会走到参数校验（否则先 401）
        assertThat(get("/favorite/status", me.token).getStatusCode().value()).as("status 缺 contentId").isEqualTo(400);
        assertThat(get("/favorite/count", null).getStatusCode().value()).as("count 缺 contentId（匿名也要 400）").isEqualTo(400);

        // 不存在的 contentId：两条端点都给"未收藏 / 0"，不 404（不给内容存在性开探测口）
        long nonexistent = 987654321L;
        ResponseEntity<String> statusResp = status(me.token, nonexistent);
        assertThat(statusResp.getStatusCode().value()).as("找不到 ≠ 报错；未收藏是合法状态").isEqualTo(200);
        assertThat(Envelope.data(statusResp).path("isFavorited").asBoolean()).isFalse();
        assertThat(countOf(count(null, nonexistent))).isZero();
    }

    // ========================================================================
    // HTTP
    // ========================================================================

    private ResponseEntity<String> status(String token, long contentId) {
        return get("/favorite/status?contentId=" + contentId, token);
    }

    private ResponseEntity<String> count(String token, long contentId) {
        return get("/favorite/count?contentId=" + contentId, token);
    }

    /** count 端点的 {@code data} 是**整数**（直接取整个 data 节点）。 */
    private static long countOf(ResponseEntity<String> response) {
        return Envelope.data(response).asLong();
    }

    private static List<String> names(JsonNode folders) {
        List<String> names = new ArrayList<>();
        for (JsonNode node : folders) {
            names.add(node.path("name").asString());
        }
        return names;
    }

    private static List<Long> ids(JsonNode folders) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : folders) {
            ids.add(node.path("id").asLong());
        }
        return ids;
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

    /** ★ 独立 oracle：按人去重的收藏数（数的是"人"，与接口口径同源但**独立复算**）。 */
    private long oracleDistinctUserCount(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM favorite_item WHERE content_id = ?", Long.class, contentId);
        return n == null ? 0L : n;
    }

    /** ★ 独立 oracle：原始记录条数（与上面的差 = "去重"的判别力）。 */
    private long oracleRecordCount(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_item WHERE content_id = ?", Long.class, contentId);
        return n == null ? 0L : n;
    }

    private long insertContent(long authorId) {
        return insertContentRow(authorId, 0);
    }

    private long insertContentWithState(long authorId, int isDeleted) {
        return insertContentRow(authorId, isDeleted);
    }

    private long insertContentRow(long authorId, int isDeleted) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description, is_deleted) VALUES (?, 1, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setString(2, "T5 测试内容 " + authorId);
            ps.setString(3, "T5 测试描述");
            ps.setInt(4, isDeleted);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    private long insertFolder(long userId, String name) {
        return insertFolderRow(userId, name, 0);
    }

    private long insertDefaultFolder(long userId, String name) {
        return insertFolderRow(userId, name, 1);
    }

    private long insertFolderRow(long userId, String name, int isDefault) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO favorite_folder (user_id, name, is_default) VALUES (?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, name);
            ps.setInt(3, isDefault);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("favorite_folder 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    /** 直接插收藏记录（夹具）；{@code contentId} 不校验 —— 失效形态正是本类的被测对象。 */
    private void insertItem(long folderId, long userId, long contentId) {
        jdbcTemplate.update("INSERT INTO favorite_item (folder_id, user_id, content_id) VALUES (?, ?, ?)",
                folderId, userId, contentId);
    }
}
