package io.github.yjhhhaaa06.videoweb.common.idempotency;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.idempotency.IdempotencyMetrics.Decision;
import io.github.yjhhhaaa06.videoweb.common.security.JwtAuthFilter;
import io.github.yjhhhaaa06.videoweb.common.web.ApiErrorWriter;
import io.github.yjhhhaaa06.videoweb.common.web.RateLimitFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * 写接口幂等过滤器（写接口幂等专项 `T66`；设计与边界的唯一事实源 =
 * {@code architecture/tech/idempotency/写接口幂等.md}）。
 *
 * <p>定位：<b>"响应快照 + 占位状态机"</b>，不是锁、也不是分布式事务。同一个
 * {@code Idempotency-Key} 重发 N 次，副作用只发生一次，且每次都拿回第一次的 2xx 响应。
 *
 * <h2>★ 链序：{@code RATE_LIMIT_ORDER + 10}（鉴权与限流<b>之内侧</b>）</h2>
 * <pre>{@code
 * JwtAuthFilter(0) → RateLimitFilter(10) → ★本类(20) → DispatcherServlet → Controller
 * }</pre>
 * 三个收益都来自位置本身，不需要额外代码：
 * <ol>
 *   <li><b>429 的请求到不了这里</b> ⇒ 不会留下 {@code PENDING} 残留。否则客户端按
 *       {@code Retry-After} 重试时，命中的正是自己上次留下的占位，越限流越拿不到结果；</li>
 *   <li>{@code ATTR_USER_ID} 已由 {@link JwtAuthFilter} 放好 ⇒ 不需要二次解析 token
 *       （401 / 403 同样在外侧短路，本类<b>不做任何鉴权判断</b>）；</li>
 *   <li>天然早于 {@code DispatcherServlet} ⇒ 占位早于 multipart 解析，
 *       <b>第二个请求的文件根本不会落盘</b>——这一条决定了它不能放 Controller / AOP 附近。</li>
 * </ol>
 * 用 "…+ 10" 而不是裸数字，把"必须在鉴权与限流之后"这条结构约束钉进常量本身。
 * ⚠️ <b>不要</b>并进 {@link RateLimitFilter}：两种 fail-open 的风险等级不同
 * （限流不可用 = 放行；幂等不可用 = 重试会重复写），混进一个 {@code catch} 就再也分不清档位。
 *
 * <h2>判定流程（写接口幂等.md §五 / §七 / §九）</h2>
 * <pre>{@code
 * 查表（非 KEYED ⇒ 直接放行）→ 缺 key ⇒ 放行且不占位 → 非法 key ⇒ 400
 *   → ACQUIRE（命中 DONE ⇒ 校验指纹后重放；命中 PENDING ⇒ 轮询 2×100 ms 后 409）
 *   → 进 chain（JSON 才包装 request 以便算指纹）
 *   → 2xx ⇒ COMPLETE（先写快照）→ finally：未收尾则 RELEASE（CAS）→ copyBodyToResponse
 * }</pre>
 *
 * <h2>★ 顺序约束：快照必须落在响应体 flush 之前</h2>
 * 由此得到一条可依赖的性质：<b>客户端收到 2xx ⇒ 快照已写</b>。它同时覆盖
 * "服务端成功、响应在网络上丢了"（最常见）与"flush 时客户端已断开"
 * （那时快照已在盘上，重连重发命中 {@code DONE}）。
 *
 * <h2>★ 缺 key / 非法 key 是两件事</h2>
 * 没传 = <b>放行且不占位</b>（{@code Idempotency-Key} 是可选增强，退化为普通请求 ⇒
 * 既有全部用例、临时验证一行都不用改）；传了但不合法 = <b>400</b>（客户端 bug，
 * 假装成功等于骗它"你有幂等保护"）。<b>非 KEYED 端点传了 key</b> ⇒ 忽略、不报错。
 *
 * <h2>★ 指纹的三个阶段（不要合成一条）</h2>
 * <table>
 *   <caption>指纹口径</caption>
 *   <tr><th>阶段</th><th>比对？</th><th>原因</th></tr>
 *   <tr><td>首次占位</td><td>不比对</td><td>没有可比对象 ⇒ {@code PENDING} 不带 fp，占位可早于 multipart 解析</td></tr>
 *   <tr><td>写 {@code DONE}</td><td>写 fp</td><td>handler 已读完 body，从请求缓存算</td></tr>
 *   <tr><td>命中 {@code DONE}</td><td>算本次并比对</td><td>不同 ⇒ 422；这条路径<b>不进 chain</b>，读坏请求流无副作用</td></tr>
 * </table>
 * <p>★ <b>指纹 = {@code SHA-256(method + "\n" + 实际请求路径 + "\n" + 请求体)}</b>（JSON）；
 * multipart / 非 JSON ⇒ {@code SHA-256(method + "\n" + 实际请求路径)}（<b>仅路径级</b>，不含 body）。
 * 路径取 {@code getRequestURI()} 而<b>不是</b>路由模式——两者对
 * {@code /contents/1/coins} 与 {@code /contents/2/coins} 分别相同与不同，用模式等于没算路径。
 * <p>只对 {@code application/json} 包装 request（multipart 不包装 ⇒ 不把文件读进内存）。
 * 请求缓存达到 {@code limits.fingerprint-bytes} 即视为<b>无指纹</b>
 * ——★ 因为 {@code ContentCachingRequestWrapper} 超限是<b>静默截断</b>（不抛异常、只是不再缓存），
 * 只能靠 {@code cached.length >= limit} 判定；<b>不可用截断后的 body 算指纹</b>。
 * ⚠️ JSON 且算不出 body（未读 / 为空 / 超限）时 fp <b>仍为 {@code null}</b>，
 * <b>不得</b>退化成"仅路径级"——那会把"不同 body"判成同一份载荷。
 *
 * <h2>★ 失败分档（两档不能合并）</h2>
 * <table>
 *   <caption>失败与日志</caption>
 *   <tr><th>失败</th><th>处置</th><th>日志</th></tr>
 *   <tr><td>Redis 不可达 / 熔断打开</td><td>放行（不幂等），<b>不抛 500</b></td><td>WARN</td></tr>
 *   <tr><td>脚本 / 返回值解析异常（{@link IdempotencyScriptException}）</td><td>放行</td><td><b>ERROR 持栈</b>（这是缺陷）</td></tr>
 *   <tr><td>写 {@code DONE} 失败</td><td>响应仍 2xx（业务已提交，不能骗客户端失败）</td><td>ERROR</td></tr>
 *   <tr><td>快照超 {@code limits.snapshot-bytes}</td><td>不写 {@code DONE}（降级为不幂等）</td><td>ERROR</td></tr>
 *   <tr><td>{@code copyBodyToResponse} 抛 {@code IOException}</td><td>不处理（快照已在盘上）</td><td>DEBUG（断连是常态）</td></tr>
 * </table>
 *
 * <h2>不记什么</h2>
 * 日志<b>不记</b> {@code clientKey} 原文（客户端可控串，与 401/429 不落 IP 同一取向）与快照 body。
 */
