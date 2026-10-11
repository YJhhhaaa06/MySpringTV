package io.github.yjhhhaaa06.videoweb.favorite;

import io.github.yjhhhaaa06.videoweb.favorite.dao.FavoriteItemDao;
import io.github.yjhhhaaa06.videoweb.favorite.service.FavoriteService;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * T9（收藏二期-1，R-10）：收藏域**轻量判存在**契约
 * （{@link FavoriteService#isFavorited} / {@link FavoriteService#batchIsFavorited}）的契约测试。
 *
 * <h2>为什么单列一个类（不并进 T5 的 {@link FavoriteStatusAndCountTests}）</h2>
 * T5 钉的是两条**端点**（{@code /favorite/status} 的"收在哪些夹" + {@code /favorite/count}）；
 * 本类钉的是 T9 新增的**判存在**契约 —— 它是"互动域组装"的依赖面，**没有端点**，
 * 且与 T5 的 status **口径刻意不同侧**（见下一条）。且 G8：一期的测试用例不许改，新契约单列更干净。
 *
 * <h2>★ 与 {@code /favorite/status} 的口径差（本类的核心判别力）</h2>
 * {@code status} 判"收在哪些夹"（JOIN {@code favorite_folder}）；本契约判"**记录存在**"（**不 JOIN**）。
 * 两者在正常态一致，在"孤儿行"边缘态**刻意不同侧**（夹被删而条目未删；该态无 API 可达，
 * 前提由 {@code deleteFolder} 的 {@code @Transactional} 承担，判别力已由 I-04 在 T8 收口）：
 * <ul>
 *   <li>{@code isFavorited} ⇒ **true**（"我收藏过"这个事实仍成立；与收藏数同侧）；</li>
 *   <li>{@code /favorite/status} ⇒ false（JOIN 丢行）—— 该分叉是已登记的已知边缘态，不是缺陷。</li>
 * </ul>
 * 本类用 {@code 孤儿行} 用例把这个分叉**双向钉住**（没有它，"不 JOIN"就只是代码形状、无判别力）。
 *
 * <h2>★ 期望值怎么来的（规避"迎合型伪测试"）</h2>
 * "多夹仍 true"出自口径"按人去重、判记录存在"（R-06 同一人进多夹只算一会话）；
 * "他人 / 别的内容不串"是判据 {@code (user_id, content_id)} 的直接推论；
 * "失效内容仍 true"出自 R-03 / R-07③（失效记录不清理）；"孤儿行 true + status false"
 * 出自 T9 红线"不 JOIN 的轻量判存在"与 {@code FavoriteItemDao#existsByUserAndContent} 的口径声明。
 *
 * <h2>★ 反向验证（注入 → 确认变红 → 还原；实测记录见 T9 回写）</h2>
 * <ul>
 *   <li>把 {@code existsByUserAndContent} 改回 JOIN {@code favorite_folder} 版
 *       ⇒ {@code 孤儿行仍判已收藏} 变红（true → false）；</li>
 *   <li>把 {@code batchIsFavorited} 改成循环调 {@code isFavorited}
 *       ⇒ {@code 批量一趟查询不循环编单条} 变红（DAO 单条方法被调 N 次）；</li>
 *   <li>去掉两条 SQL 的 {@code user_id} 条件 ⇒ {@code 单条…他人不串} 与 {@code 批量…} 变红。</li>
 * </ul>
 *
 * <h2>独立性</h2>
 * 夹具直插 {@code favorite_item} / {@code favorite_folder}（两条 SQL 的被判对象），
 * oracle 直查行数与列 —— 不经接口回显。
 */
class FavoriteExistenceTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    @Autowired
    private FavoriteService favoriteService;

    /** spy：真实执行 + 验证"批量走一趟查询、单条方法零次"。 */
    @MockitoSpyBean
    private FavoriteItemDao itemDao;

    @BeforeEach
    void 清空本域相关表() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ========================================================================
    // 单条判存在
    // ========================================================================

    @Test
    @DisplayName("★单条判存在：多夹仍 true；本人的别的内容、他人的记录都不串")
    void 单条判存在多夹仍真且不串() {
        TestUser me = register("13900009101", "t9-exist-me");
        TestUser other = register("13900009102", "t9-exist-other");
        TestUser stranger = register("13900009103", "t9-exist-stranger");
        long a = insertContent(me.id());
        long b = insertContent(me.id());

        long myFolder1 = insertFolder(me.id(), false);
        long myFolder2 = insertFolder(me.id(), false);
        insertItem(myFolder1, me.id(), a);
        insertItem(myFolder2, me.id(), a);              // 同一人进两个夹
        long otherFolder = insertFolder(other.id(), false);
        insertItem(otherFolder, other.id(), a);         // 干扰：另一人收了 a
        insertItem(otherFolder, other.id(), b);         // 干扰：另一人收了 b

        // 独立 oracle：a 在 me 名下确实有 2 条记录（多夹），去重后 1 条
        assertThat(countRows("SELECT COUNT(*) FROM favorite_item WHERE user_id = ? AND content_id = ?",
                me.id(), a)).as("多夹夹具").isEqualTo(2L);

        assertThat(favoriteService.isFavorited(me.id(), a)).as("多夹也只答'收藏过'").isTrue();
        assertThat(favoriteService.isFavorited(me.id(), b))
                .as("★ 我收了 a、没收 b —— 漏 content_id 条件（我收过别的）/ 漏 user_id 条件（别人收了 b）都会变 true")
                .isFalse();
        assertThat(favoriteService.isFavorited(other.id(), b)).as("对照：另一个人收的是 b").isTrue();
        assertThat(favoriteService.isFavorited(stranger.id(), a)).as("从没收藏过的人 ⇒ false").isFalse();
    }

    @Test
    @DisplayName("★批量判存在：完整映射（不重不漏）；一趟查询、不循环调单条；空入参不发查询")
    void 批量判存在完整映射且一趟查询() {
        TestUser me = register("13900009104", "t9-exist-batch");
        TestUser other = register("13900009105", "t9-exist-batch-other");
        long a = insertContent(me.id());
        long b = insertContent(me.id());
        long c = insertContent(other.id());
        long myFolder = insertFolder(me.id(), false);
        insertItem(myFolder, me.id(), a);
        long otherFolder = insertFolder(other.id(), false);
        insertItem(otherFolder, other.id(), b);         // 干扰：另一人收了 b

        Map<Long, Boolean> result = favoriteService.batchIsFavorited(me.id(), List.of(a, b, c, a));

        assertThat(result.keySet())
                .as("★ 不重不漏：入参 4 条（含重复）⇒ 恰好 3 个唯一 key")
                .containsExactlyInAnyOrder(a, b, c);
        assertThat(result.get(a)).isTrue();
        assertThat(result.get(b)).as("★ 另一人收了 b —— 漏 user_id 条件会变 true").isFalse();
        assertThat(result.get(c)).isFalse();

        assertThat(favoriteService.batchIsFavorited(me.id(), List.of())).as("空入参 ⇒ 空 Map").isEmpty();

        // ★ 一趟批量 + 单条方法零次（逐条调用会让 100 条列表变 100 次点查）
        verify(itemDao, times(1)).findFavoritedContentIds(eq(me.id()), anyList());
        verify(itemDao, never()).existsByUserAndContent(anyLong(), anyLong());
    }

    // ========================================================================
    // 口径钉子：记录存在性（不判有效性、不 JOIN）
    // ========================================================================

    @Test
    @DisplayName("★失效内容仍判已收藏：判据是记录本身，不判内容有效性（R-03 / R-07③）")
    void 失效内容仍判已收藏() {
        TestUser me = register("13900009106", "t9-exist-deleted");
        long softDeleted = insertContentWithState(me.id(), 1);      // is_deleted = 1
        long folder = insertFolder(me.id(), false);
        insertItem(folder, me.id(), softDeleted);                   // 直插（addItem 会拒失效内容）

        assertThat(favoriteService.isFavorited(me.id(), softDeleted))
                .as("★ 失效内容的记录照样在（不自动清理）⇒ '我还收着它'为 true")
                .isTrue();
    }

    @Test
    @DisplayName("★口径钉子：孤儿行（夹已删、记录未删）仍判已收藏 —— 判存在不 JOIN 夹表；对照 status 会丢行")
    void 孤儿行仍判已收藏而状态端点丢行() {
        TestUser me = register("13900009107", "t9-exist-orphan");
        long content = insertContent(me.id());
        long folder = insertFolder(me.id(), false);
        insertItem(folder, me.id(), content);
        // 直删夹、留下孤儿条目（孤儿行边缘态：正常路径由 deleteFolder 的同事务兜住，无 API 可达；
        // 该前提的判别力见 I-04 的 T8 收口）
        jdbcTemplate.update("DELETE FROM favorite_folder WHERE id = ?", folder);

        assertThat(countRows("SELECT COUNT(*) FROM favorite_item WHERE user_id = ? AND content_id = ?",
                me.id(), content)).as("孤儿行仍在").isEqualTo(1L);
        assertThat(countRows("SELECT COUNT(*) FROM favorite_folder WHERE id = ?", folder)).as("夹已删").isZero();

        assertThat(favoriteService.isFavorited(me.id(), content))
                .as("★ 不 JOIN：记录存在 ⇒ true（本契约服务'我收藏过没有'这个事实，与收藏数同侧）")
                .isTrue();
        // 对照：JOIN 版（/favorite/status）在孤儿态丢行 ⇒ false。
        // 该分叉是 I-04 已登记的已知边缘态 —— 双向上锁，防止将来悄悄改侧。
        // ⚠️ status 是 @RequiresLogin 端点：必须带 token，否则 401 的"缺 data"会被 asBoolean()
        //    读成 false，把一个假绿伪装成"分叉成立"。
        var statusResp = get("/favorite/status?contentId=" + content, me.token());
        assertThat(statusResp.getStatusCode().value()).as("对照端点必须先 200，断言才有判别力").isEqualTo(200);
        assertThat(Envelope.data(statusResp).path("isFavorited").asBoolean())
                .as("对照：status 的 JOIN 版在孤儿态答 false（分叉已登记，不是缺陷）")
                .isFalse();
    }

    // ========================================================================
    // 夹具与独立 oracle
    // ========================================================================

    private TestUser register(String phone, String username) {
        String token = registerAndGetToken(phone, username, PASSWORD);
        return new TestUser(userIdOf(phone), token);
    }

    private record TestUser(long id, String token) {
    }

    private long countRows(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    private long insertContent(long authorId) {
        return insertContentWithState(authorId, 0);
    }

    private long insertContentWithState(long authorId, int isDeleted) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description, is_deleted) VALUES (?, 1, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setString(2, "T9 判存在测试内容 " + authorId);
            ps.setString(3, "T9 测试描述");
            ps.setInt(4, isDeleted);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    private long insertFolder(long userId, boolean isDefault) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO favorite_folder (user_id, name, is_default) VALUES (?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, "T9 测试夹 " + userId);
            ps.setInt(3, isDefault ? 1 : 0);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("favorite_folder 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    private void insertItem(long folderId, long userId, long contentId) {
        jdbcTemplate.update("INSERT INTO favorite_item (folder_id, user_id, content_id) VALUES (?, ?, ?)",
                folderId, userId, contentId);
    }
}
