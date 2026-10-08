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
 * 收藏一期 <b>T3：私密开关 + 他人公开夹端点</b> 的契约测试（{@code 能力 一期-3} / R-05 / R-08）。
 *
 * <h2>覆盖什么</h2>
 * <ul>
 *   <li>★ {@code POST /favorite/folder/update} 升级为**部分更新**（给了才改；T2 交接①）、
 *       私密开关落库（DB 终态 oracle）、空更新 400、{@code isPrivate} 取值与非法格式 400；</li>
 *   <li>★ {@code GET /favorite/folder/public?userId=X} —— **私密"一期"的全部可观察行为**：
 *       只返回该用户的公开夹（私密不出现、别人的不出现）、匿名可访问、条目形状与顺序契约；</li>
 *   <li>400 **先于** 403/404 的顺序（T2 钉死的口径在加可选字段后不许打乱，T2 交接②）；</li>
 *   <li>默认夹可设私密（需求篇只规定"默认夹删不掉"）。</li>
 * </ul>
 *
 * <h2>★ 为什么"私密"的所有断言都落在公开端点上</h2>
 * 设私密后，{@code /folder/list}（我的）**行为零变化**（含私密是自己的权利）；
 * 所以"设了私密"与"没设"在本域内部**没有任何可观察差异**，全部判别力集中在
 * {@code /folder/public} 的"私密不出现"上（R-08 的原话："这就是'私密'一期的可观察落点"）。
 *
 * <h2>★ 期望值怎么来的（规避"迎合型伪测试"）</h2>
 * 私密过滤出自 R-08；"只给名称 + 视频数"出自 R-08/需求篇 §二；条目数口径（含失效记录、
 * 空夹留行）出自 R-07/G12（与 T2 同口径）；400 先于 403/404 出自 T2 已钉死的顺序契约；
 * 空态合法出自 R-01。盖住实现，这些断言**照样写得出来**。
 *
 * <h2>★ 反向验证（T3 验收要求；T8 收口复核）—— 结果见 {@code temp-script/t3_injections.sh} 的运行记录</h2>
 * <ul>
 *   <li><b>去掉公开 SQL 的 {@code AND f.is_private = 0}</b> ⇒ 公开端点用例变红
 *       （这正是 T3 验收点名的注入点）；</li>
 *   <li><b>让 service 把 isPrivate 恒当 null 传下去</b>（等价于"开关没接上"）⇒ 私密开关用例变红；</li>
 *   <li><b>去掉"两个都没给 ⇒ 400"的判断</b> ⇒ 空更新用例变红。</li>
 * </ul>
 *
 * <h2>覆盖不到的机制（诚实声明）</h2>
 * 公开端点"无 {@code @RequiresLogin} / 无 {@code @CurrentUserId}"的**机制级**判别力在
 * {@code SecurityContractTests.favorite公开端点不得判为需登录}（HTTP 侧"匿名 200"对
 * '有没有误标 @RequiresLogin' 其实是**弱断言**：本端点不收 {@code @CurrentUserId}，
 * 误标类级注解时才会 401，而"误标"的完整探测交给机制级用例）。
 */
class FavoritePrivacyTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    /** 夹具用的"内容 id"，刻意**不**在 content 表插行（模拟内容已失效，R-07 照算口径用）。 */
    private static final long GHOST_CONTENT_ID = 990101L;

    @BeforeEach
    void 清空本域相关表() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ========================================================================
    // 1. 私密开关（update 端点的部分更新形态）
    // ========================================================================

    @Test
    @DisplayName("★设为私密：200 + DB 终态 is_private=1；「我的列表」仍含它且 isPrivate=true（正向断言）")
    void 设为私密落库且我的列表仍可见() {
        TestUser me = register("13800002001", "私密用户");
        long folder = insertFolder(me.id, "要隐藏的夹", false, false);
        insertItem(folder, me.id, 1001L);
        insertItem(folder, me.id, GHOST_CONTENT_ID);

        ResponseEntity<String> resp = update(folder, me.token, fields("isPrivate", "1"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.code(resp)).isEqualTo(200);
        assertThat(Envelope.data(resp).asString()).as("写端点的 data 是中文文案（同 T2）").isEqualTo("修改成功");
        assertThat(isPrivateOf(folder)).as("独立 oracle：直查 DB 终态").isEqualTo(1);

        // 「我的」列表：私密夹必须还在（设私密不改变自己的可见性——需求篇只说"别人看不到"）
        JsonNode item = folderOf(listFolders(me.token), folder);
        assertThat(item.has("isPrivate")).as("★ 键名逐字是 isPrivate").isTrue();
        assertThat(item.path("isPrivate").asBoolean())
                .as("★ 正向断 true —— T2 只断过 false，而 path().asBoolean() 对缺失键也返回 false，"
                        + "只有 true 能证明映射链（resultMap → getter → JSON 键）整条是通的").isTrue();
        assertThat(item.path("itemCount").asLong()).as("设私密不动条目数").isEqualTo(2L);
    }

    @Test
    @DisplayName("部分更新「给了才改」：只给 name 不动私密；只给 isPrivate 不动名字；两个都给则都改")
    void 部分更新给了才改() {
        TestUser me = register("13800002002", "部分更新用户");
        long folderA = insertFolder(me.id, "原名A", false, false);
        long folderB = insertFolder(me.id, "原名B", false, true);   // 已是私密
        long folderC = insertFolder(me.id, "原名C", false, false);

        // ① 只给 name：私密状态必须原样（若实现是"读改写两个字段"，这条也绿——它证明的是**改动范围**）
        assertThat(update(folderA, me.token, fields("name", "新名A")).getStatusCode().value()).isEqualTo(200);
        assertThat(nameOf(folderA)).isEqualTo("新名A");
        assertThat(isPrivateOf(folderA)).as("没给 isPrivate ⇒ 不许动它").isZero();

        // ② 只给 isPrivate：名字必须原样
        assertThat(update(folderB, me.token, fields("isPrivate", "0")).getStatusCode().value()).isEqualTo(200);
        assertThat(isPrivateOf(folderB)).as("没给 name ⇒ 不许动它").isZero();
        assertThat(nameOf(folderB)).as("名字必须逐字原样（哪怕把「改回公开」当成一次改名都是错的）").isEqualTo("原名B");

        // ③ 两个都给：都改（且必须由**一条** SQL 完成才原子——见 DAO 注释；此处只断终态）
        assertThat(update(folderC, me.token, fields("name", "新名C", "isPrivate", "1"))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(nameOf(folderC)).isEqualTo("新名C");
        assertThat(isPrivateOf(folderC)).isEqualTo(1);
    }

    @Test
    @DisplayName("★空更新 → 400；且 400 先于 403/404（对别人的夹、不存在的夹**同样是 400**）")
    void 空更新被拒且先于归属校验() {
        TestUser me = register("13800002003", "我");
        TestUser other = register("13800002004", "别人");
        long mine = insertFolder(me.id, "我的夹", false, false);
        long others = insertFolder(other.id, "别人的夹", false, false);

        // 什么字段都没给：三个目标（自己的 / 别人的 / 不存在的）都必须 400 —— 参数形态先于资源归属
        assertThat(update(mine, me.token, new LinkedMultiValueMap<>()).getStatusCode().value())
                .as("空更新多半是调用方漏传字段的 bug，静默 200 会让它永远不被发现").isEqualTo(400);
        assertThat(update(others, me.token, new LinkedMultiValueMap<>()).getStatusCode().value())
                .as("★ 对别人的夹也必须是 400 而不是 403 —— T2 钉死的「400 判在 403 之前」顺序契约，"
                        + "加可选字段后不许打乱（T2 交接②）").isEqualTo(400);
        assertThat(update(999999L, me.token, new LinkedMultiValueMap<>()).getStatusCode().value())
                .as("对不存在的夹同样是 400（参数校验不碰库）").isEqualTo(400);

        // 被拒后 DB 一字未改
        assertThat(nameOf(mine)).isEqualTo("我的夹");
        assertThat(isPrivateOf(mine)).isZero();
        assertThat(nameOf(others)).as("别人的夹不得被动过").isEqualTo("别人的夹");
    }

    @Test
    @DisplayName("isPrivate 取值：1/true/0/false 均可（大小写不敏感）；非法值（含空串）→ 400 且 DB 不变")
    void 私密开关取值与非法格式() {
        TestUser me = register("13800002005", "取值用户");
        long folder = insertFolder(me.id, "开关夹", false, false);

        // 四个合法写法（大小写不敏感）——与 /content/commentEnabled 同一值集
        assertThat(update(folder, me.token, fields("isPrivate", "true")).getStatusCode().value()).isEqualTo(200);
        assertThat(isPrivateOf(folder)).isEqualTo(1);
        assertThat(update(folder, me.token, fields("isPrivate", "FALSE")).getStatusCode().value()).isEqualTo(200);
        assertThat(isPrivateOf(folder)).as("大写 FALSE 也要认").isZero();
        assertThat(update(folder, me.token, fields("isPrivate", "1")).getStatusCode().value()).isEqualTo(200);
        assertThat(update(folder, me.token, fields("isPrivate", "0")).getStatusCode().value()).isEqualTo(200);
        assertThat(isPrivateOf(folder)).isZero();

        // 非法值：给了就必须合法 —— "空串"也算给了（前端传空字段是 bug，不能悄悄当没给）
        assertThat(update(folder, me.token, fields("isPrivate", "maybe")).getStatusCode().value()).isEqualTo(400);
        assertThat(update(folder, me.token, fields("isPrivate", "")).getStatusCode().value()).isEqualTo(400);
        assertThat(update(folder, me.token, fields("isPrivate", "2")).getStatusCode().value())
                .as("值域只有 0/1（+true/false），2 不是'真值'").isEqualTo(400);
        assertThat(update(folder, me.token, fields("name", "好名字", "isPrivate", "maybe"))
                .getStatusCode().value()).as("非法 isPrivate 不能因为同时给了合法 name 就被放过").isEqualTo(400);

        assertThat(isPrivateOf(folder)).as("全部被拒 ⇒ DB 保持最后一次成功的值（0）").isZero();
        assertThat(nameOf(folder)).as("非法请求不得改名").isEqualTo("开关夹");
    }

    @Test
    @DisplayName("★默认夹也能设为私密（需求篇只规定「默认夹删不掉」，没有「默认夹不能私密」）")
    void 默认夹可设私密() {
        TestUser me = register("13800002006", "默认夹用户");
        long defaultFolder = insertDefaultFolder(me.id, "默认收藏夹");

        assertThat(update(defaultFolder, me.token, fields("isPrivate", "1")).getStatusCode().value())
                .as("不写针对 is_default 的特判 —— B 站口径：默认夹可设私密").isEqualTo(200);
        assertThat(isPrivateOf(defaultFolder)).isEqualTo(1);
        // 反向对照：默认夹**仍然**删不掉（is_private 不参与删除判定 —— 它是载荷字段，不是权限）
        assertThat(removeFolder(defaultFolder, me.token).getStatusCode().value()).isEqualTo(409);
    }

    // ========================================================================
    // 2. 他人视角公开夹端点（"私密"的全部可观察行为）
    // ========================================================================

    @Test
    @DisplayName("★公开端点：只返回该用户的公开夹（私密不出现、别人的不出现）；顺序=默认夹置顶+创建序")
    void 公开端点只返回该用户的公开夹() {
        TestUser me = register("13800002007", "被看的人");
        TestUser other = register("13800002008", "路人");

        long defaultFolder = insertDefaultFolder(me.id, "默认收藏夹");      // 公开
        long publicA = insertFolder(me.id, "公开甲", false, false);
        long privateB = insertFolder(me.id, "私密乙", false, true);
        long emptyPublic = insertFolder(me.id, "空公开夹", false, false);
        long someoneElse = insertFolder(other.id, "路人的夹", false, false);

        insertItem(defaultFolder, me.id, 1001L);
        insertItem(publicA, me.id, 1002L);
        insertItem(publicA, me.id, GHOST_CONTENT_ID);   // 失效内容的记录照算（R-07 同口径）
        insertItem(privateB, me.id, 1003L);
        insertItem(someoneElse, other.id, 1004L);

        ResponseEntity<String> resp = publicFolders(me.id);

        assertThat(resp.getStatusCode().value()).as("匿名 GET 必须 200").isEqualTo(200);
        JsonNode data = Envelope.data(resp);
        assertThat(data.isArray()).isTrue();
        // ★ 全量名单断言（含顺序）：过滤/排序/串人有任何一处错都会在这里露出来
        assertThat(names(data))
                .as("默认夹置顶 + 其余按创建序；私密乙不出现；路人的夹不出现")
                .containsExactly("默认收藏夹", "公开甲", "空公开夹");
        // 独立 oracle：条目数直查 favorite_item（不信接口回显）
        assertThat(itemNamed(data, "默认收藏夹").path("itemCount").asLong()).isEqualTo(oracleItemCount(defaultFolder));
        assertThat(itemNamed(data, "公开甲").path("itemCount").asLong())
                .as("2 条记录（含失效内容的记录）—— 与「我的夹列表」同口径（R-07）")
                .isEqualTo(oracleItemCount(publicA)).isEqualTo(2L);
        assertThat(itemNamed(data, "空公开夹").path("itemCount").asLong())
                .as("空公开夹必须留行、计 0（LEFT JOIN 的意义）").isEqualTo(oracleItemCount(emptyPublic)).isZero();
    }

    @Test
    @DisplayName("★公开端点条目形状：恰好 name + itemCount 两键（R-08「一期只给名称 + 视频数」）")
    void 公开端点条目形状() {
        TestUser me = register("13800002009", "形状用户");
        long folder = insertFolder(me.id, "公开夹", false, false);

        JsonNode data = Envelope.data(publicFolders(me.id));

        assertThat(data.size()).as("本用例需非空列表才有判别力").isEqualTo(1);
        JsonNode item = data.get(0);
        assertThat(item.path("name").asString()).isEqualTo("公开夹");
        assertThat(item.path("itemCount").asLong()).isEqualTo(oracleItemCount(folder));
        // ★ 键集是契约：多一个键 = 可能把"我的夹"的信息（如 isPrivate/isDefault/id）泄露到公开面；
        //   少一个键 = 破坏"名称 + 视频数"。两端都要拦（同 FollowFlowTests "字段形状是契约"）。
        assertThat(item.propertyNames())
                .as("公开面字段集由 R-08 冻结（含 id 都不给：一期他人公开夹没有任何按 id 的消费方）")
                .containsExactlyInAnyOrder("name", "itemCount");
    }

    @Test
    @DisplayName("公开端点匿名可访问：无 token→200；坏 token 不 401；夹主本人带 token 也**只**看到公开夹")
    void 公开端点匿名与本人视角一致() {
        TestUser me = register("13800002010", "本人");
        insertFolder(me.id, "公开夹", false, false);
        long privateFolder = insertFolder(me.id, "私密夹", false, true);

        // ① 匿名
        ResponseEntity<String> anon = publicFolders(me.id);
        assertThat(anon.getStatusCode().value()).isEqualTo(200);
        assertThat(names(Envelope.data(anon))).containsExactly("公开夹");

        // ② 坏 token：JwtAuthFilter 对"公开端点 + 坏 token"按匿名放行，不得误杀成 401
        ResponseEntity<String> badToken = get("/favorite/folder/public?userId=" + me.id, "not-a-jwt");
        assertThat(badToken.getStatusCode().value())
                .as("公开端点收到坏 token ⇒ 按匿名处理（见 JwtAuthFilter 类注释）").isEqualTo(200);
        assertThat(names(Envelope.data(badToken))).containsExactly("公开夹");

        // ③ ★ 夹主本人带合法 token 调用：**同款结果**（"他人视角"端点不因'是本人'而放行私密——
        //    刻意不做这条分支，分支写错即泄露；"我的"走 /folder/list 另一条）
        ResponseEntity<String> self = publicFolders(me.id, me.token);
        assertThat(self.getStatusCode().value()).isEqualTo(200);
        assertThat(names(Envelope.data(self)))
                .as("本人视角也只看得到公开夹（另一条 /folder/list 才含私密）").containsExactly("公开夹");

        // 反向对照：本人的 /folder/list **确实**含那个私密夹（排除"私密夹根本没建出来"的假绿）
        assertThat(names(Envelope.data(listFolders(me.token))))
                .containsExactlyInAnyOrder("公开夹", "私密夹");
        assertThat(folderExists(privateFolder)).isTrue();
    }

    @Test
    @DisplayName("公开端点空态：用户不存在 / 用户只有私密夹 → 200 + []（不是 404，不给存在性探测口）")
    void 公开端点空态不报错() {
        TestUser me = register("13800002011", "只有私密夹的人");
        insertFolder(me.id, "私密夹", false, true);

        ResponseEntity<String> noSuchUser = publicFolders(999999L);
        assertThat(noSuchUser.getStatusCode().value())
                .as("不校验 userId 存在性 —— 404 会给'这个用户存不存在'开探测口；空态是合法状态（同 R-01）")
                .isEqualTo(200);
        assertThat(Envelope.hasDataField(noSuchUser)).as("data 键必须存在（NON_NULL 只剥 null；空数组要留在信封里）")
                .isTrue();
        assertThat(Envelope.data(noSuchUser).size()).isZero();

        ResponseEntity<String> allPrivate = publicFolders(me.id);
        assertThat(allPrivate.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.data(allPrivate).size())
                .as("有夹但全是私密 ⇒ 也是空列表（与'一个夹都没有'同形）").isZero();
    }

    @Test
    @DisplayName("公开端点参数：缺 userId → 400；userId 非数字 → 400（缺参/坏参由参数绑定交给全局出口）")
    void 公开端点参数非法400() {
        assertThat(get("/favorite/folder/public", null).getStatusCode().value()).as("缺 userId").isEqualTo(400);
        assertThat(publicFoldersRaw("abc").getStatusCode().value()).as("userId 非数字").isEqualTo(400);
    }

    // ========================================================================
    // HTTP
    // ========================================================================

    private ResponseEntity<String> update(long folderId, String token, MultiValueMap<String, String> extraFields) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("folderId", String.valueOf(folderId));
        form.addAll(extraFields);
        return postForm("/favorite/folder/update", form, token);
    }

    private ResponseEntity<String> removeFolder(long folderId, String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("folderId", String.valueOf(folderId));
        return postForm("/favorite/folder/remove", form, token);
    }

    private ResponseEntity<String> listFolders(String token) {
        return get("/favorite/folder/list", token);
    }

    private ResponseEntity<String> publicFolders(long userId) {
        return get("/favorite/folder/public?userId=" + userId, null);
    }

    private ResponseEntity<String> publicFolders(long userId, String token) {
        return get("/favorite/folder/public?userId=" + userId, token);
    }

    private ResponseEntity<String> publicFoldersRaw(String rawUserId) {
        return get("/favorite/folder/public?userId=" + rawUserId, null);
    }

    /** POST + {@code x-www-form-urlencoded}（收藏域的写形态，对齐 /like/*、/follow/* 与 T2 测试）。 */
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

    private static MultiValueMap<String, String> fields(String... keyValuePairs) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            form.add(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return form;
    }

    // ========================================================================
    // 信封解析
    // ========================================================================

    /** 列表响应里的条目名（按响应顺序）。 */
    private static List<String> names(JsonNode data) {
        List<String> names = new ArrayList<>();
        for (JsonNode node : data) {
            names.add(node.path("name").asString());
        }
        return names;
    }

    /** 从列表响应里取某个名字的条目；找不到直接失败（比返回 null 更早暴露问题）。 */
    private static JsonNode itemNamed(JsonNode data, String name) {
        for (JsonNode node : data) {
            if (name.equals(node.path("name").asString())) {
                return node;
            }
        }
        throw new AssertionError("列表里应含「" + name + "」，实际=" + data);
    }

    /** 从"我的夹列表"响应里取某个 id 的条目。 */
    private static JsonNode folderOf(ResponseEntity<String> listResponse, long folderId) {
        JsonNode data = Envelope.data(listResponse);
        for (JsonNode node : data) {
            if (node.path("id").asLong() == folderId) {
                return node;
            }
        }
        throw new AssertionError("列表里应含夹 " + folderId + "，实际=" + data);
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

    /** ★ 独立 oracle：直查 DB 的私密标志（返回 int：-1 = 行不存在）。 */
    private int isPrivateOf(long folderId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT is_private FROM favorite_folder WHERE id = ?", Integer.class, folderId);
        return value == null ? -1 : value;
    }

    private String nameOf(long folderId) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM favorite_folder WHERE id = ?", String.class, folderId);
    }

    /** ★ 独立 oracle：夹内**收藏记录**数（含失效内容的记录，与 T2 同一算法）。 */
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

    /** 直接插自建夹（夹具）；走接口建夹的地方一律用 HTTP 端点。 */
    private long insertFolder(long userId, String name, boolean isDefault, boolean isPrivate) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO favorite_folder (user_id, name, is_default, is_private) VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, name);
            ps.setInt(3, isDefault ? 1 : 0);
            ps.setInt(4, isPrivate ? 1 : 0);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("favorite_folder 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    /** 直接插默认夹（夹具）：一期没有"建默认夹"的端点（懒建落点在 T4），只能用 DB 造前提。 */
    private long insertDefaultFolder(long userId, String name) {
        return insertFolder(userId, name, true, false);
    }

    /**
     * 直接插收藏记录（夹具）。
     *
     * <p>⚠️ {@code contentId} **不校验**：夹具刻意用不存在的 id 造"内容已失效"的记录（R-07）——
     * 收藏记录表本就没有指向 content 的外键（一期口径）。
     */
    private void insertItem(long folderId, long userId, long contentId) {
        jdbcTemplate.update("INSERT INTO favorite_item (folder_id, user_id, content_id) VALUES (?, ?, ?)",
                folderId, userId, contentId);
    }
}