@Slf4j
@Component
@Order(IdempotencyFilter.IDEMPOTENCY_ORDER)
public class IdempotencyFilter extends OncePerRequestFilter {

    /**
     * 链序：必须落在限流器（{@link RateLimitFilter#RATE_LIMIT_ORDER}）<b>之内侧</b>。
     * 见类注释「链序」的三条理由。
     */
    public static final int IDEMPOTENCY_ORDER = RateLimitFilter.RATE_LIMIT_ORDER + 10;

    /** 客户端幂等键请求头（★ 不能复用 {@code X-Request-Id}：那是服务端生成的 trace id）。 */
    public static final String HEADER_KEY = "Idempotency-Key";

    /** 重放标记响应头：<b>仅重放命中时出现</b>，首次响应不带。 */
    public static final String HEADER_REPLAYED = "Idempotent-Replayed";

    private static final String REPLAYED_TRUE = "true";

    /** key 合法形态：字符集排除 {@code :} 与 {@code /}，故 {@code idem:{slug}:{uid}:{key}} 的边界唯一可解析。 */
    private static final Pattern CLIENT_KEY = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** {@code missing_key} / 回退查表 的按端点日志节流窗口（写接口幂等.md §七：按端点 5 min）。 */
    private static final long LOG_THROTTLE_INTERVAL_MILLIS = 5 * 60 * 1000L;

