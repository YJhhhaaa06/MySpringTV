package io.github.yjhhhaaa06.videoweb.comment;

import io.github.yjhhhaaa06.videoweb.common.idempotency.IdempotencyFilter;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/v1/comments} 的**幂等运行期用例**（写接口幂等专项 `T66`；用例矩阵的唯一事实源 =
 * {@code architecture/tech/idempotency/写接口幂等-测试与契约.md} §二）。
 *
 * <p>已有的 {@code IdempotencyScopeTableTests} 只证明"注册表登记齐全"（静态）。本类补的是
 * **运行期效果层**：同 key 重发到底有没有把副作用钉成一次、重放拿回的是不是**同一份**响应。
 * 没有这一类用例，"机制接上了"只是 review 的判断，不是可复算的事实。
 *
 * <h2>三条断言纪律（本类的取值方式）</h2>
 * <ol>
 *   <li><b>独立 oracle</b>：{@code comment} 行数、{@code content.comment_count}、{@code comment.reply_count}
 *       一律直查 DB（{@code jdbcTemplate}），**不信接口回显**——回显与"真的写了一行"是两件事，
 *       尤其在本类这种"响应可能来自 Redis 快照"的场景里，回显正确恰恰不能证明没多写一行。</li>
 *   <li><b>每条用例带对照</b>：{@link #otherToken()} / 另一条内容。没有对照时，
 *       把 Redis key 里的 {@code uid} 去掉（{@code idem:{slug}:{key}}）全部用例仍会绿——
 *       那正是"跨用户串味"这类最坏事故的形态。</li>
 *   <li><b>不断言谁先谁后</b>：并发用例只断言"合法结果集合"与"行数 = 1"，见
 *       {@link #并发同key_业务只执行一次()}。</li>
 * </ol>
 *
 * <h2>★ 用例 5 的陷阱（"同 key 重发能成功"不等于"换个 body 能成功"）</h2>
 * {@link #首次失败后同key同body重发_能成功()} 必须让第一次因**可恢复的外部状态**失败
 * （{@code content.comment_enabled = 0}），再用**逐字节相同**的 body 重发。若第二次换了 body，
 * 测到的是 422（用例 4）而不是"{@code FAILED} 是墓碑不是锁"。
 *
 * <h2>Redis 清理</h2>
 * key 形态是 {@code idem:{slug}:{uid}:{clientKey}}，基类的 {@code DELETE FROM users} 清不掉它
 * （且自增 id 不重置）⇒ 必须在 {@code @BeforeEach} 里显式删 {@code idem:*}。
 * <p>clientKey 用 {@code UUID} 后缀而不是裸数字串：日志/访问行里有手机号脱敏，
 * 一串纯数字会被误判成手机号（既有用例踩过）。
 */
class CommentIdempotencyTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";
    private static final String AUTHOR_PHONE = "13800009001";
    private static final String OTHER_PHONE = "13800009002";

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void resetIdempotencyState() {
        // 基类的 DELETE FROM users 先跑（JUnit 5 父类 @BeforeEach 在前），这里再清本域的两张表。
        jdbcTemplate.update("DELETE FROM comment");
        jdbcTemplate.update("DELETE FROM content");
        if (!idemKeys().isEmpty()) {
            redis.delete(idemKeys());
        }
        assertThat(idemKeys()).as("进门必须没有任何 idem:* 残留（否则上一条用例的 DONE 会替本用例答题）").isEmpty();
    }

    // ==================== 用例 1：同 key 重发 ⇒ 同一份响应 ====================

    @Test
    @DisplayName("用例1：同 key 重发两次都 2xx，body 逐字节相同（含同一个 commentId）；仅第二次带 Idempotent-Replayed")
    void 同key重发_逐字节重放首次响应() {
        String token = registerAndGetToken(AUTHOR_PHONE, "idem-author", PASSWORD);
        String otherToken = registerAndGetToken(OTHER_PHONE, "idem-other", PASSWORD);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);
        String key = clientKey("replay");

        ResponseEntity<String> first = addComment(contentId, "只此一条", null, token, idemKey(key));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("首次响应不带重放标记（契约：仅重放命中时出现）").isNull();
        long commentId = Envelope.num(first, "commentId");
        assertThat(commentId).as("`N5` 起 2xx 必须回 commentId，否则本用例的\"同一标识\"无从断言").isPositive();

        ResponseEntity<String> second = addComment(contentId, "只此一条", null, token, idemKey(key));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED)).isEqualTo("true");
        assertThat(second.getBody())
                .as("重放必须逐字节相同——含同一个 commentId（`N5` 让这条不再真空转）")
                .isEqualTo(first.getBody());
        assertThat(Envelope.num(second, "commentId")).isEqualTo(commentId);

        // ★ 对照：另一个用户用**同一个 clientKey** 必须是一次独立执行（key 里含 uid）
        ResponseEntity<String> other = addComment(contentId, "只此一条", null, otherToken, idemKey(key));

        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(other.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("另一个用户命中别人的快照 = key 里漏了 uid（跨用户串味）").isNull();
        assertThat(Envelope.num(other, "commentId"))
                .as("不同用户同 clientKey 必须是两条不同的评论").isNotEqualTo(commentId);
        assertThat(countComments(contentId)).isEqualTo(2);
    }

    // ==================== 用例 2：独立 oracle 复算副作用 ====================

    @Test
    @DisplayName("用例2：同 key 重发——comment 行数只 +1；主楼看 content.comment_count，回复再加 reply_count（直查 DB）")
    void 同key重发_副作用只发生一次_独立oracle() {
        String token = registerAndGetToken(AUTHOR_PHONE, "idem-oracle", PASSWORD);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);
        long otherContentId = insertContent(userIdOf(AUTHOR_PHONE), true);

        String mainKey = clientKey("oracle-main");
        addComment(contentId, "主楼", null, token, idemKey(mainKey));
        addComment(contentId, "主楼", null, token, idemKey(mainKey));

        assertThat(countComments(contentId)).as("同 key 两次只落一行").isEqualTo(1);
        assertThat(commentCountOf(contentId)).as("content.comment_count 只 +1").isEqualTo(1);

        long mainId = commentIdOf(contentId, "主楼");
        String replyKey = clientKey("oracle-reply");
        addComment(contentId, "回复主楼", mainId, token, idemKey(replyKey));
        addComment(contentId, "回复主楼", mainId, token, idemKey(replyKey));

        assertThat(countComments(contentId)).as("主楼 1 + 回复 1").isEqualTo(2);
        assertThat(commentCountOf(contentId)).as("回复也计进 comment_count，且只 +1").isEqualTo(2);
        assertThat(replyCountOf(mainId)).as("主楼 reply_count 只 +1（不信接口回显）").isEqualTo(1);

        // ★ 对照：另一条内容 + 另一个 key ⇒ 计数确实会涨（证明上面的 '1' 不是写死的期望值）
        addComment(otherContentId, "另一条内容", null, token, idemKey(clientKey("oracle-other-content")));
        assertThat(countComments(otherContentId)).isEqualTo(1);
        assertThat(commentCountOf(otherContentId)).isEqualTo(1);
        assertThat(countComments(contentId)).as("另一条内容的评论不得串到本条").isEqualTo(2);
    }

    // ==================== 用例 3：并发同 key ====================

    @Test
    @DisplayName("用例3：并发两请求同 key——恰好一个真正执行，另一个重放或 409；comment 行数 = 1")
    void 并发同key_业务只执行一次() throws Exception {
        String token = registerAndGetToken(AUTHOR_PHONE, "idem-concurrent", PASSWORD);
        String otherToken = registerAndGetToken(OTHER_PHONE, "idem-concurrent-other", PASSWORD);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);
        HttpHeaders headers = idemKey(clientKey("concurrent"));

        List<ResponseEntity<String>> responses = concurrentlyPost(
                () -> addComment(contentId, "并发同 key", null, token, headers));

        assertThat(responses).hasSize(2);
        assertThat(responses).allSatisfy(resp ->
                assertThat(resp.getStatusCode().value()).as("合法结果只有 2xx 与 409").isIn(200, 409));

        long executed = responses.stream().filter(CommentIdempotencyTests::executed).count();
        assertThat(executed)
                .as("恰好一个请求真正执行（2xx 且不带重放标记）——★ 不断言谁先谁后")
                .isEqualTo(1);
        long replayOrBusy = responses.stream().filter(resp -> !executed(resp)).count();
        assertThat(replayOrBusy).isEqualTo(1);
        assertThat(responses.stream().filter(resp -> resp.getStatusCode().value() == 409))
                .allSatisfy(resp -> assertThat(Envelope.code(resp)).isEqualTo("IDEMPOTENCY_IN_PROGRESS"));

        assertThat(countComments(contentId)).as("两个请求只允许落一行").isEqualTo(1);
        assertThat(commentCountOf(contentId)).isEqualTo(1);

        // ★ 对照：另一个用户持同一个 clientKey ⇒ 一条独立的新行（占位是 per-user 的）
        ResponseEntity<String> other = addComment(contentId, "并发同 key", null, otherToken, headers);
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(other.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED)).isNull();
        assertThat(countComments(contentId)).isEqualTo(2);
    }

    // ==================== 用例 4：同 key 不同 body ====================

    @Test
    @DisplayName("用例4：同 key 不同 body ⇒ 422 IDEMPOTENCY_KEY_CONFLICT，且 comment 行数不变（零副作用）")
    void 同key不同body_422零副作用() {
        String token = registerAndGetToken(AUTHOR_PHONE, "idem-conflict", PASSWORD);
        String otherToken = registerAndGetToken(OTHER_PHONE, "idem-conflict-other", PASSWORD);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);
        String key = clientKey("conflict");

        ResponseEntity<String> first = addComment(contentId, "原载荷", null, token, idemKey(key));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countComments(contentId)).isEqualTo(1);

        ResponseEntity<String> conflict = addComment(contentId, "换了载荷", null, token, idemKey(key));

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(Envelope.code(conflict)).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(conflict.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("冲突走错误信封，不是重放").isNull();
        assertThat(countComments(contentId)).as("422 必须零副作用").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE content_id = ? AND content = ?",
                Long.class, contentId, "换了载荷"))
                .as("被拒载荷一行都不能落").isZero();

        // ★ 对照一：同一用户换个 key + 同一份"新载荷" ⇒ 正常执行（证明 422 是 key 复用引起的，不是 body 的错）
        ResponseEntity<String> freshKey = addComment(contentId, "换了载荷", null, token,
                idemKey(clientKey("conflict-new-key")));
        assertThat(freshKey.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countComments(contentId)).isEqualTo(2);

        // ★ 对照二：另一个用户用同一个 key + 同一份"新载荷" ⇒ 正常执行（证明冲突判定按 uid 隔离）
        ResponseEntity<String> other = addComment(contentId, "换了载荷", null, otherToken, idemKey(key));
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(other.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED)).isNull();
        assertThat(countComments(contentId)).isEqualTo(3);
    }

    // ==================== 用例 5：首次失败（墓碑不是锁） ====================

    @Test
    @DisplayName("用例5：首次 409 COMMENT_DISABLED 后，同 key 同 body 重发 ⇒ 200（FAILED 是墓碑不是锁）")
    void 首次失败后同key同body重发_能成功() {
        String token = registerAndGetToken(AUTHOR_PHONE, "idem-retry", PASSWORD);
        String otherToken = registerAndGetToken(OTHER_PHONE, "idem-retry-other", PASSWORD);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);
        String key = clientKey("failed-tombstone");

        // ★ 失败来自"可恢复的外部状态"，而不是载荷——第二次必须发**逐字节相同**的 body
        setCommentEnabled(contentId, false);
        ResponseEntity<String> failed = addComment(contentId, "关评后再发", null, token, idemKey(key));

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(Envelope.code(failed)).isEqualTo("COMMENT_DISABLED");
        assertThat(countComments(contentId)).as("失败的第一次零副作用").isZero();

        setCommentEnabled(contentId, true);
        ResponseEntity<String> retried = addComment(contentId, "关评后再发", null, token, idemKey(key));

        assertThat(retried.getStatusCode()).as("FAILED 墓碑必须允许重新占位执行").isEqualTo(HttpStatus.OK);
        assertThat(retried.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("这次是真的执行，不是重放").isNull();
        assertThat(Envelope.num(retried, "commentId")).isPositive();
        assertThat(countComments(contentId)).isEqualTo(1);
        assertThat(commentCountOf(contentId)).isEqualTo(1);

        // ★ 对照：另一个用户持同一个 key（此时内容已开放）⇒ 自己的新行（墓碑/占位同样是 per-user 的）
        ResponseEntity<String> other = addComment(contentId, "关评后再发", null, otherToken, idemKey(key));
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(other.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED)).isNull();
        assertThat(countComments(contentId)).isEqualTo(2);
    }

    // ==================== 用例 6：缺 key / 非法 key ====================

    @Test
    @DisplayName("用例6：缺 key ⇒ 放行且 Redis 无 idem:* 键；非法 key（65 字符 / 含冒号）⇒ 400 零副作用")
    void 缺key放行_非法key400零副作用() {
        String token = registerAndGetToken(AUTHOR_PHONE, "idem-key-shape", PASSWORD);
        long contentId = insertContent(userIdOf(AUTHOR_PHONE), true);

        // 缺 key：放行、不占位
        ResponseEntity<String> noKey = post("/api/v1/comments",
                commentBody(contentId, "没带 key", null), token);

        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(noKey.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("没有幂等器参与 ⇒ 不可能有重放标记").isNull();
        assertThat(countComments(contentId)).isEqualTo(1);
        assertThat(idemKeys()).as("缺 key 必须不占位（一个字面都不许写）").isEmpty();

        // 非法：超长（65 字符）
        ResponseEntity<String> tooLong = addComment(contentId, "超长 key", null, token,
                idemKey("k".repeat(65)));
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(Envelope.code(tooLong)).isEqualTo("IDEMPOTENCY_KEY_INVALID");

        // 非法：含 ':'（会破坏 idem:{slug}:{uid}:{key} 的边界解析）
        ResponseEntity<String> withColon = addComment(contentId, "含冒号 key", null, token,
                idemKey("abc:def"));
        assertThat(withColon.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(Envelope.code(withColon)).isEqualTo("IDEMPOTENCY_KEY_INVALID");

        assertThat(countComments(contentId)).as("两个 400 都必须零副作用").isEqualTo(1);
        assertThat(idemKeys()).as("非法 key 也不许占位").isEmpty();

        // ★ 对照：同一份载荷 + 合法 key ⇒ 正常执行（证明 400 是 key 形态引起的，不是载荷/端点坏了）
        ResponseEntity<String> valid = addComment(contentId, "合法 key", null, token,
                idemKey("k".repeat(64)));
        assertThat(valid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countComments(contentId)).isEqualTo(2);
        assertThat(idemKeys()).hasSize(1);
    }

    // ==================== 夹具 / oracle / 工具 ====================

    private Map<String, Object> commentBody(long contentId, String message, Long parentId) {
        // LinkedHashMap：并发用例要求两次请求的 body **逐字节相同** ⇒ 不能依赖 Map.of 的迭代顺序
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contentId", contentId);
        body.put("message", message);
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        return body;
    }

    private ResponseEntity<String> addComment(long contentId, String message, Long parentId,
                                              String token, HttpHeaders headers) {
        return post("/api/v1/comments", commentBody(contentId, message, parentId), token, headers);
    }

    /** clientKey：{@code [A-Za-z0-9._-]{1,64}}，用 UUID 后缀避开"纯数字被当手机号"的脱敏误判。 */
    private static String clientKey(String tag) {
        return "t66." + tag + "." + UUID.randomUUID();
    }

    private static HttpHeaders idemKey(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(IdempotencyFilter.HEADER_KEY, key);
        return headers;
    }

    private Set<String> idemKeys() {
        Set<String> keys = redis.keys("idem:*");
        return keys == null ? Set.of() : keys;
    }

    /** 该响应是否"真的执行了业务"（2xx 且不是重放）。 */
    private static boolean executed(ResponseEntity<String> response) {
        return response.getStatusCode().value() == 200
                && response.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED) == null;
    }

    /**
     * 两个线程**同时**发同一个请求（用闸门让两条请求尽量同刻到达）。
     *
     * <p>为什么不用 {@code parallelStream}：它用的是公共池、并发度不可控；
     * 这里要的是"恰好两条、同刻起跑"。
     */
    private List<ResponseEntity<String>> concurrentlyPost(java.util.function.Supplier<ResponseEntity<String>> call)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<String>>> futures = List.of(
                    pool.submit(() -> {
                        start.await();
                        return call.get();
                    }),
                    pool.submit(() -> {
                        start.await();
                        return call.get();
                    }));
            start.countDown();
            return List.of(
                    futures.get(0).get(30, TimeUnit.SECONDS),
                    futures.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private long insertContent(long authorId, boolean commentEnabled) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description, comment_enabled) "
                            + "VALUES (?, 1, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setString(2, "T66 测试内容 " + authorId);
            ps.setString(3, "T66 测试描述");
            ps.setBoolean(4, commentEnabled);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    private void setCommentEnabled(long contentId, boolean enabled) {
        jdbcTemplate.update("UPDATE content SET comment_enabled = ? WHERE id = ?", enabled, contentId);
    }

    /** 独立 oracle：该内容下的**全部** comment 行数（含软删——本类不删评论，故等于落库次数）。 */
    private long countComments(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE content_id = ?", Long.class, contentId);
        return n == null ? 0 : n;
    }

    private int commentCountOf(long contentId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT comment_count FROM content WHERE id = ?", Integer.class, contentId);
        assertThat(n).as("content %s 应存在", contentId).isNotNull();
        return n;
    }

    private int replyCountOf(long commentId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT reply_count FROM comment WHERE comment_id = ?", Integer.class, commentId);
        assertThat(n).as("comment %s 应存在", commentId).isNotNull();
        return n;
    }

    private long commentIdOf(long contentId, String text) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT comment_id FROM comment WHERE content_id = ? AND content = ?",
                Long.class, contentId, text);
        assertThat(id).as("内容 %s 下应存在正文为 %s 的评论", contentId, text).isNotNull();
        return id;
    }
}
