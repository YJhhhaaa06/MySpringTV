package io.github.yjhhhaaa06.videoweb.like;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切片 S3 验收：like 的 **8 个端点**的 HTTP 契约与业务分支。
 *
 * <p>断言可观察行为（HTTP 状态 + 信封 + DB 终态），DB 终态走独立 oracle。
 * <b>缓存侧不断言</b>——这里只关心"接口该返回什么"；缓存语义与时序由 {@link LikeCacheTests} 负责。
 * 这样分层后，"接口契约"与"缓存正确性"的失败原因不会互相掩盖。
 *
 * <h2>用例来源（语义翻译，非机械复制）</h2>
 * <ul>
 *   <li>{@code test_consistency.C-01/C-02}：点赞 +1 / 取消 −1（相对差值，不锁绝对值）</li>
 *   <li>{@code test_consistency.C-04/C-05}：评论点赞 +1 / 取消 −1</li>
 *   <li>{@code test_boundary.E-07}：重复点赞 → 409（★ `T59` 契约变更：改为 **200 幂等 no-op**）</li>
 *   <li>{@code test_boundary.E-11}：给不存在的内容点赞 → 404</li>
 *   <li>{@code test_coupon_my_and_comment_status.TestLikeCommentStatus}：
 *       评论点赞状态两态（未点赞 false → 点赞 true → 取消 false）</li>
 * </ul>
 *
 * <h2>两个容易写错的契约点（都已固定为测试）</h2>
 * ① 写操作是 {@code PUT}/{@code DELETE}、**没有请求体**（`T58` 路由定性；`T59` 幂等语义）；
 * ② <b>{@code /like} 是前缀保护</b> ⇒ 连 {@code /like/content/count} 这种不消费 userId 的读端点
 *   也**必须登录**（401）。这是 TV 既有行为，pytest 有断言，原样保留。
 */
class LikeFlowTests extends AbstractLikeIntegrationTest {

    private static final String AUTHOR_PHONE = "13800005001";
    private static final String AUTHOR = "like-author";
    private static final String OTHER_PHONE = "13800005002";
    private static final String OTHER = "like-other";

    /**
     * 内容的"作者"——多数用例并不关心谁上传的内容（{@code content.user_id} 无外键指向 users），
     * 故用一个字面量，省掉一次注册。需要真正的第二用户时才会用 {@link #AUTHOR_PHONE}。
     */
    private static final long ANY_AUTHOR_ID = 9001L;

    // ==================== 内容点赞：成功路径 ====================

    @Test
    @DisplayName("点赞内容：200 + 无 data（`T59`：String 文案退役）；记录 +1 行、content.like_count +1（独立 oracle 双复算）")
    void 点赞内容成功() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        long userId = userIdOf(OTHER_PHONE);
        long contentId = insertContent(ANY_AUTHOR_ID);

        ResponseEntity<String> resp = likeContent(contentId, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.code(resp)).isEqualTo("OK");
        assertThat(Envelope.hasDataField(resp)).as("成功但无数据 ⇒ 不出现 data").isFalse();

