package io.github.yjhhhaaa06.videoweb.common.config;

import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserIdArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web MVC 配置。
 *
 * <p>TV 的手写 {@code RequestParser}（拆包 JSON、拼参数）与
 * {@code BaseServletUtil}（分页参数归一化，曾**反复重构四轮**）在此彻底退役——
 * 改由 Spring 的消息转换器与参数解析器承担（踩坑清单 §7【已爆】）。
 *
 * <p>目前承担三件事：① 注册 {@link CurrentUserIdArgumentResolver}；
 * ② 挂载 {@code /upload/**} 静态媒体服务（S7）；
 * ③ 前端 MPA 外壳的 **history 路由转发**（见 {@link #addViewControllers}）。
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
     * 前端 MPA 外壳的 history 路由：把「浏览器地址栏里的 B 站式 URL」转发到**对应入口 HTML**。
     *
     * <h2>为什么必须补</h2>
     * 前端是「MPA 外壳 + 应用内 SPA」（口径见 {@code architecture/frontend/前端.md} §一）：
     * 每个入口一个 HTML、应用内用 {@code createWebHistory}。打包产物里只有
     * {@code index.html / search.html / video.html / opus.html / space.html / favorites.html / history.html / dynamic.html / creator.html}
     * 这几个**文件名**，而用户地址栏里是 {@code /search}、{@code /video/92}、{@code /opus/91}、
     * {@code /watchlater}、{@code /dynamic}、{@code /creator}
     * —— 没有这层转发，刷新深链就是 404。
     * dev / preview 侧由 {@code vite.config.ts} 的 {@code htmlHistoryFallback}
     * 承担**同一张映射表**；两处必须同步改（TS 与 Java 之间没法互相校验，靠这条注释 + review）。
     *
     * <h2>★ 为什么用 ViewController 而不是 {@code @Controller}</h2>
     * 这是一个**页面导航**，不是对外接口。用 {@code @Controller} 会带来三件错事：
     * <ol>
     *   <li>它会被 {@code ApiContractInventoryTests} 扫进端点登记簿（判据是「bean 上有
     *       {@code @Controller}」），于是登记簿里多出几条 {@code GET /video/{id} → String}；
     *       而登记簿同时要求「非 DELETE 端点必须返回 {@code ApiResponse}」⇒ 直接红；</li>
     *   <li>{@code RequestMappingLookup} 会把它当业务端点 ⇒ 默认拒绝 ⇒ 每条都得标
     *       {@code @Public}，凭空多出一个「放行口」要维护；</li>
     *   <li>OpenAPI spec 会把它导成 API 端点。</li>
     * </ol>
     * {@code ViewController} 由独立的 {@code viewControllerHandlerMapping} 处理（order 1，先于
     * {@code requestMappingHandlerMapping}），处理器是 {@code ParameterizableViewController}，
     * **不是 {@code HandlerMethod}** ⇒ 不进登记簿、不进 spec、不参与三条鉴权注解的判定
     * （静态 HTML 本来就该公开）。这才是「页面导航 ≠ 接口」的正确落点。
     *
     * <h2>★ 与限流跳过清单的关系</h2>
     * 这些路径在 {@code RateLimitFilter#SKIP_EXACT} 里：HTML 外壳是静态文件、不产生业务成本，
     * 与 {@code /assets/**} 同口径（429 掉一个 HTML 与 429 掉 CSS 是同一类事故）。
     * 但**真正发业务请求的是页面里的 {@code /api/v1/**}** —— 跳过的是外壳，不是数据。
     *
     * <h2>转发保留地址栏</h2>
     * {@code forward} 是服务端内部转发：浏览器 URL 仍是 {@code /video/92}，
     * 应用内 router 因此能按真实路径匹配（换成 {@code redirect} 会把 URL 变成
     * {@code /video.html}，router 立刻落到 not-found，且分享出去的深链也变了）。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/search").setViewName("forward:/search.html");
        registry.addViewController("/video/{id}").setViewName("forward:/video.html");
        // 图文详情（2026-10-27）：`/opus/{id}` → opus.html（B 站图文类的标准链接形状）
        registry.addViewController("/opus/{id}").setViewName("forward:/opus.html");
        registry.addViewController("/space/{id}").setViewName("forward:/space.html");
        registry.addViewController("/favorites").setViewName("forward:/favorites.html");
        // 稍后再看：独立 URL、复用收藏页入口（2026-10-26 晚）
        registry.addViewController("/watchlater").setViewName("forward:/favorites.html");
        registry.addViewController("/history").setViewName("forward:/history.html");
        // 动态（2026-10-27）：`/dynamic` → dynamic.html（`GET /feed` 的页面）
        registry.addViewController("/dynamic").setViewName("forward:/dynamic.html");
        // 创作中心（2026-10-28）：`/creator` → creator.html。
        // ⚠️ 页面路由**刻意不用 `/upload`**：`/upload/**` 是媒体静态路径（见 addResourceHandlers），
        //    两者撞在同一前缀上会互相吃掉（dev 侧 Vite 代理同理）。
        registry.addViewController("/creator").setViewName("forward:/creator.html");
        registry.addViewController("/placeholder/{feature}").setViewName("forward:/index.html");
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
