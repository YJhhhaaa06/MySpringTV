package io.github.yjhhhaaa06.videoweb.favorite;

import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 收藏一期 T1 的**结构契约**验收：V2 建出的表、列、唯一键，是不是"一次到位"的那一版。
 *
 * <h2>这类断言为什么值得写（它防的是什么）</h2>
 * 一期功能的行为由 T2~T6 的用例覆盖；本类只钉**schema 级**的三条，它们都是
 * "**后补要付 V3 + 存量回填**"的东西（分期篇 §一「错位 2：后期里混了必须一次到位的东西」）：
 *
 * <ol>
 *   <li><b>列集</b>：{@code is_private} / {@code description}（R-05）、{@code favorite_count}（R-06）
 *       —— 建表时带上零成本，之后补要新版本号 + 回填存量行。</li>
 *   <li><b>唯一键</b>：{@code uk_folder_content} 是 T4「重复收藏 409」的唯一实现手段
 *       （不靠"先查后插"）；{@code uk_user_default} 是 T2「默认夹懒建」在并发下**唯一**的
 *       防重复手段。两者丢一个，对应任务就只能退回"靠时序运气"。</li>
 *   <li><b>索引</b>：{@code idx_user_content}（按人去重）与 {@code idx_folder_time}（夹内倒序分页）
 *       —— 这两个**没有可观察行为**可断言（不写索引功能照样对），故只能在这里查
 *       {@code information_schema}。⚠️ 但它们**不是**在断言实现：去掉索引，本条立刻变红。</li>
 * </ol>
 *
 * <h2>反向验证（T8 会复核）</h2>
 * 逐条都能"注入缺陷 ⇒ 变红"：删 {@code is_private} 列 / 删 {@code uk_folder_content} 键 /
 * 删 {@code uk_user_default} 键，对应用例必然失败。判据见仓库根 {@code AGENTS.md}「测试纪律」④。
 *
 * <p>断言**可观察行为**优先：唯一键的两条不是查 {@code information_schema}，而是**真插两行**
 * 看它拦不拦 —— 查得到索引名不等于它真的起作用（生成列 + 唯一键这种组合尤其要实测）。
 */
class FavoriteSchemaTests extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 清空本域相关表() {
        // 只清自己用到的表：本类不碰 users，也不需要跟别人抢（表全新建、无并发测试类）。
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ==================== 1. 列集（一次到位的那些列） ====================

    @Test
    @DisplayName("V2 建出收藏两表，且列集与骨架一致（含 is_private / description / favorite_count）")
    void 收藏两表的列集与骨架一致() {
        assertThat(列名("favorite_folder")).as("favorite_folder 的列（default_uniq 是默认夹唯一键的判别列，见 §2）")
                .containsExactly("id", "user_id", "name", "description", "is_private", "is_default",
                        "default_uniq", "create_time", "update_time");

        assertThat(列名("favorite_item")).as("favorite_item 的列（user_id 是「按人去重」的冗余列）")
                .containsExactly("id", "folder_id", "user_id", "content_id", "create_time");
    }

    @Test
    @DisplayName("content 增加了 favorite_count 列：一期只建不用，但默认值必须是 0（二期回填的起点）")
    void content的收藏数列建好且默认0() {
        Map<String, Object> col = jdbcTemplate.queryForMap(
                "SELECT is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'content' AND column_name = 'favorite_count'");
        assertThat(col.get("is_nullable")).as("计数列不该有 NULL 态").isEqualTo("NO");
        assertThat(col.get("column_default")).as("存量行的起点 = 0；二期回填前该列全是 0").isEqualTo("0");

        // 行为侧：不带该列插入的内容行，读出来必须是 0（默认值真的生效）
        long contentId = 插入内容(9001L);
        Integer value = jdbcTemplate.queryForObject(
                "SELECT favorite_count FROM content WHERE id = ?", Integer.class, contentId);
        assertThat(value).isZero();
    }

    // ==================== 2. 唯一键（真插两行，看它拦不拦） ====================

    @Test
    @DisplayName("★同一夹内同一内容只能有一条：重复收藏撞 uk_folder_content（T4 的 409 就靠它）")
    void 同一夹内重复收藏撞唯一键() {
        long folderId = 插入收藏夹(1L, "自建夹", 0);
        long contentId = 插入内容(9001L);
        jdbcTemplate.update("INSERT INTO favorite_item (folder_id, user_id, content_id) VALUES (?, ?, ?)",
                folderId, 1L, contentId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO favorite_item (folder_id, user_id, content_id) VALUES (?, ?, ?)",
                folderId, 1L, contentId))
                .as("同一夹重复收藏必须被唯一键拒绝（否则并发下会插出两条）")
                .isInstanceOf(DuplicateKeyException.class);

