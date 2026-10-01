package io.github.yjhhhaaa06.videoweb.common.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
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