    /**
     * 安全方法（RFC 9110 §9.2.1）—— 「不改变服务端状态」的方法，{@code Idempotency-Key} 对它们无意义，
     * 注册表（{@link IdempotencyScope}）里也没有安全方法条目 ⇒ 直接直通，不查表、不算指纹。
     *
     * <p>★ 这条早退同时修掉一个**观测污染**（2026-10-26 随前端静态转发暴露）：页面导航
     * （{@code /video/92}、{@code /search}）、静态资源（{@code /assets/**}）与未映射路径都没有
     * {@code RequestMapping} 处理器 ⇒ 取不到 {@code BEST_MATCHING_PATTERN_ATTRIBUTE} ⇒ 以前
     * **每个这样的 GET 都会记一次 {@code degraded} 并打一条"链序可能被改坏"的 WARN**。
     * 那个信号本来是给"真写请求取不到路由"用的，被静态流量淹没后就不再指向缺陷。
     */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /** 409 的 {@code Retry-After}：短轮询已等过 {@code poll.attempts × interval-millis}，再给 1 s。 */
    private static final long IN_PROGRESS_RETRY_AFTER_SECONDS = 1;

    /** 回退节流表的容量上限：只在"链序已坏"的世界里生效，超出即整体清空（不做 LRU，太重）。 */
    private static final int FALLBACK_LOG_MAX_ENTRIES = 64;

    /**
     * {@code missing_key} 的日志节流表（key = 路由，只有 4 条 KEYED ⇒ 天然有界）。
     * 不加锁：并发下最多多打一行，无正确性影响。
     */
    private final ConcurrentMap<String, Long> missingKeyLoggedAt = new ConcurrentHashMap<>();

    /**
     * 查表回退的日志节流表（key = 「方法 空格 原始路径」）。
     *
     * <p>★ 回退本身是<b>结构性异常</b>（见 {@link #patternOf(HttpServletRequest)}），必须留痕；
     * 但链序真的坏掉时它会按请求命中 ⇒ 必须节流，否则会把 {@code system.log} 刷满。
     * 与 {@link #missingKeyLoggedAt} 分开两张表：两类事件的频次不同源，合并会让"回退 5 min 内
     * 只打一次"被缺 key 的正常流量吃掉。
     *
     * <p>⚠️ 这张表的 key 含<b>原始路径</b>（路由模式正是拿不到的那个东西），故它**不是天然有界**的
     * ——用 {@link #FALLBACK_LOG_MAX_ENTRIES} 兜住上限（清空重建的代价只是多打几行）。
     */
    private final ConcurrentMap<String, Long> patternFallbackLoggedAt = new ConcurrentHashMap<>();

    private final IdempotencyProperties properties;
    private final IdempotencyStore store;
    private final IdempotencyMetrics metrics;
    private final ApiErrorWriter errorWriter;

