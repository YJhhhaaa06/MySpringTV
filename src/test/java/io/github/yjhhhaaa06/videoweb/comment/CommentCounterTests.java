package io.github.yjhhhaaa06.videoweb.comment;

import io.github.yjhhhaaa06.videoweb.comment.model.dto.AddCommentRequest;
import io.github.yjhhhaaa06.videoweb.comment.service.CommentService;
import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * 切片 S2 的**计数口径与事务边界**测试（决策表 CM-1 / CM-2 的固化）。
 *
 * <h2>为什么单列一个类</h2>
 * {@link CommentFlowTests} 钉的是"接口该返回什么"，本类钉的是**数据一致性的不变式**——
 * 这些不变式在接口层看不见（接口只回 "删除成功"），但它们错了会造成静默的计数漂移，
 * 而计数是列表排序与展示的输入。分开是为了让"哪些测试在守护一致性"一眼可见。
 *
 * <h2>三个不变式</h2>
 * <ol>
 *   <li>{@link #先单删回复再删主楼不得二次扣减()} —— {@code countFloorReplies} 的
 *       "1 + **剩余未删**回复数"口径。这同时是 CM-2"必须先计数再软删"顺序的**回归测试**：
 *       若把顺序反过来，软删会把楼内回复一起置 1，计数会数到 0 ⇒ 少扣。</li>
 *   <li>{@link #删回复时replyCount不得变负()} —— {@code updateReplyCount} 的防负守卫。</li>
 *   <li>{@link #计数更新失败时评论不落库()} / {@link #删除时计数失败则软删回滚()} ——
 *       两条路径的**原子性**。后者同时证明删除路径的 {@code @Transactional} **真的在生效**：
 *       若有人把它挪到私有的 {@code doDeleteComment} 上（Spring 自调用陷阱），此用例会失败。</li>
 * </ol>
 */
class CommentCounterTests extends AbstractCommentIntegrationTest {

    private static final String AUTHOR_PHONE = "13800004001";
    private static final String AUTHOR = "counter-author";
    private static final String OTHER_PHONE = "13800004002";
    private static final String OTHER = "counter-other";

    @Autowired
    private CommentService commentService;

    /** spy 真实执行 + 可局部打桩；用来制造"写库成功、计数失败"的场面。 */
    @MockitoSpyBean
    private ContentDao contentDao;

    @BeforeEach
    void resetStubs() {
        Mockito.reset(contentDao);
    }

    // ==================== 不变式 1：整栋删除的计数口径 ====================

    /**
     * ★ CM-2 的两个要点合成一条：**整栋删除口径** + **先计数再软删的顺序**。
     *
     * <p>场面：主楼 + 3 条回复（计数 4）→ 单删 1 条回复（计数 3）→ 删主楼。
     * 此时楼内只剩 2 条未删回复，故删主楼应扣 {@code 1 + 2 = 3} ⇒ 计数归 0。
     *
     * <p>若把"先计数再软删"反过来：{@code softDeleteFloor} 会把主楼与楼内回复一次性置
     * {@code is_deleted=1}，随后 {@code countFloorReplies}（带 {@code is_deleted=0}）数到 **0**，
     * 于是只扣 1 ⇒ 计数残留 2，本用例失败。
     *
     * <p>若把口径写成"1 + 全部楼内回复数（含已删）"：会扣 1+3=4 ⇒ 计数变 -1 或报错，同样失败。
     */
    @Test
    @DisplayName("★先单删一条回复、再删主楼：只扣 1+剩余未删回复数（不二次扣减，且顺序不可反）")
    void 先单删回复再删主楼不得二次扣减() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        String otherToken = registerAndGetToken(OTHER_PHONE, OTHER);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);

        addComment(contentId, "主楼", null, authorToken);
        long mainId = findCommentId(contentId, "主楼");
        addComment(contentId, "回复一", mainId, otherToken);
        addComment(contentId, "回复二", mainId, otherToken);
        addComment(contentId, "回复三", mainId, otherToken);
        long reply1 = findCommentId(contentId, "回复一");
        assertThat(commentCountOf(contentId)).isEqualTo(4);
        assertThat(replyCountOf(mainId)).isEqualTo(3);

        // 先单删一条回复：计数 4→3，主楼 reply_count 3→2
        assertThat(deleteComment(reply1, otherToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(commentCountOf(contentId)).isEqualTo(3);
        assertThat(replyCountOf(mainId)).isEqualTo(2);

        // 再删主楼：应扣 1(主楼) + 2(剩余未删回复) = 3
        assertThat(deleteComment(mainId, authorToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(commentCountOf(contentId))
                .as("整栋删除只扣 1+剩余未删回复数；若先软删再计数会数到 0 而只扣 1（残留 2）")
                .isZero();
        assertThat(countLiveComments(contentId)).isZero();
        assertThat(countAllComments(contentId)).as("4 行都还在（软删）").isEqualTo(4);
        assertThat(replyCountOf(mainId)).as("删主楼不动 reply_count").isEqualTo(2);
    }

    // ==================== 不变式 2：防负守卫 ====================

    /**
     * {@code reply_count} 的防负守卫（{@code AND reply_count + ? >= 0}）。
     *
     * <p>直接种出"有回复但 reply_count 停在 0"的不一致状态（模拟历史增量丢失），
     * 再删该回复：守卫应拦住这次 UPDATE，使计数停在 0 而不是 -1。
     * 注意删除**本身仍然成功**——守卫只作用于计数列，不改变软删结果。
     */
    @Test
    @DisplayName("删回复时 reply_count 为 0：防负守卫拦住，不得变成 -1（删除本身仍成功）")
    void 删回复时replyCount不得变负() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertContent(authorId, true);

        long mainId = insertComment(contentId, authorId, "主楼", null, null);
        long replyId = insertComment(contentId, authorId, "回复", mainId, null);
        assertThat(replyCountOf(mainId)).as("种出不一致状态：有回复但计数停在 0").isZero();
        // 夹具必须让 content.comment_count 与评论行数自洽（主楼 + 1 回复 = 2）。
        // 否则本次删除会触发 content.comment_count（int unsigned）从 0 再 -1 的下溢，
        // 那是 CM-2 记录的"已知并接受的旧实现尖锐处"，会以 500 掩盖本用例真正要测的防负守卫。
        jdbcTemplate.update("UPDATE content SET comment_count = 2 WHERE id = ?", contentId);

        assertThat(deleteComment(replyId, token).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(replyCountOf(mainId)).as("防负守卫：0 + (-1) < 0 ⇒ 该 UPDATE 不生效").isZero();
        assertThat(isCommentDeleted(replyId)).as("计数被守卫拦住不影响软删本身").isTrue();
    }

    // ==================== 不变式 3：两条路径的事务原子性 ====================

    /**
     * ★ CM-1 原子性：评论已插入、随后的计数更新失败 ⇒ **整笔回滚**，不留"孤儿评论"。
     *
     * <p>用 spy 只让 {@code updateCommentCount} 抛异常，其余 DAO 全走真实路径
     * （方法逐一列举而非 {@code any()}，保证测的确实是"插入是否被回滚"）。
     */
    @Test
    @DisplayName("★CM-1：计数更新失败 ⇒ 评论插入被回滚（不留孤儿评论）")
    void 计数更新失败时评论不落库() {
        long authorId = 4001L;
        long contentId = insertContent(authorId, true);
        doThrow(new DataAccessException("模拟计数更新失败") {
        }).when(contentDao).updateCommentCount(anyLong(), anyInt());

        assertThatThrownBy(() -> commentService.addComment(authorId, new AddCommentRequest(contentId, "会被回滚", null)))
                .isInstanceOf(DataAccessException.class);

        assertThat(countAllComments(contentId))
                .as("计数失败必须让整笔事务回滚——留下孤儿评论就是静默脏数据")
                .isZero();
        assertThat(commentCountOf(contentId)).isZero();
    }

    /**
     * ★ CM-2 原子性，并且是"删除路径事务真的生效"的**回归测试**。
     *
     * <p>删除走的是公开入口 {@code deleteCommentByUser} → 私有的 {@code doDeleteComment}。
     * 若有人把 {@code @Transactional} 从公开入口挪到私有方法上，Spring 的**自调用**
     * 会绕过代理 ⇒ 事务静默失效 ⇒ 软删不会回滚 ⇒ 本用例失败。
     */
    @Test
    @DisplayName("★CM-2：删除时计数失败 ⇒ 软删被回滚（同时证明 @Transactional 未被自调用绕过）")
    void 删除时计数失败则软删回滚() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertContent(authorId, true);
        long commentId = insertComment(contentId, authorId, "待删", null, null);
        jdbcTemplate.update("UPDATE content SET comment_count = 1 WHERE id = ?", contentId);

        doThrow(new DataAccessException("模拟计数更新失败") {
        }).when(contentDao).updateCommentCount(anyLong(), anyInt());

        assertThatThrownBy(() -> commentService.deleteCommentByUser(commentId, authorId))
                .isInstanceOf(DataAccessException.class);

        assertThat(isCommentDeleted(commentId))
                .as("软删必须随事务回滚；若不回滚说明 @Transactional 没生效（自调用陷阱）")
                .isFalse();
        assertThat(commentCountOf(contentId)).isEqualTo(1);
    }

    // ==================== 管理员删除（本切片无端点，service 级覆盖） ====================

    @Test
    @DisplayName("管理员删除：可删他人评论，且整栋口径与用户自删一致（本切片无端点，仅 service 级）")
    void 管理员可删任意评论() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertContent(authorId, true);
        addComment(contentId, "主楼", null, authorToken);
        long mainId = findCommentId(contentId, "主楼");
        addComment(contentId, "楼内回复", mainId, authorToken);

        // 无 operator 语义：管理员删（isAdmin=true 跳过归属校验）
        commentService.deleteCommentByAdmin(mainId);

        assertThat(isCommentDeleted(mainId)).isTrue();
        assertThat(isCommentDeleted(findCommentId(contentId, "楼内回复"))).isTrue();
        assertThat(commentCountOf(contentId)).isZero();

        // 删不存在仍 404（管理员不绕过"存在性"校验）
        assertThatThrownBy(() -> commentService.deleteCommentByAdmin(999_999_999L))
                .isInstanceOf(NotFoundException.class);
    }
}
