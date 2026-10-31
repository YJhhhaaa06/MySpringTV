package io.github.yjhhhaaa06.videoweb.history;

import io.github.yjhhhaaa06.videoweb.content.AbstractContentIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 播放记录写路径（T47）的**行为契约**：进度上报（{@code PUT /api/v1/contents/{id}/progress}）
 * 与播放事件（{@code POST /api/v1/contents/{id}/play}）。路径为 `T58` 契约变更
 * （依据《接口契约.md》§2.3/§2.4：report 定 PUT、contentId 进路径），语义断言不受影响。
 *
 * <h2>两条端点的分工就是本类的主轴</h2>
 * <table>
 *   <caption>断什么、为什么</caption>
 *   <tr><th>端点</th><th>本类钉住的口径</th></tr>
 *   <tr><td>{@code /report}</td><td><b>只写缓冲、不落库</b>（`R-19` 起的契约变更）；落库后仍是
 *       <b>后到即新（LWW）</b>、按时长 clamp、<b>不写 play_count</b></td></tr>
 *   <tr><td>{@code /play}</td><td>**当天**首播 {@code +1}；同一天重复 play 不 +；**并发两次恰 +1**；
 *       行不存在时建行（{@code progress=0}）；DB 不变量 {@code play_count == COUNT(content_play_dedup)}</td></tr>
 * </table>
 *
 * <h2>期望值怎么来（独立 oracle）</h2>
 * <ul>
 *   <li>进度 / 播放量一律**直查 DB**（{@code jdbcTemplate}），不信接口回显；</li>
 *   <li>播放量不变量用**独立语句**复算（{@code SELECT COUNT(*) FROM content_play_dedup}），
 *       与被测实现走的不是同一条路径；</li>
 *   <li>LWW 的期望值来自用户拍板的口径（{@code history-需求.md} §六"后到即新"），
 *       不是"看到实现这么写就这么断"。</li>
 * </ul>
 *
 * <h2>★ `R-26`（2026-10-31）：口径从"一人一次（永久）"改成"一人·一内容·一自然日一次"</h2>
 * 去重标记从 {@code watch_history.play_counted} 搬进账本 {@code content_play_dedup}
 * （日期进主键）。因此本类：① 原来的 {@code play_counted} 断言全部改成"账本行";
 * ② 新增**日界**用例（昨天计过 ⇒ 今天再计一次）。这是**契约跟随**（G8 例外条），不是迎合实现。
 * <p>★ 日界用例**杀不掉"用 UTC 算日期"这个缺陷**（本机 +08:00 下每天只有 0:00–8:00 才错开一天）——
 * 那条的唯一确定性判别力在 {@code PlayDayTests}（固定时刻断言），别把这条当成时区防线。
 *
 * <h2>夹具</h2>
 * 复用内容域的 {@link AbstractContentIntegrationTest}（作者 / 内容 / 媒体行三条直插语句，
 * 同 comment / feed 测试的手法）；本类只补 {@code watch_history} 的清理与"给视频行设时长"。
 *
 * <h2>反向验证（注入 ⇒ 必红）</h2>
 * ① 去掉 clamp ⇒ "超过时长被截断"红；
 * ② 把 {@code report} 改成"进度只能增"（{@code GREATEST}）⇒ LWW 用例红；
 * ③ ODKU 不显式 SET {@code update_time} ⇒ "重复上报仍刷新时间"红（值没变时列上的 ON UPDATE 不触发）；
 * ④ 把账本 {@code INSERT IGNORE} 改成无条件 {@code INSERT} + 忽略异常 ⇒ 重复 play / 并发用例红；
 * ⑤ 首播判定换回 ODKU 的 affected rows ⇒ 重复 play 用例红（{@code useAffectedRows=false} 的歧义）；
 * ⑥ 账本主键去掉 {@code play_date}（改成 {@code (user_id, content_id)}）⇒ 日界用例红
 *    （"昨天计过 ⇒ 今天还能再计"变成插不进去）。
 *
 * <h2>★ `R-19`（T51-2）的契约变更，以及为什么改这些断言</h2>
 * {@code /report} 从"同步落库"改成"写 Redis 缓冲"，落库由 {@code /flush} 触发。因此凡"上报后直查 DB"
 * 的断言都必须先 flush —— 这是**契约跟随**（G8 的例外条：口径变了，oracle 必须跟），不是迎合实现。
 * 依据：{@code history-上报缓冲.md} §3。
 *
 * <p>★ 轮询周期推到 1h：本类会手动 flush，而 {@code HistoryFlushScheduler} 也会按周期兜底 ——
 * 两边同时动同一批键会让"上报后 DB 仍为空"这类断言偶发红。
 */