        // 唯一键的**边界**：同内容进另一个夹、同夹放另一个内容都必须成功
        //（收藏数按人去重、一个内容可进多个夹 —— 键只约束 (folder_id, content_id)）
        long otherFolderId = 插入收藏夹(1L, "另一个夹", 0);
        long otherContentId = 插入内容(9001L);
        jdbcTemplate.update("INSERT INTO favorite_item (folder_id, user_id, content_id) VALUES (?, ?, ?)",
                otherFolderId, 1L, contentId);
        jdbcTemplate.update("INSERT INTO favorite_item (folder_id, user_id, content_id) VALUES (?, ?, ?)",
                folderId, 1L, otherContentId);
        assertThat(收藏记录数(folderId)).isEqualTo(2L);
    }

    @Test
    @DisplayName("★每用户至多一个默认夹，自建夹不受限（T2 懒建的并发靠 uk_user_default 兜住）")
    void 每用户至多一个默认夹() {
        assertThat(列名("favorite_folder")).as("判别列必须在（唯一键挂在它上面）").contains("default_uniq");
        assertThat(唯一索引("favorite_folder")).containsEntry("uk_user_default", "user_id,default_uniq");
        assertThat(全部索引("favorite_folder")).as("uk_user_default 前导列已是 user_id，不该再有冗余索引")
                .containsOnlyKeys("PRIMARY", "uk_user_default");

        long first = 插入收藏夹(7L, "默认收藏夹", 1);
        assertThat(first).isPositive();

        assertThatThrownBy(() -> 插入收藏夹(7L, "第二个默认夹", 1))
                .as("同一用户第二个默认夹必须被拒（两个事务同时懒建时，先提交的赢，后到的撞键）")
                .isInstanceOf(DuplicateKeyException.class);

        // 边界：同一用户建任意多个**自建夹**必须都成功（唯一索引不约束 NULL 才成立）
        插入收藏夹(7L, "自建 1", 0);
        插入收藏夹(7L, "自建 2", 0);
        插入收藏夹(7L, "自建 3", 0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_folder WHERE user_id = ?", Long.class, 7L)).isEqualTo(4L);

        // 另一个用户的默认夹不受影响（键是 (user_id, default_uniq)）
        插入收藏夹(8L, "默认收藏夹", 1);
    }

    @Test
    @DisplayName("favorite_item 的另两个索引就位：idx_user_content（按人去重）、idx_folder_time（夹内倒序分页）")
    void 收藏记录的两个查询索引就位() {
        Map<String, String> keys = 全部索引("favorite_item");
        assertThat(keys).containsEntry("uk_folder_content", "folder_id,content_id")
                .containsEntry("idx_user_content", "user_id,content_id")
                .containsEntry("idx_folder_time", "folder_id,create_time");

        // 索引全集逐一对账：多一个都要显式认账 —— 一期**刻意没有** content_id 前导索引
        //（收藏数走 COUNT(DISTINCT user_id) WHERE content_id = ? 的"笨查询"，
        //  它的耗时基线是二期优化的对照，T5 要求记录；先加索引就把基线毁了）。
        assertThat(keys).containsOnlyKeys("PRIMARY", "uk_folder_content", "idx_user_content", "idx_folder_time");
    }

    // ==================== 夹具与 oracle ====================

    private List<String> 列名(String table) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ordinal_position",
                String.class, table);
    }

    /** 表上的**全部**索引：{@code {索引名 → 逗号分隔的列序列}}（含 PRIMARY 与唯一键）。 */
    private Map<String, String> 全部索引(String table) {
        return jdbcTemplate.query(
                "SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols "
                        + "FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? "
                        + "GROUP BY index_name",
                FavoriteSchemaTests::按索引名聚成映射, table);
    }

    /** 表上的**唯一**索引（{@code non_unique = 0}，含 PRIMARY）。 */
    private Map<String, String> 唯一索引(String table) {
        return jdbcTemplate.query(
                "SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols "
                        + "FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND non_unique = 0 "
                        + "GROUP BY index_name",
                FavoriteSchemaTests::按索引名聚成映射, table);
    }

    private static Map<String, String> 按索引名聚成映射(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, String> map = new LinkedHashMap<>();
        while (rs.next()) {
            map.put(rs.getString("index_name"), rs.getString("cols"));
        }
        return map;
    }

    private long 插入收藏夹(long userId, String name, int isDefault) {
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

    private long 插入内容(long authorId) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description) VALUES (?, 1, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setString(2, "T1 结构用例内容");
            ps.setString(3, "T1 结构用例描述");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    private long 收藏记录数(long folderId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_item WHERE folder_id = ?", Long.class, folderId);
        return n == null ? 0L : n;
    }
}
