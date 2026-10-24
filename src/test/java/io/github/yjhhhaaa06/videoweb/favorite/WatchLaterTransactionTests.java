package io.github.yjhhhaaa06.videoweb.favorite;

import io.github.yjhhhaaa06.videoweb.favorite.dao.WatchLaterDao;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.PreparedStatement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * 收藏三期 <b>T17</b> 的**声明式机制生效证明**：{@code WatchLaterService.move} 的
 * {@code @Transactional} 不是装饰（照 {@code FavoriteTransactionTests} 的范式）。
 *
 * <h2>为什么单列一个类</h2>
 * {@code WatchLaterMoveTests} 断言的是"两次写都成功"的**终态** ⇒ 去掉 {@code @Transactional}
 * 仍绿（假绿）。要证明事务在场，必须让事务在**已有成功写之后**失败：
 * <ul>
 *   <li>有事务 ⇒ 回滚（断言看到 {@code favorite_item} 写之前的旧态 = 0）；</li>
 *   <li>无事务 ⇒ 前面的写已提交（{@code favorite_item} = 1）⇒ 用例变红。</li>
 * </ul>
 *
 * <h2>★ 打桩点为什么必须选"最后一步"</h2>
 * {@code move} 的顺序是"加入目标夹（第一步，真实执行）→ 从稍后再看删行（第二步，**打桩抛错**）"
 * （见 {@code WatchLaterService.move}）。若让第一步就失败，"什么都没写"在两个世界里都成立 ⇒
 * 断言无鉴别力。故打桩点在**第二步**（在成功写之后才执行）。
 *
 * <p>⚠️ 不对 MyBatis mapper 用 {@code doCallRealMethod().doThrow(...)}：mapper 是 JDK 动态代理、
 * 方法在 Mockito 眼里是抽象方法（同 {@code FavoriteTransactionTests} 的实测注记）⇒ 用
 * {@code @MockitoSpyBean}（默认委托真实实现）+ **只打桩第二步**。
 */
class WatchLaterTransactionTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    @MockitoSpyBean
    private WatchLaterDao watchLaterDao;

    @BeforeEach
    void 清空本域相关表并复位打桩() {
        // 复位打桩必须先于夹具（避免上一用例的 stub 泄漏到本用例）。
        Mockito.reset(watchLaterDao);
        jdbcTemplate.update("DELETE FROM watch_later");
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content_media");
        jdbcTemplate.update("DELETE FROM content");
    }

    @Test
    @DisplayName("★机制证明：move 第二写(从池中删行)失败 ⇒ @Transactional 回滚，第一步(加入目标夹)复原")
    void 移动第二步失败时加入回滚() {
        TestUser me = register("13800008001", "事务移动用户");
        long video = insertVideoContent(me.id, "事务视频");
        long folder = insertFolderRow(me.id, "目标夹");
        add(me.token, video);
        assertThat(oracleWatchLaterRows(me.id, video)).as("前置：内容在稍后再看里").isEqualTo(1L);

        // 从池中删行 = move 的**第二步**（发生在加入目标夹之后）—— 打桩让它抛错。
        doThrow(new IllegalStateException("boom: 模拟从稍后再看删行失败"))
                .when(watchLaterDao).deleteByUserAndContentIds(anyLong(), any());

        ResponseEntity<String> resp = move(me.token, video, folder);
        assertThat(resp.getStatusCode().value()).as("非业务异常 → 500").isEqualTo(500);

        assertThat(oracleFavoriteItemRows(folder, me.id, video))
                .as("★ @Transactional 必须真的生效：第二步失败要回滚第一步插进目标夹的行"
                        + "（无事务则 addItem 自身事务已提交 ⇒ 此处为 1）")
                .isZero();
        assertThat(oracleWatchLaterRows(me.id, video))
                .as("★ '移'没完成 ⇒ 内容仍在稍后再看（不是'移出后丢失'）").isEqualTo(1L);
    }

    // ========================================================================
    // HTTP
    // ========================================================================

    private ResponseEntity<String> add(String token, long contentId) {
        // `T58` 契约变更：contentId 进路径（PUT /api/v1/watch-later/{id}，接口契约.md §2.3/§2.4）。
        return put("/api/v1/watch-later/" + contentId, null, token);
    }

    private ResponseEntity<String> move(String token, long contentId, long folderId) {
        // `T58` 契约变更：contentId 进路径
        // （PUT /api/v1/watch-later/{id}/folder，接口契约.md §2.3/§2.4）。
        // `T59` 载荷面：folderId 走 JSON body。
        return put("/api/v1/watch-later/" + contentId + "/folder", Map.of("folderId", folderId), token);
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

    private long oracleFavoriteItemRows(long folderId, long userId, long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_item WHERE folder_id = ? AND user_id = ? AND content_id = ?",
                Long.class, folderId, userId, contentId);
        return n == null ? 0L : n;
    }

    private long oracleWatchLaterRows(long userId, long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_later WHERE user_id = ? AND content_id = ?",
                Long.class, userId, contentId);
        return n == null ? 0L : n;
    }

    private long insertFolderRow(long userId, String name) {
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

    private long insertVideoContent(long authorId, String title) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description, is_deleted) VALUES (?, 1, ?, ?, 0)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setString(2, title);
            ps.setString(3, "T17 事务测试描述");
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
}