    public IdempotencyFilter(IdempotencyProperties properties,
                             IdempotencyStore store,
                             IdempotencyMetrics metrics,
                             ApiErrorWriter errorWriter) {
        this.properties = properties;
        this.store = store;
        this.metrics = metrics;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // ★ 安全方法直通：见 {@link #SAFE_METHODS}（页面导航 / 静态资源 / 探活都不该进幂等判定）。
        if (SAFE_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String pattern = patternOf(request);
        String routeKey = request.getMethod() + " " + pattern;
        IdempotencyScope.Entry entry = IdempotencyScope.find(request.getMethod(), pattern);
        if (entry == null || entry.scope() != IdempotencyScope.Scope.KEYED) {
            // 未登记 / NATURAL / 非 GET：一律放行。NATURAL 条目只服务完整性测试，运行期不查第二次。
            chain.doFilter(request, response);
            return;
        }

        Long userId = userIdOf(request);
        if (userId == null) {
            // 结构上不可达：4 条 KEYED 全是登录端点，401 由 JwtAuthFilter 在外侧短路。
            // 真到了这里说明链序被改坏了 ⇒ 放行（不占位）+ WARN，绝不在这里自己判鉴权。
            log.warn("幂等过滤器拿不到 userId（鉴权应在外侧短路），放行且不占位: route={}", routeKey);
            metrics.recordDecision(Decision.DEGRADED);
            chain.doFilter(request, response);
            return;
        }

        String clientKey = request.getHeader(HEADER_KEY);
        if (clientKey == null) {
            metrics.recordDecision(Decision.MISSING_KEY);
            logMissingKey(routeKey, userId);
            chain.doFilter(request, response);
            return;
        }
        if (!CLIENT_KEY.matcher(clientKey).matches()) {
            // ★ 只记长度，不回显入参（客户端可控串）。
            metrics.recordDecision(Decision.INVALID_KEY);
            log.warn("幂等键不合法（400，零副作用）: route={} userId={} length={}",
                    routeKey, userId, clientKey.length());
            errorWriter.write(request, response, ErrorCode.IDEMPOTENCY_KEY_INVALID);
            return;
        }

        String key = CacheKeys.idempotency(entry.slug(), userId, clientKey);
        String owner = UUID.randomUUID().toString();

        IdempotencyStore.AcquireResult acquired;
        try {
            acquired = acquireWithPolling(key, owner, entry.pendingTtlSeconds());
        } catch (CacheUnavailableException e) {
            // 外部故障 ⇒ 放行（可用性优先）。这时"重试会重复写"是已接受的代价。
            log.warn("幂等器不可用，放行（fail-open，不幂等）: route={} userId={} 原因={}",
                    routeKey, userId, e.getMessage());
            metrics.recordDecision(Decision.DEGRADED);
            chain.doFilter(request, response);
            return;
        } catch (IdempotencyScriptException e) {
            // ★ 自身缺陷：同样放行，但档位必须更高——ERROR 是"幂等已静默消失"的唯一出口。
            log.error("幂等脚本/返回值异常，放行（fail-open）——幂等器自身缺陷，必须排查: route={} userId={}",
                    routeKey, userId, e);
            metrics.recordDecision(Decision.DEGRADED);
            chain.doFilter(request, response);
            return;
        }

        if (acquired.kind() == IdempotencyStore.Kind.BUSY) {
            metrics.recordDecision(Decision.PENDING);
            log.warn("同一幂等键的请求仍在处理中（409）: route={} userId={}", routeKey, userId);
            errorWriter.write(request, response, ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    ErrorCode.IDEMPOTENCY_IN_PROGRESS.getMessage(),
                    ApiErrorWriter.retryAfter(IN_PROGRESS_RETRY_AFTER_SECONDS));
            return;
        }
        if (acquired.kind() == IdempotencyStore.Kind.DONE) {
            replayOrConflict(request, response, routeKey, userId, acquired.snapshot());
            return;
        }

        executeAndSettle(request, response, chain, key, owner, routeKey, userId);
    }

    /**
     * 占到位之后的正常路径（写接口幂等.md §5.1 的伪代码，逐行对应）。
     *
     * <p>★ 顺序不可调：{@code COMPLETE}（写快照）在 {@code copyBodyToResponse()}（flush）<b>之前</b>。
     * 反过来会让"响应已发出但快照没写"成为可能 ⇒ 客户端重试时重新执行。
     */
    private void executeAndSettle(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain, String key, String owner,
                                  String routeKey, Long userId) throws ServletException, IOException {
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        // ★ 只有 JSON 才包装 request：multipart 包装会把整个文件读进内存。
        //   multipart 的指纹走"仅路径级"分支（见 fingerprintOfCachedRequest），不需要读 body。
        HttpServletRequest chainRequest = isJson(request)
                ? new ContentCachingRequestWrapper(request, properties.limits().fingerprintBytes())
                : request;

        boolean settled = false;
        Decision decision = Decision.MISS;
        try {
            chain.doFilter(chainRequest, responseWrapper);
            int status = responseWrapper.getStatus();
            if (status >= 200 && status < 300) {
                // ★ 只固化 2xx：错误响应绝不进快照（"非 2xx 可安全重试"依赖注册表里的单事务前提）。
                byte[] body = responseWrapper.getContentAsByteArray();
                if (body.length > properties.limits().snapshotBytes()) {
                    log.error("响应快照超过上限，降级为不幂等（业务已提交，响应照常 2xx）: "
                                    + "route={} userId={} bytes={} limit={}",
                            routeKey, userId, body.length, properties.limits().snapshotBytes());
                    decision = Decision.DEGRADED;
                } else {
                    try {
                        settled = store.complete(key, owner, status, responseWrapper.getContentType(),
                                fingerprintOfCachedRequest(chainRequest),
                                new String(body, StandardCharsets.UTF_8));
                        if (!settled) {
                            // 占位已不属于本请求（如处理时长超过 PENDING TTL 被接管）。不是错误：
                            // 响应照常 2xx，只是这次结果没能固化。
                            log.warn("幂等收尾 CAS 未命中（占位已被接管或已过期）: route={} userId={}",
                                    routeKey, userId);
                        }
                    } catch (CacheUnavailableException e) {
                        log.error("写幂等快照失败（业务已提交，响应保持 2xx）: route={} userId={} 原因={}",
                                routeKey, userId, e.getMessage());
                        decision = Decision.DEGRADED;
                    } catch (IdempotencyScriptException e) {
                        log.error("写幂等快照时脚本/返回值异常（幂等器自身缺陷，必须排查）: route={} userId={}",
                                routeKey, userId, e);
                        decision = Decision.DEGRADED;
                    } catch (RuntimeException e) {
                        // 兜底：业务已经提交、响应已写进缓冲 ⇒ 未预期异常不得把"已成功"变成 5xx
                        // （对照 RateLimitFilter#settleMissPenalty 的同款兜底手法）。
                        log.error("写幂等快照出现未预期异常（已兜底，响应保持 2xx）: route={} userId={}",
                                routeKey, userId, e);
                        decision = Decision.DEGRADED;
                    }
                }
            }
        } finally {
            // ★ RELEASE 必须 CAS：成功路径上已写下 DONE 时它自然返回 0；
            //   写成"无条件 HSET FAILED"的话，一次客户端断连就会把 DONE 覆盖掉 ⇒ 下次重发重复写。
            if (!settled) {
                releaseQuietly(key, owner, routeKey, userId);
            }
            try {
                responseWrapper.copyBodyToResponse();
            } catch (IOException e) {
                // 快照已在盘上（COMPLETE 在前）⇒ 断连是常态，DEBUG 足够。
                log.debug("回写响应体失败（客户端可能已断开；幂等快照不受影响）: route={}", routeKey, e);
            }
            metrics.recordDecision(decision);
        }
    }

    /**
     * 命中 {@code DONE}：校验指纹后重放首次响应。
     *
     * <p>★ 这条路径<b>不进 chain</b> ⇒ 可以直接读请求流，读坏的流没有副作用。
     * 指纹比对只在"快照里有 fp 且本次能算出 fp"时进行；<b>算不出</b>（JSON 的超限 / handler 未读）
     * 时视为"无指纹"而不是"不匹配"——后者会把正常重试误判成 422。
     * <p>★ 两条来源不同、口径必须一致：JSON ⇒ body 级；multipart / 非 JSON ⇒ <b>仅路径级</b>
     * （首次写 {@code DONE} 时 {@link #fingerprintOfCachedRequest} 也是这么算的）。
     */
    private void replayOrConflict(HttpServletRequest request, HttpServletResponse response,
                                  String routeKey, Long userId,
                                  IdempotencyRedisOps.Snapshot snapshot) throws IOException {
        String currentFingerprint = null;
        if (snapshot.fingerprint() != null) {
            currentFingerprint = isJson(request)
                    ? fingerprintOfRawRequest(request)
                    : fingerprintOfPath(request);
            if (currentFingerprint != null && !currentFingerprint.equals(snapshot.fingerprint())) {
                metrics.recordDecision(Decision.CONFLICT);
                log.warn("同一幂等键配了不同载荷（422，零副作用）: route={} userId={}", routeKey, userId);
                errorWriter.write(request, response, ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
                return;
            }
        }

        byte[] body = snapshot.bodyBytes();
        response.setStatus(snapshot.status());
        if (!snapshot.contentType().isEmpty()) {
            response.setContentType(snapshot.contentType());
        }
        response.setContentLength(body.length);
        // ★ 只在这里出现（首次响应不带）。
        response.setHeader(HEADER_REPLAYED, REPLAYED_TRUE);
        try {
            response.getOutputStream().write(body);
        } catch (IOException e) {
            // 与 executeAndSettle 的 copyBodyToResponse 同款：断连是常态，快照已在盘上 ⇒ DEBUG。
            // ★ 不能让它冒出去：一次成功的重放若因对端断开而抛 IOException，就会被容器的错误分发
            //   记成 5xx（幂等器自身"重放失败"却污染访问日志，排障时会以为是业务失败）。
            log.debug("重放响应体失败（客户端可能已断开；幂等快照不受影响）: route={}", routeKey, e);
        }
        metrics.recordDecision(Decision.HIT);
        log.debug("重放首次响应: route={} userId={} status={} bytes={} fpMatched={}",
                routeKey, userId, snapshot.status(), body.length, currentFingerprint != null);
    }

    /**
     * 占位 + 短轮询（写接口幂等.md §五："命中 {@code PENDING} ⇒ 2×100 ms，仍 PENDING ⇒ 409"）。
     *
     * <p>★ 2026-10-28 起本仓启用虚拟线程（{@code spring.threads.virtual.enabled=true}）⇒
     * {@link Thread#sleep} 只挂起虚拟线程、不占平台工作线程；本段仍只在**真并发同 key**
     * 的冲突路径上、最多 {@code attempts × interval-millis}，不要调大这个乘积。
     */
    private IdempotencyStore.AcquireResult acquireWithPolling(String key, String owner, int pendingTtlSeconds) {
        IdempotencyStore.AcquireResult result = store.acquire(key, owner, pendingTtlSeconds);
        int attempts = properties.poll().attempts();
        for (int i = 0; i < attempts && result.kind() == IdempotencyStore.Kind.BUSY; i++) {
            try {
                Thread.sleep(properties.poll().intervalMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;   // 中断 ⇒ 按"仍在处理中"处理（409），不吞掉中断位
            }
            result = store.acquire(key, owner, pendingTtlSeconds);
        }
        return result;
    }

    /** 收尾的墓碑写入：任何失败都不得让已完成的响应变失败（本方法在 {@code finally} 里跑）。 */
    private void releaseQuietly(String key, String owner, String routeKey, Long userId) {
        try {
            store.release(key, owner);
        } catch (CacheUnavailableException e) {
            log.warn("释放幂等占位失败（响应不受影响；占位将靠 TTL 自愈）: route={} userId={} 原因={}",
                    routeKey, userId, e.getMessage());
        } catch (IdempotencyScriptException e) {
            log.error("释放幂等占位时脚本/返回值异常（幂等器自身缺陷，必须排查）: route={} userId={}",
                    routeKey, userId, e);
        } catch (RuntimeException e) {
            // 兜底：本方法在 finally 里跑——未预期异常不得把"已完成的请求"变成失败
            log.error("释放幂等占位出现未预期异常（已兜底，不影响响应）: route={} userId={}", routeKey, userId, e);
        }
    }

    // ========================================================================
    // 取模式 / 算指纹 / 小工具
    // ========================================================================

    /**
     * 查表用的路径：<b>路由模式</b>，不是原始 URI。
     *
     * <p>★ 属性由 {@code JwtAuthFilter} 里的 {@code RequestMappingLookup#resolve} 触发的
     * {@code getHandler} 顺带写入（本过滤器在它之内侧）。用 {@code getRequestURI()} 会让
     * {@code POST /api/v1/contents/{id}/coins} 静默落"未登记"——那等于幂等保护**静默消失**
     * （限流那张表踩过这个坑，{@code RateLimitFilter#costOf} 同款处理）。
     *
     * <h2>★ 取不到属性时<b>不接受静默回退</b></h2>
     * 属性缺失只有一种成因：<b>链序被改坏</b>（本类跑到了 {@code JwtAuthFilter} 之前，或
     * {@code RequestMappingLookup} 被摘掉）。回退到原始 URI 后，带 {@code {id}} 的路由会查不到登记 ⇒
     * 幂等保护静默消失。故回退分支必须同时做两件事：
     * <ul>
     *   <li>记一次 {@link Decision#DEGRADED}（"幂等已降级"的唯一观测口）；</li>
     *   <li>按端点 <b>5 min 节流</b>打 WARN（链序坏了是缺陷，但按请求打会刷满日志）。</li>
     * </ul>
     *
     * @return 未映射 / 不可解析时返回原始路径（口径仍是"未登记 ⇒ 放行"，但已留痕）
     */
    private String patternOf(HttpServletRequest request) {
        Object bestMatchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (bestMatchingPattern != null) {
            return bestMatchingPattern.toString();
        }
        String uri = request.getRequestURI();
        String path = uri.substring(request.getContextPath().length());
        metrics.recordDecision(Decision.DEGRADED);
        String routeKey = request.getMethod() + " " + path;
        if (patternFallbackLoggedAt.size() >= FALLBACK_LOG_MAX_ENTRIES) {
            // 只有"链序已坏 + 有人在扫不同路径"同时成立才会走到这里；清空的代价只是再打几行
            patternFallbackLoggedAt.clear();
        }
        if (throttled(patternFallbackLoggedAt, routeKey)) {
            log.warn("取不到路由模式（HandlerMapping.BEST_MATCHING_PATTERN 缺失），回退原始路径查表"
                    + "——链序可能被改坏（本类必须排在 JwtAuthFilter 之内侧），带 {id} 的端点会静默失去幂等: "
                    + "route={}", routeKey);
        }
        return path;
    }

    private static Long userIdOf(HttpServletRequest request) {
        Object attribute = request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        return attribute instanceof Long id ? id : null;
    }

    /** {@code Content-Type} 是否 JSON（只对 JSON 包装 request 算指纹）。 */
    private static boolean isJson(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null || contentType.isEmpty()) {
            return false;
        }
        try {
            return MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON);
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    /**
     * 指纹里的<b>实际请求路径</b>：{@code getRequestURI()} 去掉 {@code contextPath}，<b>不含 query</b>。
     *
     * <p>★ 为什么是原始 URI 而不是 {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}：
     * 路由模式对 {@code /contents/1/coins} 与 {@code /contents/2/coins} <b>完全相同</b>
     * （都是 {@code /contents/{id}/coins}）——拿它算指纹，等于"换了目标也算同一份载荷"，
     * 本任务要修的正是这一类<b>假成功</b>。（查表仍必须用模式，见 {@link #patternOf}：
     * 两处口径不同是刻意的——查表要"端点身份"，指纹要"这一次打的到底是谁"。）
     *
     * <p>归一化只做"去 contextPath"一项：本仓未配置 {@code server.servlet.context-path}
     * （实测为空，且 {@code AccessLogFilter} 也是这么取的），这一句是防御性的，口径与
     * {@link #patternOf} 的回退分支一致。
     * <p>★ 不额外做 URL 解码：Servlet 规范的 {@code getRequestURI()} 返回<b>未解码</b>的 URI
     * （Tomcat 同此），而写入与比对两侧取的是同一个来源 ⇒ 天然自洽；主动解码反而会让
     * {@code %2F} 与 {@code /} 两种写法撞在一起，把两种不同的请求算成同一份。
     * <p>★ 不含 query：4 条 {@code KEYED} 端点都不读 query，拼进去只会让"重试时顺手加个
     * {@code ?_t=} 破缓存"被误判成 422。
     */
    private static String actualPathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath == null || contextPath.isEmpty() || !uri.startsWith(contextPath)) {
            return uri;
        }
        return uri.substring(contextPath.length());
    }

    /** 指纹的公共前缀：{@code method + "\n" + 实际路径}（JSON 分支再追加 {@code "\n" + body}）。 */
    private static String fingerprintHead(HttpServletRequest request) {
        return request.getMethod() + "\n" + actualPathOf(request);
    }

    /** chain 之后的指纹：JSON ⇒ body 级；multipart / 非 JSON ⇒ <b>仅路径级</b>。 */
    private String fingerprintOfCachedRequest(HttpServletRequest chainRequest) {
        if (chainRequest instanceof ContentCachingRequestWrapper wrapper) {
            return fingerprintOfBody(chainRequest, wrapper.getContentAsByteArray());
        }
        return fingerprintOfPath(chainRequest);
    }

    /**
     * 重放路径取指纹：把请求体读进一个临时缓存（本路径不进 chain，读坏流无副作用）。
     *
     * <p>达到上限就停止读——再读也没意义（{@code >= limit} 一律视为无指纹）。
     */
    private String fingerprintOfRawRequest(HttpServletRequest request) throws IOException {
        int limit = properties.limits().fingerprintBytes();
        ContentCachingRequestWrapper wrapper = new ContentCachingRequestWrapper(request, limit);
        ServletInputStream in = wrapper.getInputStream();
        byte[] buffer = new byte[Math.min(limit, 8192)];
        while (wrapper.getContentAsByteArray().length < limit && in.read(buffer) >= 0) {
            // 只为把请求体填进 wrapper 的缓存
        }
        return fingerprintOfBody(request, wrapper.getContentAsByteArray());
    }

    /** 仅路径级指纹（multipart / 非 JSON）：{@code SHA-256(method + "\n" + 路径)}。 */
    private String fingerprintOfPath(HttpServletRequest request) {
        return sha256(fingerprintHead(request), null);
    }

    /**
     * body 级指纹：{@code SHA-256(method + "\n" + 路径 + "\n" + body)}。
     *
     * <p>三种"无指纹"：① handler 没读 body（缓存为空 —— 不能因此判"不匹配"）；
     * ② 达到 {@code fingerprint-bytes} 上限（★ {@code ContentCachingRequestWrapper} 超限是
     * <b>静默截断</b>，只能靠长度判定）；③ 真的没有 body。三者都返回 {@code null}，
     * <b>绝不</b>退化成"仅路径级"（否则不同 body 会被判成同一份载荷）。
     */
    private String fingerprintOfBody(HttpServletRequest request, byte[] cached) {
        int limit = properties.limits().fingerprintBytes();
        if (cached == null || cached.length == 0) {
            return null;
        }
        if (cached.length >= limit) {
            log.warn("请求体达到指纹缓存上限，本次不校验指纹（不可用截断后的 body 算指纹）: bytes={} limit={}",
                    cached.length, limit);
            return null;
        }
        return sha256(fingerprintHead(request) + "\n", cached);
    }

    /** 分段喂 SHA-256：body 可能是原始字节（JSON 不保证是合法 UTF-8 子串），不经字符串转换。 */
    private static String sha256(String head, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(head.getBytes(StandardCharsets.UTF_8));
            if (body != null) {
                digest.update(body);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现，走不到这里；真到了也不该让请求失败 ⇒ 当作"无指纹"。
            log.error("SHA-256 不可用（JDK 异常），本次不校验指纹", e);
            return null;
        }
    }

    /**
     * 缺 key 的日志<b>按端点 5 min 节流</b>（写接口幂等.md §七）。
     *
     * <p>为什么节流：缺 key 是"客户端选择"而不是异常，按请求打会放大 {@code system.log}；
     * 频次由指标 {@code idempotency.decisions{result=missing_key}} 全量承载。
     */
    private void logMissingKey(String routeKey, Long userId) {
        if (!throttled(missingKeyLoggedAt, routeKey)) {
            return;
        }
        log.warn("未带 Idempotency-Key，放行且不占位（可选增强，退化为普通请求）: route={} userId={}",
                routeKey, userId);
    }

    /**
     * 按端点节流的判定：{@code true} = 本次该打日志。
     *
     * <p>不加锁：并发下最多多打一行，无正确性影响。容量由调用方的表决定
     * （{@code missing_key} 的 key 是路由模式，天然有界；回退表的 key 含原始路径，故另加了上限）。
     */
    private static boolean throttled(ConcurrentMap<String, Long> loggedAt, String routeKey) {
        long now = System.currentTimeMillis();
        Long previous = loggedAt.get(routeKey);
        if (previous != null && now - previous < LOG_THROTTLE_INTERVAL_MILLIS) {
            return false;
        }
        loggedAt.put(routeKey, now);
        return true;
    }
}
