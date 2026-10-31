package io.github.yjhhhaaa06.videoweb.history;

import io.github.yjhhhaaa06.videoweb.content.AbstractContentIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 历史删除（`DELETE /api/v1/history`，`R-26` / `T77`）的**行为契约**。
 *
 * <h2>它为什么能存在</h2>
 * 旧口径下去重标记 {@code play_counted} 长在历史行上 ⇒ 删行会丢掉"这个人计过数"的证据 ⇒
 * 再播被判首播、播放量重复 +1，于是"历史不可删"成了登记过的代价。`R-26` 把证据搬进账本
 * {@code content_play_dedup}（日期进主键）后，删行**碰不到计数**。本类钉的就是这条链。
 *
 * <h2>断什么（期望值全部出自契约，不看出实现）</h2>
 * <table>
 *   <caption>四组</caption>
 *   <tr><th>面</th><th>口径</th></tr>
 *   <tr><td>作用域</td><td>只删**当前用户**的行；别人的行、没选中的行都不动</td></tr>
 *   <tr><td>幂等</td><td>重复删 / 行不存在 / id 打错 ⇒ **204**；★ 但**缺参** ⇒ 400（两件事不能混）</td></tr>
 *   <tr><td>与播放量的关系</td><td>删历史**不减** {@code play_count}；同一天再播**不再计**，换一天再播**再计一次**</td></tr>
 *   <tr><td>与缓冲的关系</td><td>删完 `/flush` 不得把行写回来（否则用户看到"删了又回来"）</td></tr>
 * </table>
 *
 * <h2>反向验证（注入 ⇒ 必红）</h2>
 * ① {@code deleteByUserIdAndContentIds} 去掉 {@code user_id = ?} ⇒ "别人那条不受影响"红；
 * ② 把删除接上 {@code UPDATE content SET play_count = play_count - 1} ⇒ "删历史不减播放量"红；
 * ③ {@code /play} 的账本判定忽略 {@code play_date}（改成只查 user+content 有无行）⇒
 *    "次日再播再计"红；④ 去掉 {@code forgetBuffered} 的调用 ⇒ 两条缓冲用例红（行被 flush 写回来）。
 * <p>⚠️ 轮询周期推到 1h：本类手动 `/flush`，而 {@code HistoryFlushScheduler} 也会按周期兜底 ——
 * 两边同时动同一批键会让"缓冲里还有 / 已经没了"这类断言偶发红。
 */
