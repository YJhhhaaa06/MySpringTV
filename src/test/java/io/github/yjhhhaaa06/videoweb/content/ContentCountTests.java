package io.github.yjhhhaaa06.videoweb.content;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T43 验收：**计数搬出内容载荷**（用户 2026-10-19 拍板）。
 *
 * <h2>本类钉的三件事（每一件都能被一行实现改动弄红）</h2>
 * <ol>
 *   <li><b>载荷形状</b>：{@code content:{id}} 的 JSON **不含** {@code likeCount} / {@code commentCount}
 *       —— 它们已搬进独立 key。判别力：把字段加回 DTO ⇒ {@link #内容缓存JSON不含计数()} 红；</li>
 *   <li><b>不再连坐失效</b>：点赞 / 发评论之后，{@code content:{id}} **仍在缓存里**。
 *       判别力：恢复 {@code contentCache.invalidateContent(...)} ⇒ 两条"key 仍在"断言红；</li>
 *   <li><b>值仍然正确</b>：不失效不代表答案错 —— 详情里的四个计数分别来自四个独立来源
 *       （like_count 列 / comment_count 列 / favorite_count 列 / coin_count 列），
 *       用**互不相同的取值**交叉验证"没接错线"。★ T44-1 起 likeCount 的回源口径从
 *       {@code content_like} 行数切到 {@code content.like_count} 列（对齐 T12 的 favorite 先例），
 *       本类的 like oracle 随之改为列口径（夹具也从"插行"改为"写列"——插行不维护列，
 *       在新口径下不再是有效造数）。</li>
 * </ol>
 *
 * <h2>为什么"值正确"必须单独钉</h2>
 * 只断言"内容 key 没被删"是**半个契约**：把 {@code ContentDetailVO#setLikeCount} 写成常量 0，
 * 上面三条全绿。故 {@link #详情四个计数分别来自四个独立来源()} 用 1 / 3 / 7 / 2 四个不同数字
 * 一次钉住"哪个字段来自哪一路"。
 *
 * <h2>★ 反向验证（注入一处必红）</h2>
 * <ul>
 *   <li>{@code ContentCacheDTO} 加回 {@code likeCount} 字段 ⇒ {@link #内容缓存JSON不含计数()} 红；</li>
 *   <li>恢复点赞 / 评论的 {@code invalidateContent} ⇒ 两条"{@code content:{id}} 仍在"断言红；</li>
 *   <li>{@code CommentCacheChangedContentListener} 改成不失效评论数键 ⇒
 *       {@link #发评论不再失效内容缓存但评论数立刻正确()} 的第二段读到 0；</li>
 *   <li>详情装配把四个数写错字段（例如 like 填 comment 的值）⇒
 *       {@link #详情四个计数分别来自四个独立来源()} 红。</li>
 * </ul>
 */
class ContentCountTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900007001";
    private static final String AUTHOR = "count-author";
    private static final String VIEWER_PHONE = "13900007002";
    private static final String VIEWER = "count-viewer";

    // ========================================================================
    // 1. 载荷形状
    // ========================================================================

    @Test
    @DisplayName("★内容缓存 JSON 不含 likeCount / commentCount（计数已搬出载荷）")
    void 内容缓存JSON不含计数() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "计数载荷测试");

        assertThat(get("/search/IdSearch?contentId=" + contentId, token).getStatusCode().value())
                .as("前置：详情可读（同时完成内容 key 的 miss 回填）").isEqualTo(200);

        String json = cachedContentJson(contentId);
        assertThat(json).as("前置：content:{id} 已被回填").isNotNull();
        assertThat(json)
                .as("★ 载荷是**慢变字段**的集合：计数字段不得出现在 JSON 里")
                .doesNotContain("\"likeCount\"")
                .doesNotContain("\"commentCount\"");
        assertThat(json).as("对照：慢变字段仍在（否则上面的断言可能只是没缓存成功）")
                .contains("\"title\"")
                .contains("计数载荷测试");
    }

    // ========================================================================
    // 2. 点赞：不再失效内容缓存，但详情立刻正确
    // ========================================================================

    @Test
    @DisplayName("★点赞不再失效内容缓存（content:{id} 仍在），详情 likeCount 仍立刻等于行数")
    void 点赞不再失效内容缓存() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        String viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "点赞失效测试");

        // 预热：详情 + 内容 key
        JsonNode before = Envelope.data(get("/search/IdSearch?contentId=" + contentId, authorToken));
        assertThat(before.path("likeCount").asLong()).as("前置：0 赞").isZero();
        assertThat(cachedContentJson(contentId)).as("前置：内容 key 已预热").isNotNull();

        ResponseEntity<String> likeResp = postForm("/like/content/add",
                query("contentId", String.valueOf(contentId)), viewerToken);
        assertThat(likeResp.getStatusCode().value()).isEqualTo(200);

        // ★ 核心断言：内容 key 没有被 DEL（T43 之前这里会被 LIKE_CHANGED 监听器删掉）
        assertThat(cachedContentJson(contentId))
                .as("★ 点赞不得失效内容载荷（计数已独立成 key）").isNotNull();

        // 值仍要正确：计数 key 冷 ⇒ miss 回源 COUNT(*)，不依赖内容载荷
        JsonNode after = Envelope.data(get("/search/IdSearch?contentId=" + contentId, authorToken));
        assertThat(after.path("likeCount").asLong())
                .as("★ 详情 likeCount = content.like_count 列（T44-1 起回源口径；写路径维护列 ⇒ 与行数相等）")
                .isEqualTo(countLikeRows(contentId))
                .isEqualTo(1L);
        assertThat(isContentKeyWarm(contentId)).as("再读之后内容 key 仍在").isTrue();
    }

    // ========================================================================
    // 3. 评论：不再失效内容缓存，但评论数立刻正确
    // ========================================================================

    @Test
    @DisplayName("★发评论不再失效内容缓存，但评论数独立 key 被失效并读回新值")
    void 发评论不再失效内容缓存但评论数立刻正确() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        String viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "评论失效测试");

        // 预热：详情（顺带把 content:commentCount 填成 "0"）
        JsonNode before = Envelope.data(get("/search/IdSearch?contentId=" + contentId, authorToken));
        assertThat(before.path("commentCount").asLong()).as("前置：0 评论").isZero();
        assertThat(cachedContentJson(contentId)).as("前置：内容 key 已预热").isNotNull();
        assertThat(redis.hasKey(CacheKeys.contentCommentCount(contentId)))
                .as("前置：评论数 key 已预热（否则'被失效'无法观测）").isTrue();

        ResponseEntity<String> add = post("/comment/add",
                Map.of("contentId", contentId, "message", "第一条", "parentId", 0), viewerToken);
        assertThat(add.getStatusCode().value()).as("前置：评论成功: %s", add.getBody()).isEqualTo(200);

        // ★ 内容载荷不动
        assertThat(cachedContentJson(contentId))
                .as("★ 发评论不得失效内容载荷").isNotNull();
        // ★ 评论数 key 被 AFTER_COMMIT 监听器 DEL（旧值 0 不再服务）
        assertThat(redis.hasKey(CacheKeys.contentCommentCount(contentId)))
                .as("★ 评论数 key 必须在提交后被失效（DEL，而不是猜 delta）").isFalse();

        JsonNode after = Envelope.data(get("/search/IdSearch?contentId=" + contentId, authorToken));
        assertThat(after.path("commentCount").asLong())
                .as("★ 详情 commentCount = content.comment_count（独立 oracle）")
                .isEqualTo(commentCountColumn(contentId))
                .isEqualTo(1L);
        assertThat(redis.hasKey(CacheKeys.contentCommentCount(contentId)))
                .as("读 miss 后评论数 key 已回填").isTrue();
    }

    // ========================================================================
    // 4. 四路来源交叉验证
    // ========================================================================

    @Test
    @DisplayName("★详情四个计数分别来自四个独立来源（1 / 3 / 7 / 2 交叉验证不串线）")
    void 详情四个计数分别来自四个独立来源() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "四路来源测试");

        // 四个**互不相同**的值，任何"接错线"都会被抓到：
        // ★ T44-1：likeCount 回源口径 = content.like_count 列（不再读行数），夹具随之写列
        jdbcTemplate.update("UPDATE content SET like_count = 1 WHERE id = ?", contentId);
        jdbcTemplate.update("UPDATE content SET comment_count = 3 WHERE id = ?", contentId);
        jdbcTemplate.update("UPDATE content SET favorite_count = 7 WHERE id = ?", contentId);
        jdbcTemplate.update("UPDATE content SET coin_count = 2 WHERE id = ?", contentId);

        JsonNode data = Envelope.data(get("/search/IdSearch?contentId=" + contentId, token));

        assertThat(data.path("likeCount").asLong()).as("likeCount ← content.like_count（T44-1 起读列）").isEqualTo(1L);
        assertThat(data.path("commentCount").asLong()).as("commentCount ← content.comment_count").isEqualTo(3L);
        assertThat(data.path("favoriteCount").asLong()).as("favoriteCount ← content.favorite_count").isEqualTo(7L);
        assertThat(data.path("coinCount").asLong()).as("coinCount ← content.coin_count").isEqualTo(2L);

        // 详情是"平铺"契约：四个键都在顶层，且不在 interaction 子对象里
        JsonNode interaction = data.path("interaction");
        assertThat(interaction.has("likeCount")).as("计数不进 interaction（那是'我的状态'）").isFalse();
        assertThat(interaction.has("favoriteCount")).isFalse();
        assertThat(interaction.has("coinCount")).isFalse();
    }

    // ========================================================================
    // 夹具 / oracle
    // ========================================================================

    private boolean isContentKeyWarm(long contentId) {
        return Boolean.TRUE.equals(redis.hasKey(CacheKeys.content(contentId)));
    }

    /** 独立 oracle：{@code content.comment_count} 列（不读接口回显、不读缓存）。 */
    private long commentCountColumn(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT comment_count FROM content WHERE id = ?", Long.class, contentId);
        assertThat(n).as("内容 %s 应存在", contentId).isNotNull();
        return n;
    }
}
