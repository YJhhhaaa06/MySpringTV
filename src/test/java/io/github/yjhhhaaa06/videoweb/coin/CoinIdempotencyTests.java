package io.github.yjhhhaaa06.videoweb.coin;

import io.github.yjhhhaaa06.videoweb.common.idempotency.IdempotencyFilter;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/v1/contents/{id}/coins} 的**幂等运行期用例**（写接口幂等专项 `T67`；用例矩阵的唯一事实源 =
 * {@code architecture/tech/idempotency/写接口幂等-测试与契约.md} §二）。
 *
 * <p>骨架照 {@code comment/CommentIdempotencyTests}：6 条通用用例 + 本域专属断言。
 * 主代码**一行没改** —— 投币端点在 {@code IdempotencyScope} 里早已登记成 {@code KEYED}，
 * 本类的作用是证明"通用机制对**累加语义**的端点也成立"。
 *
 * <h2>★ 为什么投币必须单独有一套（封顶挡不住重试）</h2>
 * 投币有"单人单内容封顶 2 枚"的兜底，看起来像幂等。它不是：
 * <ul>
 *   <li>{@code count = 1} 的重试正好落在合法区间 ⇒ 会**真扣第二次**（封顶只在第 3 枚时说话）；</li>
 *   <li>封顶是**按 (user, content) 的资源上限**，幂等是**按意图（key）的去重**——两件事。</li>
 * </ul>
 *
 * <h2>★★ 两条专属用例是本任务存在的理由</h2>
 * <ol>
 *   <li>{@link #两个不同key各投一枚_累计两枚()} —— 投币是**累加语义**：两台设备各投 1 枚是两次
 *       独立意图，最终 {@code amount = 2}。若实现图省事把 Redis key 里的 {@code clientKey}
 *       换成 {@code contentId}（"按内容去重"，已被明确否决的 set 语义），本条**立刻变红**。</li>
 *   <li>{@link #同key投两枚的重发_不叠加()} —— 同 key 投 {@code count = 2} 的重发，{@code amount}
 *       仍是 2（不是 4）。配的对照是"换个 key 再投 1 枚 ⇒ 409 封顶"：证明**去重靠 key，不靠封顶**。</li>
 * </ol>
 *
 * <h2>三条断言纪律（取值方式同评论那套）</h2>
 * <ol>
 *   <li><b>独立 oracle</b>：{@code users.coin_deci} / {@code content_coin.amount} /
 *       {@code content.coin_count} 一律直查 DB，**不信接口回显** —— 本类的响应可能整段来自 Redis 快照，
 *       "回显正确"恰恰不能证明没多扣一枚。</li>
 *   <li><b>每条用例带对照</b>：另一用户持同一个 {@code clientKey}（证明 key 含 uid），
 *       或换 {@code key} / 换内容（证明断言里的 {@code 1} 不是写死的期望值）。</li>
 *   <li><b>不断言谁先谁后</b>：并发用例只断言"合法结果集合"与列终态，见
 *       {@link #并发同key_业务只执行一次()}。</li>
 * </ol>
 * ⚠️ <b>对照为什么"换内容"时必须同时换 key</b>：指纹只覆盖**请求体**（{@code {"count":N}}），
 * 不含路径 ⇒ 同一个 key 打到另一条内容、body 又一样时，拿到的是第一次的快照（重放）。
 * 那是"key 是意图标识"的直接推论（客户端复用 key ⇒ 同一个意图），故对照一律换 key。
 *
 * <h2>Redis 清理</h2>
 * key 形态是 {@code idem:{slug}:{uid}:{clientKey}}，基类 {@code AbstractCoinIntegrationTest#resetState}
 * 已整体清 Redis；本类进门再断一次"没有任何 {@code idem:*} 残留"——
 * 否则上一条用例的 {@code DONE} 会替本用例答题（{@code DELETE FROM users} 清不掉它，自增 id 也不重置）。
 * <p>clientKey 用 {@code UUID} 后缀而不是裸数字串：访问日志里有手机号脱敏，一串纯数字会被误判成手机号。
 */
class CoinIdempotencyTests extends AbstractCoinIntegrationTest {

    private static final String VOTER_PHONE = "13900003901";
    private static final String OTHER_PHONE = "13900003902";
    private static final String AUTHOR_PHONE = "13900003903";

    private static final int VIDEO = 1;

    @BeforeEach
    void assertNoIdempotencyResidue() {
        // 基类的 resetState 已 clearRedis（JUnit 5 父类 @BeforeEach 在前）⇒ 这里只是把前提钉成断言。
        assertThat(idemKeys())
                .as("进门必须没有任何 idem:* 残留（否则上一条用例的 DONE 会替本用例答题）")
                .isEmpty();
    }

    // ==================== 用例 1：同 key 重发 ⇒ 同一份响应 ====================

    @Test
    @DisplayName("用例1：同 key 重发两次都 200，body 逐字节相同（含同一个 balance）；仅第二次带 Idempotent-Replayed")
    void 同key重发_逐字节重放首次响应() {
        TestUser voter = registerUser(VOTER_PHONE, "t67_replay");
        TestUser author = registerUser(AUTHOR_PHONE, "t67_replay_author");
        setCoinDeci(voter.id(), 30);              // 3.0 枚
        long contentId = insertContent(author.id(), VIDEO, "T67 幂等视频一");
        String key = clientKey("replay");

        ResponseEntity<String> first = addCoin(voter.token(), contentId, 1, idemKey(key));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("首次响应不带重放标记（契约：仅重放命中时出现）").isNull();
        assertThat(Envelope.data(first).path("balance").decimalValue())
                .as("30 − 1×10 = 20 分位 = 2.0 枚").isEqualByComparingTo("2.0");

        ResponseEntity<String> second = addCoin(voter.token(), contentId, 1, idemKey(key));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED)).isEqualTo("true");
        assertThat(second.getBody())
                .as("重放必须逐字节相同——含同一个 balance（投币的 2xx 快照就是它）")
                .isEqualTo(first.getBody());

        // 独立 oracle：三处列终态（不读上面的回显）
        assertThat(coinDeciOf(voter.id())).as("只扣一次：30 − 10 = 20").isEqualTo(20);
        assertThat(amountOf(voter.id(), contentId)).as("关系行只累加一次").isEqualTo(1);
        assertThat(coinCountOf(contentId)).as("累计被投币数只 +1").isEqualTo(1);
        assertThat(authorCoinDeciOf(author.id())).as("作者只收一次：+1 分位").isEqualTo(1);

        // ★ 对照：另一个用户用**同一个 clientKey** ⇒ 一次独立执行（key 里含 uid）
        TestUser other = registerUser(OTHER_PHONE, "t67_replay_other");
        setCoinDeci(other.id(), 20);
        ResponseEntity<String> otherResp = addCoin(other.token(), contentId, 1, idemKey(key));

        assertThat(otherResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otherResp.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("另一个用户命中别人的快照 = key 里漏了 uid（跨用户串味）").isNull();
        assertThat(amountOf(other.id(), contentId)).as("另一用户自己的一行").isEqualTo(1);
        assertThat(coinCountOf(contentId)).as("另一人是独立的一次投币").isEqualTo(2);
        assertThat(coinDeciOf(voter.id())).as("对照不得动到本人余额").isEqualTo(20);
    }

    // ==================== 用例 2：独立 oracle 复算副作用 ====================

    @Test
    @DisplayName("用例2：同 key 重发——amount 只 +1、coin_deci 只减一次、coin_count 只 +1（直查 DB）")
    void 同key重发_副作用只发生一次_独立oracle() {
        TestUser voter = registerUser(VOTER_PHONE, "t67_oracle");
        TestUser author = registerUser(AUTHOR_PHONE, "t67_oracle_author");
        setCoinDeci(voter.id(), 40);
        long contentId = insertContent(author.id(), VIDEO, "T67 幂等视频二");
        long otherContentId = insertContent(author.id(), VIDEO, "T67 幂等视频二-对照");

        String key = clientKey("oracle");
        addCoin(voter.token(), contentId, 1, idemKey(key));
        addCoin(voter.token(), contentId, 1, idemKey(key));

        assertThat(countCoinRows()).as("同 key 两次只有一行（uk_user_content 也兜得住）").isEqualTo(1);
        assertThat(amountOf(voter.id(), contentId)).as("amount 只 +1").isEqualTo(1);
        assertThat(coinDeciOf(voter.id())).as("coin_deci 只减一次：40 − 10 = 30").isEqualTo(30);
        assertThat(coinCountOf(contentId)).as("coin_count 只 +1").isEqualTo(1);
        assertThat(authorCoinDeciOf(author.id())).as("作者收益只加一次").isEqualTo(1);

        // ★ 对照：同一用户、**另一条内容 + 另一个 key** ⇒ 副作用真的会再发生一次
        //   （没有这个对照，"期望值 1 / 30 / 1" 是写死的还是算出来的就分不清）
        String otherKey = clientKey("oracle-other-content");
        assertThat(addCoin(voter.token(), otherContentId, 1, idemKey(otherKey)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(amountOf(voter.id(), otherContentId)).isEqualTo(1);
        assertThat(coinCountOf(otherContentId)).isEqualTo(1);
        assertThat(coinDeciOf(voter.id())).as("40 − 10（重发只扣一次）− 10（另一条内容）= 20").isEqualTo(20);
        assertThat(authorCoinDeciOf(author.id())).as("作者收两次").isEqualTo(2);
        assertThat(countCoinRows()).as("两条内容 ⇒ 两行").isEqualTo(2);
        assertThat(coinCountOf(contentId)).as("另一条内容的投币不得串到本条").isEqualTo(1);
    }

    // ==================== 用例 3：并发同 key ====================

    @Test
    @DisplayName("用例3：并发两请求同 key——恰好一个真正执行，另一个重放或 409；amount = 1")
    void 并发同key_业务只执行一次() throws Exception {
        TestUser voter = registerUser(VOTER_PHONE, "t67_concurrent");
        TestUser author = registerUser(AUTHOR_PHONE, "t67_concurrent_author");
        setCoinDeci(voter.id(), 20);
        long contentId = insertContent(author.id(), VIDEO, "T67 幂等视频三");
        HttpHeaders headers = idemKey(clientKey("concurrent"));

        List<ResponseEntity<String>> responses = concurrentlyPost(
                () -> addCoin(voter.token(), contentId, 1, headers));

        assertThat(responses).hasSize(2);
        assertThat(responses).allSatisfy(resp ->
                assertThat(resp.getStatusCode().value()).as("合法结果只有 2xx 与 409").isIn(200, 409));

        long executed = responses.stream().filter(CoinIdempotencyTests::executed).count();
        assertThat(executed)
                .as("恰好一个请求真正执行（2xx 且不带重放标记）——★ 不断言谁先谁后")
                .isEqualTo(1);
        assertThat(responses.stream().filter(resp -> !executed(resp))).hasSize(1);
        assertThat(responses.stream().filter(resp -> resp.getStatusCode().value() == 409))
                .allSatisfy(resp -> assertThat(Envelope.code(resp)).isEqualTo("IDEMPOTENCY_IN_PROGRESS"));

        // 独立 oracle：DB 终态只反映一次投币
        assertThat(amountOf(voter.id(), contentId)).as("两个请求只投成 1 枚").isEqualTo(1);
        assertThat(coinCountOf(contentId)).isEqualTo(1);
        assertThat(coinDeciOf(voter.id())).as("只扣一次：20 − 10 = 10（不是 0）").isEqualTo(10);
        assertThat(authorCoinDeciOf(author.id())).as("作者只收一次：1").isEqualTo(1);

        // ★ 对照：另一个用户持同一组请求头 ⇒ 自己的一次独立投币（占位是 per-user 的）
        TestUser other = registerUser(OTHER_PHONE, "t67_concurrent_other");
        setCoinDeci(other.id(), 20);
        ResponseEntity<String> otherResp = addCoin(other.token(), contentId, 1, headers);

        assertThat(otherResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otherResp.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED)).isNull();
        assertThat(amountOf(other.id(), contentId)).isEqualTo(1);
        assertThat(coinCountOf(contentId)).isEqualTo(2);
        assertThat(coinDeciOf(voter.id())).as("对照不得动到本人余额").isEqualTo(10);
    }

    // ==================== 用例 4：同 key 不同 body ====================

    @Test
    @DisplayName("用例4：同 key 不同 body（count 1 vs 2）⇒ 422 IDEMPOTENCY_KEY_CONFLICT，且零副作用")
    void 同key不同body_422零副作用() {
        TestUser voter = registerUser(VOTER_PHONE, "t67_conflict");
        TestUser author = registerUser(AUTHOR_PHONE, "t67_conflict_author");
        setCoinDeci(voter.id(), 50);
        long contentId = insertContent(author.id(), VIDEO, "T67 幂等视频四");
        long freshContentId = insertContent(author.id(), VIDEO, "T67 幂等视频四-对照");
        String key = clientKey("conflict");

        ResponseEntity<String> first = addCoin(voter.token(), contentId, 1, idemKey(key));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(amountOf(voter.id(), contentId)).isEqualTo(1);

        ResponseEntity<String> conflict = addCoin(voter.token(), contentId, 2, idemKey(key));

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(Envelope.code(conflict)).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(conflict.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("冲突走错误信封，不是重放").isNull();

        // ★ 零副作用：币余额 / 关系行 / 累计数 / 作者收益全不动
        assertThat(coinDeciOf(voter.id())).as("50 − 10（首次）= 40，被拒的 2 枚一枚都没扣").isEqualTo(40);
        assertThat(amountOf(voter.id(), contentId)).as("amount 停在 1（不是 3）").isEqualTo(1);
        assertThat(coinCountOf(contentId)).as("coin_count 停在 1").isEqualTo(1);
        assertThat(authorCoinDeciOf(author.id())).as("作者收益停在 1").isEqualTo(1);

        // ★ 对照一：同一用户换个 key + 同一份"新载荷" ⇒ 正常执行（证明 422 是 key 复用引起的，不是 body 的错）
        ResponseEntity<String> freshKey = addCoin(voter.token(), freshContentId, 2,
                idemKey(clientKey("conflict-new-key")));
        assertThat(freshKey.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(amountOf(voter.id(), freshContentId)).isEqualTo(2);
        assertThat(coinCountOf(freshContentId)).isEqualTo(2);

        // ★ 对照二：另一个用户用同一个 key + 同一份"新载荷" ⇒ 正常执行（证明冲突判定按 uid 隔离）
        TestUser other = registerUser(OTHER_PHONE, "t67_conflict_other");
        setCoinDeci(other.id(), 20);
        ResponseEntity<String> otherResp = addCoin(other.token(), contentId, 2, idemKey(key));

        assertThat(otherResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otherResp.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED)).isNull();
        assertThat(amountOf(other.id(), contentId)).isEqualTo(2);
        assertThat(coinCountOf(contentId)).as("1（本人）+ 2（另一用户）= 3").isEqualTo(3);
    }

    // ==================== 用例 5：首次失败（墓碑不是锁） ====================

    @Test
    @DisplayName("用例5：首次 400 COIN_INSUFFICIENT 后，同 key 同 body 重发 ⇒ 200（FAILED 是墓碑不是锁）")
    void 首次失败后同key同body重发_能成功() {
        TestUser voter = registerUser(VOTER_PHONE, "t67_retry");
        TestUser author = registerUser(AUTHOR_PHONE, "t67_retry_author");
        setCoinDeci(voter.id(), 0);               // 外部状态：一枚都没有
        long contentId = insertContent(author.id(), VIDEO, "T67 幂等视频五");
        String key = clientKey("failed-tombstone");

        // ★ 失败来自"可恢复的外部状态"（余额），不是载荷 ⇒ 第二次必须发**逐字节相同**的 body。
        //   换 body 测到的是 422（用例 4），不是"FAILED 允许重新执行"。
        ResponseEntity<String> failed = addCoin(voter.token(), contentId, 2, idemKey(key));

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(Envelope.code(failed)).isEqualTo("COIN_INSUFFICIENT");
        assertThat(countCoinRows()).as("失败的第一次零副作用").isZero();
        assertThat(amountOf(voter.id(), contentId)).as("关系行随事务回滚").isNull();
        assertThat(coinCountOf(contentId)).isZero();

        setCoinDeci(voter.id(), 20);              // 充硬币：外部状态恢复
        ResponseEntity<String> retried = addCoin(voter.token(), contentId, 2, idemKey(key));

        assertThat(retried.getStatusCode()).as("FAILED 墓碑必须允许重新占位执行").isEqualTo(HttpStatus.OK);
        assertThat(retried.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("这次是真的执行，不是重放").isNull();
        assertThat(amountOf(voter.id(), contentId)).isEqualTo(2);
        assertThat(coinCountOf(contentId)).isEqualTo(2);
        assertThat(coinDeciOf(voter.id())).as("20 − 2×10 = 0").isZero();
        assertThat(authorCoinDeciOf(author.id())).as("作者收益 +2 分位").isEqualTo(2);

        // ★ 对照：另一个用户持同一个 key + 同一份载荷 ⇒ 自己的一次独立执行（墓碑也是 per-user 的）
        TestUser other = registerUser(OTHER_PHONE, "t67_retry_other");
        setCoinDeci(other.id(), 20);
        ResponseEntity<String> otherResp = addCoin(other.token(), contentId, 2, idemKey(key));

        assertThat(otherResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otherResp.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED)).isNull();
        assertThat(amountOf(other.id(), contentId)).isEqualTo(2);
        assertThat(coinCountOf(contentId)).as("2（本人）+ 2（另一用户）= 4").isEqualTo(4);
    }

    // ==================== 用例 6：缺 key / 非法 key ====================

    @Test
    @DisplayName("用例6：缺 key ⇒ 放行且 Redis 无 idem:*；非法 key（65 字符 / 含冒号）⇒ 400 零副作用")
    void 缺key放行_非法key400零副作用() {
        TestUser voter = registerUser(VOTER_PHONE, "t67_key_shape");
        TestUser author = registerUser(AUTHOR_PHONE, "t67_key_shape_author");
        setCoinDeci(voter.id(), 40);
        long contentId = insertContent(author.id(), VIDEO, "T67 幂等视频六");

        // 缺 key：放行、不占位（退化为普通请求）
        ResponseEntity<String> noKey = addCoin(voter.token(), contentId, 1);

        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(noKey.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("没有幂等器参与 ⇒ 不可能有重放标记").isNull();
        assertThat(amountOf(voter.id(), contentId)).as("放行 ⇒ 业务真的执行了一次").isEqualTo(1);
        assertThat(idemKeys()).as("缺 key 必须不占位（一个字面都不许写）").isEmpty();

        // 非法：超长（65 字符）
        ResponseEntity<String> tooLong = addCoin(voter.token(), contentId, 1, idemKey("k".repeat(65)));
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(Envelope.code(tooLong)).isEqualTo("IDEMPOTENCY_KEY_INVALID");

        // 非法：含 ':'（会破坏 idem:{slug}:{uid}:{key} 的边界解析）
        ResponseEntity<String> withColon = addCoin(voter.token(), contentId, 1, idemKey("abc:def"));
        assertThat(withColon.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(Envelope.code(withColon)).isEqualTo("IDEMPOTENCY_KEY_INVALID");

        // 零副作用：两个 400 一条都没投成
        assertThat(amountOf(voter.id(), contentId)).as("两个 400 都必须零副作用").isEqualTo(1);
        assertThat(coinCountOf(contentId)).isEqualTo(1);
        assertThat(coinDeciOf(voter.id())).as("40 − 10（只有缺 key 那次真扣）").isEqualTo(30);
        assertThat(idemKeys()).as("非法 key 也不许占位").isEmpty();

        // ★ 对照：同一份载荷 + 合法 key（64 字符边界值）⇒ 正常执行（证明 400 是 key 形态引起的）
        ResponseEntity<String> valid = addCoin(voter.token(), contentId, 1, idemKey("k".repeat(64)));
        assertThat(valid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(amountOf(voter.id(), contentId)).isEqualTo(2);
        assertThat(idemKeys()).as("合法 key ⇒ 恰好一个占位").hasSize(1);
    }

    // ==================== ★ 专属 1：两个不同 key = 两次独立意图 ====================

    @Test
    @DisplayName("★专属1：两个不同 key 各投 1 枚 ⇒ 最终累计 2 枚（amount/coin_count/作者收益各两次、扣 2 枚）")
    void 两个不同key各投一枚_累计两枚() {
        TestUser voter = registerUser(VOTER_PHONE, "t67_accum");
        TestUser author = registerUser(AUTHOR_PHONE, "t67_accum_author");
        setCoinDeci(voter.id(), 30);              // 3.0 枚
        long contentId = insertContent(author.id(), VIDEO, "T67 幂等视频-累加");

        // 两台设备各点一次 ⇒ 两个 key，两次独立意图
        assertThat(addCoin(voter.token(), contentId, 1, idemKey(clientKey("device-a"))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(addCoin(voter.token(), contentId, 1, idemKey(clientKey("device-b"))).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(amountOf(voter.id(), contentId))
                .as("★两个不同 key = 两次独立意图 ⇒ amount = 2（★实现按内容去重就会得到 1）")
                .isEqualTo(2);
        assertThat(coinCountOf(contentId)).as("coin_count = 2").isEqualTo(2);
        assertThat(authorCoinDeciOf(author.id())).as("作者收益加两次：2 分位").isEqualTo(2);
        assertThat(coinDeciOf(voter.id())).as("★coin_deci 是十分位：2 枚 = 20 ⇒ 30 − 20 = 10").isEqualTo(10);
        assertThat(countCoinRows()).as("单人单内容仍只有一行（uk_user_content 是聚合行）").isEqualTo(1);
        assertThat(sumAmountOf(contentId)).as("不变量：coin_count == SUM(amount)").isEqualTo(2);

        // ★ 对照：另一用户在同一内容上再投 1 枚 ⇒ 封顶按 (user, content) 算，累计语义是真累加
        TestUser other = registerUser(OTHER_PHONE, "t67_accum_other");
        setCoinDeci(other.id(), 20);
        assertThat(addCoin(other.token(), contentId, 1, idemKey(clientKey("device-c"))).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(amountOf(other.id(), contentId)).as("另一用户自己的行 = 1").isEqualTo(1);
        assertThat(countCoinRows()).isEqualTo(2);
        assertThat(coinCountOf(contentId)).as("公共计数是三人次之和：2 + 1").isEqualTo(3);
        assertThat(coinDeciOf(voter.id())).as("★去掉 WHERE user_id 就会读到别人的行 ⇒ 本人余额断言先红").isEqualTo(10);
    }

    // ==================== ★ 专属 2：同 key 的重发不叠加 ====================

    @Test
    @DisplayName("★专属2：同 key 投 count=2 的重发 ⇒ amount 仍是 2（不是 4）；换个 key 才撞封顶")
    void 同key投两枚的重发_不叠加() {
        TestUser voter = registerUser(VOTER_PHONE, "t67_two");
        TestUser author = registerUser(AUTHOR_PHONE, "t67_two_author");
        setCoinDeci(voter.id(), 20);
        long contentId = insertContent(author.id(), VIDEO, "T67 幂等视频-两枚");
        String key = clientKey("two-coins");

        ResponseEntity<String> first = addCoin(voter.token(), contentId, 2, idemKey(key));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = addCoin(voter.token(), contentId, 2, idemKey(key));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED)).isEqualTo("true");
        assertThat(second.getBody()).isEqualTo(first.getBody());

        assertThat(amountOf(voter.id(), contentId))
                .as("★重放不是第二次累加 ⇒ 仍是 2；若实现重放后又加一遍会得到 4（封顶则会得到 409）")
                .isEqualTo(2);
        assertThat(coinCountOf(contentId)).isEqualTo(2);
        assertThat(coinDeciOf(voter.id())).as("20 − 2×10 = 0，只扣一次").isZero();
        assertThat(authorCoinDeciOf(author.id())).isEqualTo(2);

        // ★ 对照：换个 key 再投 1 枚 ⇒ 真的进业务 ⇒ 撞封顶 409。
        //   这条把"去重靠 key"与"封顶兜底"分开：同 key 是 200（重放），换 key 才是 409。
        ResponseEntity<String> capped = addCoin(voter.token(), contentId, 1, idemKey(clientKey("two-coins-extra")));
        assertThat(capped.getStatusCode()).as("不同 key ⇒ 真的执行 ⇒ 撞封顶").isEqualTo(HttpStatus.CONFLICT);
        assertThat(Envelope.code(capped)).isEqualTo("COIN_ALREADY_FULL");
        assertThat(amountOf(voter.id(), contentId)).as("封顶拒单零副作用").isEqualTo(2);
        assertThat(coinDeciOf(voter.id())).as("封顶写在扣款之前 ⇒ 一分没扣").isZero();
    }

    // ==================== 夹具 / 工具 ====================

    /**
     * 投币请求体。
     *
     * <p>用 {@link LinkedHashMap} 而不是 {@code Map.of}：并发用例要求两次请求的 body **逐字节相同**，
     * 不能依赖 Map 的迭代顺序（本端点只有一个字段，但把这条约定写在夹具上比靠"记得只有一个字段"可靠）。
     */
    private Map<String, Object> coinBody(int count) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", count);
        return body;
    }

    /** 投币端点（{@code contentId} 在路径上、{@code count} 在 JSON body；{@code T58}/{@code T59} 契约）。 */
    private ResponseEntity<String> addCoin(String token, long contentId, int count, HttpHeaders headers) {
        return post("/api/v1/contents/" + contentId + "/coins", coinBody(count), token, headers);
    }

    /** 不带 {@code Idempotency-Key} 的投币（用例 6 的"缺 key 放行"）。 */
    private ResponseEntity<String> addCoin(String token, long contentId, int count) {
        return post("/api/v1/contents/" + contentId + "/coins", coinBody(count), token);
    }

    /** clientKey：{@code [A-Za-z0-9._-]{1,64}}，用 UUID 后缀避开"纯数字被当手机号"的脱敏误判。 */
    private static String clientKey(String tag) {
        return "t67." + tag + "." + UUID.randomUUID();
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
     * <p>不用 {@code parallelStream}：它用的是公共池、并发度不可控；这里要的是"恰好两条、同刻起跑"。
     */
    private List<ResponseEntity<String>> concurrentlyPost(Supplier<ResponseEntity<String>> call)
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
}