@TestPropertySource(properties = {
        "video.history.buffer.poll-interval=1h",
        "video.history.buffer.buffer-ttl=3h"
})
class HistoryDeleteTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900000021";
    private static final String AUTHOR = "delete-author";
    private static final String VIEWER_PHONE = "13900000022";
    private static final String VIEWER = "delete-viewer";
    private static final String OTHER_PHONE = "13900000023";
    private static final String OTHER = "delete-viewer-2";

    /** 业务日（与 {@code PlayDay} 同一条口径：Asia/Shanghai 的日历日）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private String viewerToken;
    private long viewerId;
    private long authorId;
    private String otherToken;
    private long otherId;

    @BeforeEach
    void setUpUsers() {
        // 父类 @BeforeEach 先清 users / content / Redis，这里重新注册。
        viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);
        viewerId = userIdOf(VIEWER_PHONE);
        otherToken = registerAndGetToken(OTHER_PHONE, OTHER);
        otherId = userIdOf(OTHER_PHONE);
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        authorId = userIdOf(AUTHOR_PHONE);
        jdbcTemplate.update("DELETE FROM watch_history");
        jdbcTemplate.update("DELETE FROM content_play_dedup");
    }

    // ==================== 作用域 ====================

    @Test
    @DisplayName("★批量删除只删选中的：没选中的、以及**别人的**同内容历史都不受影响")
    void 批量删除只删选中的() {
        long c1 = insertVideoContent(authorId, "删除 A");
        long c2 = insertVideoContent(authorId, "删除 B");
        long c3 = insertVideoContent(authorId, "删除 C");
        play(c1);
        play(c2);
        play(c3);
        playAs(otherToken, c1);

        ResponseEntity<String> resp = delete("/api/v1/history?contentIds=" + c1 + "," + c2, viewerToken);

        assertThat(resp.getStatusCode().value()).as("删除成功 = 204 无 body（§2.4）").isEqualTo(204);
        assertThat(resp.getBody()).as("204 不带信封").isNull();
        assertThat(历史内容Ids(viewerId))
                .as("★ 只删选中的两条，c3 必须还在（否则就是'顺手删多了'）")
                .containsExactly(c3);
        assertThat(历史内容Ids(otherId))
                .as("★ 删除作用域是当前登录用户：别人看同一个视频的历史一行都不能动")
                .containsExactly(c1);
    }

    @Test
    @DisplayName("★重复删除 / 行不存在 / id 不属于我 ⇒ 都 204（幂等），且不误伤别人")
    void 删除是幂等的() {
        long mine = insertVideoContent(authorId, "删除幂等 我的");
        play(mine);                       // 只有我有历史
        playAs(otherToken, mine);         // 对方也有（对照）

        assertThat(delete("/api/v1/history?contentIds=" + mine, viewerToken).getStatusCode().value())
                .as("第一次").isEqualTo(204);
        assertThat(delete("/api/v1/history?contentIds=" + mine, viewerToken).getStatusCode().value())
                .as("★ 第二次：没有这条记录了，仍然是 204（不报 404 / 409）").isEqualTo(204);
        assertThat(delete("/api/v1/history?contentIds=999999", viewerToken).getStatusCode().value())
                .as("从来没有过的 id 也 204（与'缺参 400'是两件事）").isEqualTo(204);

        assertThat(历史内容Ids(viewerId)).isEmpty();
        assertThat(历史内容Ids(otherId))
                .as("★ 幂等删自己的记录，不能顺着 contentId 把别人的也删掉")
                .containsExactly(mine);
    }

    // ==================== 端点形态 ====================

    @Test
    @DisplayName("★参数形态：缺参 400、contentIds 与 all 互斥 400、超过 500 条 400")
    void 删除参数错误路径() {
        ResponseEntity<String> missing = delete("/api/v1/history", viewerToken);
        assertThat(missing.getStatusCode().value())
                .as("★ 什么都不指定 ≠ 清空：这是缺参，必须是 400").isEqualTo(400);
        assertThat(Envelope.msg(missing)).contains("all=true");

        ResponseEntity<String> both = delete("/api/v1/history?all=true&contentIds=1", viewerToken);
        assertThat(both.getStatusCode().value()).as("两种形态互斥").isEqualTo(400);
        assertThat(Envelope.msg(both)).contains("不能同时");

        String tooMany = LongStream.rangeClosed(1, 501).mapToObj(String::valueOf)
                .collect(Collectors.joining(","));
        ResponseEntity<String> overLimit = delete("/api/v1/history?contentIds=" + tooMany, viewerToken);
        assertThat(overLimit.getStatusCode().value())
                .as("★ 上限的作用是把'超过 URL 长度'变成可解释的 400（容器抛 414/431 就没法归因了）")
                .isEqualTo(400);
        assertThat(Envelope.msg(overLimit)).contains("500");

        // 边界：恰好 500 条必须放行（判据是 > 500 才拒）
        String exactly = LongStream.rangeClosed(1, 500).mapToObj(String::valueOf)
                .collect(Collectors.joining(","));
        assertThat(delete("/api/v1/history?contentIds=" + exactly, viewerToken).getStatusCode().value())
                .as("500 条是允许的（这些 id 都没有记录 ⇒ 仍然 204）").isEqualTo(204);
    }

    @Test
    @DisplayName("★两种批量写法都认：逗号分隔 与 重复参数（§2.5 的 query 批量约定）")
    void 批量参数两种形态都绑得上() {
        long c1 = insertVideoContent(authorId, "形态 逗号");
        long c2 = insertVideoContent(authorId, "形态 重复");
        play(c1);
        play(c2);

        LinkedMultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        query.add("contentIds", String.valueOf(c1));
        query.add("contentIds", String.valueOf(c2));

        assertThat(deleteQuery("/api/v1/history", query, viewerToken).getStatusCode().value()).isEqualTo(204);
        assertThat(历史内容Ids(viewerId)).isEmpty();
    }

    @Test
    @DisplayName("清空：all=true 删光；空历史再清空仍是 204")
    void 清空历史() {
        long c1 = insertVideoContent(authorId, "清空 A");
        long c2 = insertVideoContent(authorId, "清空 B");
        play(c1);
        play(c2);

        assertThat(delete("/api/v1/history?all=true", viewerToken).getStatusCode().value()).isEqualTo(204);
        assertThat(历史内容Ids(viewerId)).isEmpty();
        assertThat(delete("/api/v1/history?all=true", viewerToken).getStatusCode().value())
                .as("空历史再清空：幂等 204").isEqualTo(204);
    }

    @Test
    @DisplayName("★失效占位也删得掉（不校验内容存在性 —— 否则那几条永远清不掉）")
    void 失效内容的历史也删得掉() {
        long c1 = insertVideoContent(authorId, "删除失效");
        play(c1);
        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", c1);

        assertThat(delete("/api/v1/history?contentIds=" + c1, viewerToken).getStatusCode().value())
                .isEqualTo(204);
        assertThat(历史内容Ids(viewerId)).isEmpty();
    }

    @Test
    @DisplayName("匿名删除 ⇒ 401，且一行都不许删")
    void 匿名删除被拒() {
        long c1 = insertVideoContent(authorId, "删除匿名");
        play(c1);

        assertThat(delete("/api/v1/history?contentIds=" + c1, null).getStatusCode().value()).isEqualTo(401);
        assertThat(delete("/api/v1/history?all=true", null).getStatusCode().value()).isEqualTo(401);
        assertThat(历史内容Ids(viewerId)).as("被拒的请求不许产生副作用").containsExactly(c1);
    }

    // ==================== 与播放量的关系（`R-26` 的核心） ====================

    @Test
    @DisplayName("★删历史不减播放量；同一天再播**不再计**，换一天再播**再计一次**")
    void 删除后播放量的口径() {
        long contentId = insertVideoContent(authorId, "删除与播放量");
        LocalDate today = LocalDate.now(ZONE);

        play(contentId);
        assertThat(播放量(contentId)).isEqualTo(1);

        delete("/api/v1/history?contentIds=" + contentId, viewerToken);
        assertThat(历史内容Ids(viewerId)).as("行已经删了").isEmpty();
        assertThat(播放量(contentId))
                .as("★ 删历史不减播放量（B站 同口径）：计数证据在账本里，删行删不到它")
                .isEqualTo(1);

        play(contentId);
        assertThat(播放量(contentId))
                .as("★ 同一天再播：账本今天那一笔还在 ⇒ 不重复计（这正是'删了历史就重复计'的老坑）")
                .isEqualTo(1);
        assertThat(历史内容Ids(viewerId))
                .as("★ 但历史行要重新出现 —— 页面在播、列表里却没有它才是 bug")
                .containsExactly(contentId);

        // 模拟"第二天"：把账本那一笔挪到昨天（不改系统时钟 —— 日界判定的纯函数由 PlayDayTests 钉）
        jdbcTemplate.update("UPDATE content_play_dedup SET play_date = ? WHERE user_id = ? AND content_id = ?",
                today.minusDays(1), viewerId, contentId);
        play(contentId);
        assertThat(播放量(contentId))
                .as("★ 换一天就应该再计一次（per-day 口径的全部意义）")
                .isEqualTo(2);
        assertThat(账本日期(contentId)).containsExactly(today.minusDays(1), today);
    }

    // ==================== 与 Redis 缓冲的关系（"删了又回来"） ====================

    @Test
    @DisplayName("★删单条会清掉该条的缓冲：随后 flush 不得把它写回来（同一用户另一条不受影响）")
    void 删单条清掉该条缓冲() {
        long c1 = insertVideoContent(authorId, "缓冲 删除");
        long c2 = insertVideoContent(authorId, "缓冲 保留");
        // 只进缓冲、不落库（`/report` 的契约：写 Redis，不写 DB）
        put("/api/v1/contents/" + c1 + "/progress", Map.of("progress", 100), viewerToken);
        put("/api/v1/contents/" + c2 + "/progress", Map.of("progress", 200), viewerToken);
        assertThat(历史内容Ids(viewerId)).as("上报不落库").isEmpty();

        delete("/api/v1/history?contentIds=" + c1, viewerToken);
        flush();

        assertThat(历史内容Ids(viewerId))
                .as("★ c1 的缓冲已被遗忘 ⇒ flush 后不能冒出这一行（否则就是'删了又回来'）")
                .containsExactly(c2);
        assertThat(进度(c2)).as("另一条的缓冲不受影响").isEqualTo(200);
    }

    @Test
    @DisplayName("★清空会清掉整个缓冲：随后 flush 一行都写不回来")
    void 清空清掉整个缓冲() {
        long c1 = insertVideoContent(authorId, "清空缓冲 A");
        long c2 = insertVideoContent(authorId, "清空缓冲 B");
        put("/api/v1/contents/" + c1 + "/progress", Map.of("progress", 100), viewerToken);
        put("/api/v1/contents/" + c2 + "/progress", Map.of("progress", 200), viewerToken);

        delete("/api/v1/history?all=true", viewerToken);
        flush();

        assertThat(历史内容Ids(viewerId))
                .as("★ 清空连缓冲一起清：flush 兜底不得把两条都写回来")
                .isEmpty();
    }

    // ==================== 夹具 / oracle ====================

    private void play(long contentId) {
        playAs(viewerToken, contentId);
    }

    private void playAs(String token, long contentId) {
        ResponseEntity<String> resp = post("/api/v1/contents/" + contentId + "/play", null, token);
        assertThat(resp.getStatusCode().value()).as("play 应 200：%s", resp.getBody()).isEqualTo(200);
    }

    private void flush() {
        ResponseEntity<String> resp = post("/api/v1/history/flush", null, viewerToken);
        assertThat(resp.getStatusCode().value()).as("flush 应 200：%s", resp.getBody()).isEqualTo(200);
    }

    /** 独立 oracle：该用户的历史行（直查 DB，不信接口的 total / list）。 */
    private List<Long> 历史内容Ids(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT content_id FROM watch_history WHERE user_id = ? ORDER BY content_id",
                Long.class, userId);
    }

    private Integer 进度(long contentId) {
        return jdbcTemplate.queryForObject(
                "SELECT progress_seconds FROM watch_history WHERE user_id = ? AND content_id = ?",
                Integer.class, viewerId, contentId);
    }

    private Integer 播放量(long contentId) {
        return jdbcTemplate.queryForObject("SELECT play_count FROM content WHERE id = ?", Integer.class, contentId);
    }

    private List<LocalDate> 账本日期(long contentId) {
        return jdbcTemplate.queryForList(
                "SELECT play_date FROM content_play_dedup WHERE user_id = ? AND content_id = ? ORDER BY play_date",
                LocalDate.class, viewerId, contentId);
    }
}
