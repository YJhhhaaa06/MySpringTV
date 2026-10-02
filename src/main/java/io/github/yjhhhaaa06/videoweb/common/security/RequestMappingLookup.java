package io.github.yjhhhaaa06.videoweb.common.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;

/**
 * 判定「当前请求的处理器是否要求登录」——读取 {@link RequiresLogin} 注解。
 *
 * <p>这是取代 TV {@code AuthFilter} 两份硬编码路径清单的机制核心：
 * 通过 {@link HandlerMapping#getHandler} 拿到即将执行的 {@link HandlerMethod}，
 * 再检查方法或声明类上是否有 {@link RequiresLogin}。
 *
 * <p><b>为什么可以在这里调 getHandler</b>：{@code AbstractHandlerMapping.getHandler}
 * 把解析结果缓存在请求属性 {@code HandlerMapping.LOOKUP_PATH} 上，
 * DispatcherServlet 随后的同路径查找是幂等的，不会重复解析或产生副作用。
 * 若路径未映射（404），返回 false——真正的 404 由 DispatcherServlet 正常产生，
 * 本类不越权处理路由语义。
 *
 * <p><b>作用域</b>：本 bean 只注入到 {@link JwtAuthFilter}（不是 {@code Filter} 或
 * {@code WebMvcConfigurer}），以避免与 {@code HandlerMappingIntrospector} 的
 * 早期初始化产生循环依赖。
 *
 * <h2>⚠️ 2026-10-02（S3 期间）修复：本类曾**完全失效**</h2>
 * 原实现直接对 {@code handlerMapping.getHandler(request)} 的返回值做
 * {@code instanceof HandlerMethod} 判断，但 {@code AbstractHandlerMapping.getHandler}
 * 返回的是 **{@code HandlerExecutionChain}**（包装了 handler + 拦截器链），
 * 于是 {@code instanceof} **恒为 false** ⇒ 本类恒返回 false ⇒
 * {@code @RequiresLogin} **从未生效过**。
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
     * @return true 表示该请求的目标处理器打了 {@link RequiresLogin}（方法或类级）
     */
    public boolean requiresLogin(HttpServletRequest request) {
        HandlerMethod handler = resolveHandler(request);
        if (handler == null) {
            // 未映射的路径：不要求登录，交由 DispatcherServlet 产出 404
            return false;
        }
        return handler.hasMethodAnnotation(RequiresLogin.class)
                || handler.getBeanType().isAnnotationPresent(RequiresLogin.class);
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
                log.debug("handler resolved: {} {} -> {}.{} (requiresLogin={})",
                        request.getMethod(), request.getRequestURI(),
                        handlerMethod.getBeanType().getSimpleName(), handlerMethod.getMethod().getName(),
                        handlerMethod.hasMethodAnnotation(RequiresLogin.class)
                                || handlerMethod.getBeanType().isAnnotationPresent(RequiresLogin.class));
                return handlerMethod;
            }
            log.debug("handler not a HandlerMethod: {} {} -> {}",
                    request.getMethod(), request.getRequestURI(),
                    handler == null ? "null" : handler.getClass().getName());
            return null;
        } catch (Exception e) {
            // 方法不匹配（405）、参数解析前置失败等：不在此处判定鉴权，交由 MVC 正常处理
            log.debug("解析处理器失败，按不需登录处理: {} {} -> {}",
                    request.getMethod(), request.getRequestURI(), e.toString());
            return null;
        }
    }
}
