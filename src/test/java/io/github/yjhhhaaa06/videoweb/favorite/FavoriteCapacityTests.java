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

import java.sql.PreparedStatement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收藏三期 <b>T16：收藏夹容量上限</b> 的契约测试（速记 {@code 三期-T16}）。
 *
 * <h2>覆盖什么</h2>
 * <ul>
 *   <li><b>夹数</b>：自建夹至多 20 个（第 21 个 ⇒ 400）· ★ <b>默认夹不计入</b> ·
 *       ★ 参数形态先于状态（夹名非法 + 夹数已满 ⇒ 报的是名称）；</li>
 *   <li><b>夹内条数</b>：自建夹 500（恰好 500 可加、第 501 条 ⇒ 400）· 默认夹 5000
 *       （★ 且与自建夹**分流**：500 条的默认夹照样能加）· <b>move 撞上限 ⇒ 400</b>
 *       （不判就能"绕道 move 把夹撑爆"）；</li>
 *   <li>★ <b>顺序口径</b>：容量 400 判在"夹归属 403/404"<b>之后</b>
 *       （往他人已满的夹 add ⇒ <b>403 而非 400</b>）；</li>
 *   <li>★ <b>20 上限不拦默认夹懒建</b>（满 20 个自建夹的用户，不选夹收藏仍 ⇒ 200 且默认夹建出来）
 *       —— 这是最尖锐的一条：若被拦住就是**不可自愈**的死局（重演 I-08 的互锁）；</li>
 *   <li>★ 满夹时"重复收藏"⇒ <b>400 而非 409</b>（已知副作用的钉子，见 {@code FavoriteService.addItem}）。</li>
 * </ul>
 *
 * <h2>★ 期望值怎么来的（规避"迎合型伪测试"）</h2>
 * 三个数字出自**需求篇 §六 / 三期设计 §三**（用户 2026-10-14 拍板：5000 / 500 / 20；
 * B 站实测是 5 万 / 1000 / 99 且未公示 ⇒ 本仓刻意取更小的演示值）——
 * **不从 {@code FavoriteLimitProperties} 读**（那会让用例跟着实现漂移），写死字面量；
 * 配置侧由 {@code FavoriteLimitPropertiesBindingTests} 单独钉住。
 * 400 与 403 的分界出自 T16 拍板的口径（"参数型 400 先于归属 / 状态型 400 后于归属"），
 * 盖住实现照样写得出来。
 *
 * <h2>夹具与 oracle</h2>
 * 条数一律用 {@code jdbcTemplate} **直插**（多行一条 INSERT，5000 行也不再是 5000 次往返），
 * 断言一律用**独立 oracle 直查 DB**（{@code COUNT(*)}），不信接口回显。
 *
 * <h2>反向注入清单（T16 收口逐条实测；每条都是"去掉/改掉那行实现 ⇒ 对应用例必红"）</h2>
 * <ul>
 *   <li>去掉 {@code createFolder} 的夹数判定 ⇒ 夹数两条用例必红；</li>
 *   <li>去掉 {@code addItem} / {@code moveItem} 的容量判定 ⇒ 条数三条 + move 一条必红；</li>
 *   <li>把夹数 SQL 的 {@code AND is_default = 0} 去掉（数全部夹）⇒ "默认夹不计入"必红；</li>
 *   <li>把容量判定挪到夹归属<b>之前</b> ⇒ "他人已满的夹 ⇒ 403"必红（会变 400）。</li>
 * </ul>
 */
class FavoriteCapacityTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    /** 自建夹内容上限（需求篇 §六；与 {@code video.favorite.custom-folder-max-items} 同值）。 */
    private static final int CUSTOM_FOLDER_MAX_ITEMS = 500;

    /** 默认夹内容上限（需求篇 §六；与 {@code video.favorite.default-folder-max-items} 同值）。 */
    private static final int DEFAULT_FOLDER_MAX_ITEMS = 5000;

    /** 自建夹个数上限（需求篇 §六；与 {@code video.favorite.custom-folder-max-count} 同值）。 */
    private static final int CUSTOM_FOLDER_MAX_COUNT = 20;

    @BeforeEach
    void 清空本域相关表() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ========================================================================
    // 1. 夹数上限（20）
    // ========================================================================

    @Test
    @DisplayName("★自建夹至多 20 个：第 20 个可建、第 21 个 ⇒ 400（且库里仍是 20 行）")
    void 自建夹个数上限() {
        TestUser me = register("13800007001", "夹数用户甲");
        for (int i = 0; i < CUSTOM_FOLDER_MAX_COUNT - 1; i++) {
            insertFolder(me.id, "夹" + i, 0);
        }

        ResponseEntity<String> twentieth = addFolder("第 20 个", me.token);
        assertThat(twentieth.getStatusCode().value()).as("恰好到上限必须建得成（否则先被自己拦掉）").isEqualTo(200);
        assertThat(countCustomFolders(me.id)).as("库里恰好 20 个自建夹").isEqualTo(CUSTOM_FOLDER_MAX_COUNT);

        ResponseEntity<String> twentyFirst = addFolder("第 21 个", me.token);
        assertThat(twentyFirst.getStatusCode().value()).as("超上限 ⇒ 400").isEqualTo(400);
        assertThat(Envelope.code(twentyFirst)).isEqualTo(400);
        assertThat(Envelope.msg(twentyFirst)).as("文案要能自解释（用户看得到）").contains("上限");
        assertThat(countCustomFolders(me.id)).as("被拒的请求不得留下第 21 行").isEqualTo(CUSTOM_FOLDER_MAX_COUNT);
    }

    @Test
    @DisplayName("★默认夹不计入个数上限：1 个默认夹 + 19 个自建夹 ⇒ 第 20 个自建夹照样建得成")
    void 默认夹不计入个数上限() {
        TestUser me = register("13800007002", "夹数用户乙");
        insertFolder(me.id, "默认收藏夹", 1);
        for (int i = 0; i < CUSTOM_FOLDER_MAX_COUNT - 1; i++) {
            insertFolder(me.id, "夹" + i, 0);
        }

        // ★ 判别力所在：若实现"数全部夹"，此处会因 count=20 而 400（默认夹 + 19 自建）。
        assertThat(addFolder("第 20 个自建夹", me.token).getStatusCode().value())
                .as("默认夹不计入 ⇒ 此时自建夹才 19 个，必须建得成").isEqualTo(200);
        assertThat(countCustomFolders(me.id)).isEqualTo(CUSTOM_FOLDER_MAX_COUNT);

        // 反向对照：再建一个就必须 400（排除"上限根本没生效"导致的假绿）
        assertThat(addFolder("第 21 个自建夹", me.token).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("★顺序契约：夹数已满时，夹名非法仍报**名称**错（参数形态先于资源状态）")
    void 夹名非法先于个数上限() {
        TestUser me = register("13800007003", "夹数用户丙");
        for (int i = 0; i < CUSTOM_FOLDER_MAX_COUNT; i++) {
            insertFolder(me.id, "夹" + i, 0);
        }

        // 两个拒因都是 400 ⇒ 只有 msg 能区分谁先判（这就是本用例的判别力所在）。
        ResponseEntity<String> blankName = addFolder("   ", me.token);
        assertThat(blankName.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(blankName))
                .as("★ 夹名非法必须**先**判（不碰库的参数校验先于资源状态）：报的是名称那条，不是上限那条")
                .contains("名称")
                .doesNotContain("上限");

        // 对照：合法名字走到底才是上限那条 400 —— 排除"名称分支把一切都吞了"的假绿。
        assertThat(Envelope.msg(addFolder("合法名字", me.token))).contains("上限");
    }

    // ========================================================================
    // 2. 夹内条数上限（自建 500 / 默认 5000，按 is_default 分流）
    // ========================================================================

    @Test
    @DisplayName("★自建夹 500 条：恰好 500 可加、第 501 条 ⇒ 400（且库里仍是 500）")
    void 自建夹条数上限() {
        TestUser me = register("13800007004", "条数用户甲");
        long folder = insertFolder(me.id, "快满的夹", 0);
        insertItemsBulk(folder, me.id, 800001L, CUSTOM_FOLDER_MAX_ITEMS - 1);
        long contentA = insertContent(me.id);
        long contentB = insertContent(me.id);

        assertThat(addItem(me.token, contentA, folder).getStatusCode().value())
                .as("第 500 条必须收得进（夹恰好能装到上限）").isEqualTo(200);
        assertThat(oracleItemCount(folder)).isEqualTo(CUSTOM_FOLDER_MAX_ITEMS);

        ResponseEntity<String> overflow = addItem(me.token, contentB, folder);
        assertThat(overflow.getStatusCode().value()).as("第 501 条 ⇒ 400").isEqualTo(400);
        assertThat(Envelope.code(overflow)).isEqualTo(400);
        assertThat(oracleItemCount(folder))
                .as("被拒后行数不得变化（400 必须发生在写库之前）").isEqualTo(CUSTOM_FOLDER_MAX_ITEMS);
        assertThat(itemRowCount(folder, contentB)).as("被拒的内容一条都没进").isZero();
    }

    @Test
    @DisplayName("★默认夹上限 5000 且与自建夹**分流**：500 条的默认夹照样能加；满 5000 才 400")
    void 默认夹条数上限与自建夹分流() {
        TestUser me = register("13800007005", "条数用户乙");
        long defaultFolder = insertFolder(me.id, "默认收藏夹", 1);
        insertItemsBulk(defaultFolder, me.id, 810001L, CUSTOM_FOLDER_MAX_ITEMS);
        long contentA = insertContent(me.id);
        long contentB = insertContent(me.id);

        // ★ 判别力所在：若实现把默认夹也按自建夹的 500 判，此处会 400（正好 500 ⇒ >= 500）。
        assertThat(addItem(me.token, contentA, defaultFolder).getStatusCode().value())
                .as("默认夹用的是 5000 那一档 ⇒ 500 条时仍可加").isEqualTo(200);

        // 补齐到 5000（此时含上面刚加的那条）⇒ 再加以必须 400
        insertItemsBulk(defaultFolder, me.id, 820001L, DEFAULT_FOLDER_MAX_ITEMS - CUSTOM_FOLDER_MAX_ITEMS - 1);
        assertThat(oracleItemCount(defaultFolder)).as("前提：默认夹恰好 5000 条").isEqualTo(DEFAULT_FOLDER_MAX_ITEMS);

        assertThat(addItem(me.token, contentB, defaultFolder).getStatusCode().value())
                .as("默认夹第 5001 条 ⇒ 400").isEqualTo(400);
        assertThat(oracleItemCount(defaultFolder)).isEqualTo(DEFAULT_FOLDER_MAX_ITEMS);
    }

    @Test
    @DisplayName("★move 撞上限 ⇒ 400：目标夹满时移动被拒，**两边都原样保留**")
    void 移动到已满的夹被拒() {
        TestUser me = register("13800007006", "移动用户");
        long source = insertFolder(me.id, "源夹", 0);
        long fullTarget = insertFolder(me.id, "已满的目标夹", 0);
        long content = insertContent(me.id);
        insertItemRow(source, me.id, content);
        insertItemsBulk(fullTarget, me.id, 830001L, CUSTOM_FOLDER_MAX_ITEMS);

        ResponseEntity<String> resp = moveItem(me.token, source, fullTarget, content);

        assertThat(resp.getStatusCode().value())
                .as("★ 漏判 move 就能绕道把目标夹撑爆 ⇒ 必须 400").isEqualTo(400);
        assertThat(Envelope.code(resp)).isEqualTo(400);
        assertThat(itemRowCount(source, content)).as("被拒的移动必须**留在原位**（同 409 的口径）").isOne();
        assertThat(oracleItemCount(fullTarget)).as("目标夹一行都没多").isEqualTo(CUSTOM_FOLDER_MAX_ITEMS);
    }

    @Test
    @DisplayName("★顺序口径：往**他人已满的夹** add ⇒ 403（不是 400）—— 状态型 400 判在归属之后")
    void 他人已满的夹返回403而非400() {
        TestUser me = register("13800007007", "我");
        TestUser other = register("13800007008", "别人");
        long othersFullFolder = insertFolder(other.id, "别人的满夹", 0);
        insertItemsBulk(othersFullFolder, other.id, 840001L, CUSTOM_FOLDER_MAX_ITEMS);
        long content = insertContent(me.id);

        ResponseEntity<String> resp = addItem(me.token, content, othersFullFolder);

        assertThat(resp.getStatusCode().value())
                .as("★ 容量判定必须在归属**之后**：否则'他人夹已满'⇒400、'他人夹未满'⇒403，"
                        + "等于给他人夹（含私密夹）的夹内尺寸开探测口").isEqualTo(403);
        assertThat(oracleItemCount(othersFullFolder)).as("别人的夹不得被写入").isEqualTo(CUSTOM_FOLDER_MAX_ITEMS);
    }

    @Test
    @DisplayName("★满夹时重复收藏 ⇒ 400 而非 409（已知副作用的钉子：容量先于撞唯一键）")
    void 满夹时重复收藏返回400() {
        TestUser me = register("13800007009", "重复用户");
        long folder = insertFolder(me.id, "已满的夹", 0);
        long content = insertContent(me.id);
        // 前 499 条是背景，第 500 条就是本用例要重复收藏的那条内容
        insertItemsBulk(folder, me.id, 850001L, CUSTOM_FOLDER_MAX_ITEMS - 1);
        insertItemRow(folder, me.id, content);

        ResponseEntity<String> resp = addItem(me.token, content, folder);

        // ★ 这是**有意为之**的取舍（不是缺陷）：要保住 409 就得"先查后插"——本仓明确拒绝的形态
        //   （并发下必漏 + 多查一次）。故取"夹满 ⇒ 一律 400"这个更简单、不变量更强的口径，
        //   并把现行为钉死，免得将来被无声改掉。
        assertThat(resp.getStatusCode().value())
                .as("夹已满 ⇒ 400（先判容量、后撞唯一键；未满时同一条请求仍是 409）").isEqualTo(400);
        assertThat(oracleItemCount(folder)).as("400 之后行数不变").isEqualTo(CUSTOM_FOLDER_MAX_ITEMS);
    }

    // ========================================================================
    // 3. ★ 20 上限**不得**拦默认夹的懒建（最尖锐的一条）
    // ========================================================================

    @Test
    @DisplayName("★夹数已满 20 也不拦默认夹懒建：不选夹收藏 ⇒ 200 且默认夹建出来（否则是不自愈的死局）")
    void 夹数上限不拦默认夹懒建() {
        TestUser me = register("13800007010", "满夹新人");
        for (int i = 0; i < CUSTOM_FOLDER_MAX_COUNT; i++) {
            insertFolder(me.id, "夹" + i, 0);
        }
        long content = insertContent(me.id);
        assertThat(defaultFolderCount(me.id)).as("前提：该用户还没有默认夹").isZero();

        ResponseEntity<String> resp = addItem(me.token, content, null);

        // 若 20 上限误拦懒建 ⇒ 该用户**再也收藏不了任何东西**（不选夹恒失败），
        // 且前端的"默认收藏夹·首次收藏时创建"占位行永远建不出来（重演 I-08 的互锁）——
        // 不可自愈：唯一出路是删一个自建夹。故这条必须钉死。
        assertThat(resp.getStatusCode().value())
                .as("★ 20 上限只拦 createFolder，绝不拦默认夹懒建").isEqualTo(200);
        assertThat(defaultFolderCount(me.id)).as("默认夹被懒建出来").isEqualTo(1);
        assertThat(oracleItemCount(defaultFolderIdOf(me.id))).as("内容落进了默认夹").isEqualTo(1);

        // 对照：同一时刻再建自建夹仍是 400（说明上限确实在生效，不是"整个检查没跑"）
        assertThat(addFolder("第 21 个", me.token).getStatusCode().value()).isEqualTo(400);
    }

    // ========================================================================
    // HTTP（写 = POST + form，同收藏域其余用例）
    // ========================================================================

    private ResponseEntity<String> addFolder(String name, String token) {
        return postForm("/favorite/folder/add", form("name", name), token);
    }

    private ResponseEntity<String> addItem(String token, long contentId, Long folderId) {
        MultiValueMap<String, String> f = form("contentId", contentId);
        if (folderId != null) {
            f.add("folderId", String.valueOf(folderId));
        }
        return postForm("/favorite/add", f, token);
    }

    private ResponseEntity<String> moveItem(String token, long fromFolderId, long toFolderId, long contentId) {
        MultiValueMap<String, String> f = form("fromFolderId", fromFolderId);
        f.add("toFolderId", String.valueOf(toFolderId));
        f.add("contentId", String.valueOf(contentId));
        return postForm("/favorite/move", f, token);
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

    private static MultiValueMap<String, String> form(String name, String value) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(name, value);
        return form;
    }

    private static MultiValueMap<String, String> form(String name, long value) {
        return form(name, String.valueOf(value));
    }

    // ========================================================================
    // 夹具与独立 oracle（直连 DB，不经接口）
    // ========================================================================

    private record TestUser(long id, String token) {
    }

    private TestUser register(String phone, String username) {
        String token = registerAndGetToken(phone, username, PASSWORD);
        return new TestUser(userIdOf(phone), token);
    }

    private long insertContent(long authorId) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description, is_deleted) VALUES (?, 1, ?, ?, 0)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setString(2, "T16 容量测试内容 " + authorId);
            ps.setString(3, "T16 容量测试描述");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
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

    private void insertItemRow(long folderId, long userId, long contentId) {
        jdbcTemplate.update("INSERT INTO favorite_item (folder_id, user_id, content_id) VALUES (?, ?, ?)",
                folderId, userId, contentId);
    }

    /**
     * 批量造收藏记录：**一条多行 INSERT**（5000 行也只往返一次，夹具不该成为用例的耗时来源）。
     *
     * <p>⚠️ 这里直接拼字面量而不走 {@code ?} 占位：值全是测试自造的 {@code long}（无注入面），
     * 而 5000 行 × 3 参数会让占位符版本难读且无收益。
     */
    private void insertItemsBulk(long folderId, long userId, long firstContentId, int count) {
        assertThat(count).as("夹具条数必须为正").isPositive();
        StringBuilder sql = new StringBuilder(
                "INSERT INTO favorite_item (folder_id, user_id, content_id) VALUES ");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('(').append(folderId).append(',').append(userId).append(',')
                    .append(firstContentId + i).append(')');
        }
        jdbcTemplate.update(sql.toString());
    }

    /** ★ 独立 oracle：夹内收藏**记录**数（含失效条目，不读接口回显）。 */
    private long oracleItemCount(long folderId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_item WHERE folder_id = ?", Long.class, folderId);
        return n == null ? 0L : n;
    }

    /** (folder_id, content_id) 精确对查的行数（比"数总数"更能定位写错行）。 */
    private long itemRowCount(long folderId, long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_item WHERE folder_id = ? AND content_id = ?",
                Long.class, folderId, contentId);
        return n == null ? 0L : n;
    }

    /** ★ 独立 oracle：该用户的**自建夹**个数（与实现那条 SQL 各自推导，不共用）。 */
    private long countCustomFolders(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_folder WHERE user_id = ? AND is_default = 0",
                Long.class, userId);
        return n == null ? 0L : n;
    }

    private long defaultFolderCount(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_folder WHERE user_id = ? AND is_default = 1",
                Long.class, userId);
        return n == null ? 0L : n;
    }

    private long defaultFolderIdOf(long userId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM favorite_folder WHERE user_id = ? AND is_default = 1", Long.class, userId);
        assertThat(ids).as("默认夹应恰好一条").hasSize(1);
        return ids.get(0);
    }
}
