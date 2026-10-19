package io.github.yjhhhaaa06.videoweb.coin;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 投币专项 T36 的**结构契约**验收：V5 建出的列 / 表 / 唯一键，是不是设计 §二 的那一版。
 *
 * <h2>为什么这类断言值得写（它防的是什么）</h2>
 * 行为面由 T37–T40 的用例覆盖；本类只钉**schema 级**三件事，它们的共同点是
 * "**后补要付 V6 + 存量回填**"：
 *
 * <ol>
 *   <li><b>列类型</b>：{@code users.coin_deci} 必须是**有符号** {@code int}
 *       （扣款靠条件守卫拦负数，不是靠 unsigned + CAST 技巧，见设计 §十 #6）；
 *       {@code content.coin_count} 是 {@code int unsigned} 且**只增不减**
 *       ⇒ 刻意**没有** like_count 那套防负守卫（口径差异，不是漏改）。</li>
 *   <li><b>唯一键</b>：{@code uk_user_content} 是"同一人同一内容只聚一行"的唯一实现手段；
 *       封顶 2 枚走的是这条行上的条件 {@code UPDATE}，没有它并发就会插出两行、
 *       {@code SUM(amount)} 直接错。</li>
 *   <li><b>索引</b>：{@code idx_content_id}（内容维度读）**没有可观察行为**可断言
 *       （不写索引功能照样对）⇒ 只能查 {@code information_schema}。
 *       ⚠️ 这不是在断言实现：删掉索引，本条立刻变红。</li>
 * </ol>
 *
 * <h2>断言选择：能"真插一行"就不查 catalog</h2>
 * 默认值（{@code 0} / {@code NULL}）与唯一键都走**真实 INSERT**：catalog 里写着
 * {@code DEFAULT 0} 不等于"省略该列时真的落 0"（生成列、隐式转换都可能让两者分叉）。
 * 只有"索引存在性"这类**纯结构、无行为**的东西才查 {@code information_schema}。
 *
 * <h2>反向验证（改哪一行必红）</h2>
 * 删 {@code coin_deci} 的 {@code DEFAULT 0} ⇒ {@link #新用户与内容的硬币列默认值生效()} 红；
 * 把 {@code coin_deci} 改成 {@code unsigned} ⇒ {@link #users的余额列是有符号十分位整数()} 红；
 * 删 {@code uk_user_content} ⇒ {@link #同一用户对同一内容只能有一行()} 红；
 * 删 {@code idx_content_id} ⇒ {@link #投币记录的索引就位()} 红。
 */
class CoinSchemaTests extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 清空本域相关表() {
        // 只清自己用到的表：本类造的内容/用户都带唯一前缀，不清 users（避免与别的类抢）。
        jdbcTemplate.update("DELETE FROM content_coin");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ==================== 1. 列类型（设计 §二 逐字） ====================

    @Test
    @DisplayName("users 的余额列是有符号十分位整数：int（不是 unsigned）、NOT NULL、默认 0")
    void users的余额列是有符号十分位整数() {
        Map<String, Object> col = 列信息("users", "coin_deci");
        assertThat(col.get("column_type"))
                .as("有符号 int：扣款靠条件守卫拦负数，给 unsigned 会让 CAST 技巧渗进来（设计 §十 #6）")
                .isEqualTo("int");
        assertThat(col.get("is_nullable")).as("余额不该有 NULL 态").isEqualTo("NO");
        assertThat(col.get("column_default")).as("存量用户的起点 = 0").isEqualTo("0");
    }

    @Test
    @DisplayName("users 的签到日是可为空的 date：口径是「哪一天」，与时刻无关")
    void users的签到日是可为空的date() {
        Map<String, Object> col = 列信息("users", "last_sign_date");
        assertThat(col.get("column_type")).as("date 而非 datetime").isEqualTo("date");
        assertThat(col.get("is_nullable"))
                .as("从未签到的用户必须是 NULL —— 签到幂等的条件写靠 (NULL OR < today) 判据")
                .isEqualTo("YES");
        assertThat(col.get("column_default")).as("无默认值：未签到就是未知，不是某个具体日期").isNull();
    }

    @Test
    @DisplayName("content 的被投币数是 unsigned 且 NOT NULL 默认 0（只增不减 ⇒ 无需防负守卫）")
    void content的被投币数是非负整数() {
        Map<String, Object> col = 列信息("content", "coin_count");
        assertThat(col.get("column_type"))
                .as("与 like_count / comment_count 同为 unsigned；本列只增，故不需要 CAST 守卫")
                .isEqualTo("int unsigned");
        assertThat(col.get("is_nullable")).as("计数列不该有 NULL 态").isEqualTo("NO");
        assertThat(col.get("column_default")).isEqualTo("0");
    }

    // ==================== 2. 默认值（行为侧：真插一行，省略该列） ====================

    @Test
    @DisplayName("新用户与内容的硬币列默认值生效：余额 0 分、签到日 NULL、被投币数 0")
    void 新用户与内容的硬币列默认值生效() {
        long userId = 插入用户("coinschema_default_user");
        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT coin_deci, last_sign_date FROM users WHERE id = ?", userId);
        assertThat(user.get("coin_deci")).as("新用户 0 枚硬币（需求 §二：注册不送币）")
                .isEqualTo(0);
        assertThat(user.get("last_sign_date")).as("新用户从未签到").isNull();

        long contentId = 插入内容(userId);
        Integer coinCount = jdbcTemplate.queryForObject(
                "SELECT coin_count FROM content WHERE id = ?", Integer.class, contentId);
        assertThat(coinCount).as("新内容还没被投过币").isZero();
    }

    // ==================== 3. 唯一键（真插两行，看它拦不拦） ====================

    @Test
    @DisplayName("★同一用户对同一内容只能有一行：重复投币撞 uk_user_content")
    void 同一用户对同一内容只能有一行() {
        long authorId = 插入用户("coinschema_author");
        long contentId = 插入内容(authorId);
        long voterId = 插入用户("coinschema_voter");
        jdbcTemplate.update("INSERT INTO content_coin (user_id, content_id, amount) VALUES (?, ?, ?)",
                voterId, contentId, 1);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO content_coin (user_id, content_id, amount) VALUES (?, ?, ?)",
                voterId, contentId, 1))
                .as("同一 (user_id, content_id) 的重插必须被唯一键拒绝——"
                        + "封顶走的是这行上的条件 UPDATE，重复行会让 SUM(amount) 直接错")
                .isInstanceOf(DuplicateKeyException.class);

        // 唯一键的**边界**：另一人对同一内容、同一人对另一条内容都必须成功
        //（键只约束 (user_id, content_id) 这一对）
        long otherVoterId = 插入用户("coinschema_voter2");
        long otherContentId = 插入内容(authorId);
        jdbcTemplate.update("INSERT INTO content_coin (user_id, content_id, amount) VALUES (?, ?, ?)",
                otherVoterId, contentId, 2);
        jdbcTemplate.update("INSERT INTO content_coin (user_id, content_id, amount) VALUES (?, ?, ?)",
                voterId, otherContentId, 1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_coin", Long.class)).isEqualTo(3L);
    }

    @Test
    @DisplayName("投币记录的索引就位：uk_user_content（用户维度聚合）+ idx_content_id（内容维度复算）")
    void 投币记录的索引就位() {
        Map<String, String> keys = 全部索引("content_coin");
        assertThat(keys).containsEntry("uk_user_content", "user_id,content_id")
                .containsEntry("idx_content_id", "content_id");

        // 索引全集逐一对账：多一个都要显式认账。
        // ★ 刻意**没有** idx_user_id —— uk_user_content 的前导列就是 user_id（同 favorite_item 的取舍）。
        assertThat(keys).containsOnlyKeys("PRIMARY", "uk_user_content", "idx_content_id");
    }

    // ==================== 夹具与 oracle ====================

    private Map<String, Object> 列信息(String table, String column) {
        return jdbcTemplate.queryForMap(
                "SELECT column_type, is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                table, column);
    }

    /** 表上的**全部**索引：{@code {索引名 → 逗号分隔的列序列}}（含 PRIMARY 与唯一键）。 */
    private Map<String, String> 全部索引(String table) {
        return jdbcTemplate.query(
                "SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols "
                        + "FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? "
                        + "GROUP BY index_name",
                CoinSchemaTests::按索引名聚成映射, table);
    }

    private static Map<String, String> 按索引名聚成映射(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, String> map = new LinkedHashMap<>();
        while (rs.next()) {
            map.put(rs.getString("index_name"), rs.getString("cols"));
        }
        return map;
    }

    private long 插入用户(String username) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO users (username, hashed_password) VALUES (?, ?)",
                    new String[]{"id"});
            ps.setString(1, username);
            ps.setString(2, "not-a-real-hash");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("users 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    private long 插入内容(long authorId) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description) VALUES (?, 1, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setString(2, "T36 结构用例内容");
            ps.setString(3, "T36 结构用例描述");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }
}
