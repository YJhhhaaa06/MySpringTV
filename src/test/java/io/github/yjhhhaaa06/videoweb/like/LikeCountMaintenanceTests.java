package io.github.yjhhhaaa06.videoweb.like;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T44-1 验收：**点赞数回源口径切换**（{@code COUNT(*) content_like} → {@code content.like_count} 列）
 * + **作者删除内容时列归零**。
 *
 * <h2>背景（为什么有这个专项）</h2>
 * {@code content.like_count} 列自 V1 起存在、由点赞写路径事务内 ±1 维护（T34 防负守卫），
 * 但读路径从未消费它——计数缓存 miss 的回源一直是实时 {@code COUNT(*)} 聚合。
 * T44-2 的详情计数聚合读要一趟从 content 行取齐四计数，like 是其中唯一随热度变贵的成员
 * （favorite 在 T12 已做过同款切换），故先行切换。
 *
 * <h2>本类钉住的三件事（每一件都能被一行实现改动弄红）</h2>
 * <ol>
 *   <li><b>不变量</b>：{@code content.like_count == COUNT(*) content_like} 在点赞 / 取消后成立
 *       （双 oracle 独立复算，谁漂移都红）；</li>
 *   <li><b>删除归零</b>：作者删内容后列 == 0 且 {@code /like/content/count} 仍返回 0
 *       ——去掉 {@code ContentService#deleteContent} 里的 {@code resetLikeCount} ⇒ 红
 *       （列残留 1，行为回退成"已删内容返回旧计数"）；</li>
 *   <li><b>回源读列</b>（T44-1 的存在证明）：人为把列改成 99（行数仍为 1）⇒ 端点返回 99
 *       ——把回源改回 {@code COUNT(*)} ⇒ 此用例红（它只会返回 1）。
 *       ★ 这是"口径切换"类改动的判别力所在：值断言在两种实现下不同。</li>
 * </ol>
 *
 * <h2>契约回归（G8：外部行为不许变）</h2>
 * 已删内容的 {@code /like/content/count} 在切换前（COUNT(*)，行已删）返回 0；
 * 切换后（读列）必须仍返回 0 —— 这正是"删除时归零"要保的性质，用例 2 一并钉住。
 *
 * <h2>夹具对照纪律</h2>
 * 用例 3 造**两个**内容：漂移的 c1 与不漂移的 c2（对照）——防止"断言恒 99"这种
 * 与实现无关的假绿（全表 UPDATE 才能造出那种绿）。
 */
class LikeCountMaintenanceTests extends AbstractLikeIntegrationTest {

    private static final String AUTHOR_PHONE = "13800008001";
    private static final String AUTHOR = "likecol-author";
    private static final String VIEWER_PHONE = "13800008002";
    private static final String VIEWER = "likecol-viewer";
    private static final String VIEWER2_PHONE = "13800008003";
    private static final String VIEWER2 = "likecol-viewer2";

    @Test
    @DisplayName("★不变量：点赞/取消后 content.like_count == COUNT(*) content_like（双 oracle 相等）")
    void 不变量_点赞与取消后列等于行数() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertContent(authorId);
        String viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);
        String viewer2Token = registerAndGetToken(VIEWER2_PHONE, VIEWER2);

        assertThat(likeContent(contentId, viewerToken).getStatusCode().value()).isEqualTo(200);
        assertThat(contentLikeCountColumn(contentId))
                .as("★ 列 == 行数（1 人点赞）：写路径必须事务内维护列")
                .isEqualTo(countContentLikeRows(contentId))
                .isEqualTo(1);

        assertThat(likeContent(contentId, viewer2Token).getStatusCode().value()).isEqualTo(200);
        assertThat(contentLikeCountColumn(contentId))
                .as("★ 列 == 行数（2 人点赞）")
                .isEqualTo(countContentLikeRows(contentId))
                .isEqualTo(2);

        assertThat(cancelContentLike(contentId, viewerToken).getStatusCode().value()).isEqualTo(200);
        assertThat(contentLikeCountColumn(contentId))
                .as("★ 取消后列 == 行数（回到 1）")
                .isEqualTo(countContentLikeRows(contentId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★作者删除内容后 like_count 归零，/like/content/count 对已删内容仍返回 0 且不回填")
    void 作者删除内容后列归零且计数端点契约不变() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertContent(authorId);
        String viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);

        assertThat(likeContent(contentId, viewerToken).getStatusCode().value()).isEqualTo(200);

        // 预热计数键（否则"被失效后不回填"无法观测——冷 key 的"不存在"恒真，零判别力）
        ResponseEntity<String> warm = get("/api/v1/contents/" + contentId + "/like-count", authorToken);
        assertThat(Envelope.data(warm).asLong()).as("前置：1 赞").isEqualTo(1L);
        assertThat(contentLikeCountValue(contentId)).as("前置：计数键已预热").isEqualTo("1");

        assertThat(delete("/api/v1/contents/" + contentId, authorToken)
                .getStatusCode().value()).as("前置：作者删除成功").isEqualTo(200);

        // ★ T44-1 核心：行物理删、列归零（去掉 deleteContent 里的 resetLikeCount ⇒ 此断言红：
        //   列残留 1，不变量对软删行破裂）
        assertThat(contentLikeCountColumn(contentId))
                .as("★ 删除后 like_count 必须归零（与行删除同事务）").isZero();
        assertThat(countContentLikeRows(contentId)).as("行已物理删").isZero();

        // 契约回归（G8）：切换前 COUNT(*) 对已删内容天然返回 0；切换后（读列）必须仍是 0
        JsonNode data = Envelope.data(get("/api/v1/contents/" + contentId + "/like-count", authorToken));
        assertThat(data.asLong()).as("★ /like/content/count 对已删内容仍返回 0（外部契约不变）").isZero();
        // 键被回填为 "0"：软删行仍存在（列查询刻意不带 is_deleted 过滤），列值 0 就是真相——
        // 缓存复现列值，与旧行数口径（回填 0）完全一致。"不回填"分支只服务"行真的不存在"。
        assertThat(contentLikeCountValue(contentId))
                .as("★ 计数键回填列值 0（若 resetLikeCount 被去掉，这里是 1 且上一条断言已红）").isEqualTo("0");
    }

    @Test
    @DisplayName("★回源读列不读行数（列漂移判别）：列=99 行=1 ⇒ 端点返回 99；对照内容不受影响")
    void 回源读列_列漂移判别() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long drifted = insertContent(authorId);
        long control = insertContent(authorId);   // 对照：防止"断言恒 99"式假绿
        String viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);

        assertThat(likeContent(drifted, viewerToken).getStatusCode().value()).isEqualTo(200);
        assertThat(likeContent(control, viewerToken).getStatusCode().value()).isEqualTo(200);

        // 人为制造列漂移：列 = 99，行数仍 = 1（两种回源口径在这里给出不同答案——判别力所在）
        jdbcTemplate.update("UPDATE content SET like_count = 99 WHERE id = ?", drifted);

        assertThat(Envelope.data(get("/api/v1/contents/" + drifted + "/like-count", token)).asLong())
                .as("★ 回源必须读列（把回源改回 COUNT(*) content_like ⇒ 此断言红：只会返回 1）")
                .isEqualTo(99L);
        assertThat(Envelope.data(get("/api/v1/contents/" + control + "/like-count", token)).asLong())
                .as("对照：未漂移内容仍返回真实计数（排除'恒 99'式假绿）")
                .isEqualTo(1L);
    }
}
