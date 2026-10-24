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
 * 收藏三期 <b>T18：他人公开夹"点开看内容"</b> 的契约测试
 * （{@code GET /api/v1/favorite-folders/{id}/items}；来源 favorite-需求.md §二，补一期 R-08 的裁剪）。
 *
 * <h2>覆盖什么（期望值全部出自业务口径 / 契约，盖住实现照样写得出来）</h2>
 * <ul>
 *   <li>★ <b>他人公开夹可点开且内容正确</b>：匿名 GET 公开夹 ⇒ 200，条目 = 该夹的
 *       {@code favorite_item} 行（独立 oracle 直查复算），且**别的夹的记录不混入**（对照）；</li>
 *   <li>★ <b>私密夹与不存在夹**同一拒绝口径**</b>：两者都 404、**文案逐字相同**
 *       （断言"两者相等"而不是"各自等于某个字面量"是本题的核心 —— 只要可区分就是缺陷）；</li>
 *   <li>★ <b>匿名可访问</b>（与 {@code /folder/public} 同款）：无 token ⇒ 200、坏 token 不 401
 *       —— 这条钉住"**绝不能**加类级 {@code @RequiresLogin}"；</li>
 *   <li>失效条目**同口径脱敏占位**（R-03）：{@code invalid=true}、标题「内容已失效」、
 *       封面 / 作者 {@code null}，且保留 {@code contentId}；{@code total} 含失效条目（R-07）；</li>
 *   <li>空公开夹 ⇒ {@code list=[]} + {@code total=0}（不 404，空态合法）；</li>
 *   <li>分页形状复用 {@code PageResult<FavoriteItemVO>}（信封 5 键 + 条目 7 键，**不新造**）；</li>
 *   <li>参数：缺 {@code folderId} ⇒ 400、非数字 ⇒ 400（缺/坏参由 Spring 参数绑定拦）。</li>
 * </ul>
 *
 * <h2>★ 期望值怎么来的（规避"迎合型伪测试"）</h2>
 * "公开夹的内容 = 该夹的收藏记录" 出自favorite-需求.md §二"点开看内容"；"私密与不存在不可区分" 出自
 * T18 拍板（2026-10-14，用户选 404 —— 理由：可区分即等于对外暴露"该夹存在且是私密的"）；
 * "匿名可访问" 出自favorite.md §三（{@code /folder/public} 的冻结契约，本条同信任边界）；
 * 脱敏占位与"total 含失效" 出自 R-03 / R-07 / G12（与 {@code /api/v1/favorites} **同一条装配**）；
 * 空态合法出自 R-01。盖住实现，这些断言照样写得出来。
 *
 * <h2>★ 反向验证（T18 验收点名的两条；逐条注入 → 确认变红 → 还原，实测记录见 T18 回写）</h2>
 * <ul>
 *   <li><b>去掉服务端的 {@code is_private = 0} 判定</b>（{@code folder == null || isPrivate}
 *       里的后一半）⇒ {@code 私密夹与不存在夹同一拒绝口径} <b>必红</b>
 *       （私密夹会变成 200；夹具特意断言"私密夹**确实存在**" ⇒ 红不是靠'夹本来就没有'）；</li>
 *   <li><b>给 {@code FavoriteController} 加类级 {@code @RequiresLogin}</b> ⇒ 本类匿名用例 + 坏 token 用例
 *       <b>变红</b>（HTTP 401），且 {@code SecurityContractTests.favorite公开夹内容端点不得判为需登录}
 *       <b>最先红</b>（机制级、最精确的信号）。</li>
 * </ul>
 *
 * <h2>独立性</h2>
 * 关键计数/成员一律**独立 oracle** 直查 {@code favorite_item}（不信接口回显）；
 * 收藏时间用 **DB 时钟**（{@code NOW() - INTERVAL n DAY}）造，不绑 JVM 时间（容器是 UTC）。
 */
class PublicFolderItemsTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    /** 失效占位标题（R-03；独立写死，不抄实现常量）。 */
    private static final String INVALID_TITLE = "内容已失效";

    /** 刻意**不**在 content 表插行的"幽灵内容 id"（内容行真没了，R-07 失效形态之一）。 */
    private static final long GHOST_CONTENT_ID = 990701L;

    @BeforeEach
    void 清空本域相关表() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        jdbcTemplate.update("DELETE FROM content_media");
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ========================================================================
    // 1. ★ 他人公开夹可点开且内容正确（+ 对照：别的夹不混入）
    // ========================================================================

    @Test
    @DisplayName("★他人公开夹可点开：匿名 200、条目 = 该夹收藏记录（独立 oracle 复算）、别的夹记录不混入")
    void 他人公开夹可点开且内容正确() {
        TestUser owner = register("13800005001", "被看的人");
        long publicFolder = insertFolder(owner.id, "公开甲", false, false);
        long noisePublicFolder = insertFolder(owner.id, "公开丙", false, false);   // 对照：同人的**另一个**公开夹
        TestUser stranger = register("13800005002", "另一个人");
        long strangersFolder = insertFolder(stranger.id, "路人的公开夹", false, false);

        long c1 = insertContent(owner.id, "正常内容1", 0);
        long c2 = insertContent(owner.id, "正常内容2", 0);
        // ★ 从旧到新插（daysAgo 3→1），于是 favorite_item.id 序与收藏时间序**反向** ——
        //   这样才分得出"按收藏时间倒序"与"按 id 序"（见 FavoriteItemListTests 的同款教训）。
        insertItem(publicFolder, owner.id, c1, 3);
        insertItem(publicFolder, owner.id, GHOST_CONTENT_ID, 1);   // 幽灵内容：应渲染占位
        insertItem(publicFolder, owner.id, c2, 2);
        // 干扰行：**别的夹**的记录不得混入（缺这个对照，去掉 SQL 的 folder_id 过滤仍可能绿）。
        insertItem(noisePublicFolder, owner.id, insertContent(owner.id, "干扰内容A", 0), 2);
        insertItem(strangersFolder, stranger.id, insertContent(stranger.id, "干扰内容B", 0), 1);

        ResponseEntity<String> resp = items(null, publicFolder, null, null);

        assertThat(resp.getStatusCode().value()).as("匿名 GET 公开夹内容必须 200").isEqualTo(200);
        JsonNode data = Envelope.data(resp);
        assertThat(data.path("total").asLong())
                .as("★ total = 该夹的 favorite_item 行数（独立 oracle 直查）")
                .isEqualTo(oracleItemCount(publicFolder));
        assertThat(data.path("total").asLong()).isEqualTo(3L);
        // ★ 全量名单 + 顺序断言：最近收藏（1 天前）在前；且**恰好**这三条（别的夹一条都不混入）
        assertThat(contentIds(data.path("list")))
                .as("★ 收藏时间倒序；且不含 公开丙 / 路人夹 的记录（缺对照 ⇒ 去掉 folder_id 过滤也绿）")
                .containsExactly(GHOST_CONTENT_ID, c2, c1);
        assertThat(oracleItemCount(noisePublicFolder)).as("对照：干扰夹确有记录（否则本断言无鉴别力）").isEqualTo(1L);

        // 正常条目的展示字段（证明装配走的是 content 装载契约，不是自持 SQL）
        JsonNode normal = requireItem(data.path("list"), c2);
        assertThat(normal.path("invalid").asBoolean()).isFalse();
        assertThat(normal.path("title").asString()).isEqualTo("正常内容2");
        assertThat(normal.path("authorName").asString()).as("作者来自 content 装载契约").isEqualTo("被看的人");
    }

    // ========================================================================
    // 2. ★ 私密夹与不存在夹：同一拒绝口径（不可区分）
    // ========================================================================

    @Test
    @DisplayName("★私密夹与不存在夹**同一拒绝口径**：都是 404 且文案逐字相同（不可区分才是重点）")
    void 私密夹与不存在夹同一拒绝口径() {
        TestUser owner = register("13800005003", "有私密夹的人");
        long privateFolder = insertFolder(owner.id, "私密乙", false, true);
        insertItem(privateFolder, owner.id, insertContent(owner.id, "私密里的内容", 0), 1);

        // ★ 夹 **确实存在**（这是判别力的前提）：没有它，"私密夹 404" 与 "夹本来就不存在"
        //   在断言上无法区分 —— 去掉 is_private 判定后本用例也照样绿（假绿）。
        assertThat(folderExists(privateFolder)).as("★ 对照组：私密夹必须真的存在").isTrue();

        ResponseEntity<String> privateResp = items(null, privateFolder, null, null);
        ResponseEntity<String> missingResp = items(null, 999999999L, null, null);

        assertThat(privateResp.getStatusCode().value())
                .as("私密夹 ⇒ 不得返回 200（返回内容/FK 列表都会泄露）").isEqualTo(404);
        assertThat(missingResp.getStatusCode().value()).isEqualTo(404);
        // ★ 核心：两者**逐字相同**（同码同文案）。只断"各自是 404"不够 ——
        //   若实现给私密夹换了另一条文案，探测者仍能区分"存在且私密"。
        assertThat(privateResp.getStatusCode()).as("状态码必须相等（不可区分）").isEqualTo(missingResp.getStatusCode());
        assertThat(Envelope.msg(privateResp)).as("文案必须非空（两边都 null 也'相等'，那是假绿）").isNotBlank();
        assertThat(Envelope.msg(privateResp))
                .as("★ 私密夹与不存在夹的 msg 必须**逐字相同** ⇒ 不可区分")
                .isEqualTo(Envelope.msg(missingResp));
    }

    // ========================================================================
    // 3. ★ 匿名可访问（绝不类级 @RequiresLogin）
    // ========================================================================

    @Test
    @DisplayName("★匿名可访问：无 token→200；坏 token 不 401（与 /folder/public 同信任边界）")
    void 匿名可访问不得误拦() {
        TestUser owner = register("13800005004", "公开用户");
        long folder = insertFolder(owner.id, "公开夹", false, false);
        insertItem(folder, owner.id, insertContent(owner.id, "内容", 0), 1);

        assertThat(items(null, folder, null, null).getStatusCode().value()).isEqualTo(200);

        // 坏 token：JwtAuthFilter 对"公开端点 + 坏 token"按匿名放行，不得误杀成 401
        ResponseEntity<String> badToken = get("/api/v1/favorite-folders/" + folder + "/items", "not-a-jwt");
        assertThat(badToken.getStatusCode().value())
                .as("公开端点收到坏 token ⇒ 按匿名处理（见 JwtAuthFilter 类注释）").isEqualTo(200);
        assertThat(contentIds(Envelope.data(badToken).path("list"))).hasSize(1);

        // ★ 夹主本人带合法 token 调用：**同款结果**（本端点表达的是"他人公开视角"，
        //   不因'是本人'而放行私密 —— "我的"走 /api/v1/favorites 另一条）
        assertThat(items(owner.token, folder, null, null).getStatusCode().value()).isEqualTo(200);
    }

    // ========================================================================
    // 4. 失效占位 / 空态 / 分页 / 形状（与 /api/v1/favorites 同口径，此处只钉"公开面也如此"）
    // ========================================================================

    @Test
    @DisplayName("失效条目同口径脱敏占位：invalid=true、标题「内容已失效」、封面/作者 null、保留 contentId；total 含它")
    void 失效条目脱敏占位() {
        TestUser owner = register("13800005005", "含失效的夹主");
        long folder = insertFolder(owner.id, "含失效", false, false);
        long alive = insertContent(owner.id, "正常的", 0);
        long softDeleted = insertContent(owner.id, "作者已删的", 1);
        insertItem(folder, owner.id, alive, 2);
        insertItem(folder, owner.id, softDeleted, 1);
        insertItem(folder, owner.id, GHOST_CONTENT_ID, 3);

        JsonNode data = Envelope.data(items(null, folder, null, null));
        assertThat(data.path("total").asLong()).as("total 含失效条目（R-07 / G12）").isEqualTo(3L);

        for (long invalidContent : new long[]{softDeleted, GHOST_CONTENT_ID}) {
            JsonNode item = requireItem(data.path("list"), invalidContent);
            assertThat(item.path("invalid").asBoolean()).isTrue();
            assertThat(item.path("title").asString()).as("标题位写「内容已失效」").isEqualTo(INVALID_TITLE);
            assertThat(item.path("title").asString()).as("★ 不得带原标题（R-03）").isNotEqualTo("作者已删的");
            assertThat(item.path("coverUrl").isNull()).isTrue();
            assertThat(item.path("authorName").isNull()).isTrue();
            assertThat(item.path("contentId").asLong()).as("保留 contentId").isEqualTo(invalidContent);
        }
    }

    @Test
    @DisplayName("空公开夹：list=[]、total=0、totalPages=0（**不 404**）；信封是分页形状")
    void 空公开夹返回空页而非404() {
        TestUser owner = register("13800005006", "空夹主");
        long folder = insertFolder(owner.id, "空公开夹", false, false);

        ResponseEntity<String> resp = items(null, folder, null, null);
        assertThat(resp.getStatusCode().value()).as("空夹是合法状态，不 404").isEqualTo(200);
        JsonNode data = Envelope.data(resp);
        assertThat(data.path("list").isArray()).as("空态是空数组，不是 null / 缺键").isTrue();
        assertThat(data.path("list").size()).isZero();
        assertThat(data.path("total").asLong()).isZero();
        assertThat(data.path("pageSize").asLong()).as("缺省信封 = 100").isEqualTo(100L);
        assertThat(data.path("totalPages").asLong()).isZero();
    }

    @Test
    @DisplayName("分页 + 形状：pageSize 生效、信封 5 键、条目 7 键（复用 PageResult<FavoriteItemVO>，不新造）")
    void 分页与形状契约() {
        TestUser owner = register("13800005007", "分页夹主");
        long folder = insertFolder(owner.id, "分页公开夹", false, false);
        for (int i = 4; i >= 1; i--) {
            insertItem(folder, owner.id, insertContent(owner.id, "内容" + i, 0), i);
        }

        JsonNode page1 = Envelope.data(items(null, folder, "1", "2"));
        assertThat(page1.path("total").asLong()).as("与独立 oracle 一致").isEqualTo(oracleItemCount(folder)).isEqualTo(4L);
        assertThat(page1.path("list").size()).as("pageSize=2 ⇒ 第 1 页 2 条").isEqualTo(2);
        assertThat(page1.path("totalPages").asLong()).isEqualTo(2L);

        assertThat(page1.propertyNames())
                .as("分页信封字段集一次性冻结（复用 PageResult，T18 红线④ 不新造形状）")
                .containsExactlyInAnyOrder("list", "total", "page", "pageSize", "totalPages");
        assertThat(page1.path("list").get(0).propertyNames())
                .as("条目形状与 /api/v1/favorites **同形**（复用 FavoriteItemVO）")
                .containsExactlyInAnyOrder("id", "contentId", "favoriteTime", "invalid",
                        "title", "coverUrl", "authorName");
    }

    @Test
    @DisplayName("参数：folderId 进路径后非数字 → 400（缺 id 已不可表达，见注释）")
    void 参数非法400() {
        // `T58` 契约变更（接口契约.md §2.3）：folderId 从 query 搬进路径 ⇒ **非法 id = 400**；
        //   **"缺 id" = 404**，但少了 id 就没有路由匹配，"缺参 400"这条旧断言已不可达 ——
        //   退役它，不用双斜杠去撞路径归一化（那测的是容器 URL 处理、不是本契约）。
        assertThat(get("/api/v1/favorite-folders/abc/items", null).getStatusCode().value())
                .as("folderId 非数字").isEqualTo(400);
    }

    // ========================================================================
    // HTTP / 信封解析
    // ========================================================================

    private ResponseEntity<String> items(String token, long folderId, String page, String pageSize) {
        // `T58` 契约变更：夹 id 进路径（GET /api/v1/favorite-folders/{id}/items），page/pageSize 仍在 query。
        StringBuilder url = new StringBuilder("/api/v1/favorite-folders/" + folderId + "/items");
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

    private static JsonNode requireItem(JsonNode list, long contentId) {
        for (JsonNode item : list) {
            if (item.path("contentId").asLong() == contentId) {
                return item;
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

    /** ★ 独立 oracle：夹内收藏记录数（数的是**记录行**，含失效）。 */
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

    /** 插入一条可装配的内容：视频行 + 封面行（列表里是正常卡片）。 */
    private long insertContent(long authorId, String title, int isDeleted) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description, is_deleted) VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setInt(2, 1);
            ps.setString(3, title);
            ps.setString(4, "T18 测试描述");
            ps.setInt(5, isDeleted);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        long contentId = key.longValue();
        jdbcTemplate.update("INSERT INTO content_media (content_id, url, type, sort) VALUES (?, ?, 1, 1)",
                contentId, "/upload/video/" + contentId + ".mp4");
        jdbcTemplate.update("INSERT INTO content_media (content_id, url, type, sort) VALUES (?, ?, 3, 0)",
                contentId, "/upload/cover/" + contentId + ".png");
        return contentId;
    }

    /** 插一条收藏记录，收藏时间用 DB 时钟造（{@code NOW() - INTERVAL n DAY}，容器 UTC）。 */
    private void insertItem(long folderId, long userId, long contentId, int daysAgo) {
        jdbcTemplate.update(
                "INSERT INTO favorite_item (folder_id, user_id, content_id, create_time) "
                        + "VALUES (?, ?, ?, NOW() - INTERVAL ? DAY)",
                folderId, userId, contentId, daysAgo);
    }

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
}
