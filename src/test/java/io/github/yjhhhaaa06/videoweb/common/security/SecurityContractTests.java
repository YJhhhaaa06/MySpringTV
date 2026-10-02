package io.github.yjhhhaaa06.videoweb.common.security;

import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全契约测试：{@link RequiresLogin} → 是否真的被 {@link RequestMappingLookup} 识别。
 *
 * <h2>存在理由（这个类本该在切片 0 就存在）</h2>
 * {@link RequiresLogin} 的 Javadoc 一直写着"可被测试自动校验（见 {@code SecurityContractTests}）"，
 * 但那个类**从未被创建**——于是机制坏了很久没人知道（见 {@link RequestMappingLookup} 的修复说明）。
 *
 * <p><b>为什么端到端测试抓不到这个 bug</b>：修复前，所有需要登录的端点恰好都接收
 * {@code @CurrentUserId}，而那个参数解析器取不到 userId 时会抛 401。
 * 于是"无 token → 401"的断言**在机制完全失效的情况下也照样通过**——
 * 401 来自参数解析器，不是来自 {@code @RequiresLogin}。
 * 端到端断言只看到"401"，看不到"401 是谁给的"。
 *
 * <p>所以本类**直接断言机制本身**（拿 {@link RequestMappingLookup} 对各类端点求值），
 * 而不是断言 HTTP 状态码。这是"断言可观察行为"原则的一处必要例外：
 * 当"谁给出的结果"本身是契约时，就得直接测那个来源。
 *
 * <p>配套的 HTTP 级覆盖是 {@code LikeFlowTests.未登录全部401}——它专门包含
 * **不接收 {@code @CurrentUserId}** 的端点（{@code /like/content/count}），
 * 那正是修复前唯一会漏的那种端点。
 */
class SecurityContractTests extends AbstractIntegrationTest {

    @Autowired
    private RequestMappingLookup mappingLookup;

    private boolean requiresLogin(String method, String uri) {
        return mappingLookup.requiresLogin(new MockHttpServletRequest(method, uri));
    }

    @Test
    @DisplayName("★回归：类级 @RequiresLogin 且**不接收** @CurrentUserId 的端点也必须判为需登录")
    void 类级注解且不接收UserId的端点需登录() {
        assertThat(requiresLogin("GET", "/like/content/count"))
                .as("2026-10-02 修复前的漏洞点：该端点标了 @RequiresLogin 但不接收 userId，"
                        + "机制失效时它在无 token 下返回 200（其余端点因 @CurrentUserId 抛 401 而掩盖了问题）")
                .isTrue();
        assertThat(requiresLogin("GET", "/like/comment/count"))
                .as("同上：另一个不接收 userId 的受保护端点")
                .isTrue();
    }

    @Test
    @DisplayName("类级 @RequiresLogin：该控制器全部端点判为需登录")
    void 类级注解覆盖全部端点() {
        assertThat(requiresLogin("POST", "/like/content/add")).isTrue();
        assertThat(requiresLogin("POST", "/like/content/remove")).isTrue();
        assertThat(requiresLogin("POST", "/like/comment/add")).isTrue();
        assertThat(requiresLogin("POST", "/like/comment/remove")).isTrue();
        assertThat(requiresLogin("GET", "/like/content/status")).isTrue();
        assertThat(requiresLogin("GET", "/like/comment/status")).isTrue();
    }

    @Test
    @DisplayName("方法级 @RequiresLogin：user / coupon / comment 的受保护端点判为需登录")
    void 方法级注解端点需登录() {
        assertThat(requiresLogin("GET", "/user/me")).isTrue();
        assertThat(requiresLogin("POST", "/user/changePassword")).isTrue();
        assertThat(requiresLogin("POST", "/user/changeUserName")).isTrue();
        assertThat(requiresLogin("POST", "/coupon/grab")).isTrue();
        assertThat(requiresLogin("GET", "/coupon/my")).isTrue();
        assertThat(requiresLogin("POST", "/comment/add")).isTrue();
        assertThat(requiresLogin("POST", "/comment/delete")).isTrue();
    }

    @Test
    @DisplayName("公开端点不得被判为需登录（否则会误拦匿名访问）")
    void 公开端点不需登录() {
        assertThat(requiresLogin("POST", "/user/login")).isFalse();
        assertThat(requiresLogin("POST", "/user/register")).isFalse();
        assertThat(requiresLogin("GET", "/coupon/list")).isFalse();
    }

    @Test
    @DisplayName("未映射的路径返回 false：真正的 404 由 DispatcherServlet 产生，本机制不越权处理路由")
    void 未映射路径不需登录() {
        assertThat(requiresLogin("GET", "/no/such/endpoint")).isFalse();
    }
}
