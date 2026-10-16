package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.cache.RateLimitRedisOps;
import io.github.yjhhhaaa06.videoweb.common.cache.RateLimitScriptException;
import io.github.yjhhhaaa06.videoweb.common.config.RateLimitProperties;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.log.AccessLogFilter;
import io.github.yjhhhaaa06.videoweb.common.security.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 限流闸门过滤器（限流专项 <b>T25</b>；口径的唯一事实源 = {@code architecture/tech/限流.md} §三）。
 *
 * <p>把 T24 落地的 {@link RateLimitRedisOps#tryAcquire}（一次 Lua：「惰性补充 + 询问费 + 两桶 AND
 * 闸门 + 放行扣减」）接到请求链上。本类只做**接线**四件事：链序、覆盖范围、429 出口、fail-open；
 * 算法 / 桶结构都在 Redis 层，成本信号①②（端点权重表 / 缓存 miss 系数）的接线归 <b>T26</b>。
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
 * <h2>覆盖范围（口径的唯一事实源 = {@code architecture/tech/限流.md} §三）</h2>
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
 * <h2>★ 429 出口：与 {@code JwtAuthFilter#writeError} 同款形态</h2>
 * 过滤器在 DispatcherServlet 之前，{@code @RestControllerAdvice} 覆盖不到 ⇒ 自写
 * {@link ApiResponse}；并**补记** {@link AccessLogFilter#ATTR_BUSINESS_CODE}——否则 429 的
 * 访问行记成 {@code code=0}，与静态资源混为一谈，访问日志三态塌成两态。
 * 另带 {@code Retry-After} 响应头（秒，向上取整；令牌桶天生知道自己何时补齐）。
 *
 * <h2>★ fail-open：两种失败都放行，但日志分两档</h2>
 * Redis 不可达 / 熔断打开（{@code guarded} 快速失败）⇒ **放行**（可用性优先，
 * 对齐本仓"缓存不可用不许 500"的既有口径）。⚠️ {@code RateLimitScriptException}（限流器
 * **自身缺陷**：脚本错 / 返回解析失败，T24 刻意不归一成 {@code CacheUnavailableException}）
 * **同样放行**——若它 500，限流器就成了整个 API 的单点故障；但它的日志档位
 * **必须更高（ERROR）**："限流静默消失"的唯一出口就是这行日志。
 * 正式的日志口径 / 指标归 <b>T28</b>；resilience 组测试钉住"两种失败都放行"的行为契约。
 *
 * <h2>成本（T25 阶段的口径）</h2>
 * 每请求固定取 {@code cost.default-base}（信号③未接、信号①②归 T26——分工在
 * {@code RateLimitRedisOps#tryAcquire} 的 javadoc 已钉）。漏配路由的兜底**不是 0**
 * （"漏配 = 不限"正是本机制要防的），本阶段"全部请求 = 兜底单位"是 T26 之前的临时形态。
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
     * （多放行裸 {@code /actuator} 一个路径），已留痕 {@code architecture/tech/限流.md} §三。
     */
    private static final List<String> SKIP_PREFIXES = List.of("/static/", "/actuator/", "/upload/");

    private static final String HEADER_RETRY_AFTER = "Retry-After";

    private final RateLimitProperties properties;
    private final RateLimitRedisOps ops;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimitProperties properties,
                           RateLimitRedisOps ops,
                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.ops = ops;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isSkipped(request)) {
            chain.doFilter(request, response);
            return;
        }

        Long userId = (Long) request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        String userKey = userId != null
                ? CacheKeys.rateLimitUser(userId)
                : CacheKeys.rateLimitAnon(request.getRemoteAddr());
        RateLimitProperties.Bucket userBucket = userId != null
                ? properties.user().authed()
                : properties.user().anon();
        // 成本：T25 阶段固定取兜底权重（权重表 / miss 系数归 T26；"漏配 = 不限"由"必为正"
        // 绑定期校验 + default-base 兜底双重排除，见 RateLimitProperties）。
        double cost = properties.cost().defaultBase();

        try {
            RateLimitRedisOps.Decision decision = ops.tryAcquire(
                    userKey,
                    CacheKeys.rateLimitServer(),
                    new RateLimitRedisOps.BucketSpec(userBucket.capacity(), userBucket.refillPerSec()),
                    new RateLimitRedisOps.BucketSpec(
                            properties.server().capacity(), properties.server().refillPerSec()),
                    cost,
                    properties.cost().rejectDiscount());
            if (!decision.allowed()) {
                writeTooManyRequests(request, response, decision.retryAfterSeconds());
                return;
            }
        } catch (CacheUnavailableException e) {
            // ★ fail-open（见类注释）：Redis 不可达 / 熔断打开（guarded 快速失败抛的也是它）。
            log.warn("限流器不可用，放行（fail-open）: {}", e.getMessage());
        } catch (RateLimitScriptException e) {
            // ★ 限流器自身缺陷：同样放行（D-5），但档位必须更高——ERROR 是"限流已静默消失"
            // 的唯一出口（T24 刻意不归一就是为了在这里按类型分开；正式口径归 T28）。
            log.error("限流脚本/返回值异常，放行（fail-open）——限流器自身缺陷，必须排查", e);
        }

        chain.doFilter(request, response);
    }

    /**
     * 跳过清单（见类注释「覆盖范围」）。静态资源 / actuator 不是业务成本，也不该被 429。
     *
     * <p>⚠️ 路径取**未解码、未规范化**的 {@code getRequestURI()}（与 {@code JwtAuthFilter#isAdminPath}
     * 同款假设）——"用 {@code /static/../api/...} 让前缀匹配基于原始 URI"这类规范化绕过，
     * 依赖 Spring Security 的默认 {@code StrictHttpFirewall}（排在本过滤器之前）拒绝含
     * {@code ..} / {@code //} 的请求。该依赖属**潜伏边界**声明：若将来迁移 Security（F-01/F-02）
     * 或关闭防火墙，须回到这里重估。另：{@code getRequestURI()} 不含 query（servlet 规范），
     * 精确匹配不会被 {@code ?x=1} 击穿。
     */
    private boolean isSkipped(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
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
     * 429 短路出口（{@code JwtAuthFilter#writeError} 同款：自写信封 + 补记业务码属性，
     * 理由见类注释「429 出口」）。另带 {@code Retry-After} 头（秒；放行路径不写此头）。
     */
    private void writeTooManyRequests(HttpServletRequest request,
                                      HttpServletResponse response,
                                      long retryAfterSeconds) throws IOException {
        int code = ErrorCode.RATE_LIMITED.getCode();
        request.setAttribute(AccessLogFilter.ATTR_BUSINESS_CODE, code);
        HttpStatus status = HttpStatus.resolve(code);
        response.setStatus((status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status).value());
        response.setHeader(HEADER_RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.error(code, ErrorCode.RATE_LIMITED.getMessage()));
    }
}
