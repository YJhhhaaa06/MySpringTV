package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 前端 MPA 外壳的 history 路由转发契约（口径 = {@code architecture/frontend/前端.md} §一）。
 *
 * <h2>它钉住什么</h2>
 * 生产环境把「B 站式地址栏 URL」转发到**对应入口 HTML**：
 * {@code /search → search.html}、{@code /video/{id} → video.html}、{@code /opus/{id} → opus.html}、
 * {@code /space/{id} → space.html}、{@code /favorites → favorites.html}、
 * {@code /watchlater → favorites.html}（同一入口、独立路由）、{@code /history → history.html}、
 * {@code /dynamic → dynamic.html}、{@code /placeholder/** → index.html}、{@code / → index.html}。
 * 没有这层转发，打包产物上线后**刷新任意深链都是 404**（dev 侧由 Vite 中间件兜着，
 * 所以这个洞只在生产暴露 —— 正是"dev 绿、线上 404"的典型）。
 *
 * <h2>为什么用 MockMvc 而不是真实 HTTP</h2>
 * 真实 HTTP 只能断言"最终 200/404"，而 200 依赖 {@code frontend/dist} 是否已 stage 到
 * {@code src/main/resources/static}（构建产物不入库，CI 上可以不存在）——
 * 那样的用例会在"没构建前端"的机器上假红，也会在"shell 转发到错文件"时**依然绿**
 * （四个入口都会 200，只要任何一个 html 存在）。
 * MockMvc 断言的是**转发目标**本身（{@code forwardedUrl}），不依赖产物存在。
 *
 * <h2>★ 为什么断言 {@code redirectedUrl(null)}</h2>
 * 用 {@code redirect} 也能让页面显示出来，但会把地址栏从 {@code /video/92} 改成
 * {@code /video.html} ⇒ 应用内 router 立刻落到 not-found，且用户复制出去的链接不再是深链。
 * "能打开"与"URL 不变"是两件事，必须分开断言。
 */
@AutoConfigureMockMvc
class StaticForwardingContractTests extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("入口深链 → 对应入口 HTML：forward（不是 redirect），地址栏保持 B 站式 URL")
    void 入口深链转发到对应入口HTML() throws Exception {
        assertForward("/", "/index.html");
        assertForward("/search", "/search.html");
        assertForward("/video/77", "/video.html");
        // 图文详情（2026-10-27）：/opus/{id} 的页面入口
        assertForward("/opus/77", "/opus.html");
        assertForward("/space/77", "/space.html");
        assertForward("/favorites", "/favorites.html");
        assertForward("/watchlater", "/favorites.html");
        assertForward("/history", "/history.html");
        // 动态（2026-10-27）：/feed 的页面入口
        assertForward("/dynamic", "/dynamic.html");
        // 创作中心（2026-10-28）：投稿页入口
        assertForward("/creator", "/creator.html");
        assertForward("/placeholder/live", "/index.html");
    }

    @Test
    @DisplayName("HTML 外壳是公开的：转发链上不会冒出 401（页面导航不是接口，不该要求登录）")
    void HTML外壳公开() throws Exception {
        // 反向验证：给 WebMvcConfig 的转发改成 @Controller 且漏标 @Public，
        // 默认拒绝会让这四条变 401 ⇒ 本用例红（`forwardedUrl` 也断言不到）。
        assertForward("/search", "/search.html");
        assertForward("/video/1", "/video.html");
        assertForward("/space/1", "/space.html");
        assertForward("/creator", "/creator.html");
    }

    @Test
    @DisplayName("前端构建产物匿名可达：/assets/** 缺文件是 404，不是 401")
    void 静态产物匿名可达() throws Exception {
        // 这条同时钉住 RequestMappingLookup 的"保护域 = @Controller 端点"：
        // 资源处理器不是 HandlerMethod ⇒ 不进授权判定（否则首屏 JS 会被 401 掉）。
        mockMvc.perform(get("/assets/not-built-yet.js"))
                .andExpect(status().isNotFound())
                .andExpect(redirectedUrl(null));
    }

    private void assertForward(String path, String target) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl(target))
                .andExpect(redirectedUrl(null));
    }
}
