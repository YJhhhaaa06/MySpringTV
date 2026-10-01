package io.github.yjhhhaaa06.videoweb.common.config;

import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserIdArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web MVC 配置。
 *
 * <p>TV 的手写 {@code RequestParser}（拆包 JSON、拼参数）与
 * {@code BaseServletUtil}（分页参数归一化，曾**反复重构四轮**）在此彻底退役——
 * 改由 Spring 的消息转换器与参数解析器承担（踩坑清单 §7【已爆】）。
 *
 * <p>目前只注册一个自定义解析器（{@link CurrentUserIdArgumentResolver}）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserIdArgumentResolver currentUserIdArgumentResolver;

    public WebMvcConfig(CurrentUserIdArgumentResolver currentUserIdArgumentResolver) {
        this.currentUserIdArgumentResolver = currentUserIdArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserIdArgumentResolver);
    }
}