        // 独立 oracle：行数与计数字列互为复算（不读接口回显）
        assertThat(countContentLikeRows(contentId, userId)).isEqualTo(1L);
        assertThat(countContentLikeRows(contentId)).isEqualTo(1L);
        assertThat(contentLikeCountColumn(contentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("取消内容点赞：204 无体；记录归零、like_count 归零（T59 契约变更）")
    void 取消内容点赞() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        long userId = userIdOf(OTHER_PHONE);
        long contentId = insertContent(ANY_AUTHOR_ID);
        likeContent(contentId, token);

        ResponseEntity<String> resp = cancelContentLike(contentId, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(resp.getBody()).as("204 必须无体").isNullOrEmpty();
        assertThat(countContentLikeRows(contentId, userId)).isZero();
        assertThat(contentLikeCountColumn(contentId)).isZero();
    }

    // ==================== 内容点赞：拒绝路径 ====================

    /**
     * ★ `T59` 契约变更：重复点赞从 409 改为 **200 幂等 no-op**（PUT 是幂等设置）。
     * 判别力：把 {@code INSERT IGNORE} 改回普通 INSERT ⇒ 第二次撞唯一键 → 409 ⇒ 本用例红。
     */
    @Test
    @DisplayName("★重复点赞：200 幂等，且 like_count 只加一次（`T59` 契约变更，旧 E-07 的 409 退役）")
    void 重复点赞幂等200() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        long contentId = insertContent(ANY_AUTHOR_ID);
        assertThat(likeContent(contentId, token).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = likeContent(contentId, token);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.code(second)).isEqualTo("OK");
        assertThat(Envelope.hasDataField(second)).as("幂等成功也无 data").isFalse();
        assertThat(countContentLikeRows(contentId)).as("仍然只有一条记录").isEqualTo(1L);
        assertThat(contentLikeCountColumn(contentId)).as("计数不得被加两次").isEqualTo(1);
    }

    @Test
    @DisplayName("取消未点赞的赞：204 幂等，且不产生负计数（`T59` 契约变更，旧 409 退役）")
    void 取消未点赞幂等204() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        long contentId = insertContent(ANY_AUTHOR_ID);

        ResponseEntity<String> resp = cancelContentLike(contentId, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(resp.getBody()).as("204 必须无体").isNullOrEmpty();
        assertThat(contentLikeCountColumn(contentId)).isZero();
        assertThat(countContentLikeRows(contentId)).isZero();
    }

    @Test
    @DisplayName("给不存在的内容点赞：404（旧 E-11）")
    void 内容不存在404() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);

        ResponseEntity<String> resp = likeContent(999_999_999L, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(Envelope.code(resp)).isEqualTo("CONTENT_NOT_FOUND");
    }

    // ==================== 状态 / 计数读端点 ====================

    @Test
    @DisplayName("内容点赞状态两态：未点赞 false → 点赞 true → 取消 false；计数与 DB 一致")
    void 内容状态两态() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        long contentId = insertContent(ANY_AUTHOR_ID);

        // 未点赞
        assertThat(contentStatus(contentId, token)).isFalse();
        assertThat(contentCount(contentId, token)).isZero();

        // 点赞
        likeContent(contentId, token);
        assertThat(contentStatus(contentId, token)).isTrue();
        assertThat(contentCount(contentId, token)).isEqualTo(1);

