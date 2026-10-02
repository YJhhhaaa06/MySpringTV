package io.github.yjhhhaaa06.videoweb.common.security;

import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 解析 {@link CurrentUserId} 参数：从请求属性取 userId。
 *
 * <h2>两种语义（S5 起）</h2>
 * <ul>
 *   <li>{@code @CurrentUserId}（{@code required = true}，默认）——若取不到（说明该端点忘了标
 *       {@link RequiresLogin}），抛 {@link io.github.yjhhhaaa06.videoweb.common.exception.AuthException}
 *       （401）而**不是**返回 null：<b>失败要显式</b>，避免把"未登录"静默变成 {@code userId = 0} 这种脏数据。</li>
 *   <li>{@code @CurrentUserId(required = false)}——取不到就注入 {@code null}。
 *       服务"匿名可见但已登录则个性化"的端点（{@code /start} / {@code /search/*} / {@code /profile} /
 *       {@code /comment/show} / {@code /comment/replies}）。</li>
 * </ul>
 *
 * <p>注意：匿名请求**带了非法 token** 时，{@link JwtAuthFilter} 对公开端点会把坏 token
 * 按匿名处理（不写请求属性）⇒ 这里返回 null，与 TV 的行为一致。
 */
@Component
public class CurrentUserIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class)
                && Long.class.equals(wrap(parameter.getParameterType()));
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        Object userId = request == null ? null : request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        if (userId != null) {
            return userId;
        }
        CurrentUserId annotation = parameter.getParameterAnnotation(CurrentUserId.class);
        boolean required = annotation == null || annotation.required();
        if (!required) {
            return null;
        }
        throw new io.github.yjhhhaaa06.videoweb.common.exception.AuthException(
                ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getMessage());
    }

    private static Class<?> wrap(Class<?> type) {
        return type == long.class ? Long.class : type;
    }
}
