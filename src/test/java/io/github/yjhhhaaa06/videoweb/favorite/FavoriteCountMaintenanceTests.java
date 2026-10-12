package io.github.yjhhhaaa06.videoweb.favorite;

import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 收藏二期 <b>T12：{@code content.favorite_count} 冗余列的启用</b>（能力 二期-3；
 * 维护口径 =「事务内批量重算」，{@code 收藏功能-分期与设计.md} §3.2）。
 *
 * <h2>★ 本类钉住的不变量（二期判据②）</h2>
 * <pre>content.favorite_count == COUNT(DISTINCT user_id) FROM favorite_item WHERE content_id = ?</pre>
 * oracle 独立复算：直接查 {@code favorite_item} 聚合（不读接口回显、不读缓存、不读列自身），
 * 与列直查对表 —— 任何一条写路径后两者必须相等。一期那套"实时 COUNT"就是这条不变量的右边
 * （R-06 立下的验收口径，T12 落地即兑现）。
 *
 * <h2>覆盖什么</h2>
 * <ul>
 *   <li><b>四条写路径各自的重算</b>：add（单内容）/ remove（批量 id）/ move（★ 任务书原文
 *       只列三条，move 的"源夹不含该内容"边缘态等价于新增收藏、必须接入 —— 用户 2026-10-12
 *       拍板）/ deleteFolder（批量：删夹前取受影响内容集，一趟聚合 + 一趟批量 UPDATE）；</li>
 *   <li><b>对照夹具</b>：每处都有"另一人 / 另一内容 / 另一夹"的干扰行（T8 立下的纪律 ——
 *       没有对照，"去掉过滤 / 写错范围"的注入是发现不了的）；</li>
 *   <li><b>"清零"也要落列</b>：移出最后一个夹后聚合不出行 ⇒ 补 0 写回 —— 不变量对 0 成立；</li>
 *   <li><b>重算写的是绝对值，不是 ±delta</b>：人工把列改漂（直改 DB），下一次写路径把它
 *       拉回真值 —— 增量式实现在这里必然变红（漂移被 ±1 放大而不是修复）；</li>
 *   <li><b>读切换的证明</b>：直改列 + 清缓存 ⇒ 端点回显被改的值 —— 可观察地证明回源读的是
 *       列（实时聚合下不可能看到 999）；</li>
 *   <li><b>批量是"一趟"</b>：deleteFolder 对 N 个受影响内容只发**一次**
 *       {@code updateFavoriteCounts}（逐行 UPDATE 就是 N 次往返，§3.2 强制探索的确认点）。</li>
 * </ul>
 *
 * <h2>★ 事务机制证明（G5 / AGENTS「测试纪律」④：最后一步打桩）</h2>
 * T12 把 {@code addItem} / {@code removeItems} 从"单写无事务"升级为"双写有事务"
 * （关系行 + 计数重算必须同进同退）。证明手法同 {@code FavoriteTransactionTests}：
 * 让事务在**已有成功写之后**失败（{@code @MockitoSpyBean} 打桩 {@code ContentDao.updateFavoriteCounts}
 * —— 它是两步重算的最后一步）：
 * <ul>
 *   <li>有事务 ⇒ 回滚（断言看到关系行**复原**）；</li>
 *   <li>无事务（假如有人把 @Transactional 摘掉）⇒ 关系行已提交 ⇒ 断言看到新态 ⇒ 用例变红。</li>
 * </ul>
 *
 * <h2>夹具口径</h2>
 * 与 {@code FavoriteStatusAndCountTests} 的 T12 改造同款：凡"要让计数看见"的状态一律走
 * **真实写路径**（HTTP add/remove/move/deleteFolder）；直插 DB 只用于"人工漂移 / 读切换证明"
 * 这类**故意绕开写路径**的场面（那正是被测对象）。用例前清 Redis（进程级单例残留纪律）。
 */
class FavoriteCountMaintenanceTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    @Autowired
    private StringRedisTemplate redis;

    /** spy：机制证明的打桩点（updateFavoriteCounts = 重算的"最后一步"）+ 批量单趟的调用计数。 */
    @MockitoSpyBean
    private ContentDao contentDao;

    @BeforeEach
    void 清空本域相关表并复位打桩() {
        // 复位打桩必须先于夹具（避免上一用例的 stub 泄漏到本用例）。
        Mockito.reset(contentDao);
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content");
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ========================================================================
    // 一、add：首次收藏 / 收进第二个夹 / 对照行不受干扰
    // ========================================================================

    @Test
    @DisplayName("★add：首次收藏后列 == oracle；另一内容的对照行计数不被重算波及")
    void add首次收藏后列与oracle一致且对照行不受波及() {
        TestUser a = register("13700002101", "重算甲");
        TestUser b = register("13700002102", "重算乙");
        long content = insertContent(a.id);
        long otherContent = insertContent(a.id);      // ★ 对照内容：只被乙收藏
        long folderA = insertFolder(a.id, "甲的夹");
        long folderB = insertFolder(b.id, "乙的夹");

        // 先让对照内容有自己的真值（乙收藏它 ⇒ 列 = 1）
        assertThat(addItem(folderB, otherContent, b.token).getStatusCode().value()).isEqualTo(200);
        assertThat(columnOf(otherContent)).as("前置：对照内容列 = 1").isEqualTo(1L);

        // 甲收藏主内容（对照行在**同一次重算的范围外**）
        assertThat(addItem(folderA, content, a.token).getStatusCode().value()).isEqualTo(200);

        assertThat(oracleDistinctUserCount(content)).as("独立 oracle：1 人").isEqualTo(1L);
        assertThat(columnOf(content))
                .as("★ add 后不变量成立：列 == COUNT(DISTINCT user_id)").isEqualTo(1L);
        assertThat(columnOf(otherContent))
                .as("★ 对照：重算范围只含本次受影响的内容，另一内容的列**不得**被顺手清掉/改掉")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("★add 收进第二个夹：DB 2 行、列仍 1（按人去重；夹内唯一键与判定维度不重合的场面）")
    void add收进第二个夹计数仍按人算() {
        TestUser me = register("13700002103", "多夹重算用户");
        long content = insertContent(me.id);
        long folderX = insertFolder(me.id, "夹X");
        long folderY = insertFolder(me.id, "夹Y");

        assertThat(addItem(folderX, content, me.token).getStatusCode().value()).isEqualTo(200);
        assertThat(addItem(folderY, content, me.token).getStatusCode().value()).isEqualTo(200);

        assertThat(oracleRecordCount(content)).as("DB 里确实 2 行").isEqualTo(2L);
        assertThat(oracleDistinctUserCount(content)).as("按人去重 = 1").isEqualTo(1L);
        assertThat(columnOf(content))
                .as("★ 重算（COUNT DISTINCT）天然答对'收进第二个夹不 +1'—— 增量式 INCR 在这里会写成 2")
                .isEqualTo(1L);
    }

    // ========================================================================
    // 二、remove：部分移出不减 / 最后一个夹移出必须"清零落列"
    // ========================================================================

    @Test
    @DisplayName("★remove：移出一个夹（另一夹还在）列不变；移出最后一个夹 ⇒ 列清零（聚合不出 ⇒ 补 0 写回）")
    void remove部分移出不变最后移出清零() {
        TestUser me = register("13700002104", "清零用户");
        long content = insertContent(me.id);
        long folderA = insertFolder(me.id, "夹A");
        long folderB = insertFolder(me.id, "夹B");
        assertThat(addItem(folderA, content, me.token).getStatusCode().value()).isEqualTo(200);
        assertThat(addItem(folderB, content, me.token).getStatusCode().value()).isEqualTo(200);

        // 移出 A：B 里还有 ⇒ 仍 1
        assertThat(removeItems(folderA, List.of(content), me.token).getStatusCode().value()).isEqualTo(200);
        assertThat(oracleDistinctUserCount(content)).isEqualTo(1L);
        assertThat(columnOf(content)).as("部分移出不减（同一人还在）").isEqualTo(1L);

        // 移出 B（最后一个）：聚合查不到行 ⇒ 必须补 0 写回 —— "清零"同样要落列
        assertThat(removeItems(folderB, List.of(content), me.token).getStatusCode().value()).isEqualTo(200);
        assertThat(oracleDistinctUserCount(content)).isZero();
        assertThat(columnOf(content))
                .as("★ 清零也要落列：GROUP BY 聚合不出该内容，靠调用方补 0 —— 漏了这步列会停在 1")
                .isZero();
    }

    // ========================================================================
    // 三、move：源夹不含该内容的边缘态 = 新增收藏（任务书"三条路径"漏掉、用户拍板接入的那条）
    // ========================================================================

    @Test
    @DisplayName("★move 边缘态：源夹不含该内容（删 0 行）⇒ 等价新增收藏，列必须 +1（D1 拍板的判别力）")
    void move源夹不含该内容时列必须跟着涨() {
        TestUser me = register("13700002105", "move边缘用户");
        long content = insertContent(me.id);
        long from = insertFolder(me.id, "空源夹");
        long to = insertFolder(me.id, "目标夹");

        assertThat(columnOf(content)).as("前置：没人收藏 ⇒ 0").isZero();

        // move(from, to)：插入目标夹成功 + 从源夹删 0 行 ⇒ (user, content) 关系**新增**了
        assertThat(moveItem(from, to, content, me.token).getStatusCode().value()).isEqualTo(200);

        assertThat(oracleDistinctUserCount(content)).as("DB 终态：确实新收藏了 1 人").isEqualTo(1L);
        assertThat(columnOf(content))
                .as("★ 若 move 不接入重算（任务书原文的三条路径），列会停在 0 ⇒ 不变量被 API 可达路径打破")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("★move 常态（源夹确实含有）：列不变（重算幂等，把正确的值再写一遍）")
    void move常态下列不变() {
        TestUser me = register("13700002106", "move常态用户");
        long content = insertContent(me.id);
        long from = insertFolder(me.id, "源夹");
        long to = insertFolder(me.id, "目标夹");
        assertThat(addItem(from, content, me.token).getStatusCode().value()).isEqualTo(200);

        assertThat(moveItem(from, to, content, me.token).getStatusCode().value()).isEqualTo(200);

        assertThat(oracleDistinctUserCount(content)).isEqualTo(1L);
        assertThat(columnOf(content)).isEqualTo(1L);
    }

    // ========================================================================
    // 四、deleteFolder：批量重算（多内容 × 多人交叉），且只发一趟批量 UPDATE
    // ========================================================================

    @Test
    @DisplayName("★deleteFolder：夹内全部内容各自重算正确（含'另一夹仍收藏'的），夹外内容不受影响；批量 UPDATE 只发一趟")
    void 删夹后每个受影响内容重算正确且批量只发一趟() {
        TestUser a = register("13700002107", "删夹甲");
        TestUser b = register("13700002108", "删夹乙");
        long x = insertContent(a.id);      // X：甲(doomed) + 乙(keep) ⇒ 删夹后 2 → 1
        long y = insertContent(a.id);      // Y：甲(doomed) + 甲(keep)  ⇒ 删夹后行少一半、人数仍 1
        long z = insertContent(a.id);      // Z：只在甲的 keep 夹 ⇒ 对照，不受影响
        long doomed = insertFolder(a.id, "要删的夹");
        long keepA = insertFolder(a.id, "甲留着的夹");
        long keepB = insertFolder(b.id, "乙的夹");

        assertThat(addItem(doomed, x, a.token).getStatusCode().value()).isEqualTo(200);
        assertThat(addItem(keepB, x, b.token).getStatusCode().value()).isEqualTo(200);
        assertThat(addItem(doomed, y, a.token).getStatusCode().value()).isEqualTo(200);
        assertThat(addItem(keepA, y, a.token).getStatusCode().value()).isEqualTo(200);
        assertThat(addItem(keepA, z, a.token).getStatusCode().value()).isEqualTo(200);

        // 前置自检：三列都与 oracle 一致（X=2, Y=1, Z=1）
        assertThat(columnOf(x)).isEqualTo(2L);
        assertThat(columnOf(y)).isEqualTo(1L);
        assertThat(columnOf(z)).isEqualTo(1L);

        // 清掉前置 add 们的重算调用记录 —— 让后面的 verify 只反映 deleteFolder 这一次批量
        Mockito.reset(contentDao);
        assertThat(deleteFolder(doomed, a.token).getStatusCode().value()).isEqualTo(200);

        assertThat(oracleRecordCount(x)).as("X：甲的记录随夹删除（R-02）").isEqualTo(1L);
        assertThat(oracleDistinctUserCount(x)).isEqualTo(1L);
        assertThat(columnOf(x)).as("★ X：2 人 → 1 人（乙的还在）").isEqualTo(1L);
        assertThat(oracleRecordCount(y)).as("Y：甲剩 keep 夹里一条").isEqualTo(1L);
        assertThat(columnOf(y)).as("★ Y：人数没变（同一人还在另一夹）⇒ 仍 1").isEqualTo(1L);
        assertThat(columnOf(z)).as("★ 对照：夹外内容 Z 不受重算波及").isEqualTo(1L);

        // ★ 批量 = 一趟：doomed 夹里受影响的是 {x, y} ⇒ updateFavoriteCounts **恰发一次**
        //（逐行 UPDATE 就是 N 次往返）。载荷范围正确性已由上面的列值断言证明：
        // x/y 被重算（列等于删夹后的真值）而夹外的 z 不被波及（仍 1）。
        verify(contentDao, times(1)).updateFavoriteCounts(anyList());
    }

    // ========================================================================
    // 五、重算写绝对值（自愈）：人工漂移被下一次写路径拉回 —— 增量式实现在此必红
    // ========================================================================

    @Test
    @DisplayName("★重算写绝对值：直改列制造漂移（5）⇒ 下一次写路径把它拉回真值（±delta 实现会 6/4）")
    void 重算写绝对值漂移自愈() {
        TestUser me = register("13700002109", "漂移用户");
        long content = insertContent(me.id);
        long folder = insertFolder(me.id, "自愈夹");
        assertThat(addItem(folder, content, me.token).getStatusCode().value()).isEqualTo(200);
        assertThat(columnOf(content)).isEqualTo(1L);

        // 人工漂移（绕开写路径直改 DB）—— 冗余列是第二处事实源，允许被改错，但必须自愈
        jdbcTemplate.update("UPDATE content SET favorite_count = 5 WHERE id = ?", content);

        // 再收藏进另一个夹：人数不变（仍 1）⇒ 重算把列拉回 1（不是 5+1=6）
        long folder2 = insertFolder(me.id, "自愈夹二");
        assertThat(addItem(folder2, content, me.token).getStatusCode().value()).isEqualTo(200);
        assertThat(oracleDistinctUserCount(content)).isEqualTo(1L);
        assertThat(columnOf(content))
                .as("★ 重算 = 幂等复算绝对值：漂移被拉回真值；若实现是 ±delta，这里会是 6")
                .isEqualTo(1L);
    }

    // ========================================================================
    // 六、读切换证明：回源读的是列（不是实时聚合）
    // ========================================================================

    @Test
    @DisplayName("★读切换证明：直改列 + 清缓存 ⇒ 端点回显被改的值（实时聚合实现不可能看到 999）")
    void 读切换后回源读的是列() {
        TestUser me = register("13700002110", "读切换用户");
        long content = insertContent(me.id);
        long folder = insertFolder(me.id, "切换夹");
        assertThat(addItem(folder, content, me.token).getStatusCode().value()).isEqualTo(200);
        assertThat(columnOf(content)).isEqualTo(1L);

        // 直改列 + 清缓存：下一次读必然 miss ⇒ 回源 ⇒ 读到什么就是用什么当事实源
        jdbcTemplate.update("UPDATE content SET favorite_count = 999 WHERE id = ?", content);
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }

        ResponseEntity<String> resp = get("/favorite/count?contentId=" + content, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.data(resp).asLong())
                .as("★ 端点答 999 ⇒ 回源读的是列；若仍在实时 COUNT(DISTINCT)，这里只能是 1")
                .isEqualTo(999L);
    }

    // ========================================================================
    // 七、事务机制证明（最后一步打桩：updateFavoriteCounts 失败 ⇒ 关系行必须回滚）
    // ========================================================================

    @Test
    @DisplayName("★机制证明(T12)：add 的重算失败 ⇒ @Transactional 回滚，已插入的关系行复原")
    void add重算失败时关系行回滚() {
        TestUser me = register("13700002111", "add回滚用户");
        long content = insertContent(me.id);
        long folder = insertFolder(me.id, "回滚夹");

        // 重算 = add 的**最后一步**（插行已成功）—— 打桩让它抛错（有事务 ⇒ 回滚；无事务 ⇒ 行已提交）
        doThrow(new DataAccessException("boom: 模拟计数重算失败") {
        }).when(contentDao).updateFavoriteCounts(anyList());

        ResponseEntity<String> resp = addItem(folder, content, me.token);
        assertThat(resp.getStatusCode().value()).as("非业务异常 → 500").isEqualTo(500);

        assertThat(oracleRecordCount(content))
                .as("★ @Transactional 必须真的生效：重算失败要回滚已插入的收藏行（无事务则提交后 = 1）")
                .isZero();
        assertThat(columnOf(content)).as("列同样未被写入").isZero();
    }

    @Test
    @DisplayName("★机制证明(T12)：remove 的重算失败 ⇒ @Transactional 回滚，已删除的关系行复原")
    void remove重算失败时关系行回滚() {
        TestUser me = register("13700002112", "remove回滚用户");
        long content = insertContent(me.id);
        long folder = insertFolder(me.id, "回滚夹");
        assertThat(addItem(folder, content, me.token).getStatusCode().value()).isEqualTo(200);
        Mockito.reset(contentDao);   // 前置 add 的重算调用不复位会干扰后续打桩判读

        // 重算 = remove 的**最后一步**（删行已成功）—— 打桩让它抛错
        doThrow(new DataAccessException("boom: 模拟计数重算失败") {
        }).when(contentDao).updateFavoriteCounts(anyList());

        ResponseEntity<String> resp = removeItems(folder, List.of(content), me.token);
        assertThat(resp.getStatusCode().value()).as("非业务异常 → 500").isEqualTo(500);

        assertThat(oracleRecordCount(content))
                .as("★ @Transactional 必须真的生效：重算失败要回滚已删除的收藏行（无事务则提交后 = 0）")
                .isEqualTo(1L);
        assertThat(columnOf(content)).as("列与明细同进同退 ⇒ 仍是 1").isEqualTo(1L);
    }

    // ========================================================================
    // HTTP（与 FavoriteCacheTests 同款：写 = POST + form）
    // ========================================================================

    private ResponseEntity<String> addItem(long folderId, long contentId, String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("folderId", String.valueOf(folderId));
        form.add("contentId", String.valueOf(contentId));
        return postForm("/favorite/add", form, token);
    }

    private ResponseEntity<String> removeItems(long folderId, List<Long> contentIds, String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("folderId", String.valueOf(folderId));
        for (Long contentId : contentIds) {
            form.add("contentIds", String.valueOf(contentId));
        }
        return postForm("/favorite/remove", form, token);
    }

    private ResponseEntity<String> moveItem(long fromFolderId, long toFolderId, long contentId, String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("fromFolderId", String.valueOf(fromFolderId));
        form.add("toFolderId", String.valueOf(toFolderId));
        form.add("contentId", String.valueOf(contentId));
        return postForm("/favorite/move", form, token);
    }

    private ResponseEntity<String> deleteFolder(long folderId, String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("folderId", String.valueOf(folderId));
        return postForm("/favorite/folder/remove", form, token);
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

    // ========================================================================
    // 夹具与 oracle（直连 DB，不经接口）
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
                    "INSERT INTO content (user_id, type, title, description) VALUES (?, 1, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setString(2, "T12 测试内容 " + authorId);
            ps.setString(3, "T12 测试描述");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    private long insertFolder(long userId, String name) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO favorite_folder (user_id, name, is_default) VALUES (?, ?, 0)",
                    new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, name);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("favorite_folder 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    // ==================== 独立 oracle（互为对照的两条直查） ====================

    /** 冗余列的当前值（不经缓存、不经接口）。 */
    private long columnOf(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT favorite_count FROM content WHERE id = ?", Long.class, contentId);
        return n == null ? 0L : n;
    }

    /** ★ 不变量的右边：按人去重的实时聚合（与一期"笨查询"同口径，独立复算）。 */
    private long oracleDistinctUserCount(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM favorite_item WHERE content_id = ?", Long.class, contentId);
        return n == null ? 0L : n;
    }

    /** 原始记录条数（多夹时会 > 人数 —— 与去重口径的对照）。 */
    private long oracleRecordCount(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_item WHERE content_id = ?", Long.class, contentId);
        return n == null ? 0L : n;
    }
}
