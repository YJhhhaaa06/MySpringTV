package io.github.yjhhhaaa06.videoweb.history;

import io.github.yjhhhaaa06.videoweb.content.AbstractContentIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 续播位置（{@code GET /api/v1/contents/{id}/progress}，`T58` 起资源化）的**行为契约**
 * （播放记录专项 T49-2）。
 *
 * <h2>期望值怎么来（盖住实现照样写得出来）</h2>
 * <ul>
 *   <li><b>无记录 ⇒ 0</b>：出自 {@code history-需求.md} §三「没有记录 ⇒ {@code 0}」
 *       —— 不是 404、也不是 {@code null}；</li>
 *   <li><b>有记录 ⇒ 存储秒数</b>：走真实写路径（{@code PUT /api/v1/contents/{id}/progress}）造前置状态，
 *       再断言读端点回显的确实是写进去的那个数（往返一致）；</li>
 *   <li>★ <b>已删内容仍返回进度</b>：出自 {@code history-需求.md} §三「已删除的视频仍能查到历史进度
 *       （内容是死是活由详情 404 负责）」—— 这条直接钉"position 不判存在性"；</li>
 *   <li><b>隔离</b>：进度是"我的"，键 {@code (user_id, content_id)} 必须按当前用户过滤。</li>
 * </ul>
 *
 * <h2>反向验证（注入 ⇒ 必红，实测见任务回写）</h2>
 * 给 {@code position} 加"内容不存在 ⇒ 404"⇒ 「已删仍答」与「不存在 ⇒ 0」两条红；
 * 把 {@code position} 改回"读缓冲优先"（`R-19` 的旧实现）⇒ {@code 缓冲里的新进度不参与续播} 红。
 *
 * <p>★ 轮询周期推到 1h：本类自己用 {@code /flush} 造前置状态，不要让兜底线程同时动同一批键。
 */
