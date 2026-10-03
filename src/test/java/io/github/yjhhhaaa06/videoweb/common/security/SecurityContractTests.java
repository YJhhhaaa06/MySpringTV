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
    @DisplayName("类级 @RequiresLogin：follow 全部端点判为需登录（TV 的 `/follow` 是**前缀**保护项）")
    void follow类级注解覆盖全部端点() {
        assertThat(requiresLogin("POST", "/follow/add")).isTrue();
        assertThat(requiresLogin("POST", "/follow/remove")).isTrue();
        assertThat(requiresLogin("GET", "/follow/following")).isTrue();
        assertThat(requiresLogin("GET", "/follow/followers")).isTrue();
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
    @DisplayName("S5：content 作者写端点（TV 的 PROTECTED_EXACT 精确项）判为需登录")
    void content写端点需登录() {
        // TV 的 AuthFilter 里，/content/** 下**只有这几条**在精确清单里（该域没有前缀级保护）
        // ⇒ 新实现必须用**方法级** @RequiresLogin（用类级会把将来的公开端点误拦）。
        assertThat(requiresLogin("POST", "/content/commentEnabled")).isTrue();
        assertThat(requiresLogin("POST", "/content/update")).isTrue();
        assertThat(requiresLogin("POST", "/content/mediaDelete")).isTrue();
        assertThat(requiresLogin("POST", "/content/delete")).isTrue();
    }

    @Test
    @DisplayName("S5：content / comment 读端点公开（TV 清单不含它们；带 token 只做个性化）")
    void content读端点公开() {
        assertThat(requiresLogin("GET", "/search/IdSearch")).isFalse();
        assertThat(requiresLogin("GET", "/search/keywordSearch")).isFalse();
        assertThat(requiresLogin("GET", "/start")).isFalse();
        assertThat(requiresLogin("GET", "/profile")).isFalse();
    }

    @Test
    @DisplayName("S5：comment 读端点公开（TV 只保护 /comment/add 与 /comment/delete 两个精确项）")
    void comment读端点公开() {
        assertThat(requiresLogin("GET", "/comment/show")).isFalse();
        assertThat(requiresLogin("GET", "/comment/replies")).isFalse();
        // 反向对照：同域的两个写端点**仍是**需登录的（排除"整个 /comment 都被放行"的假绿）
        assertThat(requiresLogin("POST", "/comment/add")).isTrue();
        assertThat(requiresLogin("POST", "/comment/delete")).isTrue();
    }

    @Test
    @DisplayName("S7：upload 三个端点判为需登录（TV 的 `/api/upload` 是**前缀**保护项 ⇒ 类级注解）")
    void upload类级注解覆盖全部端点() {
        assertThat(requiresLogin("POST", "/api/upload/video")).isTrue();
        assertThat(requiresLogin("POST", "/api/upload/post")).isTrue();
        assertThat(requiresLogin("POST", "/api/upload/replace")).isTrue();
    }

    @Test
    @DisplayName("S8：admin 8 端点判为需登录（TV 的 `/api/admin` 是**前缀**保护项 ⇒ 类级注解）")
    void admin类级注解覆盖全部端点() {
        // ⚠️ 本组断言是 401/403 契约的**机制级**保证：
        //   缺了类级 @RequiresLogin，匿名请求会掉到 isAdminPath 判成 403（而非契约要求的 401）。
        //   端到端侧由 AdminSecurityTests 断言状态码，两者不可互相替代（SOP §2.5）。
        assertThat(requiresLogin("GET", "/api/admin/content/list")).isTrue();
        assertThat(requiresLogin("POST", "/api/admin/content/hide")).isTrue();
        assertThat(requiresLogin("POST", "/api/admin/content/unhide")).isTrue();
        assertThat(requiresLogin("POST", "/api/admin/comment/delete")).isTrue();
        assertThat(requiresLogin("GET", "/api/admin/media/me")).isTrue();
        assertThat(requiresLogin("GET", "/api/admin/media/list")).isTrue();
        assertThat(requiresLogin("POST", "/api/admin/media/scan")).isTrue();
        assertThat(requiresLogin("POST", "/api/admin/media/restore")).isTrue();
    }

    @Test
    @DisplayName("S9：feed 端点判为需登录（TV 的 `/feed` 是 PROTECTED_EXACT **精确**保护项）")
    void feed端点需登录() {
        // TV AuthFilter.PROTECTED_EXACT 含 "/feed"（精确，非前缀）⇒ 类级 @RequiresLogin 与之对应。
        assertThat(requiresLogin("GET", "/feed")).isTrue();
    }

    @Test
    @DisplayName("未映射的路径返回 false：真正的 404 由 DispatcherServlet 产生，本机制不越权处理路由")
    void 未映射路径不需登录() {
        assertThat(requiresLogin("GET", "/no/such/endpoint")).isFalse();
    }

    @Test
    @DisplayName("S7：静态媒体路径 /upload/** 不受 @RequiresLogin 管辖（媒体匿名可访问，同 TV 的 Tomcat 挂载）")
    void 静态媒体路径公开() {
        // /upload/** 由 SimpleUrlHandlerMapping（资源处理器）承接，不在 requestMappingHandlerMapping 里
        // ⇒ RequestMappingLookup 返回 false。这不是"漏保护"：媒体文件本就公开（TV 一致）。
        assertThat(requiresLogin("GET", "/upload/video/some-file.mp4")).isFalse();
    }
}
