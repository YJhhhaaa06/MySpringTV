package io.github.yjhhhaaa06.videoweb.content;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5：content **作者写路径**（{@code /content/delete}、{@code /content/mediaDelete}）的契约测试。
 *
 * <h2>★ 本类最重要的是"级联的四条独立 oracle"</h2>
 * 删作品要同时改**四张表**：{@code content}（软删）、{@code comment}（级联软删）、
 * {@code content_like}（物理删）、{@code content_media}（物理删）。
 * 旧 pytest 的写法是"删完调接口看返回 404"——那只证明了内容读不到，
 * **证明不了**评论/点赞/媒体有没有跟着清。故这里对每一张表各用一条 SQL 独立复算。
 *
 * <h2>另外三组</h2>
 * <ul>
 *   <li><b>删单图的 {@code sort} 重排</b>：删中间一张后剩余图片必须是 {@code 1..n} 连续
 *       （前端靠 {@code index+1} 定位）。</li>
 *   <li><b>提交后三处缓存都被失效</b>：内容 key、评论两键组、类型分区索引。
 *       只证明其中一处被失效不足以证明链路完整——而这正是"一次业务事实、三处缓存"的全部。</li>
 *   <li><b>权限与边界</b>：非作者 403（且不得产生任何写入）、不存在 404、未登录 401。</li>
 * </ul>
 */
class ContentAuthorTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900006001";
    private static final String AUTHOR = "author-path";
    private static final String OTHER_PHONE = "13900006002";
    private static final String OTHER = "author-other";

    // ========================================================================
    // /content/delete
    // ========================================================================

    @Test
    @DisplayName("★删作品：四张表的终态各用一条独立 oracle 复算（content/comment/content_like/content_media）")
    void 删作品_级联四条独立oracle() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        String otherToken = registerAndGetToken(OTHER_PHONE, OTHER);
        long authorId = userIdOf(AUTHOR_PHONE);
        long otherId = userIdOf(OTHER_PHONE);
        long contentId = insertPostContent(authorId, "待删除作品");

        long main = insertMainComment(contentId, otherId, "主楼评论");
        insertComment(contentId, otherId, "回复 1", main, null);
        insertComment(contentId, otherId, "回复 2", main, null);
        insertContentLike(otherId, contentId);
        insertContentLike(authorId, contentId);

        // 预热三处缓存：内容 key / 评论两键组 / 类型分区索引
        assertThat(get("/search/IdSearch?contentId=" + contentId, token).getStatusCode().value()).isEqualTo(200);
        get("/comment/show?contentId=" + contentId + "&page=1&pageSize=10", token);
        get("/start", null);
        assertThat(cachedContentJson(contentId)).as("前置：内容 key 已缓存").isNotNull();
        assertThat(redis.hasKey(rootsKey(contentId))).as("前置：评论 roots 已装载").isTrue();
        assertThat(indexContains(contentId)).as("前置：内容在推荐索引里").isTrue();

        assertThat(postQuery("/content/delete", query("contentId", String.valueOf(contentId)), token)
                .getStatusCode().value()).isEqualTo(200);

        // 四条独立 oracle
        assertThat(contentIsDeletedColumn(contentId)).as("① content.is_deleted = 1").isEqualTo(1);
        assertThat(countSoftDeletedComments(contentId)).as("② 三条评论全部 is_deleted=1")
                .isEqualTo(3);
        assertThat(countLikeRows(contentId)).as("③ content_like 物理删空").isZero();
        assertThat(countMediaRows(contentId)).as("④ content_media 物理删空").isZero();

        // 三处缓存都被失效（只查一处不足以证明链路完整）
        assertThat(cachedContentJson(contentId)).as("内容 key 被剔除").isNull();
        assertThat(redis.hasKey(rootsKey(contentId))).as("评论 roots 被整组失效").isFalse();
        assertThat(redis.hasKey(repliesKey(contentId))).as("评论 replies 也被整组失效").isFalse();
        assertThat(indexContains(contentId)).as("从推荐索引里剔除").isFalse();

        // 反向对照：别人的内容不受影响（排除"删除清空全库"的假绿）
        long otherContentId = insertVideoContent(otherId, "别人的内容");
        assertThat(countMediaRows(otherContentId)).isPositive();
        assertThat(postQuery("/content/delete", query("contentId", String.valueOf(otherContentId)), otherToken)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(countMediaRows(contentId)).as("自己的那些行没有被「复活」").isZero();
    }

    @Test
    @DisplayName("删作品：非作者 403 且**不产生任何写入**；不存在/已删 404；未登录 401")
    void 删作品_权限与边界() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        String otherToken = registerAndGetToken(OTHER_PHONE, OTHER);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertPostContent(authorId, "受保护作品");
        long main = insertMainComment(contentId, authorId, "评论");
        insertContentLike(authorId, contentId);

        ResponseEntity<String> forbidden = postQuery("/content/delete",
                query("contentId", String.valueOf(contentId)), otherToken);
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(Envelope.msg(forbidden)).isEqualTo("只能操作自己的作品");
        assertThat(contentIsDeletedColumn(contentId)).as("403 时内容不得被软删").isZero();
        assertThat(countMediaRows(contentId)).as("403 时媒体行不得被删").isEqualTo(3);
        assertThat(countLikeRows(contentId)).as("403 时点赞行不得被删").isEqualTo(1);
        assertThat(countSoftDeletedComments(contentId)).as("403 时评论不得被软删").isZero();

        assertThat(postQuery("/content/delete", query("contentId", "987654321"), authorToken)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(postQuery("/content/delete", query("contentId", String.valueOf(contentId)), null)
                .getStatusCode().value()).as("未登录 ⇒ 401").isEqualTo(401);
        assertThat(main).isPositive();
    }

    // ========================================================================
    // /content/mediaDelete
    // ========================================================================

    @Test
    @DisplayName("★删单图：行数减一，且剩余图片 sort 重排为 1..n 连续（前端 index+1 定位的前提）")
    void 删单图_sort重排() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertContent(authorId, CONTENT_TYPE_IMAGE, "三图内容", "简介", 2);
        insertMedia(contentId, "/upload/image/a.jpg", MEDIA_TYPE_IMAGE, 1);
        insertMedia(contentId, "/upload/image/b.jpg", MEDIA_TYPE_IMAGE, 2);
        insertMedia(contentId, "/upload/image/c.jpg", MEDIA_TYPE_IMAGE, 3);
        insertMedia(contentId, "/upload/cover/x.png", MEDIA_TYPE_COVER, 1);

        assertThat(mediaTypeSorts(contentId, MEDIA_TYPE_IMAGE)).containsExactly("2:1", "2:2", "2:3");

        // 删中间那张
        assertThat(postQuery("/content/mediaDelete",
                query("contentId", String.valueOf(contentId), "type", "2", "sort", "2"), token)
                .getStatusCode().value()).isEqualTo(200);

        assertThat(mediaTypeSorts(contentId, MEDIA_TYPE_IMAGE))
                .as("删中间后必须仍是 1..n 连续（sort=3 的那张被重排为 2）").containsExactly("2:1", "2:2");
        assertThat(jdbcTemplate.queryForList(
                "SELECT url FROM content_media WHERE content_id = ? ORDER BY type, sort", String.class, contentId))
                .as("b.jpg 的记录确实被删了（不是只改了 sort）")
                .containsExactly("/upload/image/a.jpg", "/upload/image/c.jpg", "/upload/cover/x.png");

        // 详情回读：imageUrls 只剩两张（提交后 REFRESH 生效）
        assertThat(Envelope.data(get("/search/IdSearch?contentId=" + contentId, token))
                .path("imageUrls").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("删单图：type != 2 → 400『仅支持删除图片』（视频/封面是结构性资源）；媒体不存在 → 404")
    void 删单图_只允许图片() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "视频内容");

        ResponseEntity<String> video = postQuery("/content/mediaDelete",
                query("contentId", String.valueOf(contentId), "type", "1", "sort", "1"), token);
        assertThat(video.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(video)).isEqualTo("仅支持删除图片");
        assertThat(countMediaRows(contentId)).as("400 时不得删除任何行").isEqualTo(2);

        ResponseEntity<String> missing = postQuery("/content/mediaDelete",
                query("contentId", String.valueOf(contentId), "type", "2", "sort", "9"), token);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(Envelope.msg(missing)).isEqualTo("媒体资源不存在");

        assertThat(postQuery("/content/mediaDelete",
                query("contentId", String.valueOf(contentId), "type", "2", "sort", "1"), null)
                .getStatusCode().value()).as("未登录 ⇒ 401").isEqualTo(401);
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    private int countSoftDeletedComments(long contentId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE content_id = ? AND is_deleted = 1",
                Integer.class, contentId);
        return n == null ? 0 : n;
    }
}
