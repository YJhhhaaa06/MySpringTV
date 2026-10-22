package io.github.yjhhhaaa06.videoweb.common.security;

import io.github.yjhhhaaa06.videoweb.common.config.JwtProperties;
import io.github.yjhhhaaa06.videoweb.common.log.AccessLogFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JwtAuthFilter} 的**段级**契约：401 与 403 的分工、以及"角色判据来自处理器"这件事。
 *
 * <h2>为什么需要它（与其它两个测试类的分工）</h2>
 * <ul>
 *   <li>{@code SecurityContractTests} 断言 {@link RequestMappingLookup} 的**判定结果**
 *       （两个布尔量），但它**不经过过滤器**；</li>
 *   <li>{@code AdminSecurityTests} 断言**状态码**，但它的 8 条路径全在 {@code /api/admin} 下
 *       ⇒ 旧实现（路径前缀）与新实现（处理器注解）给出**完全相同**的 401/403，
 *       **它无法区分**"角色判据到底是前缀还是注解"。</li>
 * </ul>
 * 本类补的正是那条缝：把 {@code @RequiresAdmin} 标在一个**不在 {@code /api/admin} 下**的桩端点上，
 * 再直接驱动过滤器。若有人把角色判据改回路径前缀，{@code 非admin路径上的RequiresAdmin仍然生效} 立刻变红。
 *
 * <p>纯单元测试（不启容器、不连库）：过滤器依赖的四个协作者全部手工装配。
 */
class JwtAuthFilterAuthzTests {

    private static final long ADMIN_ID = 1L;
    private static final long NORMAL_ID = 2L;
    private static final String SECRET = "test-secret-test-secret-test-secret";

    private final JwtService jwtService =
            new JwtService(new JwtProperties(SECRET, Duration.ofHours(1)));
    /** 只有 {@link #ADMIN_ID} 是管理员 —— 与 {@code users.role == 1} 同义。 */
    private final AdminChecker adminChecker = userId -> userId == ADMIN_ID;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private static final Probe PROBE = new Probe();

    /** 用桩处理器构造过滤器：路径写死为 {@code /probe}，**故意不在 {@code /api/admin} 下**。 */
    private JwtAuthFilter filterFor(String probeMethod) throws NoSuchMethodException {
        Method method = Probe.class.getMethod(probeMethod);
        HandlerMapping stub = request -> new HandlerExecutionChain(new HandlerMethod(PROBE, method));
        return new JwtAuthFilter(jwtService, adminChecker, objectMapper, new RequestMappingLookup(stub));
    }

    private Outcome call(JwtAuthFilter filter, String token) throws Exception {
        return call(filter, token, "POST");
    }

