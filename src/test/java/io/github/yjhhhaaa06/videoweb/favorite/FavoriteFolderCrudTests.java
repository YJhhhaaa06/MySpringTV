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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收藏一期 <b>T2：收藏夹 CRUD</b> 的契约测试（{@code 能力 一期-2}）。
 *
 * <h2>覆盖什么</h2>
 * <ul>
 *   <li>新建 / 改名 / 删除 / 我的夹列表四条端点（HTTP 状态 + 信封 + **DB 终态**）；</li>
 *   <li>★ 默认夹**拒绝删除**（需求篇 §二「删不掉」）—— 且夹与条目**都还在**；</li>
 *   <li>★ 删夹**一并删条目**（R-02，同事务）；</li>
 *   <li>★ 无夹时列表返回 {@code []} **而非报错**（R-01 懒建 ⇒ 空态合法）；</li>
 *   <li>★ 每夹条目数用**独立 oracle 复算**，且**含失效内容的记录**（R-07）；</li>
 *   <li>归属（403）/ 不存在（404）/ 名称非法（400）/ 未登录（401）四条错误线。</li>
 * </ul>
 *
 * <h2>★ 为什么每夹条目数不能信接口回显</h2>
 * 接口回显（{@code itemCount}）与被复算对象（{@code COUNT(*) FROM favorite_item WHERE folder_id = ?}）
 * 都来自 DB，看似"再查一遍没意义"。它的意义是**挡住一类具体错**：把计数写成"按内容去重"、
 * "只数还活着的内容"（{@code JOIN content}）、或者"数了别的夹/{@code GROUP BY} 写漏列"。
 * 例如 {@code COUNT(DISTINCT content_id)} 与 {@code COUNT(*)} 在本用例里会给出**不同的数**
 * （夹具刻意让同一夹里出现重复内容 id 之外的差异），故它是有判别力的复算。
 *
 * <h2>★ 期望值怎么来的（规避"迎合型伪测试"）</h2>
 * 每个断言的期望值都出自**业务口径**而非实现：条目数出自 R-07「失效也计入、数的是记录」；
 * 默认夹拒删出自需求篇 §二；空态出自 R-01；403/404 出自
 * {@code CommentService}/{@code ContentService} 的既有归属口径。盖住实现这些用例**照样写得出来**。
 *
 * <h2>反向验证（T8 收口会复核）</h2>
 * <ul>
 *   <li>去掉 {@code FavoriteService.deleteFolder} 的 {@code is_default} 判断 ⇒ 默认夹拒删用例变红；</li>
 *   <li>把 {@code LEFT JOIN favorite_item} 改成 {@code INNER JOIN} ⇒ 空夹计数用例变红（那一行会消失）；</li>
 *   <li>把 {@code COUNT(i.id)} 改成 {@code COUNT(DISTINCT content_id)} ⇒ 条目数用例变红；</li>
 *   <li>删掉 {@code @RequiresLogin} ⇒ 未登录用例与 {@code SecurityContractTests} 变红。</li>
 * </ul>
 */
class FavoriteFolderCrudTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    /** 夹具用的"内容 id"，刻意**不**在 {@code content} 表里插入对应行（模拟"内容已失效"，R-07）。 */
    private static final long GHOST_CONTENT_ID = 990001L;

    @BeforeEach
    void 清空本域相关表() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        // 无外键，删除顺序只为可读。
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ========================================================================
    // 1. 新建
    // ========================================================================

    @Test
    @DisplayName("新建夹：200 + 落库为**自建夹**（is_default=0 / is_private=0），并出现在我的列表里")
    void 新建夹落库为自建夹并出现在列表() {
        TestUser me = register("13800001001", "夹主甲");

        ResponseEntity<String> resp = addFolder("稍后再看", me.token);

        assertThat(resp.getStatusCode().value()).as("新建夹成功").isEqualTo(200);
        assertThat(Envelope.code(resp)).isEqualTo(200);
        // ⚠️ data 是**纯字符串**，故取整个 data 节点再 asString()；Envelope.str 是给对象型 data 用的
        assertThat(Envelope.data(resp).asString()).as("写端点的 data 是中文文案（同 /like/add、/follow/add）")
                .isEqualTo("创建成功");

        // 独立 oracle：直查 DB 终态（不信接口回显）
        List<FolderRow> rows = folderRows(me.id);
        assertThat(rows).as("接口成功 ⇒ 库里必须真有一行").hasSize(1);
        FolderRow row = rows.get(0);
        assertThat(row.userId).isEqualTo(me.id);
        assertThat(row.name).isEqualTo("稍后再看");
        assertThat(row.isDefault).as("新建端点的产物**永远是自建夹**（默认夹由首次收藏懒建，R-01）")
                .isFalse();
        assertThat(row.isPrivate).as("默认公开（R-05「默认公开」是列默认值，不由入参决定）").isFalse();

        // 列表能看到它，且形状（id / name / isDefault / isPrivate / itemCount）逐字段对得上
        JsonNode item = folderOf(listFolders(me.token), row.id);
        assertThat(item.path("name").asString()).isEqualTo("稍后再看");
        assertThat(item.path("isDefault").asBoolean()).isFalse();
        assertThat(item.path("isPrivate").asBoolean()).isFalse();
        assertThat(item.path("itemCount").asInt()).isZero();
    }

    @Test
    @DisplayName("新建夹：名称为空 / 全空白 / 缺参 → 400，且**库里不留行**")
    void 夹名空白或缺参被拒400() {
        TestUser me = register("13800001002", "夹主乙");

        assertThat(addFolder("", me.token).getStatusCode().value()).as("空串名").isEqualTo(400);
        assertThat(addFolder("   ", me.token).getStatusCode().value()).as("全空白名").isEqualTo(400);
        assertThat(postForm("/favorite/folder/add", new LinkedMultiValueMap<>(), me.token)
                .getStatusCode().value()).as("缺 name 参数（Spring 抛缺参 → 400）").isEqualTo(400);

        assertThat(folderRows(me.id)).as("被拒的请求不得留下任何行（400 必须发生在写库之前）").isEmpty();
    }

    @Test
    @DisplayName("新建夹：101 字符被拒 400、100 字符可建（与列 varchar(100) 对齐的边界）")
    void 夹名长度边界() {
        TestUser me = register("13800001003", "夹主丙");

        String exactly100 = "夹".repeat(100);
        assertThat(addFolder(exactly100, me.token).getStatusCode().value())
                .as("恰好 100 字符必须能建（否则先被自己拦掉）").isEqualTo(200);
        assertThat(addFolder("夹".repeat(101), me.token).getStatusCode().value())
                .as("超长必须在服务层拦成 400，而不是让 MySQL 截断/抛 DataTruncation（500）")
                .isEqualTo(400);

        assertThat(folderRows(me.id)).as("只有 100 字符那条落库").hasSize(1);
        assertThat(folderRows(me.id).get(0).name).as("落库的是原文，不被截断").isEqualTo(exactly100);
    }

    // ========================================================================
    // 2. 我的夹列表
    // ========================================================================

    @Test
    @DisplayName("★无夹时列表返回**空数组**而不是报错（R-01 懒建 ⇒ 「一个夹都没有」是合法状态）")
    void 无夹时列表返回空数组() {
        TestUser fresh = register("13800001004", "新人");

        ResponseEntity<String> resp = listFolders(fresh.token);

        assertThat(resp.getStatusCode().value()).as("空态不是错误").isEqualTo(200);
        assertThat(Envelope.code(resp)).isEqualTo(200);
        assertThat(Envelope.hasDataField(resp)).as("★ data 键必须**存在**（NON_NULL 只剥 null；空数组不是 null，必须留在信封里）")
                .isTrue();
        JsonNode data = Envelope.data(resp);
        assertThat(data.isArray()).as("data 是数组").isTrue();
        assertThat(data.size()).as("新用户没有任何夹（默认夹要等首次收藏才懒建）").isZero();
    }

    @Test
    @DisplayName("★每夹条目数 = 收藏**记录**数（独立 oracle 复算，含失效内容），且只返回自己的夹")
    void 每夹条目数按收藏记录数统计且只返回自己的夹() {
        TestUser me = register("13800001005", "多夹用户");
        TestUser other = register("13800001006", "旁人");

        long mineFull = insertFolderRow(me.id, "有内容的夹");
        long mineEmpty = insertFolderRow(me.id, "空夹");

        // 夹具：有内容的夹放 3 条记录，其中 2 条指向**不存在的内容**（模拟内容已失效/被物理清掉）
        insertItemRow(mineFull, me.id, 1001L);
        insertItemRow(mineFull, me.id, GHOST_CONTENT_ID);
        insertItemRow(mineFull, me.id, 1002L);   // ← 与上一条同夹不同内容：COUNT(*) 才能与 DISTINCT 区分
        // 旁人有一个夹、放 2 条：用来验证"只返回自己的夹"
        long otherFolder = insertFolderRow(other.id, "旁人的夹");
        insertItemRow(otherFolder, other.id, 1001L);
        insertItemRow(otherFolder, other.id, 1002L);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content WHERE id = ?", Long.class, GHOST_CONTENT_ID))
                .as("前提：该内容 id 在 content 表里确实不存在（失效条目）").isZero();

        ResponseEntity<String> resp = listFolders(me.token);
        JsonNode data = Envelope.data(resp);

        assertThat(data.size()).as("只返回「我的」两个夹，旁人的夹不得出现").isEqualTo(2);
        assertThat(folderIds(data)).as("★ 旁人的夹不在结果里（列表按 user_id 过滤）")
                .doesNotContain(otherFolder).containsExactlyInAnyOrder(mineFull, mineEmpty);

        // 独立 oracle：COUNT(*) 直查 favorite_item，**与接口回显分别计算**
        // ⚠️ 两侧都用 long 比较（asLong + oracleItemCount 返回 long）——混用 int/long 会走到
        //    Object 版 isEqualTo，变成 Integer.equals(Long) ⇒ **恒不相等**，是测试自己的坑。
        assertThat(folderOf(resp, mineFull).path("itemCount").asLong())
                .as("3 条记录 ⇒ 3；失效内容的记录照样计入（R-07），故这里**不能**按内容是否存在过滤")
                .isEqualTo(oracleItemCount(mineFull))
                .isEqualTo(3L);
        assertThat(folderOf(resp, mineEmpty).path("itemCount").asLong())
                .as("★ 空夹必须出现在列表里且计数为 0（LEFT JOIN 的意义：INNER JOIN 会让这一行整体消失）")
                .isEqualTo(oracleItemCount(mineEmpty))
                .isZero();
    }

    @Test
    @DisplayName("★列表顺序契约：默认夹置顶，其余按创建先后（id 升序）")
    void 默认夹置顶其余按创建顺序() {
        TestUser me = register("13800001007", "排序用户");
        long first = insertFolderRow(me.id, "先建的");
        long second = insertFolderRow(me.id, "后建的");
        // 默认夹**故意最后创建**（R-01 懒建的常态：先建了几个自建夹，才第一次收藏）
        long defaultFolder = insertDefaultFolderRow(me.id, "默认收藏夹");

        JsonNode data = Envelope.data(listFolders(me.token));

        assertThat(folderIds(data)).as("默认夹置顶 + 自建夹按创建序；与「默认夹一定最早建」的假设无关")
                .containsExactly(defaultFolder, first, second);
    }

    // ========================================================================
    // 3. 改名
    // ========================================================================

    @Test
    @DisplayName("改名：200 + DB 终态是新名字；默认夹**也能改**（需求篇只规定默认夹删不掉）")
    void 改名写进库且默认夹可改名() {
        TestUser me = register("13800001008", "改名用户");
        long mine = insertFolderRow(me.id, "旧名");
        long defaultFolder = insertDefaultFolderRow(me.id, "默认收藏夹");

        assertThat(renameFolder(mine, "新名", me.token).getStatusCode().value()).isEqualTo(200);
        assertThat(renameFolder(defaultFolder, "我的默认夹", me.token).getStatusCode().value())
                .as("默认夹可以改名（需求篇只禁止删除）").isEqualTo(200);

        assertThat(nameOf(mine)).isEqualTo("新名");
        assertThat(nameOf(defaultFolder)).isEqualTo("我的默认夹");

        // 改名后列表跟着变（读路径看到同一终态）
        assertThat(folderOf(listFolders(me.token), mine).path("name").asString()).isEqualTo("新名");
    }

    @Test
    @DisplayName("改名：他人的夹 403、不存在 404、空白名 400 —— 且被拒时 DB **一字未改**")
    void 改名的错误线() {
        TestUser me = register("13800001009", "我");
        TestUser other = register("13800001010", "别人");
        long others = insertFolderRow(other.id, "别人的夹");

        assertThat(renameFolder(others, "我改的", me.token).getStatusCode().value())
                .as("不是自己的夹 → 403（对齐 CommentService/ContentService 的归属口径）").isEqualTo(403);
        assertThat(renameFolder(999999L, "无所谓", me.token).getStatusCode().value())
                .as("夹不存在 → 404").isEqualTo(404);
        assertThat(renameFolder(others, "   ", me.token).getStatusCode().value())
                .as("★ 空白名 → 400，**哪怕这个夹不是我的** —— 「参数形态先于资源归属」的顺序契约"
                        + "（两类拒因互不依赖，谁先谁后必须钉死；理由见 FavoriteService.renameFolder）")
                .isEqualTo(400);

        assertThat(nameOf(others)).as("三次被拒都必须**不产生任何写入**").isEqualTo("别人的夹");
    }

    // ========================================================================
    // 4. 删除
    // ========================================================================

    @Test
    @DisplayName("★删除夹：夹与夹内条目**一并删除**（R-02），且不碰别的夹")
    void 删除夹一并删除夹内条目() {
        TestUser me = register("13800001011", "删夹用户");
        long toDelete = insertFolderRow(me.id, "待删夹");
        long keep = insertFolderRow(me.id, "保留夹");
        insertItemRow(toDelete, me.id, 2001L);
        insertItemRow(toDelete, me.id, GHOST_CONTENT_ID);   // 失效条目的记录也要被删掉
        insertItemRow(keep, me.id, 2002L);

        assertThat(removeFolder(toDelete, me.token).getStatusCode().value()).isEqualTo(200);

        // DB 终态（两条写必须同进同退）
        assertThat(folderExists(toDelete)).as("夹行已删").isFalse();
        assertThat(oracleItemCount(toDelete)).as("★ 夹内记录一并删除（R-02）—— 留孤儿会让收藏数把看不见的条目算进去")
                .isZero();
        assertThat(folderExists(keep)).as("别的夹不受影响").isTrue();
        assertThat(oracleItemCount(keep)).as("别的夹的条目不受影响").isEqualTo(1);
    }

    @Test
    @DisplayName("★默认夹拒绝删除（409）：夹与条目**都原样保留**")
    void 默认夹拒绝删除() {
        TestUser me = register("13800001012", "默认夹用户");
        long defaultFolder = insertDefaultFolderRow(me.id, "默认收藏夹");
        long custom = insertFolderRow(me.id, "自建夹");
        insertItemRow(defaultFolder, me.id, 3001L);

        ResponseEntity<String> resp = removeFolder(defaultFolder, me.token);

        assertThat(resp.getStatusCode().value()).as("默认夹删不掉（需求篇 §二）").isEqualTo(409);
        assertThat(Envelope.code(resp)).isEqualTo(409);
        assertThat(folderExists(defaultFolder)).as("★ 409 之后夹必须还在").isTrue();
        assertThat(oracleItemCount(defaultFolder)).as("★ 夹内条目也不得被动过（拒删必须发生在删条目之前）")
                .isEqualTo(1);

        // 反向对照：同一次会话里删**自建夹**是成功的 —— 排除"整个删除端点都坏了"导致的假绿
        assertThat(removeFolder(custom, me.token).getStatusCode().value()).isEqualTo(200);
        assertThat(folderExists(custom)).isFalse();
    }

    @Test
    @DisplayName("删除：他人的夹 403、不存在 404 —— 且夹**依然在**")
    void 删除的错误线() {
        TestUser me = register("13800001013", "我删");
        TestUser other = register("13800001014", "别人存");
        long others = insertFolderRow(other.id, "别人的夹");
        insertItemRow(others, other.id, 4001L);

        assertThat(removeFolder(others, me.token).getStatusCode().value()).isEqualTo(403);
        assertThat(removeFolder(999999L, me.token).getStatusCode().value()).isEqualTo(404);

        assertThat(folderExists(others)).as("被拒的删除不得动别人的数据").isTrue();
        assertThat(oracleItemCount(others)).as("别人的条目也不得被带走").isEqualTo(1);
    }

    // ========================================================================
    // 5. 鉴权（HTTP 侧覆盖；机制侧由 SecurityContractTests 直接断言 RequestMappingLookup）
    // ========================================================================

    @Test
    @DisplayName("未登录：四个端点一律 401（收藏域**不能**用类级 @RequiresLogin，见 SecurityContractTests）")
    void 未登录一律401() {
        assertThat(addFolder("x", null).getStatusCode().value()).isEqualTo(401);
        assertThat(renameFolder(1L, "x", null).getStatusCode().value()).isEqualTo(401);
        assertThat(removeFolder(1L, null).getStatusCode().value()).isEqualTo(401);
        assertThat(listFolders(null).getStatusCode().value()).isEqualTo(401);
    }

    // ========================================================================
    // HTTP
    // ========================================================================

    private ResponseEntity<String> addFolder(String name, String token) {
        return postForm("/favorite/folder/add", form("name", name), token);
    }

    private ResponseEntity<String> renameFolder(long folderId, String name, String token) {
        MultiValueMap<String, String> f = form("folderId", folderId);
        f.add("name", name);
        return postForm("/favorite/folder/update", f, token);
    }

    private ResponseEntity<String> removeFolder(long folderId, String token) {
        return postForm("/favorite/folder/remove", form("folderId", folderId), token);
    }

    private ResponseEntity<String> listFolders(String token) {
        return get("/favorite/folder/list", token);
    }

    /** POST + {@code x-www-form-urlencoded}（收藏域的写形态，对齐 /like/* 与 /follow/*）。 */
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

    private static MultiValueMap<String, String> form(String name, String value) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(name, value);
        return form;
    }

    private static MultiValueMap<String, String> form(String name, long value) {
        return form(name, String.valueOf(value));
    }

    // ========================================================================
    // 信封解析（data 是数组的端点，Envelope 的 str/num 取不到，故自己走树）
    // ========================================================================

    /** 从列表响应里取某个夹的条目节点；找不到直接断言失败（比返回 null 更早暴露问题）。 */
    private static JsonNode folderOf(ResponseEntity<String> listResponse, long folderId) {
        JsonNode data = Envelope.data(listResponse);
        for (JsonNode node : data) {
            if (node.path("id").asLong() == folderId) {
                return node;
            }
        }
        throw new AssertionError("列表里应含夹 " + folderId + "，实际=" + data);
    }

    private static List<Long> folderIds(JsonNode data) {
        List<Long> ids = new java.util.ArrayList<>();
        for (JsonNode node : data) {
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

    private record FolderRow(long id, long userId, String name, boolean isDefault, boolean isPrivate) {
    }

    /** 某用户的全部夹（直查 DB）。 */
    private List<FolderRow> folderRows(long userId) {
        return jdbcTemplate.query(
                "SELECT id, user_id, name, is_default, is_private FROM favorite_folder "
                        + "WHERE user_id = ? ORDER BY id",
                (rs, i) -> new FolderRow(rs.getLong("id"), rs.getLong("user_id"), rs.getString("name"),
                        rs.getBoolean("is_default"), rs.getBoolean("is_private")),
                userId);
    }

    /** ★ 独立 oracle：夹内**收藏记录**数（不读接口回显、不读缓存）。 */
    private long oracleItemCount(long folderId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_item WHERE folder_id = ?", Long.class, folderId);
        return n == null ? 0L : n;
    }

    private boolean folderExists(long folderId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_folder WHERE id = ?", Long.class, folderId);
        return n != null && n > 0;
    }

    private String nameOf(long folderId) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM favorite_folder WHERE id = ?", String.class, folderId);
    }

    /** 直接插自建夹（夹具；走接口建夹的地方一律用 {@link #addFolder}）。 */
    private long insertFolderRow(long userId, String name) {
        return insertFolder(userId, name, 0);
    }

    /** 直接插**默认夹**（夹具）：一期没有"建默认夹"的端点（懒建落点在 T4），故只能用 DB 造前提。 */
    private long insertDefaultFolderRow(long userId, String name) {
        return insertFolder(userId, name, 1);
    }

    private long insertFolder(long userId, String name, int isDefault) {
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

    /**
     * 直接插收藏记录（夹具）。
     *
     * <p>⚠️ {@code contentId} **不校验**：夹具刻意用不存在的 id 造"内容已失效"的记录（R-07）——
     * 收藏记录表本就没有指向 content 的外键（一期口径），
     * 而"条目还在、内容没了"正是收藏夹必须容忍的状态。
     */
    private void insertItemRow(long folderId, long userId, long contentId) {
        jdbcTemplate.update("INSERT INTO favorite_item (folder_id, user_id, content_id) VALUES (?, ?, ?)",
                folderId, userId, contentId);
    }
}