        // 取消
        cancelContentLike(contentId, token);
        assertThat(contentStatus(contentId, token)).isFalse();
        assertThat(contentCount(contentId, token)).isZero();
    }

    @Test
    @DisplayName("内容点赞状态按用户隔离：A 点赞不影响 B 的状态；但计数是全局的")
    void 内容状态按用户隔离() {
        String aToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        String bToken = registerAndGetToken(OTHER_PHONE, OTHER);
        long contentId = insertContent(ANY_AUTHOR_ID);

        likeContent(contentId, aToken);

        assertThat(contentStatus(contentId, aToken)).as("A 点过").isTrue();
        assertThat(contentStatus(contentId, bToken)).as("B 没点过，不能看到 A 的状态").isFalse();
        assertThat(contentCount(contentId, bToken)).as("计数是全局的").isEqualTo(1);
        assertThat(contentLikeCountColumn(contentId)).isEqualTo(1);
    }

    // ==================== 评论点赞 ====================

    @Test
    @DisplayName("点赞评论：200 无 data；记录 +1、comment.like_count +1；取消 204 后归零")
    void 点赞评论成功() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        long authorId = ANY_AUTHOR_ID;
        long contentId = insertContent(authorId);
        long commentId = insertComment(contentId, authorId, "被点赞的评论", null, null);

        ResponseEntity<String> resp = likeComment(commentId, token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.hasDataField(resp)).as("`T59`：String 文案退役").isFalse();
        assertThat(countCommentLikeRows(commentId)).isEqualTo(1L);
        assertThat(commentLikeCountColumn(commentId)).isEqualTo(1);

        assertThat(commentStatus(commentId, token)).isTrue();
        assertThat(commentCount(commentId, token)).isEqualTo(1);

        ResponseEntity<String> cancel = cancelCommentLike(commentId, token);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(cancel.getBody()).isNullOrEmpty();
        assertThat(countCommentLikeRows(commentId)).isZero();
        assertThat(commentLikeCountColumn(commentId)).isZero();
        assertThat(commentStatus(commentId, token)).isFalse();
    }

    @Test
    @DisplayName("★给已软删的评论点赞：404（comment.is_deleted=0 口径，同 S2）")
    void 已软删评论不能点赞() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        long authorId = ANY_AUTHOR_ID;
        long contentId = insertContent(authorId);
        long commentId = insertComment(contentId, authorId, "会被删掉的评论", null, null);
        softDeleteComment(commentId);

        assertThat(likeComment(commentId, token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(countCommentLikeRows(commentId)).isZero();
    }

    @Test
    @DisplayName("★评论点赞幂等：取消未点赞 = 204；重复点赞 = 200，且仍只有一条记录（`T59` 契约变更）")
    void 评论点赞边界() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        long authorId = ANY_AUTHOR_ID;
        long contentId = insertContent(authorId);
        long commentId = insertComment(contentId, authorId, "评论", null, null);

        assertThat(cancelCommentLike(commentId, token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        likeComment(commentId, token);
        assertThat(likeComment(commentId, token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countCommentLikeRows(commentId)).isEqualTo(1L);
        assertThat(commentLikeCountColumn(commentId)).as("计数只加一次").isEqualTo(1);
    }

    // ==================== 计数防负守卫（T34） ====================

    /**
     * ★ F-04 的尖锐场景（content 侧，unsigned）：{@code content_like} 有行、但 {@code content.like_count}
     * 停在 0（数据不自洽）。取消点赞时若不设守卫，`0 + (-1)` 会因 UNSIGNED 下溢报 1690 → 500。
     * 断言 204（不回滚、不失败）+ 记录照删 + 计数停在 0（守卫 block，不自愈成负）。
     *
     * <p><b>判别力</b>：去掉守卫 ⇒ 本用例因 500 变红；"正常 −1→0" 由 {@link #取消内容点赞} 作对照。
     */
    @Test
    @DisplayName("★取消内容点赞：计数为 0 但记录仍在 ⇒ 守卫拦住下溢，返回 204 而非 500（T34）")
    void 取消点赞_计数为零不报500() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        long userId = userIdOf(OTHER_PHONE);
        long contentId = insertContent(ANY_AUTHOR_ID);
        // 夹具：有记录但计数停在 0（模拟历史增量丢失）—— 绕开接口直插，免得把计数一起改掉
        jdbcTemplate.update("INSERT INTO content_like (user_id, content_id) VALUES (?, ?)", userId, contentId);
        assertThat(contentLikeCountColumn(contentId)).as("夹具：有记录但计数停在 0").isZero();

        ResponseEntity<String> resp = cancelContentLike(contentId, token);

        assertThat(resp.getStatusCode()).as("守卫拦住下溢；不得 500").isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(countContentLikeRows(contentId, userId)).as("记录照删（守卫只作用于计数列）").isZero();
        assertThat(contentLikeCountColumn(contentId)).as("计数停在 0，不为负/不报错").isZero();
    }

    /**
     * {@code comment.like_count} 是**有符号** int：同场景不 500，但若不加守卫会静默变成 −1。
     * T34 一并加守卫后，计数停在 0。
     *
     * <p><b>判别力</b>：两版都成功（204） ⇒ 靠**列值**判别 —— 去掉守卫 ⇒ 列变成 −1 ⇒ 本用例红。
     */
    @Test
    @DisplayName("★取消评论点赞：计数为 0 但记录仍在 ⇒ 守卫拦住，计数停在 0（不是 −1）（T34）")
    void 取消评论点赞_计数为零不出现负一() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        long userId = userIdOf(OTHER_PHONE);
        long contentId = insertContent(ANY_AUTHOR_ID);
        long commentId = insertComment(contentId, ANY_AUTHOR_ID, "被取消赞的评论", null, null);
        jdbcTemplate.update("INSERT INTO comment_like (user_id, comment_id) VALUES (?, ?)", userId, commentId);
        assertThat(commentLikeCountColumn(commentId)).as("夹具：有记录但计数停在 0").isZero();

        ResponseEntity<String> resp = cancelCommentLike(commentId, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(countCommentLikeRows(commentId)).isZero();
        assertThat(commentLikeCountColumn(commentId)).as("守卫拦住 −1 ⇒ 停在 0").isZero();
    }

    // ==================== 鉴权（类级 @RequiresLogin） ====================

    @Test
    @DisplayName("★未登录：8 个端点全部 401（/like 是 TV 的**前缀**保护项，含不消费 userId 的 count）")
    void 未登录全部401() {
        long contentId = insertContent(1001L);
        long commentId = insertComment(contentId, 1001L, "评论", null, null);

        assertThat(put("/api/v1/contents/" + contentId + "/like", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(delete("/api/v1/contents/" + contentId + "/like", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(put("/api/v1/comments/" + commentId + "/like", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(delete("/api/v1/comments/" + commentId + "/like", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/contents/" + contentId + "/like", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/comments/" + commentId + "/like", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // ★ 这两个不消费 userId，但按 TV 口径仍需登录
        assertThat(get("/api/v1/contents/" + contentId + "/like-count", null).getStatusCode())
                .as("/like/content/count 不消费 userId，但 TV 的前缀保护把它也拦了——原样保留")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/comments/" + commentId + "/like-count", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // 401 不得产生任何写入
        assertThat(countContentLikeRows(contentId)).isZero();
        assertThat(countCommentLikeRows(commentId)).isZero();
    }

    // ==================== 参数校验 ====================

    @Test
    @DisplayName("参数非法：缺失 / 空 / 非数字 → 400（TV parseContentId 的手写校验 → 声明式/框架默认）")
    void 参数非法400() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        MultiValueMap<String, String> emptyForm = new LinkedMultiValueMap<>();

        // ★ 契约变更（`T58`，G8）：contentId 从 form 进路径后，"缺 id"不再是 400（请求不匹配路由），
        //   只剩"id 不可转换"这一档 —— 判据见 接口契约 §2.5.1。
        assertThat(put("/api/v1/contents/abc/like", null, token).getStatusCode())
                .as("PUT 侧非数字").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(delete("/api/v1/contents/abc/like", token).getStatusCode())
                .as("DELETE 侧非数字").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/contents/abc/like", token).getStatusCode())
                .as("GET 侧非数字").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/comments/abc/like-count", token).getStatusCode())
                .as("GET 侧非数字（count）").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ==================== helpers ====================

    private boolean contentStatus(long contentId, String token) {
        ResponseEntity<String> resp = get("/api/v1/contents/" + contentId + "/like", token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Envelope.data(resp).asBoolean();
    }

    private int contentCount(long contentId, String token) {
        ResponseEntity<String> resp = get("/api/v1/contents/" + contentId + "/like-count", token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Envelope.data(resp).asInt();
    }

    private boolean commentStatus(long commentId, String token) {
        ResponseEntity<String> resp = get("/api/v1/comments/" + commentId + "/like", token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Envelope.data(resp).asBoolean();
    }

    private int commentCount(long commentId, String token) {
        ResponseEntity<String> resp = get("/api/v1/comments/" + commentId + "/like-count", token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Envelope.data(resp).asInt();
    }
}
