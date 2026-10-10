package io.github.yjhhhaaa06.videoweb.favorite;

import io.github.yjhhhaaa06.videoweb.favorite.dao.FavoriteFolderDao;
import io.github.yjhhhaaa06.videoweb.favorite.dao.FavoriteItemDao;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * 收藏一期的**声明式机制生效证明**：为两处原本"零判别力的 {@code @Transactional}"补上可注入的判别力
 * （T8 收口；来源 {@code CURRENT_ISSUES.md} <b>I-04</b> 与 T4 审查遗留）。
 *
 * <h2>为什么单列一个类（照 {@code AdminTransactionTests} 的范式）</h2>
 * 其余收藏用例都断言"两次写都成功"的**终态** ⇒ 去掉 {@code @Transactional} 仍绿（假绿）。
 * 要证明事务在场，必须让事务在**已有成功写之后**失败：
 * <ul>
 *   <li>有事务 ⇒ 回滚（断言看到写之前的旧态）；</li>
 *   <li>无事务 ⇒ 前面的写已提交（断言看到新态）⇒ 用例变红。</li>
 * </ul>
 *
 * <h2>★ 打桩点为什么必须选"最后一步"</h2>
 * 若让**第一步**写失败，"什么都没写"在有事务 / 无事务两个世界里都成立 ⇒ 断言无鉴别力。
 * 故打桩点选在"在成功写之后才执行的那一步"：
 * <ul>
 *   <li>{@code deleteFolder}：删条目（第一步，<b>真实执行</b>）→ 删夹行（第二步，<b>打桩抛错</b>）
 *       ⇒ 断言"条目被回滚"（R-02 的"同进同退"）；</li>
 *   <li>{@code moveItem}：插目标夹（第一步，<b>真实执行</b>）→ 删源夹（第二步，<b>打桩抛错</b>）
 *       ⇒ 断言"目标夹的插入被回滚"（先插后删的同事务语义）。</li>
 * </ul>
 *
 * <p>⚠️ 不对 MyBatis mapper 用 {@code doCallRealMethod().doThrow(...)}：mapper 是 JDK 动态代理、
 * 方法在 Mockito 眼里是抽象方法（同 {@code AdminTransactionTests} 的实测注记）。本类改用
 * {@code @MockitoSpyBean}（默认委托真实实现）+ **只打桩第二步**，达到同样效果。
 */
class FavoriteTransactionTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    @MockitoSpyBean
    private FavoriteItemDao itemDao;

    @MockitoSpyBean
    private FavoriteFolderDao folderDao;

    @BeforeEach
    void 清空本域相关表并复位打桩() {
        // 复位打桩必须先于夹具（避免上一用例的 stub 泄漏到本用例）。
        Mockito.reset(itemDao, folderDao);
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ========================================================================
    // I-04：deleteFolder 的事务边界（R-02「删夹一并删条目」的"同进同退"）
    // ========================================================================

    @Test
    @DisplayName("★机制证明(I-04)：删夹第二写(删夹行)失败 ⇒ @Transactional 回滚，第一步(删条目)复原")
    void 删夹第二步失败时删条目回滚() {
        TestUser me = register("13800006001", "事务删夹用户");
        long folder = insertFolderRow(me.id, "待删夹");
        insertItemRow(folder, me.id, insertContent(me.id));
        insertItemRow(folder, me.id, 990701L);   // 幽灵 id：失效记录也要一并被删（R-07 失效也计入）

        // 删夹行 = deleteFolder 的**第二步**（发生在删条目之后）—— 打桩让它抛错。
        doThrow(new IllegalStateException("boom: 模拟删夹行失败"))
                .when(folderDao).deleteById(anyLong());

        ResponseEntity<String> resp = removeFolder(me.token, folder);
        assertThat(resp.getStatusCode().value()).as("非业务异常 → 500").isEqualTo(500);

        assertThat(folderExists(folder)).as("删夹行失败 ⇒ 夹还在").isTrue();
        assertThat(oracleItemCount(folder))
                .as("★ @Transactional 必须真的生效：第二步失败要回滚第一步已写入的删条目（无事务则提交后 = 0）")
                .isEqualTo(2L);
    }

    // ========================================================================
    // T4 遗留：moveItem 的删除步失败回滚（先插后删，纯 HTTP 造不出"插入成功、删除失败"）
    // ========================================================================

    @Test
    @DisplayName("★机制证明(T4 遗留)：移动第二写(删源夹)失败 ⇒ @Transactional 回滚，目标夹插入复原")
    void 移动第二步失败时插入回滚() {
        TestUser me = register("13800006002", "事务移动用户");
        long from = insertFolderRow(me.id, "源夹");
        long to = insertFolderRow(me.id, "目标夹");
        long content = insertContent(me.id);
        insertItemRow(from, me.id, content);

        // 删源夹 = moveItem 的**第二步**（发生在插目标夹之后）—— 打桩让它抛错。
        doThrow(new IllegalStateException("boom: 模拟删源夹失败"))
                .when(itemDao).deleteByFolderAndContentIds(anyLong(), any());

        ResponseEntity<String> resp = moveItem(me.token, from, to, content);
        assertThat(resp.getStatusCode().value()).as("非业务异常 → 500").isEqualTo(500);

        assertThat(oracleItemCount(to))
                .as("★ @Transactional 必须真的生效：第二步失败要回滚第一步插进目标夹的行（无事务则提交后 = 1）")
                .isZero();
        assertThat(oracleItemCount(from)).as("源夹原样保留").isEqualTo(1L);
    }

    // ========================================================================
    // HTTP（写 = POST + form，与收藏域其余用例同款）
    // ========================================================================

    private ResponseEntity<String> removeFolder(String token, long folderId) {
        return postForm("/favorite/folder/remove", form("folderId", folderId), token);
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

    private static MultiValueMap<String, String> form(String name, long value) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(name, String.valueOf(value));
        return form;
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
            ps.setString(2, "T8 事务测试内容 " + authorId);
            ps.setString(3, "T8 事务测试描述");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
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

    private void insertItemRow(long folderId, long userId, long contentId) {
        jdbcTemplate.update("INSERT INTO favorite_item (folder_id, user_id, content_id) VALUES (?, ?, ?)",
                folderId, userId, contentId);
    }

    /** ★ 独立 oracle：夹内收藏**记录**数（不读接口回显）。 */
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
}