    private Outcome call(JwtAuthFilter filter, String token, String method) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/probe");
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean(false);
        filter.doFilter(request, response, (req, resp) -> passed.set(true));
        return new Outcome(passed.get(), response.getStatus(),
                response.getContentAsString(), request.getAttribute(JwtAuthFilter.ATTR_USER_ID),
                request.getAttribute(AccessLogFilter.ATTR_BUSINESS_CODE));
    }

    @Test
    @DisplayName("★OPTIONS 的豁免只针对**框架内部 handler**：控制器里显式声明的 OPTIONS 业务方法照常过授权")
    void 显式OPTIONS业务方法不被豁免() throws Exception {
        // 判别力：若把筛子写成"请求方法是 OPTIONS 就放行"（不看处理器属于谁），本用例变红 ——
        // 那正是"OPTIONS 短路"最容易埋的洞：未来真有人写 @RequestMapping(method = OPTIONS)
        // 的业务端点时，鉴权会被静默跳过。
        Outcome out = call(filterFor("adminOnly"), jwtService.generateToken(NORMAL_ID), "OPTIONS");

        assertThat(out.passed()).isFalse();
        assertThat(out.status()).isEqualTo(403);
    }

    @Test
    @DisplayName("★角色判据跟着处理器走：@RequiresAdmin 在非 /api/admin 路径上同样生效（403）")
    void 非admin路径上的RequiresAdmin仍然生效() throws Exception {
        String normalToken = jwtService.generateToken(NORMAL_ID);

        Outcome out = call(filterFor("adminOnly"), normalToken);

        // 判别力：把 JwtAuthFilter 第 ③ 段改回 `path.startsWith("/api/admin")` ⇒ 本断言变红（会放行）。
        assertThat(out.passed())
                .as("角色判据若回到路径前缀，/probe 不会被判成管理端点 ⇒ 非管理员被放行")
                .isFalse();
        assertThat(out.status()).isEqualTo(403);
        assertThat(out.body()).as("403 信封文案（沿袭 TV「无权访问」）").contains("无权访问");
        assertThat(out.businessCode())
                .as("过滤器短路时补记业务码，否则访问日志的 code 会记成 0（三态塌两态）；T55 起是字符串码")
                .isEqualTo("AUTH_FORBIDDEN");
    }

    @Test
    @DisplayName("★@RequiresAdmin 蕴含登录：匿名访问该端点得 401（先登录判定、后角色判定）")
    void 匿名访问admin端点得401() throws Exception {
        Outcome out = call(filterFor("adminOnly"), null);

        assertThat(out.passed()).isFalse();
        assertThat(out.status())
                .as("若把 ②③ 两段顺序互换（或缺'admin 蕴含登录'），这里会变成 403")
                .isEqualTo(401);
        assertThat(out.businessCode()).isEqualTo("AUTH_UNAUTHORIZED");
    }

    @Test
    @DisplayName("管理员通过：放行且 userId 已放进请求属性（下游 @CurrentUserId / 限流器依赖它）")
    void 管理员放行() throws Exception {
        Outcome out = call(filterFor("adminOnly"), jwtService.generateToken(ADMIN_ID));

        assertThat(out.passed()).isTrue();
        assertThat(out.userId()).isEqualTo(ADMIN_ID);
    }

    @Test
    @DisplayName("公开端点收到非法 token ⇒ 按匿名放行（不因坏 token 杀公开接口）")
    void 公开端点坏token按匿名处理() throws Exception {
        Outcome out = call(filterFor("publicOnly"), "not-a-jwt");

        assertThat(out.passed()).as("公开端点不能被坏 token 阻断").isTrue();
        assertThat(out.userId()).as("坏 token 不写身份").isNull();
    }

    @Test
    @DisplayName("受保护端点收到非法 token ⇒ 401（区分'过期'与'其他非法'只在日志层）")
    void 受保护端点坏token得401() throws Exception {
        Outcome out = call(filterFor("loginOnly"), "not-a-jwt");

        assertThat(out.passed()).isFalse();
        assertThat(out.status()).isEqualTo(401);
    }

    /** 过滤器一次调用的可观察结果（全部取自响应与请求属性，不看内部状态）。 */
    private record Outcome(boolean passed,
                           int status,
                           String body,
                           Object userId,
                           Object businessCode) {
    }

    /**
     * 桩控制器：三种授权形态各一个方法，不注册进容器。
     *
     * <p>★ 标 {@code @RestController} 是**有意**的：{@code RequestMappingLookup} 只对"属于某个
     * {@code @Controller} 的处理器"做授权判定（框架内部 handler 如 OPTIONS 的 {@code HttpOptionsHandler}
     * 被筛掉）⇒ 桩必须真的是控制器，否则这些用例会因"不是业务端点"而恒放行。
     */
    @RestController
    static class Probe {

        @RequiresAdmin
        public void adminOnly() {
            // 仅管理员（蕴含登录）
        }

        @Public
        public void publicOnly() {
            // 匿名放行
        }

        @RequiresLogin
        public void loginOnly() {
            // 需登录
        }
    }
}
