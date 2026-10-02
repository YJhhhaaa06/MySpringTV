package io.github.yjhhhaaa06.videoweb.comment;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切片 S2 验收：comment **写路径**（发表 / 删除）的 HTTP 契约与业务分支。
 *
 * <p>断言可观察行为（HTTP 状态 + 信封 + DB 终态），不断言内部实现（决策⑦ 防"测试迎合"）。
 * DB 终态一律走**独立 oracle**（{@link AbstractCommentIntegrationTest} 的查列方法）——
 * 这在本切片是**结构性必需**而非风格偏好：{@code /comment/show} 尚未迁移（CM-3），没有接口可回显。
 *
 * <h2>用例来源（语义翻译，非机械复制）</h2>
 * <ul>
 *   <li>{@code test_comment_delete.py}：删主楼整栋 / 删回复只删自己 / 删他人 403 / 删不存在 404 / 未登录 401</li>
 *   <li>{@code test_comment_enabled.py}：评论区关闭后发评论 409</li>
 *   <li>{@code test_boundary.E-03}：缺 message → 400（并补齐 1000 字边界）</li>
 *   <li>{@code test_smoke.S-12} + {@code test_consistency.C-09}：发评论成功与计数 +1</li>
 * </ul>
 *
 * <h2>鉴权口径来源</h2>
 * TV {@code AuthFilter.PROTECTED_EXACT} 同时含 {@code /comment/add} 与 {@code /comment/delete}
 * ⇒ 两个端点都需登录。旧清单不迁移（改用 {@code @RequiresLogin} 声明），
 * 但它**是鉴权口径的事实来源**，故在此逐条固化为测试。
 */
class CommentFlowTests extends AbstractCommentIntegrationTest {

    private static final String AUTHOR_PHONE = "13800003001";
    private static final String AUTHOR = "comment-author";
    private static final String OTHER_PHONE = "13800003002";
    private static final String OTHER = "comment-other";

    // ==================== 发表：成功路径 ====================

