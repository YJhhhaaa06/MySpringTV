package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheMissSignal;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.cache.RateLimitRedisOps;
import io.github.yjhhhaaa06.videoweb.common.cache.RateLimitScriptException;
import io.github.yjhhhaaa06.videoweb.common.config.RateLimitProperties;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.security.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 限流闸门过滤器（限流专项 <b>T25</b>；口径的唯一事实源 = {@code architecture/tech/rate-limit/限流.md} §三）。
 *
 * <p>把 T24 落地的 {@link RateLimitRedisOps#tryAcquire}（一次 Lua：「惰性补充 + 询问费 + 两桶 AND
 * 闸门 + 放行扣减」）接到请求链上。本类只做**接线**五件事：链序、覆盖范围、429 出口、fail-open、
 * 可观测（T28）；算法 / 桶结构都在 Redis 层，成本信号①②（端点权重表 / 缓存 miss 系数）的接线归 <b>T26</b>。
 *
 * <h2>★ 链序：鉴权之内侧（用户 2026-10-16 拍板 A「先鉴权后扣费」）</h2>
 * <pre>{@code
 * RequestIdFilter(MIN) → AccessLogFilter(MIN+1) → Security 链(-100)
 *     → JwtAuthFilter(AUTH_FILTER_ORDER=0) → ★本类(=AUTH_FILTER_ORDER+10) → DispatcherServlet
 * }</pre>
 * 直接读请求属性 {@link JwtAuthFilter#ATTR_USER_ID}（本类跑在 {@code JwtAuthFilter} 之后，
 * 鉴权已把身份放好）：401 / 403 在鉴权层短路 ⇒ 被鉴权拒绝的请求**到不了这里**、不浪费令牌。
 * ⚠️ 本任务给 {@code JwtAuthFilter} 与本类都补了显式 {@code @Order}（此前它无序、排最低）——
 * 链序一旦变动必须同步《安全与鉴权》§2.1 与本类注释。
 *
 * <h2>覆盖范围（口径的唯一事实源 = {@code architecture/tech/rate-limit/限流.md} §三）</h2>
 * 除跳过清单外**全部过闸**：含匿名读接口、未映射路径（扫描流正是要防的对象之一）、
 * {@code /api/admin}（通过鉴权的管理员请求同样过闸）。**无任何白名单**（含管理员——
 * 管理端人工操作流量极小，不值得开"绕过口"）。
 * <p>跳过清单 = 精确 {@code /}、{@code /index.html}、{@code /favicon.ico}（浏览器默认请求，
 * 文件不存在也是 404）＋ 前缀 {@code /static/}（前端 18 文件）、{@code /actuator/}、
 * {@code /upload/}。理由：静态资源不是业务成本，一起过闸会拖慢首屏、甚至 429 掉 CSS。
 * ⚠️ {@code /upload/}（静态媒体服务，{@code WebMvcConfig#addResourceHandlers}）在原始
 * 覆盖清单里**没有点名**——执行期按"ResourceHandler 静态服务一律跳过"的同一口径补上
 * （429 掉视频文件与 429 掉 CSS 是同一类事故），登记 {@code I-19}；
 * 业务上传端点是 {@code POST /api/upload/*}，与本前缀零交集，不受影响。
 *
 * <h2>★ 429 出口：形状委托 {@link ApiErrorWriter}</h2>
 * 过滤器在 DispatcherServlet 之前，{@code @RestControllerAdvice} 覆盖不到 ⇒ 必须在过滤器内写响应；
 * 但**产物形状不在这里拼**（{@code T56}）：信封、内容类型、业务码属性补记、以及
 * {@code Retry-After}（秒，向上取整；令牌桶天生知道自己何时补齐）一律交给
 * {@link ApiErrorWriter#write}——否则"加一个错误字段要改三处、漏一处不会有测试变红"。
 *
 * <h2>★ fail-open：两种失败都放行，但日志分两档</h2>
 * Redis 不可达 / 熔断打开（{@code guarded} 快速失败）⇒ **放行**（可用性优先，
 * 对齐本仓"缓存不可用不许 500"的既有口径）。⚠️ {@code RateLimitScriptException}（限流器
 * **自身缺陷**：脚本错 / 返回解析失败，T24 刻意不归一成 {@code CacheUnavailableException}）
 * **同样放行**——若它 500，限流器就成了整个 API 的单点故障；但它的日志档位
 * **必须更高（ERROR）**："限流静默消失"的唯一出口就是这行日志。
 * 正式的日志口径 / 指标归 <b>T28</b>；resilience 组测试钉住"两种失败都放行"的行为契约。
 *
 * <h2>可观测（T28 落地；细节见 {@code architecture/tech/rate-limit/限流.md} §五）</h2>
 * <b>指标</b>（{@link RateLimitMetrics}）：{@code rate.limit.decisions{scope=user|server,
 * result=allowed|rejected}} 记**逐桶**判定（一次请求记两条——两桶判定可以不同，并成一条就答不了
 * "该扩服务器还是该管这个用户"）；{@code rate.limit.acquire} 记一次过闸命令的耗时。
 * <p><b>日志三档</b>：
 * <table>
 *   <caption>日志三档</caption>
 *   <tr><th>场景</th><th>档位</th><th>持栈</th></tr>
 *   <tr><td>拒绝（429）</td><td>WARN 结论行</td><td>否</td></tr>
 *   <tr><td>Redis 不可达 / 熔断打开（放行）</td><td>WARN 结论行</td><td>否</td></tr>
 *   <tr><td><b>脚本 / 返回值解析异常</b>（放行）</td><td><b>ERROR</b></td><td>是（这是缺陷，要能定位）</td></tr>
 * </table>
 * ★ 前两档同为 WARN 但**用词可区分**（"限流拒绝" vs "限流器不可用"），第三档靠级别区分——
 * 三者混作一档，"限流静默消失"就会被 fail-open 伪装成"Redis 挂了"。
 * <p>★ 拒绝行**不记原始 key**（登录记 {@code userId}、匿名不落 IP），理由见 {@link #logRejected}。
 * <p>★ <b>fail-open 不记决策指标</b>：判都没判出来，记什么都是假的；那两条路径只有日志出口
 * （本仓无监控消费方，日志才是唯一会被看到的出口）。
 *
 * <h2>成本（T26 口径：信号① + 信号②）</h2>
 * <b>信号①（端点权重表）</b>：{@code cost = cost.base[「方法 空格 路径」]}，未登记 ⇒ {@code cost.default-base}
 * （★ "漏配 = 不限"正是要防的 ⇒ 兜底**不是 0**；键形态由绑定期校验钉住，见 {@code RateLimitProperties}）。
 * 查表路径与跳过清单**同源**（{@link #pathOf}，未解码未规范化）。
 * <p><b>信号②（缓存 miss 系数）</b>：miss 是**本请求运行期事实**（缓存读发生在请求处理中、门之后）
 * ⇒ 门时取不到 ⇒ 拆两阶段——门时按 {@code base} 扣、请求收尾（{@link #settleMissPenalty}）按差额
 * {@code base × (multiplier − 1)} 补扣。标记的读写点：进闸门 {@link CacheMissSignal#clear()}、
 * 收尾 {@link CacheMissSignal#consume()}（"唯一 set/clear 点"纪律，同 {@code RequestIdFilter} 对 MDC）。
 * 结算细则（两桶同扣、仅"门放行过"的请求、失败吞掉）与二值（不计数）的理由见
 * {@code architecture/tech/rate-limit/限流.md} §四。
 */
@Slf4j
@Component
@Order(RateLimitFilter.RATE_LIMIT_ORDER)
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * 链序：必须落在 {@link JwtAuthFilter}（{@code AUTH_FILTER_ORDER = 0}）**之内侧**。
     * 用"鉴权序 + 10"而不是裸数字，把"限流器在鉴权之后"这条结构约束钉进常量本身——
     * 有人改 {@code AUTH_FILTER_ORDER} 时这里的相对关系不会静默散架。
     */
    public static final int RATE_LIMIT_ORDER = JwtAuthFilter.AUTH_FILTER_ORDER + 10;

    /** 跳过清单的**精确匹配**路径（上下文路径已剥）。 */
    private static final Set<String> SKIP_EXACT = Set.of("/", "/index.html", "/favicon.ico");

    /**
     * 跳过清单的**前缀**匹配（带尾 {@code /}，避免 {@code /staticfoo} 这类假想业务路径被误跳）。
     * {@code /actuator/} 用带斜杠前缀：{@code /actuator} 本身（端点链接页，200）不在清单内、
     * 会过闸——一次请求一枚令牌，可忽略。⚠️ 比原始清单的 {@code /actuator/**} 略窄
     * （多放行裸 {@code /actuator} 一个路径），已留痕 {@code architecture/tech/rate-limit/限流.md} §三。
     */
    private static final List<String> SKIP_PREFIXES = List.of("/static/", "/actuator/", "/upload/");

    private final RateLimitProperties properties;
    private final RateLimitRedisOps ops;
    private final RateLimitMetrics metrics;
    private final ApiErrorWriter errorWriter;

    public RateLimitFilter(RateLimitProperties properties,
                           RateLimitRedisOps ops,
                           RateLimitMetrics metrics,
                           ApiErrorWriter errorWriter) {
        this.properties = properties;
        this.ops = ops;
        this.metrics = metrics;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isSkipped(request)) {
            chain.doFilter(request, response);
            return;
        }

        // ★ 信号②的清理点在此（唯一读点是收尾的 consume）——同 RequestIdFilter 对 MDC 的
        //   "唯一 set/clear 点"纪律：线程池复用不得把上一请求的标记带进本请求。
        CacheMissSignal.clear();

        Long userId = (Long) request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        String userKey = userId != null
                ? CacheKeys.rateLimitUser(userId)
                : CacheKeys.rateLimitAnon(request.getRemoteAddr());
        RateLimitProperties.Bucket userBucket = userId != null
                ? properties.user().authed()
                : properties.user().anon();
        // 成本 = 信号①（端点权重表；未登记 ⇒ default-base，见 RateLimitProperties）
        double cost = costOf(request);

        boolean allowedByGate = false;
        try {
            try {
                long acquireStartNanos = System.nanoTime();
                RateLimitRedisOps.Decision decision = ops.tryAcquire(
                        userKey,
                        CacheKeys.rateLimitServer(),
                        new RateLimitRedisOps.BucketSpec(userBucket.capacity(), userBucket.refillPerSec()),
                        new RateLimitRedisOps.BucketSpec(
                                properties.server().capacity(), properties.server().refillPerSec()),
                        cost,
                        properties.cost().rejectDiscount());
                // ★ 耗时样本紧接命令返回就取（**在打点之前**）：否则窗口里会混进两次
                //   counter 查找 + 自增，"一次 Lua 往返"的口径就不实了。
                metrics.recordAcquire(System.nanoTime() - acquireStartNanos);
                recordDecisions(decision);
                if (!decision.allowed()) {
                    logRejected(request, userId, cost, decision);
                    writeTooManyRequests(request, response, decision.retryAfterSeconds());
                    return;
                }
                allowedByGate = true;
            } catch (CacheUnavailableException e) {
                // ★ fail-open（见类注释）：Redis 不可达 / 熔断打开（guarded 快速失败抛的也是它）。
                log.warn("限流器不可用，放行（fail-open）: path={} userId={} 原因={}",
                        pathOf(request), userIdForLog(userId), e.getMessage());
            } catch (RateLimitScriptException e) {
                // ★ 限流器自身缺陷：同样放行（D-5），但档位必须更高——ERROR 是"限流已静默消失"
                // 的唯一出口（T24 刻意不归一就是为了在这里按类型分开）。
                log.error("限流脚本/返回值异常，放行（fail-open）——限流器自身缺陷，必须排查: path={} userId={}",
                        pathOf(request), userIdForLog(userId), e);
            }

            chain.doFilter(request, response);
        } finally {
            // ★ 收尾结算（信号②）：consume **无条件**执行（放行 / 被拒 / 失败 / 异常都要清）——
            //   它是标记的唯一读点，漏了会把本请求的标记遗给线程池里的下一个请求。
            //   差额只在"门给出过放行决定"时补（fail-open 不算——限流器整体失灵时不记半笔账）。
            boolean missed = CacheMissSignal.consume();
            if (allowedByGate && missed) {
                settleMissPenalty(userKey, userBucket, cost);
            }
        }
    }

    /**
     * 门后差额补扣（信号②，T26）：{@code delta = base × (multiplier − 1)}，两桶同扣。
     *
     * <h2>触发条件（三条同时满足）</h2>
     * ① 门**给出过放行决定**（429 短路的请求没有处理、没有 miss；fail-open 时限流器整体
     * 失灵，不做半笔结算）；② 请求处理期间发生过缓存回源（标记被 {@code consume} 取走）；
     * ③ {@code multiplier > 1.0}（1.0 = 关闭，沿用本仓"0/1 = 关闭"惯例）。
     *
     * <h2>★ 失败必须吞掉（比门的 fail-open 更严）</h2>
     * 本方法跑在 {@code finally}、响应已在途 / 已写出 ⇒ **任何**失败都不得让请求变 500：
     * {@link CacheUnavailableException}（Redis 不可达）WARN；{@link RateLimitScriptException}
     * ERROR（**防御性**——{@code deductDelta} 当前不解析返回值、该分支不可达；保留是给"将来加解析"
     * 兜底：catch 挂在 finally 里，漏掉任何类型都会伤响应）；其余未预期异常兜底 ERROR（对照
     * {@code FeedCache.tryAcquireRebuildLock} 的 RuntimeException 兜底手法）。这两档与主路径同口径
     * （{@code architecture/tech/rate-limit/限流.md} §五：外部故障 WARN / 自身缺陷 ERROR），且同样不记桶 key。
     */
    private void settleMissPenalty(String userKey, RateLimitProperties.Bucket userBucket, double baseCost) {
        double multiplier = properties.cost().missMultiplier();
        if (multiplier <= 1.0) {
            return;     // 信号②关闭：连一次往返都不花
        }
        double delta = baseCost * (multiplier - 1.0);
        try {
            ops.deductDelta(userKey, CacheKeys.rateLimitServer(),
                    new RateLimitRedisOps.BucketSpec(userBucket.capacity(), userBucket.refillPerSec()),
                    new RateLimitRedisOps.BucketSpec(
                            properties.server().capacity(), properties.server().refillPerSec()),
                    delta);
        } catch (CacheUnavailableException e) {
            log.warn("缓存 miss 差额补扣失败（响应不受影响）: {}", e.getMessage());
        } catch (RateLimitScriptException e) {
            // 防御性（当前不可达：deductDelta 不解析返回值）——按同一档位留痕，防将来加解析后静默
            log.error("缓存 miss 差额补扣脚本/返回值异常（限流器自身缺陷，必须排查）", e);
        } catch (RuntimeException e) {
            // 兜底：本方法在 finally 里跑——未预期异常不得把"已完成的请求"变成失败
            log.error("缓存 miss 差额补扣出现未预期异常（已兜底，不影响响应）", e);
        }
    }

    /**
     * 拒绝出口的三档日志之一（<b>T28</b>）：<b>WARN 结论行、不持栈</b>
     * （对齐 {@code architecture/tech/observability/可观测.md} §六"预期业务拒绝"档：WARN 是"业务层判断过、
     * 无需人工介入"的位置）。
     *
     * <h2>★ 不记什么（结构性保证，不是纪律）</h2>
     * 不记桶 key（{@code ratelimit:anon:<ip>} / {@code ratelimit:user:<id>} 一律不进日志）；
     * 登录态记 {@code userId}，匿名态记 {@code userId=-} 且<b>不落 IP</b>——
     * IP 要脱敏得先扩 {@code LogMasker}（{@code architecture/tech/observability/可观测.md} §三 的脱敏面只认手机号），
     * 本期选择"不落盘"而不是"落盘后手写 mask"（调用点各写一遍 = 必然漏）。
     *
     * <h2>★ 已知代价：429 是请求级日志</h2>
     * 刷子场景下这里会**按请求**产生 WARN 行 ⇒ 会放大 {@code system.log}。
     * 接受它：拒绝是"业务层判断过的结论"，静默才是更坏的失败模式；
     * 第一条退路 = 按 key 节流（本期不做，口径见 {@code architecture/tech/rate-limit/限流.md} §五）。
     */
    private void logRejected(HttpServletRequest request, Long userId, double cost,
                             RateLimitRedisOps.Decision decision) {
        log.warn("限流拒绝: path={} userId={} cost={} blockedBy={} retryAfter={}s",
                pathOf(request), userIdForLog(userId), cost, blockedBy(decision),
                decision.retryAfterSeconds());
    }

    /** 逐桶判定 → 两条决策指标（{@code scope=user|server}，见 {@link RateLimitMetrics}）。 */
    private void recordDecisions(RateLimitRedisOps.Decision decision) {
        metrics.recordDecision(RateLimitMetrics.Scope.USER, decision.userBucketPassed());
        metrics.recordDecision(RateLimitMetrics.Scope.SERVER, decision.serverBucketPassed());
    }

    /** 日志里的身份：登录态 = userId，匿名 = {@code -}（**不落 IP**，理由见 {@link #logRejected}）。 */
    private static String userIdForLog(Long userId) {
        return userId == null ? "-" : userId.toString();
    }

    /**
     * 被拒时"谁挡的"（{@code user} / {@code server} / {@code both}）——逐桶判定的派生，
     * 只用于日志可读性；指标侧记的是两个桶各自的原始判定（不做归类）。
     * <p>只在 {@code !allowed} 时调用：此时至少一侧为 {@code false}，故无"都不是"分支。
     */
    private static String blockedBy(RateLimitRedisOps.Decision decision) {
        if (!decision.userBucketPassed() && !decision.serverBucketPassed()) {
            return "both";
        }
        return decision.userBucketPassed() ? "server" : "user";
    }

    /**
     * 端点权重查询（信号①）：键 = 「{@code <方法> <空格> 路径}」（与配置表同形），
     * 未登记 ⇒ {@code cost.default-base}（★ 不是 0——"漏配 = 不限"正是本机制要防的）。
     *
     * <p>路径与跳过清单**同源**（{@link #pathOf}）——"能被跳过的路径"与"被查表的路径"
     * 必须看同一个字符串，否则两套口径（未解码未规范化的前提见 {@link #isSkipped}）。
     */
    private double costOf(HttpServletRequest request) {
        Integer weight = properties.cost().base().get(request.getMethod() + " " + pathOf(request));
        return weight != null ? weight : properties.cost().defaultBase();
    }

    /**
     * 上下文路径已剥的请求路径（**未解码、未规范化**）——跳过清单与权重表查询共用。
     * 取原始 URI 的理由与依赖见 {@link #isSkipped} 的注释。
     */
    private static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /**
     * 跳过清单（见类注释「覆盖范围」）。静态资源 / actuator 不是业务成本，也不该被 429。
     *
     * <p>⚠️ 路径取**未解码、未规范化**的 {@code getRequestURI()} ——"用 {@code /static/../api/...}
     * 让前缀匹配基于原始 URI"这类规范化绕过，依赖 Spring Security 的默认 {@code StrictHttpFirewall}
     * （排在本过滤器之前）拒绝含 {@code ..} / {@code //} 的请求。该依赖属**潜伏边界**声明：
     * 若将来推翻"不迁 Security"的决定（F-01/F-02，见 {@code 安全与鉴权-已知代价.md} 第 2 条）
     * 或关闭防火墙，须回到这里重估。另：{@code getRequestURI()} 不含 query（servlet 规范），
     * 精确匹配不会被 {@code ?x=1} 击穿。
     */
    private boolean isSkipped(HttpServletRequest request) {
        String path = pathOf(request);
        if (SKIP_EXACT.contains(path)) {
            return true;
        }
        for (String prefix : SKIP_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 429 短路出口：状态、信封、{@code Retry-After} 与业务码属性**全部**委托
     * {@link ApiErrorWriter}（{@code T56} 起形状只有一份）。
     *
     * <p>本方法剩下的唯一语义是"用 {@link ErrorCode#RATE_LIMITED} + 桶算出的秒数"——
     * 也就是"限流器拒绝时对外说什么"，而不是"怎么说"。
     */
    private void writeTooManyRequests(HttpServletRequest request,
                                      HttpServletResponse response,
                                      long retryAfterSeconds) throws IOException {
        errorWriter.write(request, response, ErrorCode.RATE_LIMITED,
                ErrorCode.RATE_LIMITED.getMessage(), ApiErrorWriter.retryAfter(retryAfterSeconds));
    }
}