@TestPropertySource(properties = {
        "video.history.buffer.poll-interval=1h",
        "video.history.buffer.buffer-ttl=3h"
})
class HistoryPositionTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900000201";
    private static final String AUTHOR = "history-position-author";
    private static final String VIEWER_PHONE = "13900000202";
    private static final String VIEWER = "history-position-viewer";
    private static final String OTHER_PHONE = "13900000203";
    private static final String OTHER = "history-position-viewer-2";

    private String viewerToken;
    private long viewerId;
    private long authorId;

    @BeforeEach
    void setUpUsers() {
        // 父类先清 users / content / content_media / Redis（JUnit5 父类 @BeforeEach 先跑）⇒ 这里重新注册。
        // watch_history 没有外键、父类不清 ⇒ 本类自己清（否则上一用例的行会跨用例残留）。
        jdbcTemplate.update("DELETE FROM watch_history");
        viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);
        viewerId = userIdOf(VIEWER_PHONE);
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        authorId = userIdOf(AUTHOR_PHONE);
    }

    @Test
    @DisplayName("有内容但我没看过 ⇒ progress=0（**不 404**）；data 恰好 {progress} 一键")
    void 无记录返回零() {
        // ★ 用**存在且可播**的内容（不是不存在的 id）：这样这条用例只回答"有内容但没记录"，
        //   不与被删 / 不存在两条重复；也让"给 position 加内容校验"的反证能同时命中三条。
        long contentId = insertVideoContent(authorId, "从没看过的视频");
        ResponseEntity<String> resp = position(viewerToken, contentId);

        assertThat(resp.getStatusCode().value()).as("无记录是合法状态（不 404）").isEqualTo(200);
        JsonNode data = Envelope.data(resp);
        assertThat(data.path("progress").asInt()).as("没有记录 ⇒ 0（前端据此从头播）").isZero();
        assertThat(data.propertyNames())
                .as("★ 只给进度：时长随详情返回，本端点不重复读内容")
                .containsExactly("progress");
    }

    @Test
    @DisplayName("有记录 ⇒ 返回存储秒数（/report 写入 → /position 读回，往返一致）")
    void 有记录返回秒数() {
        long contentId = insertVideoContent(authorId, "续播视频");

        ResponseEntity<String> report = put("/api/v1/contents/" + contentId + "/progress?progress=123", null, viewerToken);
        assertThat(report.getStatusCode().value()).as("前置写应 200：%s", report.getBody()).isEqualTo(200);
        // ★ R-19：/report 只写缓冲；本类验的是"已落库的进度能被读回"，故先 flush
        post("/api/v1/history/flush", null, viewerToken);

        assertThat(Envelope.data(position(viewerToken, contentId)).path("progress").asInt())
                .as("读回的就是写进去的秒数")
                .isEqualTo(123);
    }

    @Test
    @DisplayName("★内容已删仍返回进度（不 404）：内容是死是活由详情负责")
    void 已删内容仍返回进度() {
        long deleted = insertVideoContent(authorId, "已删视频");
        // 直插历史（/report 会先校验内容存在性 ⇒ 已删内容走不了写路径，这也正是本用例的背景）
        jdbcTemplate.update("INSERT INTO watch_history (user_id, content_id, progress_seconds) VALUES (?, ?, ?)",
                viewerId, deleted, 321);
        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", deleted);

        ResponseEntity<String> resp = position(viewerToken, deleted);
        assertThat(resp.getStatusCode().value())
                .as("★ 已删内容仍答进度（给 position 加 404 判据这条就红）").isEqualTo(200);
        assertThat(Envelope.data(resp).path("progress").asInt()).isEqualTo(321);
    }

    @Test
    @DisplayName("不存在的 contentId ⇒ progress=0（**不校验内容、不 404**）")
    void 不存在内容返回零() {
        long neverExisted = 888888888L;

        ResponseEntity<String> resp = position(viewerToken, neverExisted);
        assertThat(resp.getStatusCode().value()).as("不存在的内容不报 404").isEqualTo(200);
        assertThat(Envelope.data(resp).path("progress").asInt()).isZero();
    }

    @Test
    @DisplayName("★跨用户隔离：只能查到我自己的进度（别人的记录不串）")
    void 跨用户隔离() {
        String otherToken = registerAndGetToken(OTHER_PHONE, OTHER);
        long contentId = insertVideoContent(authorId, "共享视频");
        put("/api/v1/contents/" + contentId + "/progress?progress=55", null, viewerToken);
        post("/api/v1/history/flush", null, viewerToken);

        assertThat(Envelope.data(position(viewerToken, contentId)).path("progress").asInt())
                .as("我自己：55").isEqualTo(55);
        assertThat(Envelope.data(position(otherToken, contentId)).path("progress").asInt())
                .as("★ 另一个人没看过 ⇒ 0（WHERE user_id 过滤；漏掉就串成 55）").isZero();
    }

    @Test
    @DisplayName("鉴权：未登录 / 坏 token ⇒ 401（⚠️ HTTP 侧对 @RequiresLogin 是**假绿**）")
    void 需登录() {
        long contentId = insertVideoContent(authorId, "鉴权视频");
        put("/api/v1/contents/" + contentId + "/progress?progress=1", null, viewerToken);

        // ⚠️ 假绿声明：401 由 @CurrentUserId 参数解析器兜出 ⇒ 去掉类级 @RequiresLogin 这两条**仍然绿**。
        //    注解是否在位只由 SecurityContractTests 求值（钉路由，不证明注解）。
        assertThat(position(null, contentId).getStatusCode().value()).as("未登录 ⇒ 401").isEqualTo(401);
        assertThat(position("not-a-jwt", contentId).getStatusCode().value()).as("坏 token ⇒ 401").isEqualTo(401);
    }

    // ==================== R-20（T52）：只认已落库的值 ====================

    /**
     * ★ `R-20` 起 `/position` **只点查 DB**，缓冲里的未落库进度不参与续播。
     *
     * <p>期望值 500 出自业务口径（不是抄实现）：`R-20` 明确"续播位置 = 已落库的值，
     * 缓冲里的新值由客户端本地 pending 承担"。因此"缓冲有 900、库有 500 ⇒ 返回 500"
     * 是**契约本身**，而非退化。
     *
     * <p>判别力：把 `HistoryService#position` 改回"读缓冲优先"⇒ 返回 900 ⇒ 本用例红。
     * 这条是本口径**唯一**能变红的断言（其余用例在两种实现下都绿）。
     */
    @Test
    @DisplayName("★R-20 不读缓冲：库里 500、缓冲里 900（未落库）⇒ 返回 500")
    void 缓冲里的新进度不参与续播() {
        long contentId = insertVideoContent(authorId, "缓冲不参与续播");
        put("/api/v1/contents/" + contentId + "/progress?progress=500", null, viewerToken);
        post("/api/v1/history/flush", null, viewerToken);

        // 再上报一次但**不 flush**：这条只活在缓冲里（模拟"正在播 / 崩溃 / 还没退出"）
        put("/api/v1/contents/" + contentId + "/progress?progress=900", null, viewerToken);

        assertThat(Envelope.data(position(viewerToken, contentId)).path("progress").asInt())
                .as("★ R-20：续播只认已落库的值（读到 900 说明还在读缓冲）")
                .isEqualTo(500);
    }

    private ResponseEntity<String> position(String token, long contentId) {
        return get("/api/v1/contents/" + contentId + "/progress", token);
    }
}
