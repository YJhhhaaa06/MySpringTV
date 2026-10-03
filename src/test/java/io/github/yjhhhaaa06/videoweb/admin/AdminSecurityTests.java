package io.github.yjhhhaaa06.videoweb.admin;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S8：admin 端点的**三角色**鉴权（匿名 401 / 非管理员 403 / 管理员通过）。
 *
 * <h2>★ 401 与 403 必须**分开**断言（旧 pytest 就是这么写的）</h2>
 * 这两条在 {@code JwtAuthFilter} 里来自**不同的段**：
 * <pre>
 * ② 需要登录却无身份 ⇒ 401      ← 依据类级 {@code @RequiresLogin}
 * ③ /api/admin 前缀  ⇒ 非管理员/匿名 ⇒ 403   ← 依据 isAdminPath + AdminChecker
 * </pre>
 * 若控制器漏了类级 {@code @RequiresLogin}，**匿名**请求会在第 ② 段被跳过、掉到第 ③ 段
 * 得到 **403**——"拦住"了，但状态码错。只断"非管理员 403"是**测不出**这个漏的
 * （非管理员本来就 403）。故 {@link #匿名访问全部401()} 是这个切片最有信息量的一条。
 *
 * <h2>与 {@code SecurityContractTests} 的分工</h2>
 * 本类是**端到端**（HTTP 状态码）；那个类**直接对 {@code RequestMappingLookup} 求值**
 * （机制本身是否识别注解）。按 SOP §2.5：端到端测"用户看到什么"，契约测试测"机制真的在拦"，
 * 两者不可互相替代。
 *
 * <h2>为什么"管理员侧"只断言"没被 401/403 拦下"</h2>
 * 具体业务码取决于夹具（不存在的内容 → 404、缺参 → 400…），那些由
 * {@code AdminContentTests} / {@code MediaAuditTests} 覆盖。本类只关心**闸门放行**这一件事，
 * 避免把鉴权断言和业务断言混在一起（后者一改就会误报鉴权回归）。
 */
class AdminSecurityTests extends AbstractAdminIntegrationTest {

    private static final String ADMIN_PHONE = "13900003001";
    private static final String ADMIN = "admin-security";
    private static final String NORMAL_PHONE = "13900003002";
    private static final String NORMAL = "admin-security-normal";

    /** 8 个端点的 GET / POST 形态（覆写全部 admin 端点） */
    private static final List<String> GET_PATHS = List.of(
            "/api/admin/content/list",
            "/api/admin/media/me",
            "/api/admin/media/list");

    private static final List<String> POST_PATHS = List.of(
            "/api/admin/content/hide?contentId=1",
            "/api/admin/content/unhide?contentId=1",
            "/api/admin/comment/delete?commentId=1",
            "/api/admin/media/scan",
            "/api/admin/media/restore?mediaId=1");

    @Test
    @DisplayName("★匿名访问全部 admin 端点 → 401（不是 403：类级 @RequiresLogin 必须先触发登录判定）")
    void 匿名访问全部401() {
        for (String path : GET_PATHS) {
            ResponseEntity<String> resp = get(path, null);
            assertThat(resp.getStatusCode().value()).as("匿名 GET %s 应 401，实际: %s", path, resp.getBody())
                    .isEqualTo(401);
        }
        for (String path : POST_PATHS) {
            ResponseEntity<String> resp = post(path, null, null);
            assertThat(resp.getStatusCode().value()).as("匿名 POST %s 应 401，实际: %s", path, resp.getBody())
                    .isEqualTo(401);
        }
    }

    @Test
    @DisplayName("非管理员访问全部 admin 端点 → 403（登录了但没有 role==1）")
    void 非管理员访问全部403() {
        String normalToken = registerNormal(NORMAL_PHONE, NORMAL);

        for (String path : GET_PATHS) {
            ResponseEntity<String> resp = get(path, normalToken);
            assertThat(resp.getStatusCode().value()).as("非管理员 GET %s 应 403，实际: %s", path, resp.getBody())
                    .isEqualTo(403);
        }
        for (String path : POST_PATHS) {
            ResponseEntity<String> resp = post(path, null, normalToken);
            assertThat(resp.getStatusCode().value()).as("非管理员 POST %s 应 403，实际: %s", path, resp.getBody())
                    .isEqualTo(403);
        }
    }

    @Test
    @DisplayName("管理员：8 个端点全部放行（不被 401/403 拦），且 /media/me 返回 data=true")
    void 管理员通过鉴权() {
        String adminToken = registerAdmin(ADMIN_PHONE, ADMIN);

        for (String path : GET_PATHS) {
            int status = get(path, adminToken).getStatusCode().value();
            assertThat(status).as("管理员 GET %s 不应被鉴权拦下", path).isNotIn(401, 403);
        }
        for (String path : POST_PATHS) {
            int status = post(path, null, adminToken).getStatusCode().value();
            assertThat(status).as("管理员 POST %s 不应被鉴权拦下", path).isNotIn(401, 403);
        }

        // /media/me 零逻辑，恒 200 + data=true（前端用它当"我是不是管理员"）
        ResponseEntity<String> me = get("/api/admin/media/me", adminToken);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.data(me).asBoolean()).isTrue();

        // 三个"全量读"端点：夹具为空也应是 200（不是 500）
        assertThat(get("/api/admin/content/list", adminToken).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/api/admin/media/list", adminToken).getStatusCode().value()).isEqualTo(200);
        assertThat(post("/api/admin/media/scan", null, adminToken).getStatusCode().value()).isEqualTo(200);
    }
}
