package io.github.yjhhhaaa06.videoweb.history;

import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
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
 * 播放记录域的**结构契约**验收：V6 建出的两列 + {@code watch_history} 一次到位，
 * V7 真的把两张遗留表删掉，V8 把列表索引换成覆盖复合索引，V9 只给 users 加一列（不动本域结构），
 * V10 建出播放量去重账本并把 {@code play_counted} / {@code idx_content_id} 拆掉，
 * 且**全部迁移版本都被应用**。
 *
 * <h2>覆盖什么（期望值出自 history.md §二 / §五 的 DDL，盖住实现照样写得出来）</h2>
 * <ul>
 *   <li><b>版本行</b>：{@code flyway_schema_history} 里 V1…V10 **全部 applied 且 success**；</li>
 *   <li><b>列集</b>：{@code id / user_id / content_id / progress_seconds / create_time / update_time}
 *       （`R-26` 起本表只存进度，{@code play_counted} 已搬去账本）；</li>
 *   <li>★ <b>唯一键 {@code uk_user_content (user_id, content_id)}</b>：本域"一人一内容一行"
 *       的实现手段是 {@code ON DUPLICATE KEY UPDATE} / {@code INSERT IGNORE} —— 两者撞的都是它。
 *       丢了这个键，"重复上报"会**插出两行**（历史里出现同一视频两条）
 *       （首播判定已与它无关：`R-26` 起判据是账本的 {@code (user_id, content_id, play_date)}）；</li>
 *   <li><b>索引全集</b>：{@code PRIMARY} / {@code uk_user_content} / {@code idx_user_update_content}
 *       —— 多一个都要显式认账。V8 用
 *       {@code (user_id, update_time DESC, content_id DESC, progress_seconds)} **替换**了 V6 的
 *       {@code idx_user_update}：前三列匹配列表 {@code ORDER BY}，第四列让三列 SELECT 免回表
 *       （EXPLAIN 基线见 history.md §七.1；实测 5k 行 3.15ms→0.10ms、50k 行 37.8ms→0.2ms）；
 *       ★ V10 又拆掉 {@code idx_content_id}（它唯一服务的"不变量复算"已搬到账本）；</li>
 *   <li>★ <b>账本结构</b>：主键 = {@code (user_id, content_id, play_date)} —— 三列缺一，
 *       "每天一次"就退化成"永久一次"或"每次一次"；{@code play_date} 必须是 {@code date}；</li>
 *   <li><b>两列</b>：{@code content_media.duration_seconds}（可空 —— NULL 就是"未知"）、
 *       {@code content.play_count}（{@code unsigned NOT NULL DEFAULT 0}）；</li>
 *   <li>★ <b>遗留表真的没了</b>：{@code video} / {@code videoinfo} 在 {@code information_schema} 里查不到
 *       —— "V7 文件存在"不等于"V7 被应用"，这条是它唯一的可观察证据。</li>
 * </ul>
 *
 * <h2>反向验证（注入 ⇒ 必红）</h2>
 * 删掉 V7 文件 ⇒ 遗留表用例 + 版本行用例变红；删掉 V6 的 {@code uk_user_content}
 * ⇒ 唯一键用例变红（真插两行看它拦不拦）；把 {@code duration_seconds} 改成 NOT NULL
 * ⇒ 列属性用例变红（NULL = 未知是本域的核心口径）；回退 V8 的索引（恢复成 V6 的
 * {@code idx_user_update}）⇒ 索引全集用例变红（"优化是否还在"的可执行锚点；查询计划的
 * filesort 证据是手工 EXPLAIN 基线，不是本用例的断言 —— 小夹具下优化器可能不用该索引）；
 * ★ 把 V10 的主键改成 {@code (user_id, content_id)} ⇒ 账本用例变红（同一天插两次会被拒、
 * 换一天也插不进去 ⇒ "每天一次"退化成"永久一次"）。
 */
