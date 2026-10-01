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
 * <p>若取不到（说明该端点忘了标 {@link RequiresLogin}），抛
 * {@link io.github.yjhhhaaa06.videoweb.common.exception.AuthException}（401）而不是返回 null——
 * <b>失败要显式</b>，避免把"未登录"静默变成 userId=0 这种脏数据。
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
        if (userId == null) {
            throw new io.github.yjhhhaaa06.videoweb.common.exception.AuthException(
                    ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getMessage());
        }
        return userId;
    }

    private static Class<?> wrap(Class<?> type) {
        return type == long.class ? Long.class : type;
    }
}
