package io.github.yjhhhaaa06.videoweb.upload;

import io.github.yjhhhaaa06.videoweb.common.config.UploadProperties;
import io.github.yjhhhaaa06.videoweb.common.idempotency.IdempotencyFilter;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上传两条端点（{@code POST /api/v1/contents/videos} / {@code .../posts}）的**幂等矩阵骨架**
 * （写接口幂等专项 `T68`；用例矩阵的唯一事实源 =
 * {@code architecture/tech/idempotency/写接口幂等-测试与契约.md} §二）。
 *
 * <p>6 条通用场景写在这里，两条端点各继承一遍（{@link VideoUploadIdempotencyTests} /
 * {@link PostUploadIdempotencyTests}）⇒ 12 条。差异只有四件：路径、注册表 slug、表单形状、
 * 一次成功上传落几条媒体行。
 *
 * <h2>★ 本任务独有的验收：第二次同 key 请求**一个文件都不落**</h2>
 * 观察口径是**上传根下相对路径集合的差集为空**，不是"文件总数 = N"，也不是"没调用
 * {@code saveFile}（那是断言内部调用，违反测试纪律①）：
 * <ul>
 *   <li>不能用总数：定向跑不 {@code clean}，历史用例的落盘文件会留下，总数本来就不是 0 或 N；</li>
 *   <li>差集同时钉住"过滤器早于 multipart 解析"这条**设计前提**——幂等器若挪到 Controller / AOP
 *       之后，第二次请求会先把文件写进磁盘再重放，差集立刻非空。</li>
 * </ul>
 * 首次上传**必须**先断言差集非空（{@code == mediaRowsPerUpload}），否则"第二次不落文件"可能是真空转。
 *
 * <h2>★ 用例 4 在 multipart 上的形态：换文件**不是 422**（已知代价，不是 bug）</h2>
 * `T69` 起指纹 = {@code SHA-256(method + "\n" + 实际请求路径 [+ "\n" + 请求体])}，但
 * <b>multipart / 非 JSON 只算到路径级</b>（不包装 request，避免把整个文件读进内存）。
 * 于是"同 path + 同 key + 换文件"仍是**重放**，而不是 {@code 422 IDEMPOTENCY_KEY_CONFLICT}
 * ——"换 body ⇒ 422"这条在 multipart 上**根本不存在**，`T68` 明确不硬造。
 * 见 {@link #同key不同载荷_仍是重放_已知代价()}。
 *
 * <h2>★ 用例 5 的形态：`FAILED` 是墓碑不是锁</h2>
 * 第一次必须因**可恢复条件**失败（必填 part 缺失 / 分区非法 ⇒ 400），占位随之写成 {@code FAILED}；
 * 同 key 立刻重发必须能重新占位执行。若实现从不写墓碑，占位会停在 {@code PENDING} 直到注册表里的
 * 120 s TTL 到期 ⇒ 重发必然 409 —— 本条因此同时守住两个方向。
 * <p>⚠️ 本仓**不许**在用例里等 TTL（红线）：所以"能成功"就是判据本身，不做 sleep。
 *
 * <h2>三条断言纪律（取值方式同 {@code CoinIdempotencyTests}）</h2>
 * <ol>
 *   <li><b>独立 oracle</b>：{@code content} / {@code content_media} 行数与磁盘文件一律直查，**不信接口回显**
 *       ——本类的响应可能整段来自 Redis 快照。</li>
 *   <li><b>每条用例带对照</b>：另一用户持同一 {@code clientKey}（证明 key 含 uid），或**换 key** / 换载荷
 *       （证明期望值不是写死的）。</li>
 *   <li><b>不断言谁先谁后</b>：并发用例只断言"合法结果集合"与行数，见 {@link #并发同key_业务只执行一次()}。</li>
 * </ol>
 */
abstract class AbstractUploadIdempotencyTests extends AbstractHttpIntegrationTest {

    protected static final String PASSWORD = "abc123456";
    protected static final String AUTHOR_PHONE = "13800009301";
    protected static final String OTHER_PHONE = "13800009302";

    /** 素材文件名（外部目录 {@code TV_TEST_RESOURCE_DIR}；缺失即失败不降级）。 */
    protected static final String FIXTURE_VIDEO = "test_video.mp4";
    protected static final String FIXTURE_COVER = "test_cover.png";
    protected static final String FIXTURE_IMAGE = "test_image.jpg";

    @Autowired
    protected UploadProperties uploadProperties;

    @Autowired
    private StringRedisTemplate redis;

    /** 本用例开始前上传根里的相对路径集合（{@code @AfterEach} 据此只清**本次新建**的文件）。 */
    private Set<String> mediaFilesAtStart = Set.of();

    @BeforeEach
    void resetUploadState() throws IOException {
        // 基类已 DELETE FROM users（JUnit 5 父类 @BeforeEach 在前）；这里清本域两张表
        jdbcTemplate.update("DELETE FROM content_media");
        jdbcTemplate.update("DELETE FROM content");
        // key 形态 idem:{slug}:{uid}:{clientKey}，DELETE FROM users 清不掉它，且自增 id 不重置
        // ⇒ 上一条用例的 DONE 会替本用例答题，必须显式清。
        Set<String> keys = idemKeys();
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
        assertThat(idemKeys()).as("进门必须没有任何 idem:* 残留（否则上一条用例的 DONE 会替本用例答题）")
                .isEmpty();
        mediaFilesAtStart = mediaFiles();
    }

    /**
     * 只清**本次用例新建**的文件（不能用 {@code target/test-media} 全清：同批其他用例可能正在用，
     * 也避免把"定向跑不 clean"留下的历史文件当成本次产物）。
     */
    @AfterEach
    void deleteFilesCreatedByThisTest() throws IOException {
        Path root = mediaRoot();
        for (String relative : newFiles(mediaFilesAtStart, mediaFiles())) {
            Files.deleteIfExists(root.resolve(relative));
        }
    }

    // ==================== 用例 1：同 key 重发 ⇒ 同一份响应 + ★第二次不落文件 ====================

    @Test
    @DisplayName("用例1：同 key 重发 ⇒ 同一个 contentId、第二次带 Idempotent-Replayed；★第二次一个文件都不落")
    void 同key重发_逐字节重放首次响应_且第二次不落任何文件() throws IOException {
        String token = registerAndGetToken(AUTHOR_PHONE, "t68_replay", PASSWORD);
        String otherToken = registerAndGetToken(OTHER_PHONE, "t68_replay_other", PASSWORD);
        String key = clientKey("replay");

        Set<String> beforeFirst = mediaFiles();
        ResponseEntity<String> first = postMultipart(path(), validForm(), token, idemKey(key));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("首次响应不带重放标记（契约：仅重放命中时出现）").isNull();
        long contentId = Envelope.num(first, "contentId");
        assertThat(contentId).isPositive();

        // ★ 前提：首次必须真的落盘（否则下面"第二次不落文件"是真空转）
        assertThat(newFiles(beforeFirst, mediaFiles()))
                .as("首次上传必须落 %s 个文件（每条媒体行一个文件）", mediaRowsPerUpload())
                .hasSize(mediaRowsPerUpload());
        assertThat(countContent()).as("首次上传落一条 content").isEqualTo(1);

        Set<String> beforeSecond = mediaFiles();
        ResponseEntity<String> second = postMultipart(path(), validForm(), token, idemKey(key));
        Set<String> afterSecond = mediaFiles();

        // ★★ 本任务独有的验收：第二次请求不落任何文件（相对路径集合的差集为空）。
        //    ★ 刻意先断它：这是"过滤器早于 multipart 解析"唯一可观察的证据，也是本任务存在的理由。
        //    口径解释：不用"文件总数 = N"（定向跑不 clean，历史文件会留下），
        //    也不断言"没调用 saveFile"（那是断言内部调用，违反测试纪律①）。
        assertThat(newFiles(beforeSecond, afterSecond))
                .as("★第二次同 key 请求必须一个文件都不落 —— 命中 DONE 时过滤器早于 multipart 解析；"
                        + "机制若挪到 Controller / AOP 之后，这里会多出 %s 个文件", mediaRowsPerUpload())
                .isEmpty();

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED)).isEqualTo("true");
        assertThat(second.getBody()).as("重放逐字节相同（含同一个 contentId）").isEqualTo(first.getBody());
        assertThat(Envelope.num(second, "contentId")).isEqualTo(contentId);

        // 独立 oracle：行数一个都没变
        assertThat(countContent()).as("同 key 两次只落一条 content").isEqualTo(1);
        assertThat(countMedia(contentId)).as("媒体行只落一次").isEqualTo(mediaRowsPerUpload());

        // ★ 对照：另一个用户持同一个 clientKey ⇒ 一条独立内容（Redis key 里含 uid）
        ResponseEntity<String> other = postMultipart(path(), validForm(), otherToken, idemKey(key));

        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(other.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("另一个用户命中别人的快照 = key 里漏了 uid（跨用户串味）").isNull();
        assertThat(Envelope.num(other, "contentId")).isNotEqualTo(contentId);
        assertThat(countContent()).isEqualTo(2);
    }

    // ==================== 用例 2：独立 oracle 复算副作用 ====================

    @Test
    @DisplayName("用例2：同 key 重发 ⇒ content / content_media 只 +一次、媒体 url 与磁盘文件都不变（直查 DB + 磁盘）")
    void 同key重发_副作用只发生一次_独立oracle() throws IOException {
        String token = registerAndGetToken(AUTHOR_PHONE, "t68_oracle", PASSWORD);
        String key = clientKey("oracle");

        long contentId = Envelope.num(postMultipart(path(), validForm(), token, idemKey(key)), "contentId");
        List<String> urlsAfterFirst = mediaUrlsOf(contentId);
        assertThat(urlsAfterFirst).as("首次必须落 %s 条媒体行", mediaRowsPerUpload()).hasSize(mediaRowsPerUpload());

        ResponseEntity<String> second = postMultipart(path(), validForm(), token, idemKey(key));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(countContent()).as("同 key 两次只落一条 content").isEqualTo(1);
        assertThat(countMedia()).as("content_media 只 +一次").isEqualTo(mediaRowsPerUpload());
        assertThat(mediaUrlsOf(contentId)).as("重放不重写媒体行（url 一个都没变）").isEqualTo(urlsAfterFirst);
        // 第二 oracle：DB 里的每条 url 都必须对应一个真实存在的磁盘文件
        for (String url : mediaUrlsOf(contentId)) {
            assertThat(Files.isRegularFile(diskPathOf(url)))
                    .as("媒体 url 必须有对应文件：%s", url).isTrue();
        }
        assertThat(newFiles(mediaFilesAtStart, mediaFiles()))
                .as("两次请求只落一份文件").hasSize(mediaRowsPerUpload());

        // ★ 对照：换个 key（= 另一次独立意图）⇒ 真的再落一条
        //   没有这个对照，"期望值 1 / N"是写死的还是算出来的就分不清
        long otherContentId = Envelope.num(
                postMultipart(path(), validForm(), token, idemKey(clientKey("oracle-other"))), "contentId");

        assertThat(otherContentId).isNotEqualTo(contentId);
        assertThat(countContent()).isEqualTo(2);
        assertThat(countMedia()).as("两条内容各 %s 条媒体行", mediaRowsPerUpload())
                .isEqualTo(2 * mediaRowsPerUpload());
        assertThat(countMedia(contentId)).as("另一条内容的媒体行不得串到本条").isEqualTo(mediaRowsPerUpload());
        assertThat(newFiles(mediaFilesAtStart, mediaFiles())).as("两次独立意图各落一份文件")
                .hasSize(2 * mediaRowsPerUpload());
    }

    // ==================== 用例 3：并发同 key ====================

    @Test
    @DisplayName("用例3：并发两请求同 key —— 恰好一个真正执行，另一个重放或 409；content 行数 = 1")
    void 并发同key_业务只执行一次() throws Exception {
        String token = registerAndGetToken(AUTHOR_PHONE, "t68_concurrent", PASSWORD);
        HttpHeaders headers = idemKey(clientKey("concurrent"));

        // 每条线程各建一份表单：multipart 的 body 每次一条请求，不复用同一个 MultiValueMap
        List<ResponseEntity<String>> responses = concurrentlyPost(
                () -> postMultipart(path(), validForm(), token, headers));

        assertThat(responses).hasSize(2);
        assertThat(responses).allSatisfy(resp ->
                assertThat(resp.getStatusCode().value()).as("合法结果只有 2xx 与 409").isIn(200, 409));

        long executed = responses.stream().filter(AbstractUploadIdempotencyTests::executed).count();
        assertThat(executed)
                .as("恰好一个请求真正执行（2xx 且不带重放标记）——★ 不断言谁先谁后")
                .isEqualTo(1);
        assertThat(responses.stream().filter(resp -> !executed(resp))).hasSize(1);
        assertThat(responses.stream().filter(resp -> resp.getStatusCode().value() == 409))
                .allSatisfy(resp -> assertThat(Envelope.code(resp)).isEqualTo("IDEMPOTENCY_IN_PROGRESS"));

        // 独立 oracle：终态只反映一次上传
        assertThat(countContent()).as("两个请求只允许落一条 content").isEqualTo(1);
        assertThat(countMedia()).isEqualTo(mediaRowsPerUpload());
        assertThat(newFiles(mediaFilesAtStart, mediaFiles())).as("并发下也只允许落一份文件")
                .hasSize(mediaRowsPerUpload());

        // 占位键只有一个，且落在本端点自己的 slug 命名空间里
        assertThat(idemKeys()).as("一个 key 只占一个位").hasSize(1);
        assertThat(idemKeys().iterator().next()).startsWith("idem:" + slug() + ":");
    }

    // ==================== 用例 4：同 key 不同载荷（multipart 只有路径级指纹） ====================

    @Test
    @DisplayName("用例4：同 key 换载荷（标题 + 文件全换）⇒ 仍是重放（已知代价，不是 422）；第二份载荷从未生效")
    void 同key不同载荷_仍是重放_已知代价() throws IOException {
        String token = registerAndGetToken(AUTHOR_PHONE, "t68_payload", PASSWORD);
        String key = clientKey("payload");

        ResponseEntity<String> first = postMultipart(path(), validForm(), token, idemKey(key));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        long contentId = Envelope.num(first, "contentId");
        String firstTitle = titleOf(contentId);

        Set<String> beforeSecond = mediaFiles();
        // ★ 第二份载荷：标题、简介、文件全换 ⇒ multipart body 确实不同。
        //   但指纹是**仅路径级**的（multipart 不包装 request ⇒ 不读 body）
        //   ⇒ 同 path + 同 key 仍被判成"同一次请求" ⇒ 重放，**不是 422**。
        //   这是有意保留的已知代价：要识别它必须把上传通路改成流式摘要（读文件进内存更贵），
        //   见 {@code architecture/tech/idempotency/写接口幂等.md} §十三。
        ResponseEntity<String> second = postMultipart(path(), alternativeForm(), token, idemKey(key));

        assertThat(second.getStatusCode()).as("重放仍是 200（不是 422 —— multipart 上不存在 body 级冲突）")
                .isEqualTo(HttpStatus.OK);
        assertThat(Envelope.code(second)).as("成功信封，不是 IDEMPOTENCY_KEY_CONFLICT").isEqualTo("OK");
        assertThat(second.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("★同 path 换载荷 = 仍是重放：multipart 指纹只含 method + 路径，"
                        + "这是有意保留的已知代价（不是 bug、也不是漏接线）")
                .isEqualTo("true");
        assertThat(Envelope.num(second, "contentId")).isEqualTo(contentId);
        assertThat(second.getBody()).isEqualTo(first.getBody());

        // ★ 第二份载荷一次都没生效：标题仍是第一份、行数没涨、没有新文件
        assertThat(titleOf(contentId)).as("第二份的标题从未落库").isEqualTo(firstTitle);
        assertThat(countContent()).as("换载荷没有多建一条").isEqualTo(1);
        assertThat(countMedia()).isEqualTo(mediaRowsPerUpload());
        assertThat(newFiles(beforeSecond, mediaFiles())).as("第二份载荷的文件一个都没落盘").isEmpty();

        // ★ 对照：同一份"第二载荷"换一个新 key ⇒ 真的执行
        //   （证明上面是"key 复用"引起的重放，不是第二份载荷本身就发不出去）
        long freshContentId = Envelope.num(
                postMultipart(path(), alternativeForm(), token, idemKey(clientKey("payload-new-key"))), "contentId");

        assertThat(freshContentId).isNotEqualTo(contentId);
        assertThat(countContent()).isEqualTo(2);
        assertThat(titleOf(freshContentId)).as("换 key 后第二份载荷才真的生效").isNotEqualTo(firstTitle);
    }

    // ==================== 用例 5：首次失败（墓碑不是锁） ====================

    @Test
    @DisplayName("用例5：首次 400（可恢复失败）后同 key 重发 ⇒ 200 真执行（FAILED 是墓碑不是锁）")
    void 首次失败后同key重发_能成功() throws IOException {
        String token = registerAndGetToken(AUTHOR_PHONE, "t68_retry", PASSWORD);
        String otherToken = registerAndGetToken(OTHER_PHONE, "t68_retry_other", PASSWORD);
        String key = clientKey("failed-tombstone");

        // ★ 失败必须来自"可恢复条件"（必填 part 缺失 / 分区非法），不是 key 用错：
        //   前者才会让占位从 PENDING 写成 FAILED；后者被过滤器在占位之前就 400 掉了。
        Set<String> beforeFailure = mediaFiles();
        ResponseEntity<String> failed = postMultipart(path(), failingForm(), token, idemKey(key));

        assertThat(failed.getStatusCode()).as("首次必须真的失败（否则本用例测不到墓碑）")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countContent()).as("失败的第一次零副作用").isZero();
        assertThat(newFiles(beforeFailure, mediaFiles()))
                .as("校验在落盘之前 ⇒ 失败不留任何文件").isEmpty();

        ResponseEntity<String> retried = postMultipart(path(), validForm(), token, idemKey(key));

        assertThat(retried.getStatusCode())
                .as("FAILED 是墓碑不是锁：若停在 PENDING，这里会 409（注册表 120 s TTL 内不会自愈）")
                .isEqualTo(HttpStatus.OK);
        assertThat(retried.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("这次是真的执行，不是重放").isNull();
        assertThat(countContent()).isEqualTo(1);
        assertThat(countMedia()).isEqualTo(mediaRowsPerUpload());

        // ★ 对照：另一个用户持同一个 clientKey ⇒ 自己的一次独立执行（墓碑同样 per-user）
        ResponseEntity<String> other = postMultipart(path(), validForm(), otherToken, idemKey(key));

        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(other.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED)).isNull();
        assertThat(countContent()).isEqualTo(2);
    }

    // ==================== 用例 6：缺 key / 非法 key ====================

    @Test
    @DisplayName("用例6：缺 key ⇒ 放行且无 idem:* 键；非法 key ⇒ 400 零副作用（行数与上传目录集合都不变）")
    void 缺key放行_非法key400零副作用() throws IOException {
        String token = registerAndGetToken(AUTHOR_PHONE, "t68_key_shape", PASSWORD);

        // 缺 key：放行、不占位（退化为普通请求）
        ResponseEntity<String> noKey = postMultipart(path(), validForm(), token, null);

        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(noKey.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED))
                .as("没有幂等器参与 ⇒ 不可能有重放标记").isNull();
        assertThat(countContent()).as("放行 ⇒ 业务真的执行了一次").isEqualTo(1);
        assertThat(idemKeys()).as("缺 key 必须不占位（一个字面都不许写）").isEmpty();

        long baselineContent = countContent();
        Set<String> baselineFiles = mediaFiles();

        // 非法：超长（65 字符）—— 过滤器在解析 multipart 之前就短路（不读文件、不落盘）
        ResponseEntity<String> tooLong = postMultipart(path(), validForm(), token, idemKey("k".repeat(65)));
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(Envelope.code(tooLong)).isEqualTo("IDEMPOTENCY_KEY_INVALID");

        // 非法：含 ':'（会破坏 idem:{slug}:{uid}:{key} 的边界解析）
        ResponseEntity<String> withColon = postMultipart(path(), validForm(), token, idemKey("abc:def"));
        assertThat(withColon.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(Envelope.code(withColon)).isEqualTo("IDEMPOTENCY_KEY_INVALID");

        assertThat(countContent()).as("两个 400 都必须零副作用").isEqualTo(baselineContent);
        assertThat(countMedia()).isEqualTo(mediaRowsPerUpload());
        assertThat(mediaFiles()).as("非法 key 在过滤器就短路 ⇒ 上传目录集合一个文件都不许变")
                .isEqualTo(baselineFiles);
        assertThat(idemKeys()).as("非法 key 也不许占位").isEmpty();

        // ★ 对照：合法 key（64 字符边界值）⇒ 正常执行（证明 400 是 key 形态引起的）
        ResponseEntity<String> valid = postMultipart(path(), validForm(), token, idemKey("k".repeat(64)));

        assertThat(valid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countContent()).isEqualTo(2);
        assertThat(idemKeys()).as("合法 key ⇒ 恰好一个占位").hasSize(1);
    }

    // ========================================================================
    // 端点差异（两条端点的全部差异都收在这里）
    // ========================================================================

    /** 端点路径（{@code /api/v1/contents/videos} / {@code .../posts}）。 */
    protected abstract String path();

    /** 注册表里的 slug（{@code idem:{slug}:{uid}:{key}}），用于钉住占位键的命名空间。 */
    protected abstract String slug();

    /** 一份合法的上传表单；<b>每次调用都必须新建</b>（并发用例两条请求各持一份）。 */
    protected abstract MultiValueMap<String, Object> validForm();

    /** "另一份载荷"：标题、简介、文件全换（用例 4 要造一个**逐字节不同**的 multipart body）。 */
    protected abstract MultiValueMap<String, Object> alternativeForm();

    /** 首次必失败（400）的表单：失败原因必须是**可恢复条件**（用例 5）。 */
    protected abstract MultiValueMap<String, Object> failingForm();

    /** 一次成功上传应落的 {@code content_media} 行数（= 落盘文件数）。 */
    protected abstract int mediaRowsPerUpload();

    // ========================================================================
    // 夹具 / oracle / 工具
    // ========================================================================

    /** 文本 part（multipart 的普通表单字段）。 */
    protected static void addText(MultiValueMap<String, Object> form, String name, String value) {
        form.add(name, value);
    }

    /**
     * 文件 part：文件名取素材名，contentType 显式指定（对齐旧 pytest 的 files 元组）。
     *
     * <p>不用 {@code MultipartBodyBuilder}：它引用 Reactive 的 {@code Publisher}，本仓没有 WebFlux 依赖。
     */
    protected static void addFilePart(MultiValueMap<String, Object> form, String name,
                                      String fileName, String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(contentType));
        form.add(name, new HttpEntity<>(new FileSystemResource(testResource(fileName)), headers));
    }

    protected static MultiValueMap<String, Object> newForm() {
        return new LinkedMultiValueMap<>();
    }

    /** clientKey：{@code [A-Za-z0-9._-]{1,64}}，用 UUID 后缀避开"纯数字被当手机号"的脱敏误判。 */
    protected static String clientKey(String tag) {
        return "t68." + tag + "." + UUID.randomUUID();
    }

    protected static HttpHeaders idemKey(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(IdempotencyFilter.HEADER_KEY, key);
        return headers;
    }

    /** 当前 {@code idem:*} 键（缺 key / 非法 key 用例要证明"一个字面都不许写"）。 */
    protected Set<String> idemKeys() {
        Set<String> keys = redis.keys("idem:*");
        return keys == null ? Set.of() : keys;
    }

    /** 该响应是否"真的执行了业务"（2xx 且不是重放）。 */
    protected static boolean executed(ResponseEntity<String> response) {
        return response.getStatusCode().value() == 200
                && response.getHeaders().getFirst(IdempotencyFilter.HEADER_REPLAYED) == null;
    }

    /** 上传根（基类无条件覆盖到 {@code target/test-media}；这里走生产代码用的同一个配置项）。 */
    protected Path mediaRoot() {
        return Path.of(uploadProperties.root());
    }

    /**
     * 上传根下的**全部普通文件**相对路径集合（{@code /} 分隔）。
     *
     * <p>差集 = "这次请求新落了哪些文件"。为什么是相对路径集合而不是"文件总数"：定向跑不 {@code clean}，
     * 历史文件会留下 ⇒ 总数只是噪声，差集才是信号。
     */
    protected Set<String> mediaFiles() throws IOException {
        Path root = mediaRoot();
        if (!Files.exists(root)) {
            return Set.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    /** {@code after \ before}：本次新增的文件（顺序无关，只比集合）。 */
    protected static Set<String> newFiles(Set<String> before, Set<String> after) {
        Set<String> added = new LinkedHashSet<>(after);
        added.removeAll(before);
        return added;
    }

    /** {@code /upload/{dir}/{file}} → 磁盘绝对路径（与 {@code FileUploadService#resolveAbsolutePath} 同源解析）。 */
    protected Path diskPathOf(String url) {
        assertThat(url).as("媒体 url 形状必须是 /upload/{dir}/{file}").startsWith("/upload/");
        Path path = mediaRoot();
        for (String segment : url.substring("/upload/".length()).split("/")) {
            path = path.resolve(segment);
        }
        return path;
    }

    protected long countContent() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content", Long.class);
        return n == null ? 0 : n;
    }

    protected long countMedia() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content_media", Long.class);
        return n == null ? 0 : n;
    }

    protected long countMedia(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_media WHERE content_id = ?", Long.class, contentId);
        return n == null ? 0 : n;
    }

    protected List<String> mediaUrlsOf(long contentId) {
        return jdbcTemplate.queryForList(
                "SELECT url FROM content_media WHERE content_id = ? ORDER BY type, sort", String.class, contentId);
    }

    protected String titleOf(long contentId) {
        return jdbcTemplate.queryForObject("SELECT title FROM content WHERE id = ?", String.class, contentId);
    }

    /**
     * 两个线程**同时**发同一个请求（闸门让两条请求尽量同刻到达）。
     *
     * <p>不用 {@code parallelStream}：它用公共池、并发度不可控；这里要的是"恰好两条、同刻起跑"。
     */
    protected List<ResponseEntity<String>> concurrentlyPost(Supplier<ResponseEntity<String>> call)
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
                    futures.get(0).get(60, TimeUnit.SECONDS),
                    futures.get(1).get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
