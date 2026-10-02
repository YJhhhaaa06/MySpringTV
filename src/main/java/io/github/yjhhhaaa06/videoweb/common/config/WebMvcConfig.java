package io.github.yjhhhaaa06.videoweb.common.config;

import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserIdArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web MVC 配置。
 *
 * <p>TV 的手写 {@code RequestParser}（拆包 JSON、拼参数）与
 * {@code BaseServletUtil}（分页参数归一化，曾**反复重构四轮**）在此彻底退役——
 * 改由 Spring 的消息转换器与参数解析器承担（踩坑清单 §7【已爆】）。
 *
 * <p>目前承担两件事：① 注册 {@link CurrentUserIdArgumentResolver}；
 * ② 挂载 {@code /upload/**} 静态媒体服务（S7）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserIdArgumentResolver currentUserIdArgumentResolver;
    private final UploadProperties uploadProperties;

    public WebMvcConfig(CurrentUserIdArgumentResolver currentUserIdArgumentResolver,
                        UploadProperties uploadProperties) {
        this.currentUserIdArgumentResolver = currentUserIdArgumentResolver;
        this.uploadProperties = uploadProperties;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserIdArgumentResolver);
    }

    /**
     * 静态媒体服务：{@code /upload/**} → 媒体磁盘根（S7 新增）。
     *
     * <h2>为什么必须补</h2>
     * TV 由 Tomcat 承担此事——{@code META-INF/context.xml} 的
     * {@code <PostResources base="${upload.path:-…}" webAppMount="/upload"/>}。
     * 本项目的上传接口返回 {@code /upload/{dir}/{file}} 这样的 URL，但**没有任何东西**服务它
     * ⇒ 不补则媒体恒 404、"上传 → 可播放"的闭环不成立（《决策留痕表》B-10）。
     *
     * <h2>★ 与落盘**同一属性**（TV 的启动一致性校验因此退役）</h2>
     * 落盘用 {@link UploadProperties#root()}（{@code FileUploadService}），本处也读它
     * ⇒ 两侧不可能漂移。TV 需要 {@code AppShutDownListener} 在启动时比对
     * {@code context.xml} 与 {@code app.properties} 两份独立配置，本项目**校验对象消失**（B-12）。
     *
     * <p>URL 形状 {@code /upload/video/xxx} 对应磁盘 {@code <root>/video/xxx}——
     * 即 {@code /upload/} 前缀被剥掉、其余路径原样拼到根下。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String root = uploadProperties.root();
        String location = "file:" + root + (root.endsWith("/") ? "" : "/");
        registry.addResourceHandler("/upload/**").addResourceLocations(location);
    }
}
