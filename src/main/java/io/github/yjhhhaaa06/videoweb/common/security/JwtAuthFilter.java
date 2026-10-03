package io.github.yjhhhaaa06.videoweb.common.security;

import io.github.yjhhhaaa06.videoweb.common.exception.BusinessException;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.log.AccessLogFilter;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 鉴权过滤器（决策③「保留 jwt，暂不迁移 Spring Security」）。
 *
 * <p>职责：解析 {@code Authorization: Bearer <token>}，校验后将 {@code userId} 放入请求属性，
 * 供 Controller 经 {@link CurrentUserId} 参数解析器取得。
 *
 * <p><b>相对 TV AuthFilter 的两处关键改进</b>（对应踩坑清单与决策③）：
 * <ol>
 *   <li><b>删除"两份硬编码清单"</b>。TV 的 {@code AuthFilter} 维护
 *       {@code PROTECTED_PREFIXES} + {@code PROTECTED_EXACT} 两个静态集合，
 *       「加一个需要登录的接口 = 记得改这张清单」——漏改就是安全缺口，
 *       且无法自动校验（红线靠人记得）。
 *       新设计改为**声明式**：需要登录的端点打 {@link RequiresLogin} 注解，
 *       由 {@link #requiresLogin} 通过 HandlerMapping 反射判定。单一事实源就在方法上。</li>
 *   <li>缺失/非法 token 时，**仅当该端点需要登录**才拒绝；否则匿名放行
 *       （TV 也是这个语义，但依赖清单维护正确）。</li>
 * </ol>
 *
 * <p><b>已知边界</b>（与决策③一致，非缺陷）：单 Token 无吊销能力——token 到期前无法即时踢下线。
 * 留后项：Redis token 版本号 / 双 Token。
 *
 * <p><b>为何不用 {@code @ControllerAdvice} 处理这里的异常</b>：过滤器在 DispatcherServlet 之前，
 * advice 覆盖不到。故本类自行书写错误响应（唯一一处），控制器层异常仍统一走
 * {@code GlobalExceptionHandler}。
 */
@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 请求属性名：登录用户 id。 */
    public static final String ATTR_USER_ID = "userId";

    private static final String HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AdminChecker adminChecker;
    private final ObjectMapper objectMapper;
    private final RequestMappingLookup mappingLookup;

    public JwtAuthFilter(JwtService jwtService,
                         AdminChecker adminChecker,
                         ObjectMapper objectMapper,
                         RequestMappingLookup mappingLookup) {
        this.jwtService = jwtService;
        this.adminChecker = adminChecker;
        this.objectMapper = objectMapper;
        this.mappingLookup = mappingLookup;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean loginRequired = requiresLogin(request);

        // 1) 解析令牌（存在即校验，无论端点是否需要登录——便于将来加 @CurrentUserId 可选注入）
        String token = extractToken(request);
        if (token != null) {
            try {
                Long userId = jwtService.verifyAndGetUserId(token);
                request.setAttribute(ATTR_USER_ID, userId);
            } catch (BusinessException e) {
                // 令牌非法/过期。仅在需要登录时拒绝；否则按匿名处理（不要因坏 token 阻断公开接口）
                if (loginRequired) {
                    writeError(request, response, e.getCode(), e.getMessage());
                    return;
                }
                log.debug("公开端点收到非法令牌，按匿名处理: {} {}", request.getMethod(), request.getRequestURI());
            }
        }

        // 2) 需要登录却无身份 ⇒ 401
        if (loginRequired && request.getAttribute(ATTR_USER_ID) == null) {
            writeError(request, response, ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage());
            return;
        }

        // 3) 管理员路径：叠加角色校验（沿袭 TV：/api/admin 需 role==1）
        //    走 AdminChecker 窄端口而非直接注入 UserService —— 依赖方向为「业务→基建」（S6-B1）
        if (isAdminPath(request)) {
            Long userId = (Long) request.getAttribute(ATTR_USER_ID);
            if (userId == null || !adminChecker.isAdmin(userId)) {
                writeError(request, response, ErrorCode.FORBIDDEN.getCode(), ErrorCode.FORBIDDEN.getMessage());
                return;
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * 端点是否需要登录——由方法/类上的 {@link RequiresLogin} 声明，
     * 取代 TV 的两份静态路径清单（单一事实源，见类注释）。
     */
    private boolean requiresLogin(HttpServletRequest request) {
        return mappingLookup.requiresLogin(request);
    }

    /** 管理员路径前缀。TV 同样是硬编码前缀，此处保留（角色规则数量少且稳定，无清单维护问题）。 */
    private boolean isAdminPath(HttpServletRequest request) {
        String path = path(request);
        return path.startsWith("/api/admin");
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /**
     * 写错误信封。
     *
     * <p><b>为什么要顺手记业务码</b>（第三批 T2）：访问日志的 {@code code} 字段来自
     * {@code BusinessCodeAdvice}，而 advice 只对**经过 DispatcherServlet** 的响应生效——
     * 本过滤器在 DispatcherServlet **之前**短路（401/403 根本到不了控制器）。若不在此处补记，
     * 鉴权拒绝的访问行会记成 {@code code=0}，与"静态资源"混为一谈，访问日志的三态就塌成两态。
     * 这与 TV 把 {@code code} 写在统一出口、且 AuthFilter 也走该出口是同一回事。
     */
    private void writeError(HttpServletRequest request, HttpServletResponse response, int code, String msg)
            throws IOException {
        request.setAttribute(AccessLogFilter.ATTR_BUSINESS_CODE, code);
        HttpStatus status = HttpStatus.resolve(code);
        response.setStatus((status == null ? HttpStatus.UNAUTHORIZED : status).value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, msg));
    }
}
