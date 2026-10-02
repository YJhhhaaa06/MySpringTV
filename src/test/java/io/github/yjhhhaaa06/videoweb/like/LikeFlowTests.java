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
 *   <li>{@code test_boundary.E-07}：重复点赞 → 409</li>
 *   <li>{@code test_boundary.E-11}：给不存在的内容点赞 → 404</li>
 *   <li>{@code test_coupon_my_and_comment_status.TestLikeCommentStatus}：
 *       评论点赞状态两态（未点赞 false → 点赞 true → 取消 false）</li>
 * </ul>
 *
 * <h2>两个容易写错的契约点（都已固定为测试）</h2>
 * ① 写操作是 <b>form 提交</b>（{@code x-www-form-urlencoded}），不是 JSON；
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
    @DisplayName("点赞内容：200 + data=\"点赞成功\"；记录 +1 行、content.like_count +1（独立 oracle 双复算）")
    void 点赞内容成功() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        long userId = userIdOf(OTHER_PHONE);
        long contentId = insertContent(ANY_AUTHOR_ID);

        ResponseEntity<String> resp = likeContent(contentId, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.code(resp)).isEqualTo(200);
        assertThat(Envelope.data(resp).asString()).isEqualTo("点赞成功");

        // 独立 oracle：行数与计数字列互为复算（不读接口回显）
        assertThat(countContentLikeRows(contentId, userId)).isEqualTo(1L);
        assertThat(countContentLikeRows(contentId)).isEqualTo(1L);
        assertThat(contentLikeCountColumn(contentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("取消内容点赞：200 + data=\"已取消点赞\"；记录归零、like_count 归零")
    void 取消内容点赞() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        long userId = userIdOf(OTHER_PHONE);
        long contentId = insertContent(ANY_AUTHOR_ID);
        likeContent(contentId, token);

        ResponseEntity<String> resp = unlikeContent(contentId, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.data(resp).asString()).isEqualTo("已取消点赞");
        assertThat(countContentLikeRows(contentId, userId)).isZero();
        assertThat(contentLikeCountColumn(contentId)).isZero();
    }

    // ==================== 内容点赞：拒绝路径 ====================

    /**
     * ★ 旧 pytest {@code E-07} 的翻译。除 409 外，额外断言 {@code like_count} **未被多扣**——
     * 这正是"事务回滚"的可观察证据（与 S1 的重复抢券同款断言思路）。
     */
    @Test
    @DisplayName("★重复点赞：409，且 like_count 保持不变（证明回滚，旧 E-07）")
    void 重复点赞409() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        long contentId = insertContent(ANY_AUTHOR_ID);
        assertThat(likeContent(contentId, token).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = likeContent(contentId, token);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(Envelope.code(second)).isEqualTo(409);
        assertThat(countContentLikeRows(contentId)).as("仍然只有一条记录").isEqualTo(1L);
        assertThat(contentLikeCountColumn(contentId)).as("计数不得被加两次").isEqualTo(1);
    }

    @Test
    @DisplayName("取消未点赞的赞：409，且不产生负计数（旧实现文案「未点赞，无法取消」）")
    void 取消未点赞409() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        long contentId = insertContent(ANY_AUTHOR_ID);

        ResponseEntity<String> resp = unlikeContent(contentId, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(Envelope.code(resp)).isEqualTo(409);
        assertThat(contentLikeCountColumn(contentId)).isZero();
        assertThat(countContentLikeRows(contentId)).isZero();
    }

    @Test
    @DisplayName("给不存在的内容点赞：404（旧 E-11）")
    void 内容不存在404() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);

        ResponseEntity<String> resp = likeContent(999_999_999L, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(Envelope.code(resp)).isEqualTo(404);
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
        unlikeContent(contentId, token);
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
    @DisplayName("点赞评论：200；记录 +1、comment.like_count +1；取消后归零")
    void 点赞评论成功() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        long authorId = ANY_AUTHOR_ID;
        long contentId = insertContent(authorId);
        long commentId = insertComment(contentId, authorId, "被点赞的评论", null, null);

        ResponseEntity<String> resp = likeComment(commentId, token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.data(resp).asString()).isEqualTo("点赞成功");
        assertThat(countCommentLikeRows(commentId)).isEqualTo(1L);
        assertThat(commentLikeCountColumn(commentId)).isEqualTo(1);

        assertThat(commentStatus(commentId, token)).isTrue();
        assertThat(commentCount(commentId, token)).isEqualTo(1);

        assertThat(unlikeComment(commentId, token).getStatusCode()).isEqualTo(HttpStatus.OK);
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
    @DisplayName("重复点赞评论：409；取消未点赞的评论：409（文案与内容侧不同，TV 原文保留）")
    void 评论点赞边界() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        long authorId = ANY_AUTHOR_ID;
        long contentId = insertContent(authorId);
        long commentId = insertComment(contentId, authorId, "评论", null, null);

        assertThat(unlikeComment(commentId, token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        likeComment(commentId, token);
        assertThat(likeComment(commentId, token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(countCommentLikeRows(commentId)).isEqualTo(1L);
    }

    // ==================== 鉴权（类级 @RequiresLogin） ====================

    @Test
    @DisplayName("★未登录：8 个端点全部 401（/like 是 TV 的**前缀**保护项，含不消费 userId 的 count）")
    void 未登录全部401() {
        long contentId = insertContent(1001L);
        long commentId = insertComment(contentId, 1001L, "评论", null, null);

        assertThat(postForm("/like/content/add", form("contentId", contentId), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(postForm("/like/content/remove", form("contentId", contentId), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(postForm("/like/comment/add", form("commentId", commentId), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(postForm("/like/comment/remove", form("commentId", commentId), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/like/content/status?contentId=" + contentId, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/like/comment/status?commentId=" + commentId, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // ★ 这两个不消费 userId，但按 TV 口径仍需登录
        assertThat(get("/like/content/count?contentId=" + contentId, null).getStatusCode())
                .as("/like/content/count 不消费 userId，但 TV 的前缀保护把它也拦了——原样保留")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/like/comment/count?commentId=" + commentId, null).getStatusCode())
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

        assertThat(postForm("/like/content/add", emptyForm, token).getStatusCode())
                .as("缺 contentId").isEqualTo(HttpStatus.BAD_REQUEST);

        MultiValueMap<String, String> blank = new LinkedMultiValueMap<>();
        blank.add("contentId", "");
        assertThat(postForm("/like/content/add", blank, token).getStatusCode())
                .as("contentId 为空串").isEqualTo(HttpStatus.BAD_REQUEST);

        MultiValueMap<String, String> notNumber = new LinkedMultiValueMap<>();
        notNumber.add("contentId", "abc");
        assertThat(postForm("/like/content/add", notNumber, token).getStatusCode())
                .as("contentId 非数字").isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(get("/like/content/status?contentId=abc", token).getStatusCode())
                .as("GET 侧非数字").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/like/comment/count", token).getStatusCode())
                .as("GET 侧缺参").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ==================== helpers ====================

    private boolean contentStatus(long contentId, String token) {
        ResponseEntity<String> resp = get("/like/content/status?contentId=" + contentId, token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Envelope.data(resp).asBoolean();
    }

    private int contentCount(long contentId, String token) {
        ResponseEntity<String> resp = get("/like/content/count?contentId=" + contentId, token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Envelope.data(resp).asInt();
    }

    private boolean commentStatus(long commentId, String token) {
        ResponseEntity<String> resp = get("/like/comment/status?commentId=" + commentId, token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Envelope.data(resp).asBoolean();
    }

    private int commentCount(long commentId, String token) {
        ResponseEntity<String> resp = get("/like/comment/count?commentId=" + commentId, token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Envelope.data(resp).asInt();
    }
}