    @Test
    @DisplayName("发主楼评论：200 + data=\"评论成功\"；comment 落一行（parent_id 为空）、content.comment_count +1")
    void 发主楼评论() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);

        ResponseEntity<String> resp = addComment(contentId, "第一条评论", null, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.code(resp)).isEqualTo(200);
        assertThat(Envelope.data(resp).asString()).isEqualTo("评论成功");

        // 独立 oracle：不读接口回显，直接查列
        long commentId = findCommentId(contentId, "第一条评论");
        assertThat(parentIdOf(commentId)).isNull();
        assertThat(replyToUserIdOf(commentId)).isNull();
        assertThat(commentCountOf(contentId)).isEqualTo(1);
        assertThat(countLiveComments(contentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("回复主楼：新行 parent_id=主楼、reply_to_user_id 为空；主楼 reply_count +1、comment_count +1")
    void 回复主楼() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);
        addComment(contentId, "主楼", null, token);
        long mainId = findCommentId(contentId, "主楼");

        ResponseEntity<String> resp = addComment(contentId, "回复主楼", mainId, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        long replyId = findCommentId(contentId, "回复主楼");
        assertThat(parentIdOf(replyId)).isEqualTo(mainId);
        // 回复"主楼"本身不记录 @ 目标（TV 口径：只有被回复的是楼内回复时才记）
        assertThat(replyToUserIdOf(replyId)).isNull();
        assertThat(replyCountOf(mainId)).isEqualTo(1);
        assertThat(commentCountOf(contentId)).isEqualTo(2);
    }

    /**
     * ★ CM-1 的核心语义：楼中楼**归一化**。
     *
     * <p>回复一条"楼内回复"时，新行的 {@code parent_id} 必须上溯指向**主楼**（而不是被回复的那条回复），
     * 同时 {@code reply_to_user_id} 记下**被回复那条回复的作者**（供「回复 @xxx」展示）。
     * 这条规则决定了任意深度的"回复的回复"在库里始终只有两层，是评论树结构不变式的来源。
     */
    @Test
    @DisplayName("★回复一条回复：归一化为 parent_id=主楼 + reply_to_user_id=被回复者（CM-1 归一化语义）")
    void 回复一条回复被归一化到主楼() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        String otherToken = registerAndGetToken(OTHER_PHONE, OTHER);
        long authorId = userIdOf(AUTHOR_PHONE);
        long otherId = userIdOf(OTHER_PHONE);
        long contentId = insertContent(authorId, true);

        addComment(contentId, "主楼", null, authorToken);
        long mainId = findCommentId(contentId, "主楼");
        addComment(contentId, "一级回复", mainId, otherToken);
        long firstReplyId = findCommentId(contentId, "一级回复");

        // 作者去"回复那条一级回复"
        ResponseEntity<String> resp = addComment(contentId, "二级回复", firstReplyId, authorToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        long secondReplyId = findCommentId(contentId, "二级回复");
        assertThat(parentIdOf(secondReplyId))
                .as("归一化：parent_id 必须指向主楼，而不是被回复的那条回复")
                .isEqualTo(mainId);
        assertThat(replyToUserIdOf(secondReplyId))
                .as("被 @ 者应是【一级回复】的作者")
                .isEqualTo(otherId);
        assertThat(replyCountOf(mainId)).as("主楼 reply_count 计入二级回复").isEqualTo(2);
        assertThat(commentCountOf(contentId)).isEqualTo(3);
    }

    // ==================== 发表：拒绝路径 ====================

    @Test
    @DisplayName("内容不存在：404，且不产生评论行、计数不变")
    void 内容不存在() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);

        ResponseEntity<String> resp = addComment(999_999_999L, "幽灵评论", null, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(Envelope.code(resp)).isEqualTo(404);
        assertThat(countAllComments(999_999_999L)).isZero();
    }

    /**
     * ★ content-thin 的口径测试（《切片计划》§二 要求"读旧代码确认，不要想当然"的那一点）。
     *
     * <p>{@code ContentDao.isContentExist} 的 SQL 带 {@code AND is_deleted = 0}
     * ⇒ **给已软删的内容评论必须被拒**。这条测试把这个口径钉住：
     * 若有人"顺手"把 {@code is_deleted = 0} 去掉，此用例会失败。
     */
    @Test
    @DisplayName("★内容已软删：404（content-thin 的 isContentExist 带 is_deleted=0 口径）")
    void 内容已软删() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);
        softDeleteContent(contentId);

        ResponseEntity<String> resp = addComment(contentId, "给已删内容评论", null, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(Envelope.code(resp)).isEqualTo(404);
        assertThat(countAllComments(contentId)).isZero();
    }

    @Test
    @DisplayName("被回复评论不存在：409")
    void 被回复评论不存在() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);

        ResponseEntity<String> resp = addComment(contentId, "回复幽灵", 999_999_999L, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(Envelope.code(resp)).isEqualTo(409);
        assertThat(countAllComments(contentId)).isZero();
    }

    @Test
    @DisplayName("被回复评论属于另一条内容：409（跨内容回复被拒）")
    void 跨内容回复被拒() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentA = insertContent(authorId, true);
        long contentB = insertContent(authorId, true);
        long foreignCommentId = insertComment(contentB, authorId, "B 内容下的评论", null, null);

        ResponseEntity<String> resp = addComment(contentA, "跨内容回复", foreignCommentId, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(Envelope.code(resp)).isEqualTo(409);
        assertThat(countAllComments(contentA)).isZero();
    }

    @Test
    @DisplayName("评论区已关闭（comment_enabled=0）：409，且不产生评论行（旧 test_comment_enabled 的翻译）")
    void 评论区已关闭() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);
        setCommentEnabled(contentId, false);

        ResponseEntity<String> resp = addComment(contentId, "关评论区后发帖", null, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(Envelope.code(resp)).isEqualTo(409);
        assertThat(countAllComments(contentId)).isZero();
        assertThat(commentCountOf(contentId)).isZero();
    }

    @Test
    @DisplayName("未登录发评论：401（TV AuthFilter PROTECTED_EXACT 含 /comment/add）")
    void 未登录发评论() {
        long contentId = insertContent(1001L, true);

        ResponseEntity<String> resp = addComment(contentId, "匿名评论", null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(Envelope.code(resp)).isEqualTo(401);
        assertThat(Envelope.hasDataField(resp)).as("错误信封不含 data 键").isFalse();
        assertThat(countAllComments(contentId)).isZero();
    }

    // ==================== 发表：参数校验（TV CommandConverter 口径） ====================

    @Test
    @DisplayName("参数校验：缺 message / 空白 / 缺 contentId / contentId=0 → 400（旧 pytest E-03）")
    void 发表参数校验() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);

        // 缺 message（E-03）
        assertThat(post("/comment/add", Map.of("contentId", contentId), token).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // message 全空白
        assertThat(addComment(contentId, "   ", null, token).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // 缺 contentId
        assertThat(post("/comment/add", Map.of("message", "x"), token).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // contentId = 0（TV: contentId == 0 → ParamException）
        assertThat(addComment(0L, "x", null, token).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // 非法参数不得留下任何副作用
        assertThat(countAllComments(contentId)).isZero();
    }

    @Test
    @DisplayName("message 长度边界：恰 1000 字放行，1001 字 400（TV 上限 1000）")
    void 评论长度边界() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);

        assertThat(addComment(contentId, "a".repeat(1000), null, token).getStatusCode())
                .as("恰好 1000 字应当被接受")
                .isEqualTo(HttpStatus.OK);
        assertThat(addComment(contentId, "b".repeat(1001), null, token).getStatusCode())
                .as("1001 字应当被拒（TV: 不可发送超过1000字的评论）")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countLiveComments(contentId)).isEqualTo(1);
    }

    // ==================== 删除：成功路径 ====================

    @Test
    @DisplayName("删自己的主楼：整栋软删（主楼+楼内回复）；comment_count 减 1+回复数；主楼 reply_count 不动")
    void 删主楼整栋() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        String otherToken = registerAndGetToken(OTHER_PHONE, OTHER);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);

        // 用接口建树，让计数天然正确（也顺带覆盖 CM-1 的计数联动）
        addComment(contentId, "主楼", null, authorToken);
        long mainId = findCommentId(contentId, "主楼");
        addComment(contentId, "回复一", mainId, otherToken);
        addComment(contentId, "回复二", mainId, otherToken);
        long reply1 = findCommentId(contentId, "回复一");
        long reply2 = findCommentId(contentId, "回复二");
        assertThat(commentCountOf(contentId)).isEqualTo(3);
        assertThat(replyCountOf(mainId)).isEqualTo(2);

        ResponseEntity<String> resp = deleteComment(mainId, authorToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.data(resp).asString()).isEqualTo("删除成功");

        // 独立 oracle
        assertThat(isCommentDeleted(mainId)).isTrue();
        assertThat(isCommentDeleted(reply1)).as("楼内回复随主楼整栋消失").isTrue();
        assertThat(isCommentDeleted(reply2)).as("楼内回复随主楼整栋消失").isTrue();
        assertThat(commentCountOf(contentId)).as("减去 1(主楼) + 2(回复) = 3").isZero();
        assertThat(countLiveComments(contentId)).isZero();
        assertThat(countAllComments(contentId)).as("是软删而非物理删：行还在").isEqualTo(3);
        assertThat(replyCountOf(mainId)).as("删主楼不扣 reply_count（主楼已删，无意义）").isEqualTo(2);
    }

    @Test
    @DisplayName("删自己的回复：只删自己（主楼与同楼其他回复保留）；comment_count -1、主楼 reply_count -1")
    void 删回复只删自己() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        String otherToken = registerAndGetToken(OTHER_PHONE, OTHER);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);

        addComment(contentId, "主楼", null, authorToken);
        long mainId = findCommentId(contentId, "主楼");
        addComment(contentId, "要删的回复", mainId, otherToken);
        addComment(contentId, "保留的回复", mainId, otherToken);
        long doomed = findCommentId(contentId, "要删的回复");
        long kept = findCommentId(contentId, "保留的回复");

        ResponseEntity<String> resp = deleteComment(doomed, otherToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(isCommentDeleted(doomed)).isTrue();
        assertThat(isCommentDeleted(kept)).as("同楼其他回复不受影响").isFalse();
        assertThat(isCommentDeleted(mainId)).as("主楼不受影响").isFalse();
        assertThat(commentCountOf(contentId)).isEqualTo(2);
        assertThat(replyCountOf(mainId)).isEqualTo(1);
    }

    // ==================== 删除：拒绝路径 ====================

    @Test
    @DisplayName("删他人的评论：403，且该评论未被软删（失败不留副作用）")
    void 删他人评论403() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        String otherToken = registerAndGetToken(OTHER_PHONE, OTHER);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);
        addComment(contentId, "作者的评论", null, authorToken);
        long commentId = findCommentId(contentId, "作者的评论");

        ResponseEntity<String> resp = deleteComment(commentId, otherToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(Envelope.code(resp)).isEqualTo(403);
        assertThat(isCommentDeleted(commentId)).as("403 不得留下任何脏数据").isFalse();
        assertThat(commentCountOf(contentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("删不存在/已软删的评论：404（isCommentExist 过滤 is_deleted=0）")
    void 删不存在的评论() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);
        addComment(contentId, "待删评论", null, token);
        long commentId = findCommentId(contentId, "待删评论");

        assertThat(deleteComment(999_999_999L, token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(deleteComment(commentId, token).getStatusCode()).isEqualTo(HttpStatus.OK);
        // 再删一次：已软删 ⇒ 视为不存在
        assertThat(deleteComment(commentId, token).getStatusCode())
                .as("重复删除已软删的评论应 404（不是幂等 200）")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("未登录删评论：401（TV AuthFilter PROTECTED_EXACT 含 /comment/delete）")
    void 未登录删评论() {
        ResponseEntity<String> resp = deleteComment(1L, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(Envelope.code(resp)).isEqualTo(401);
    }

    @Test
    @DisplayName("删除参数非法：缺 commentId / 非数字 → 400（TV 手写解析口径）")
    void 删除参数校验() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);

        assertThat(deleteComment(null, token).getStatusCode())
                .as("缺 commentId 应 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(deleteComment("abc", token).getStatusCode())
                .as("commentId 非数字应 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