@TestPropertySource(properties = {
        "video.history.buffer.poll-interval=1h",
        "video.history.buffer.buffer-ttl=3h"
})
class HistoryWriteTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900000001";
    private static final String AUTHOR = "history-author";
    private static final String VIEWER_PHONE = "13900000002";
    private static final String VIEWER = "history-viewer";
    private static final String OTHER_VIEWER_PHONE = "13900000003";
    private static final String OTHER_VIEWER = "history-viewer-2";

    private String viewerToken;
    private long viewerId;
    private long authorId;

    @BeforeEach
    void setUpUsers() {
        // 父类先清 users / content / Redis（JUnit5 父类 @BeforeEach 先跑）⇒ 这里重新注册。
        viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);
        viewerId = userIdOf(VIEWER_PHONE);
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        authorId = userIdOf(AUTHOR_PHONE);
        jdbcTemplate.update("DELETE FROM watch_history");
        jdbcTemplate.update("DELETE FROM content_play_dedup");
    }

    // ==================== PUT /api/v1/contents/{id}/progress ====================

    @Test
    @DisplayName("★上报只写缓冲、不落库；/flush 才落库（R-19 契约变更）")
    void 上报首次建行() {
        long contentId = insertVideoContent(authorId, "上报首次");

        ResponseEntity<String> resp = put("/api/v1/contents/" + contentId + "/progress", Map.of("progress", 42), viewerToken);

        assertThat(resp.getStatusCode().value()).as("上报应 200：%s", resp.getBody()).isEqualTo(200);
        assertThat(Envelope.msg(resp)).isEqualTo("success");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM watch_history", Long.class))
                .as("★ R-19：上报不再同步落库（落库由 /flush 或空闲轮询完成）")
                .isZero();

        flush();

        assertThat(进度(contentId)).isEqualTo(42);
        assertThat(账本行数())
                .as("★ /report 不进账本：它不是计数入口（`R-26` 后判据搬去账本，同样不碰）").isZero();
        assertThat(播放量(contentId)).as("★ /report 不写 play_count（唯一计数入口是 /play）").isZero();
    }

    @Test
    @DisplayName("★LWW：先报 100、再报 10 ⇒ 存 10（低进度覆盖高进度，不是「只增」）")
    void 上报后到即新() {
        long contentId = insertVideoContent(authorId, "上报 LWW");

        put("/api/v1/contents/" + contentId + "/progress", Map.of("progress", 100), viewerToken);
        flush();
        put("/api/v1/contents/" + contentId + "/progress", Map.of("progress", 10), viewerToken);
        flush();

        assertThat(进度(contentId))
                .as("★ 后到即新（用户 2026-10-21 拍板）：实现成 GREATEST 会是 100 ⇒ 本断言红")
                .isEqualTo(10);
    }

    @Test
    @DisplayName("★超过时长按当前时长截断（不报错）；时长未知则不截断")
    void 上报按时长截断() {
        long contentId = insertVideoContent(authorId, "上报截断");
        setVideoDuration(contentId, 10);

        ResponseEntity<String> exceeding = put("/api/v1/contents/" + contentId + "/progress", Map.of("progress", 999), viewerToken);
        assertThat(exceeding.getStatusCode().value()).as("越界不报错").isEqualTo(200);
        flush();
        assertThat(进度(contentId)).as("截到 10").isEqualTo(10);

        // 时长未知（存量 / 解析失败）⇒ 原样存，不 clamp 到 0
        long unknownDuration = insertVideoContent(authorId, "上报无时长");
        put("/api/v1/contents/" + unknownDuration + "/progress", Map.of("progress", 777), viewerToken);
        flush();
        assertThat(进度(unknownDuration)).as("null 时长 ≠ 0 ⇒ 不截断").isEqualTo(777);
    }

    @Test
    @DisplayName("★重复上报同一进度仍然刷新 update_time（否则历史列表不会移到最前）")
    void 重复上报刷新更新时间() {
        long contentId = insertVideoContent(authorId, "上报时间");
        put("/api/v1/contents/" + contentId + "/progress", Map.of("progress", 30), viewerToken);
        flush();

        // 把 update_time 拨回 2020（模拟"这条记录很久没动过"）
        jdbcTemplate.update("UPDATE watch_history SET update_time = '2020-01-01 00:00:00' "
                + "WHERE user_id = ? AND content_id = ?", viewerId, contentId);

        put("/api/v1/contents/" + contentId + "/progress", Map.of("progress", 30), viewerToken);
        flush();

        String updated = jdbcTemplate.queryForObject(
                "SELECT update_time FROM watch_history WHERE user_id = ? AND content_id = ?",
                String.class, viewerId, contentId);
        assertThat(updated)
                .as("★ 值没变 ⇒ 列上的 ON UPDATE 不触发；flush 必须显式 SET update_time = 缓冲里的接收时刻")
                .startsWith("20").isNotEqualTo("2020-01-01 00:00:00");
    }

    @Test
    @DisplayName("上报错误路径：负数 400 / 图文 400 / 不存在 404 / 已删 404 / 匿名 401")
    void 上报错误路径() {
        long videoId = insertVideoContent(authorId, "上报错误");
        long postId = insertPostContent(authorId, "上报图文");

        ResponseEntity<String> negative = put("/api/v1/contents/" + videoId + "/progress", Map.of("progress", -1), viewerToken);
        assertThat(negative.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(negative)).isEqualTo("播放进度不能为负数");

        ResponseEntity<String> post400 = put("/api/v1/contents/" + postId + "/progress", Map.of("progress", 1), viewerToken);
        assertThat(post400.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(post400)).isEqualTo("只能记录视频的播放进度");

        assertThat(put("/api/v1/contents/999999/progress", Map.of("progress", 1), viewerToken)
                .getStatusCode().value()).as("不存在 ⇒ 404").isEqualTo(404);

        // 已软删：不能往"对外不可见"的内容里写历史
        long deletedId = insertVideoContent(authorId, "上报已删");
        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", deletedId);
        assertThat(put("/api/v1/contents/" + deletedId + "/progress", Map.of("progress", 1), viewerToken)
                .getStatusCode().value()).as("已删 ⇒ 404").isEqualTo(404);

        assertThat(put("/api/v1/contents/" + videoId + "/progress", Map.of("progress", 1), null)
                .getStatusCode().value()).as("匿名 ⇒ 401").isEqualTo(401);

        flush();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM watch_history", Long.class))
                .as("★ 被拒的请求**连缓冲都没进**（flush 之后仍然一行都没有，不是'先写再校验'）")
                .isZero();
    }

    // ==================== POST /api/v1/contents/{id}/play ====================

    @Test
    @DisplayName("播放：今天首播 +1，账本记一笔「今天」，历史行被建出来（progress=0）")
    void 播放首播计数() {
        long contentId = insertVideoContent(authorId, "播放首播");

        ResponseEntity<String> resp = post("/api/v1/contents/" + contentId + "/play", null, viewerToken);

        assertThat(resp.getStatusCode().value()).as("首播应 200：%s", resp.getBody()).isEqualTo(200);
        assertThat(播放量(contentId)).isEqualTo(1);
        assertThat(账本日期(contentId))
                .as("★ 账本落的必须是**业务日**（Asia/Shanghai），不是 SQL 的 CURDATE()")
                .containsExactly(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        assertThat(进度(contentId))
                .as("★ 行不存在时建行（progress=0）：否则'先 play 后 report'会丢播放量")
                .isZero();
    }

    @Test
    @DisplayName("★重复 play 幂等：仍 200，播放量不再 +1")
    void 重复播放不重复计数() {
        long contentId = insertVideoContent(authorId, "播放重复");

        post("/api/v1/contents/" + contentId + "/play", null, viewerToken);
        ResponseEntity<String> second = post("/api/v1/contents/" + contentId + "/play", null, viewerToken);

        assertThat(second.getStatusCode().value()).as("重复播放不是错误").isEqualTo(200);
        assertThat(播放量(contentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("★并发两次 play 恰 +1（INSERT IGNORE + 条件 UPDATE 的判别力）")
    void 并发播放恰加一() throws Exception {
        long contentId = insertVideoContent(authorId, "播放并发");

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<Integer> task = () -> {
                    start.await(5, TimeUnit.SECONDS);
                    return post("/api/v1/contents/" + contentId + "/play", null, viewerToken)
                            .getStatusCode().value();
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<Integer> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS)).as("并发请求都应是 200（幂等）").isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(播放量(contentId))
                .as("★ 两个首播请求里只有一个能把账本那一行插进去 ⇒ 播放量恰 +1")
                .isEqualTo(1);
        assertThat(计数("SELECT COUNT(*) FROM content_play_dedup WHERE user_id = ? AND content_id = ?",
                viewerId, contentId))
                .as("并发只留一笔账（账本主键 (user_id, content_id, play_date) 是唯一的互斥点）")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("★日界：昨天计过 ⇒ 今天再播**再计一次**（per-day 的全部意义）；今天已计 ⇒ 不再计")
    void 播放按自然日去重() {
        long contentId = insertVideoContent(authorId, "播放日界");
        // ⚠️ "今天"由用例自己算 ⇒ 恰好跨 23:59:59 跑会偶发红（一个用例失败、另一个也失败，签名很好认）。
        //    日界本身的判别力在 `PlayDayTests`（固定时刻、任何时间跑都确定）；这里钉的是"日期进主键"。
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));

        // 账本里预置"昨天计过"——注意**不是**走接口造出来的（直插账本是独立 oracle 的造数方式）
        jdbcTemplate.update("INSERT INTO content_play_dedup (user_id, content_id, play_date) VALUES (?, ?, ?)",
                viewerId, contentId, today.minusDays(1));

        post("/api/v1/contents/" + contentId + "/play", null, viewerToken);

        assertThat(播放量(contentId))
                .as("★ 昨天那笔账**不拦**今天：把 play_date 从主键里去掉（退化成永久一次）⇒ 本断言红")
                .isEqualTo(1);
        assertThat(账本日期(contentId))
                .as("账本应有两笔：昨天 + 今天").containsExactly(today.minusDays(1), today);

        post("/api/v1/contents/" + contentId + "/play", null, viewerToken);
        assertThat(播放量(contentId)).as("同一天第二次播放不再计").isEqualTo(1);
        assertThat(账本日期(contentId))
                .as("★ 账本仍然是两笔（昨天 + 今天）：今天那笔不会被重复插入").hasSize(2);
    }

    @Test
    @DisplayName("★不变量：play_count == COUNT(账本)（两个用户各播一次 ⇒ 2；同一人重复播不加账）")
    void 播放量与账本不变量() {
        String otherToken = registerAndGetToken(OTHER_VIEWER_PHONE, OTHER_VIEWER);
        long contentId = insertVideoContent(authorId, "播放不变量");

        post("/api/v1/contents/" + contentId + "/play", null, viewerToken);
        post("/api/v1/contents/" + contentId + "/play", null, otherToken);
        post("/api/v1/contents/" + contentId + "/play", null, viewerToken);   // 重复不算

        assertThat(播放量(contentId)).isEqualTo(2);
        assertThat(oracle播放量(contentId))
                .as("★ 独立 oracle：content.play_count 必须等于账本行数（`R-26` 起'计过数的人次'= 账本行）")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("播放错误路径：图文 400 / 已删 404 / 匿名 401（都不得写计数、也不得落行）")
    void 播放错误路径() {
        long postId = insertPostContent(authorId, "播放图文");
        long videoId = insertVideoContent(authorId, "播放已删");

        ResponseEntity<String> post400 = post("/api/v1/contents/" + postId + "/play", null, viewerToken);
        assertThat(post400.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(post400)).isEqualTo("只能记录视频的播放进度");

        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", videoId);
        assertThat(post("/api/v1/contents/" + videoId + "/play", null, viewerToken).getStatusCode().value())
                .as("已删 ⇒ 404").isEqualTo(404);

        assertThat(post("/api/v1/contents/" + videoId + "/play", null, null).getStatusCode().value())
                .as("匿名 ⇒ 401").isEqualTo(401);

        assertThat(播放量(postId)).as("图文不得被计数").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM watch_history", Long.class))
                .as("★ 被拒的请求一行都不许落（不是'先写再校验'）").isZero();
    }

    // ==================== 夹具 / oracle ====================

    /**
     * 退出信号：把该用户全部缓冲落库（`R-19` 起 progress 上报不再直接写库）。
     *
     * <p>方法名直白，不藏在 {@code post} 里 —— 每个用例该在**哪一步**断库，读起来一目了然。
     */
    private void flush() {
        ResponseEntity<String> resp = post("/api/v1/history/flush", null, viewerToken);
        assertThat(resp.getStatusCode().value()).as("flush 应 200：%s", resp.getBody()).isEqualTo(200);
    }

    /** 直查进度（独立 oracle，不信接口回显）。 */
    private Integer 进度(long contentId) {
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT progress_seconds FROM watch_history WHERE user_id = ? AND content_id = ?",
                Integer.class, viewerId, contentId);
        assertThat(rows).as("应恰有一条播放记录").hasSize(1);
        return rows.get(0);
    }

    /**
     * 账本里该内容的全部计入日期（升序）。
     *
     * <p>`R-26` 起"计过数的人次"= 账本行；同一对 (user, content) 可以有**多天**各一行，
     * 所以这里返回列表而不是单个值 —— 用 {@code hasSize} 判"有没有重复记账"最直接。
     */
    private List<LocalDate> 账本日期(long contentId) {
        return jdbcTemplate.queryForList(
                "SELECT play_date FROM content_play_dedup WHERE user_id = ? AND content_id = ? ORDER BY play_date",
                LocalDate.class, viewerId, contentId);
    }

    private long 账本行数() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content_play_dedup", Long.class);
        return n == null ? 0L : n;
    }

    /** 计数类 oracle 的通用入口（SQL 里只有一个 {@code ?} 时最省事）。 */
    private long 计数(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    /** {@code content.play_count} 列（写路径的终态）。 */
    private Integer 播放量(long contentId) {
        return jdbcTemplate.queryForObject(
                "SELECT play_count FROM content WHERE id = ?", Integer.class, contentId);
    }

    /** 独立 oracle：不变量右侧（完全不同的一条语句，不复用被测实现）。 */
    private Long oracle播放量(long contentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_play_dedup WHERE content_id = ?",
                Long.class, contentId);
    }

    private void setVideoDuration(long contentId, int seconds) {
        jdbcTemplate.update("UPDATE content_media SET duration_seconds = ? WHERE content_id = ? AND type = 1",
                seconds, contentId);
    }
}
