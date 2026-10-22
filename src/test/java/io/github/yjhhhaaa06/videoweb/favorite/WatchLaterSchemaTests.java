package io.github.yjhhhaaa06.videoweb.favorite;

import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 收藏三期 <b>T17</b> 的**结构契约**验收：V4 建出的 {@code watch_later} 表一次到位，
 * 且**全部迁移版本都被应用**（防 {@code I-02} 的"只断首行"假绿）。
 *
 * <h2>覆盖什么（期望值出自favorite.md §九 的 DDL 与口径，盖住实现照样写得出来）</h2>
 * <ul>
 *   <li><b>版本行</b>：{@code flyway_schema_history} 里 V1 / V2 / V3 / V4 / V5 / V6 / V7 / V8 **全部 applied 且 success**
 *       —— ⚠️ 只断"某一行存在"会漏掉"最新一版没跑"的真相（I-02）；</li>
 *   <li><b>列集</b>：{@code id / user_id / content_id / create_time}（favorite.md §九 的 DDL）；</li>
 *   <li>★ <b>唯一键 {@code uk_user_content (user_id, content_id)}</b>：T17「重复 add ⇒ 移到最前」的
 *       实现手段是 {@code ON DUPLICATE KEY UPDATE} —— 撞的就是它。丢了这个键，
 *       "重复 add"就会**插出两行**（列表里出现两条同视频）；</li>
 *   <li><b>索引全集</b>：{@code PRIMARY} / {@code uk_user_content} / {@code idx_user_time} ——
 *       多一个都要显式认账（无冗余索引，同 V2 两表的口径）。</li>
 * </ul>
 *
 * <h2>反向验证（注入 ⇒ 必红）</h2>
 * 删 {@code uk_user_content} ⇒ 唯一键用例变红；删 V4 文件（或不写该表）⇒ 版本行/列集/索引用例变红。
 * ⚠️ 断言**可观察行为**优先：唯一键那条是**真插两行**看它拦不拦（查得到索引名 ≠ 它真的起作用）。
 */
class WatchLaterSchemaTests extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("★全部迁移版本都被应用（V1~V8 且 success）—— 不止最新一版，防 I-02 的'只断首行'假绿")
    void 全部迁移版本都被应用() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT version, success FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank");
        List<String> appliedVersions = rows.stream().map(r -> String.valueOf(r.get("version"))).toList();
        assertThat(appliedVersions)
                .as("★ V1 / V2 / V3 **已应用且必须先于 V4 / V5 应用** —— 只看'有没有最新版'会漏掉'中间版本没跑'")
                .startsWith("1", "2", "3")
                .contains("4");
        assertThat(appliedVersions.get(0)).as("首版本必须是 V1（baseline）").isEqualTo("1");
        // ★ "末版本认账"锚点：每加一版迁移都要显式改这一行（T36 的 V5 = 投币专项；
        //   T45 的 V6 = 播放记录、V7 = 删遗留表；V8 = 播放记录列表覆盖索引）。
        //   它不是"跟着实现改断言"，而是把"新增的版本有没有真的被应用"变成一次人工确认。
        assertThat(appliedVersions).as("V8 必须是最后一个已应用版本（V8 = 播放记录列表覆盖索引）").endsWith("8");
    }

    @Test
    @DisplayName("V4 建出 watch_later，列集与favorite.md §九 的 DDL 一致")
    void 表与列集与骨架一致() {
        assertThat(列名("watch_later"))
                .as("列集 = favorite.md §九 的 DDL（user_id / content_id / create_time + 自增 id）")
                .containsExactly("id", "user_id", "content_id", "create_time");
    }

    @Test
    @DisplayName("★同一用户同一内容只能有一条：重复插入撞 uk_user_content（T17'移到最前'靠它）")
    void 同一用户同一内容撞唯一键() {
        jdbcTemplate.update("INSERT INTO watch_later (user_id, content_id) VALUES (?, ?)", 1L, 100L);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO watch_later (user_id, content_id) VALUES (?, ?)", 1L, 100L))
                .as("同一 (user_id, content_id) 必须被唯一键拒绝（否则重复 add 会插出两条同视频）")
                .isInstanceOf(DuplicateKeyException.class);

        // 唯一键的**边界**：同内容进另一个用户、同用户放另一个内容都必须成功
        //（键只约束 (user_id, content_id)）
        jdbcTemplate.update("INSERT INTO watch_later (user_id, content_id) VALUES (?, ?)", 2L, 100L);
        jdbcTemplate.update("INSERT INTO watch_later (user_id, content_id) VALUES (?, ?)", 1L, 200L);
        assertThat(计数(1L)).isEqualTo(2L);
        assertThat(计数(2L)).isEqualTo(1L);
    }

    @Test
    @DisplayName("索引全集：PRIMARY + uk_user_content(user_id,content_id) + idx_user_time(user_id,create_time)，无冗余")
    void 索引全集就位() {
        assertThat(全部索引("watch_later"))
                .as("索引全集逐一对账：不加任何冗余索引（同 V2 两表口径）")
                .containsEntry("uk_user_content", "user_id,content_id")
                .containsEntry("idx_user_time", "user_id,create_time")
                .containsOnlyKeys("PRIMARY", "uk_user_content", "idx_user_time");
    }

    // ==================== 夹具与 oracle ====================

    private List<String> 列名(String table) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ordinal_position",
                String.class, table);
    }

    private Map<String, String> 全部索引(String table) {
        return jdbcTemplate.query(
                "SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols "
                        + "FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? "
                        + "GROUP BY index_name",
                rs -> {
                    Map<String, String> map = new LinkedHashMap<>();
                    while (rs.next()) {
                        map.put(rs.getString("index_name"), rs.getString("cols"));
                    }
                    return map;
                }, table);
    }

    /** 独立 oracle：该用户在稍后再看里的行数（直查表，不信接口）。 */
    private long 计数(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_later WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }
}