class HistorySchemaTests extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanWatchHistory() {
        jdbcTemplate.update("DELETE FROM watch_history");
        jdbcTemplate.update("DELETE FROM content_play_dedup");
    }

    @Test
    @DisplayName("★全部迁移版本都被应用（V1~V10 且 success）—— V7 的删表 / V8 的换索引 / V10 的搬标记也要真的跑到")
    void 全部迁移版本都被应用() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT version, success FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank");
        List<String> appliedVersions = rows.stream().map(r -> String.valueOf(r.get("version"))).toList();
        assertThat(appliedVersions)
                .as("★ 版本序必须连续 1..10：V6 建播放记录、V7 删遗留表、V8 换列表索引、V9 users.history_recording、"
                        + "V10 建去重账本并拆掉 play_counted（缺一都让上层结构对不上）")
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    }

    @Test
    @DisplayName("V6 建出 watch_history，列集与 history.md §二 的 DDL 一致（R-26 起不再有 play_counted）")
    void 表与列集与设计一致() {
        assertThat(列名("watch_history"))
                .as("列集 = history.md §二的 DDL；★ play_counted 已在 V10 搬进 content_play_dedup"
                        + "（本表只存进度 ⇒ 删除历史不影响播放量）")
                .containsExactly("id", "user_id", "content_id", "progress_seconds",
                        "create_time", "update_time");
    }

    @Test
    @DisplayName("★播放量账本：主键 = (user_id, content_id, play_date)，同一天插两次被拒、换一天插得进去")
    void 播放量账本结构就位() {
        assertThat(列名("content_play_dedup"))
                .as("账本列集（play_date 是业务日、create_time 只是记账时刻）")
                .containsExactly("user_id", "content_id", "play_date", "create_time");
        assertThat(列定义("content_play_dedup", "play_date").get("data_type"))
                .as("★ 用 date 而不是 datetime：口径是'哪个自然日'，精确时刻由 create_time 承担")
                .isEqualTo("date");

        assertThat(全部索引("content_play_dedup"))
                .as("主键三列缺一不可：少 play_date ⇒ 退化成'永久一次'；少 content_id ⇒ 退化成'每人每天一次'")
                .containsEntry("PRIMARY", "user_id,content_id,play_date")
                .containsEntry("idx_date", "play_date")
                .containsOnlyKeys("PRIMARY", "idx_date");

        jdbcTemplate.update("INSERT INTO content_play_dedup (user_id, content_id, play_date) VALUES (?, ?, ?)",
                1L, 100L, "2026-10-31");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO content_play_dedup (user_id, content_id, play_date) VALUES (?, ?, ?)",
                1L, 100L, "2026-10-31"))
                .as("同一天同一对 (user, content) 只能记一次（这就是'每天最多一次'的实现手段）")
                .isInstanceOf(DuplicateKeyException.class);

        // 边界：换一天 / 换内容 / 换用户都必须插得进去
        jdbcTemplate.update("INSERT INTO content_play_dedup (user_id, content_id, play_date) VALUES (?, ?, ?)",
                1L, 100L, "2026-11-01");
        jdbcTemplate.update("INSERT INTO content_play_dedup (user_id, content_id, play_date) VALUES (?, ?, ?)",
                1L, 200L, "2026-10-31");
        jdbcTemplate.update("INSERT INTO content_play_dedup (user_id, content_id, play_date) VALUES (?, ?, ?)",
                2L, 100L, "2026-10-31");
        assertThat(账本行数()).isEqualTo(4L);
    }

    @Test
    @DisplayName("★同一用户同一内容只能有一条：重复插入撞 uk_user_content（ODKU / INSERT IGNORE 靠它）")
    void 同一用户同一内容撞唯一键() {
        jdbcTemplate.update("INSERT INTO watch_history (user_id, content_id, progress_seconds) VALUES (?, ?, ?)",
                1L, 100L, 5);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO watch_history (user_id, content_id, progress_seconds) VALUES (?, ?, ?)", 1L, 100L, 9))
                .as("同一 (user_id, content_id) 必须被唯一键拒绝（否则重复上报会插出两条同视频）")
                .isInstanceOf(DuplicateKeyException.class);

        // 唯一键的**边界**：同内容进另一个用户、同用户放另一个内容都必须成功
        //（键只约束 (user_id, content_id) —— 少一个维度就退化成"全站一条"或"一人多条"）
        jdbcTemplate.update("INSERT INTO watch_history (user_id, content_id) VALUES (?, ?)", 2L, 100L);
        jdbcTemplate.update("INSERT INTO watch_history (user_id, content_id) VALUES (?, ?)", 1L, 200L);
        assertThat(计数(1L)).isEqualTo(2L);
        assertThat(计数(2L)).isEqualTo(1L);
    }

    @Test
    @DisplayName("索引全集：PRIMARY + uk_user_content + idx_user_update_content（V10 已拆掉死索引 idx_content_id）")
    void 索引全集就位() {
        assertThat(全部索引("watch_history"))
                .as("索引全集逐一对账：列表索引 V8 覆盖 ORDER BY + 三列；★ idx_content_id 随 play_counted 一起拆掉"
                        + "（它唯一服务的'不变量复算'已搬到账本，留着就是每次上报都付费的死索引）")
                .containsEntry("uk_user_content", "user_id,content_id")
                .containsEntry("idx_user_update_content", "user_id,update_time,content_id,progress_seconds")
                .containsOnlyKeys("PRIMARY", "uk_user_content", "idx_user_update_content");
    }

    @Test
    @DisplayName("V6 的两列：content_media.duration_seconds 可空（NULL=未知）、content.play_count 只增计数")
    void 两列就位且属性符合口径() {
        Map<String, Object> duration = 列定义("content_media", "duration_seconds");
        assertThat(duration).as("content_media.duration_seconds 必须存在").isNotNull();
        assertThat(duration.get("data_type")).as("秒数用 int").isEqualTo("int");
        assertThat(duration.get("is_nullable"))
                .as("★ 必须可空：NULL = 解析失败 / 图文 / 存量未回填（history.md §六.2）")
                .isEqualTo("YES");

        Map<String, Object> playCount = 列定义("content", "play_count");
        assertThat(playCount).as("content.play_count 必须存在").isNotNull();
        assertThat(playCount.get("data_type")).as("计数用 int").isEqualTo("int");
        assertThat(playCount.get("is_nullable")).as("非空：默认 0").isEqualTo("NO");
        assertThat(String.valueOf(playCount.get("column_default"))).as("默认 0").isEqualTo("0");
        assertThat(String.valueOf(playCount.get("column_type")))
                .as("★ unsigned：播放量只增（没有取消播放）⇒ 不需要 CAST 防负守卫")
                .startsWith("int unsigned");
    }

    @Test
    @DisplayName("★V7 真的删掉了遗留表：video / videoinfo 在 information_schema 里查不到")
    void 遗留表已删除() {
        assertThat(表存在("video")).as("video 是 TV 遗留表（src/ 零引用），V7 必须删掉").isFalse();
        assertThat(表存在("videoinfo")).as("videoinfo 同上").isFalse();
    }

    // ==================== 夹具与 oracle ====================

    private List<String> 列名(String table) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ordinal_position",
                String.class, table);
    }

    private Map<String, Object> 列定义(String table, String column) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT data_type, column_type, is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                table, column);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 独立 oracle：表是否存在（直查 information_schema，不信迁移脚本"写没写 DROP"）。 */
    private boolean 表存在(String table) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?", Long.class, table);
        return n != null && n > 0;
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

    /** 独立 oracle：该用户的播放记录行数（直查表，不信接口）。 */
    private long 计数(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_history WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }

    /** 独立 oracle：账本总行数（`R-26` 起"计过数的人次"的唯一载体）。 */
    private long 账本行数() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content_play_dedup", Long.class);
        return n == null ? 0L : n;
    }
}
