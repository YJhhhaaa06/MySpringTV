package io.github.yjhhhaaa06.videoweb.common.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 判定「当前请求的处理器要求什么」——把三个注解读成一对布尔量。
 *
 * <p>这是取代 TV {@code AuthFilter} 两份硬编码路径清单的机制核心：
 * 通过 {@link HandlerMapping#getHandler} 拿到即将执行的 {@link HandlerMethod}，再读注解。
 *
 * <p><b>为什么可以在这里调 getHandler</b>：{@code AbstractHandlerMapping.getHandler}
 * 把解析结果缓存在请求属性 {@code HandlerMapping.LOOKUP_PATH} 上，
 * DispatcherServlet 随后的同路径查找是幂等的，不会重复解析或产生副作用。
 * 若路径未映射（404），返回 {@link HandlerAuthz#NONE}——真正的 404 由 DispatcherServlet 正常产生，
 * 本类不越权处理路由语义。
 *
 * <p><b>作用域</b>：本 bean 注入到两处 —— {@link JwtAuthFilter}（运行期鉴权）与
 * {@code OpenApiConfig} 的 {@code OperationCustomizer}（`T60-2` 起：spec 的 401/403 必须与运行期
 * **同一份判定**，否则两套口径漂移且不报错）。两者都只**读**本类，不反向依赖 MVC 配置，
 * 故不会与 {@code HandlerMappingIntrospector} 的早期初始化形成环。
 *
 * <h2>★ 2026-10-18：判定口径从「声明才拦」改为「声明才放」</h2>
 * <pre>
 * 旧：loginRequired = 标了 @RequiresLogin          ⇒ 漏标 = 端点对匿名开放（fail-open）
 * 新：loginRequired = !标了 @Public                ⇒ 漏标 = 401（fail-closed）
 *     adminRequired = 标了 @RequiresAdmin          （且它**蕴含** loginRequired）
 * </pre>
 * 漏标在旧口径下**不会响亮失败**：端点若接收 {@code @CurrentUserId(required = true)}，
 * 参数解析器会兜出 401，看起来一切正常（正是下面那次"机制完全失效"的同一类假象）。
 * 真正敞开的只有不接收 userId 的端点。新口径把"漏标"变成立刻可见的 401。
 * 代价与放行口纪律见 {@link Public} / {@link RequiresAdmin} 类注释。
 *
 * <h2>★ 2026-10-18：管理员判定从「路径前缀」改为「命中的处理器」</h2>
 * 旧 {@code JwtAuthFilter#isAdminPath} 读**未解码**的 {@code getRequestURI()} 前缀，
 * 而路由匹配走解码后的 PathPattern —— 两个判据语义不同，且"新增 admin 端点必须落在
 * {@code /api/admin} 下"只是口头约定。现在角色要求写在控制器上，**规则与路由是同一份事实**。
 * 护栏：{@code SecurityContractTests#所有admin前缀控制器都必须标RequiresAdmin}
 * （扫描容器内全部 {@code @RestController}，路由落在 {@code /api/v1/admin} 前缀却没标本注解 ⇒ 红）。
 *
 * <h2>⚠️ 2026-10-02（S3 期间）修复：本类曾**完全失效**</h2>
 * 原实现直接对 {@code handlerMapping.getHandler(request)} 的返回值做
 * {@code instanceof HandlerMethod} 判断，但 {@code AbstractHandlerMapping.getHandler}
 * 返回的是 **{@code HandlerExecutionChain}**（包装了 handler + 拦截器链），
 * 于是 {@code instanceof} **恒为 false** ⇒ 本类恒返回"放行"。
 *
 * <p>它之所以长期没被发现，是因为**所有需要登录的端点恰好都用了 {@code @CurrentUserId}**：
 * 那个参数解析器在取不到 userId 时会抛 {@code AuthException}(401)，
 * 于是"鉴权看起来是好的"——**401 来自参数解析器，而不是来自本机制**。
 * 直到 S3 出现第一个"标了 {@link RequiresLogin} 但不接收 userId"的端点
 * （{@code /like/content/count}）才暴露：它在无 token 时返回了 **200**。
 *
 * <p>修复即"解开 {@code HandlerExecutionChain} 再判断 handler"。
 * 回归测试：{@code SecurityContractTests}（直接断言本类对各类端点的判定），
 * 以及 {@code LikeFlowTests.未登录全部401}（HTTP 级，覆盖"不接收 userId 的端点"这一缺口）。
 */
@Slf4j
@Component
public class RequestMappingLookup {

    private final HandlerMapping handlerMapping;

    public RequestMappingLookup(@Qualifier("requestMappingHandlerMapping") HandlerMapping handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    /**
     * 一次解析同时给出两个授权维度（过滤器每请求只调一次，避免重复 getHandler）。
     *
     * @param request 当前请求
     * @return 未映射的路径返回 {@link HandlerAuthz#NONE}（匿名放行，404 交 DispatcherServlet）
     */
    public HandlerAuthz resolve(HttpServletRequest request) {
        HandlerMethod handler = resolveHandler(request);
        if (handler == null) {
            // 未映射的路径：不要求登录，交由 DispatcherServlet 产出 404
            return HandlerAuthz.NONE;
        }
        return resolve(handler);
    }

    /**
     * 同一份判定的**第二个入口**：处理器已经在手时直接问它要不要登录（`T60-2` 起由 OpenAPI 导出使用）。
     *
     * <p>为什么要开这个口子：spec 里的 401/403 必须与**运行期同一依据**声明，若在文档侧按注解另写一套，
     * 两套口径迟早漂移（而漂移不可见 —— 文档不会报错）。所以这里只做"解析处理器 → 读注解"的拆分，
     * 判定逻辑仍只有一份。
     *
     * @param handler 目标处理器（调用方保证它已是业务控制器的处理器；非控制器返回 {@link HandlerAuthz#NONE}）
     */
    public HandlerAuthz resolve(HandlerMethod handler) {
        if (!isControllerEndpoint(handler)) {
            // Spring 内部 handler（不属于任何业务控制器）⇒ 不参与授权判定。见方法注释。
            return HandlerAuthz.NONE;
        }

        boolean adminRequired = hasAnnotation(handler, RequiresAdmin.class);
        boolean loginDeclared = hasAnnotation(handler, RequiresLogin.class);
        boolean explicitPublic = hasAnnotation(handler, Public.class);

        if (explicitPublic && (adminRequired || loginDeclared)) {
            // 声明矛盾：取更严的一条（要求登录），并留痕。不允许"安静的宽松"。
            log.warn("@Public 与 @RequiresLogin/@RequiresAdmin 同时出现在 {}#{}，按需登录处理",
                    handler.getBeanType().getSimpleName(), handler.getMethod().getName());
        }

        // 默认拒绝 + 冲突取严：admin ⇒ 必登录；显式登录 ⇒ 登录；仅 @Public ⇒ 匿名；什么都没标 ⇒ 登录
        boolean loginRequired = adminRequired || loginDeclared || !explicitPublic;
        return new HandlerAuthz(loginRequired, adminRequired);
    }

    /**
     * 该请求的目标处理器是否要求登录（`@RequiresLogin` / `@RequiresAdmin`，或**未标任何注解**）。
     *
     * <p>保留此便捷方法是为了让机制级契约测试读起来贴合语义；生产路径走 {@link #resolve}。
     */
    public boolean requiresLogin(HttpServletRequest request) {
        return resolve(request).loginRequired();
    }

    /** 该请求的目标处理器是否要求管理员角色（`@RequiresAdmin`）。 */
    public boolean requiresAdmin(HttpServletRequest request) {
        return resolve(request).adminRequired();
    }

    /**
     * 授权判定的结果：两个维度**必须一起**给出（角色要求蕴含登录要求）。
     *
     * @param loginRequired 需要有效身份（无身份 ⇒ 401）
     * @param adminRequired 需要 role == 1（无身份 401 在前，非管理员 ⇒ 403）
     */
    public record HandlerAuthz(boolean loginRequired, boolean adminRequired) {

        /** 匿名放行：未映射路径 / 静态资源 / 显式 {@code @Public}。 */
        public static final HandlerAuthz NONE = new HandlerAuthz(false, false);
    }

    /**
     * 只对**业务控制器**给出的处理器做授权判定。
     *
     * <h2>为什么需要这个筛子</h2>
     * 默认拒绝的前提是"处理器对应一个业务端点"。但 {@code requestMappingHandlerMapping} 也会产出
     * **框架内部**的 {@link HandlerMethod}，最典型的是 OPTIONS：
     * 对"路径能匹配、方法不匹配"的请求，Spring 返回内部的 {@code HttpOptionsHandler}
     * （外层是 HandlerMethod，bean 类型是框架内部类、不带任何业务注解）。若不筛掉：
     * <ul>
     *   <li>匿名 {@code OPTIONS /xx} 会被判成"未标 {@code @Public} ⇒ 需登录" ⇒ 从"200 + Allow"变 **401**
     *       （本仓无 CORS，但 curl / 探活工具会看到）；</li>
     *   <li>而且它不执行业务逻辑，拦它没有安全收益。</li>
     * </ul>
     * 筛掉的判据是"bean 类型上有没有 {@code @Controller}"（{@code @RestController} 是它的组合注解），
     * 而不是"请求方法是不是 OPTIONS"——后者会误伤**控制器里显式声明**的 OPTIONS 业务方法
     * （那类处理器同样是业务端点，必须照常过授权）。
     *
     * <p>⚠️ 由此推论：授权判定的保护域 = **{@code @Controller} 端点**。未映射路径、静态资源、
     * {@code /actuator/**}（别的 HandlerMapping）、框架内部 handler 都在域外（见 {@code 安全与鉴权.md} §2.3）。
     */
    private boolean isControllerEndpoint(HandlerMethod handler) {
        return AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), Controller.class);
    }

    private boolean hasAnnotation(HandlerMethod handler, Class<? extends java.lang.annotation.Annotation> type) {
        // 用 merged 查找（AnnotatedElementUtils）而非 isAnnotationPresent：
        // 与 Spring 自己的注解语义一致，组合注解（meta-annotation）也能被识别。
        return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), type)
                || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), type);
    }

    private HandlerMethod resolveHandler(HttpServletRequest request) {
        try {
            Object handler = handlerMapping.getHandler(request);
            // ★ 必须解开 HandlerExecutionChain：getHandler 返回的是"handler + 拦截器"的包装，
            //   不是 HandlerMethod 本身。直接 instanceof HandlerMethod 会恒为 false（曾经的 bug）。
            if (handler instanceof HandlerExecutionChain chain) {
                handler = chain.getHandler();
            }
            if (handler instanceof HandlerMethod handlerMethod) {
                log.debug("handler resolved: {} {} -> {}.{}",
                        request.getMethod(), request.getRequestURI(),
                        handlerMethod.getBeanType().getSimpleName(), handlerMethod.getMethod().getName());
                return handlerMethod;
            }
            log.debug("handler not a HandlerMethod: {} {} -> {}",
                    request.getMethod(), request.getRequestURI(),
                    handler == null ? "null" : handler.getClass().getName());
            return null;
        } catch (Exception e) {
            // 方法不匹配（405）、参数解析前置失败等：不在此处判定鉴权，交由 MVC 正常处理
            log.debug("解析处理器失败，按未映射处理: {} {} -> {}",
                    request.getMethod(), request.getRequestURI(), e.toString());
            return null;
        }
    }
}
