package io.github.yjhhhaaa06.videoweb.user;

import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切片 0 验收：user 模块功能闭环（注册 → 登录 → 持 token 访问受保护接口）。
 *
 * <p>断言的都是**可观察行为**（HTTP 状态 + 信封形状 + DB 终态），不断言内部实现——
 * 这是防"测试迎合"的核心纪律（决策⑦）：测试不该变成实现的镜像。
 *
 * <p>路径与信封形状沿袭 TV（决策⑧「路径沿袭控制迁移变量」），
 * 但 HTTP 状态码按决策⑧**正确化**：错误不再恒为 200，而是与业务码对齐。
 */
class UserFlowTests extends AbstractHttpIntegrationTest {

    private static final String PHONE = "13800000001";
    private static final String USERNAME = "alice";
    private static final String PASSWORD = "abc123456";

    // ==================== 注册 ====================

    @Test
    @DisplayName("注册成功：返回 token，DB 落一行，密码为 BCrypt 散列而非明文")
    void 注册成功() {
        ResponseEntity<String> resp = post("/user/register",
                Map.of("phone", PHONE, "username", USERNAME, "password", PASSWORD), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.code(resp)).isEqualTo(200);
        assertThat(Envelope.msg(resp)).isEqualTo("success");
        assertThat(Envelope.str(resp, "token")).isNotBlank();
        assertThat(Envelope.str(resp, "username")).isEqualTo(USERNAME);

        // 独立 oracle 复算：不信任接口回显，直接查库
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, username, phone, hashed_password, role FROM users WHERE phone = ?", PHONE);
        assertThat(row.get("username")).isEqualTo(USERNAME);

        String hashed = (String) row.get("hashed_password");
        assertThat(hashed).startsWith("$2");          // BCrypt 前缀
        assertThat(hashed).isNotEqualTo(PASSWORD);    // 绝不能是明文
        assertThat(((Number) row.get("role")).intValue()).isZero(); // 新用户默认普通用户
    }

    @Test
    @DisplayName("手机号重复：409 + 业务码 409，且 DB 不新增行")
    void 手机号重复() {
        post("/user/register", Map.of("phone", PHONE, "username", USERNAME, "password", PASSWORD), null);
        long before = countUsers();

        ResponseEntity<String> resp = post("/user/register",
                Map.of("phone", PHONE, "username", "bob", "password", PASSWORD), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(Envelope.code(resp)).isEqualTo(409);
        assertThat(countUsers()).isEqualTo(before);   // 关键：失败不留脏数据
    }

    @Test
    @DisplayName("用户名重复：409，且 DB 不新增行")
    void 用户名重复() {
        post("/user/register", Map.of("phone", PHONE, "username", USERNAME, "password", PASSWORD), null);
        long before = countUsers();

        ResponseEntity<String> resp = post("/user/register",
                Map.of("phone", "13900000002", "username", USERNAME, "password", PASSWORD), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(Envelope.code(resp)).isEqualTo(409);
        assertThat(countUsers()).isEqualTo(before);
    }

    @Test
    @DisplayName("密码不合规：400（声明式校验，取代 TV 散落的手写 if）")
    void 密码不合规() {
        ResponseEntity<String> resp = post("/user/register",
                Map.of("phone", PHONE, "username", USERNAME, "password", "abc"), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(Envelope.code(resp)).isEqualTo(400);
        assertThat(countUsers()).isZero();
    }

    @Test
    @DisplayName("手机号格式非法：400")
    void 手机号格式非法() {
        ResponseEntity<String> resp = post("/user/register",
                Map.of("phone", "12345", "username", USERNAME, "password", PASSWORD), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countUsers()).isZero();
    }

    // ==================== 登录 ====================

    @Test
    @DisplayName("登录成功：返回可解出 userId 的 token")
    void 登录成功() {
        post("/user/register", Map.of("phone", PHONE, "username", USERNAME, "password", PASSWORD), null);

        ResponseEntity<String> resp = post("/user/login",
                Map.of("phone", PHONE, "password", PASSWORD), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.str(resp, "token")).isNotBlank();
        // 信封形状契约：data 内是 id / username / token 三字段（与 TV LoginVO 一致）
        assertThat(Envelope.str(resp, "username")).isEqualTo(USERNAME);
        assertThat(Envelope.num(resp, "id")).isPositive();
    }

    @Test
    @DisplayName("密码错误：401 + 业务码 401（TV 语义，勿改成 404）")
    void 密码错误() {
        post("/user/register", Map.of("phone", PHONE, "username", USERNAME, "password", PASSWORD), null);

        ResponseEntity<String> resp = post("/user/login",
                Map.of("phone", PHONE, "password", "wrongpass123"), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(Envelope.code(resp)).isEqualTo(401);
    }

    @Test
    @DisplayName("用户不存在：401 + 业务码 401（认证语义，不是 404）")
    void 用户不存在() {
        ResponseEntity<String> resp = post("/user/login",
                Map.of("phone", "13700000009", "password", PASSWORD), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(Envelope.code(resp)).isEqualTo(401);
    }

    // ==================== 受保护接口（鉴权闭环） ====================

    @Test
    @DisplayName("无 token 访问受保护接口：401 + 统一信封")
    void 无token访问受保护接口() {
        ResponseEntity<String> resp = get("/user/me", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(Envelope.code(resp)).isEqualTo(401);
        // 错误信封不应含 data 键（TV 的 error() 就没有）——形状契约
        assertThat(Envelope.hasDataField(resp)).isFalse();
    }

    @Test
    @DisplayName("伪造 token：401")
    void 伪造token() {
        ResponseEntity<String> resp = get("/user/me", "not.a.real.token");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(Envelope.code(resp)).isEqualTo(401);
    }

    @Test
    @DisplayName("持有效 token 访问 /user/me：200 + 用户信息，且不泄露口令散列")
    void 持token访问受保护接口() {
        post("/user/register", Map.of("phone", PHONE, "username", USERNAME, "password", PASSWORD), null);
        String token = Envelope.str(post("/user/login",
                Map.of("phone", PHONE, "password", PASSWORD), null), "token");
        assertThat(token).as("登录必须返回 token").isNotBlank();

        ResponseEntity<String> resp = get("/user/me", token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Envelope.str(resp, "username")).isEqualTo(USERNAME);
        assertThat(Envelope.str(resp, "phone")).isEqualTo(PHONE);
        // 安全断言：响应体任何位置都不得出现口令散列
        assertThat(resp.getBody()).doesNotContain("hashedPassword", "hashed_password", "$2");
    }

    @Test
    @DisplayName("公开端点不受坏 token 影响（不能因坏 token 阻断登录）")
    void 公开端点忽略坏token() {
        ResponseEntity<String> resp = post("/user/login",
                Map.of("phone", PHONE, "password", PASSWORD), "garbage");

        // 用户不存在 → 401，但这是"登录失败"，不是"被过滤器拦截"：
        // 用 msg 区分二者，确保坏 token 没有让公开端点提前被拒。
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(Envelope.msg(resp)).isEqualTo("用户不存在");
    }

    // ==================== 改密 ====================

    @Test
    @DisplayName("修改密码后：旧密码失效、新密码可用")
    void 修改密码() {
        post("/user/register", Map.of("phone", PHONE, "username", USERNAME, "password", PASSWORD), null);
        String token = Envelope.str(post("/user/login",
                Map.of("phone", PHONE, "password", PASSWORD), null), "token");

        ResponseEntity<String> resp = post("/user/changePassword",
                Map.of("phone", PHONE, "oldPassword", PASSWORD, "newPassword", "newpass456"), token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(post("/user/login", Map.of("phone", PHONE, "password", PASSWORD), null)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/user/login", Map.of("phone", PHONE, "password", "newpass456"), null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("原密码错误：401，且密码未被改动")
    void 原密码错误则不修改() {
        post("/user/register", Map.of("phone", PHONE, "username", USERNAME, "password", PASSWORD), null);
        String token = Envelope.str(post("/user/login",
                Map.of("phone", PHONE, "password", PASSWORD), null), "token");

        ResponseEntity<String> resp = post("/user/changePassword",
                Map.of("phone", PHONE, "oldPassword", "totallywrong1", "newPassword", "newpass456"), token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // 独立 oracle 复算：原密码必须仍然可用
        assertThat(post("/user/login", Map.of("phone", PHONE, "password", PASSWORD), null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("未登录不能改密：401（@RequiresLogin 生效）")
    void 未登录不能改密() {
        ResponseEntity<String> resp = post("/user/changePassword",
                Map.of("phone", PHONE, "oldPassword", PASSWORD, "newPassword", "newpass456"), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ==================== 改密：手机号必须是本人（还原 TV 冻结契约） ====================

    @Test
    @DisplayName("非本人手机号不能改密：400，且密码未被改动")
    void 非本人手机号不能改密() {
        post("/user/register", Map.of("phone", PHONE, "username", USERNAME, "password", PASSWORD), null);
        String token = Envelope.str(post("/user/login",
                Map.of("phone", PHONE, "password", PASSWORD), null), "token");

        ResponseEntity<String> resp = post("/user/changePassword",
                Map.of("phone", "13900000009", "oldPassword", PASSWORD, "newPassword", "newpass456"), token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/user/login", Map.of("phone", PHONE, "password", PASSWORD), null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("★ 手机号比对先于旧密码判定：错号 + 错密码 → 400 而非 401")
    void 手机号比对先于旧密码_错号且错密码回400() {
        post("/user/register", Map.of("phone", PHONE, "username", USERNAME, "password", PASSWORD), null);
        String token = Envelope.str(post("/user/login",
                Map.of("phone", PHONE, "password", PASSWORD), null), "token");

        // TV 的 doChangePassword 里手机号比对（UserService.java:188）严格早于旧密码比对（:193）
        // ⇒ 两个都错时必须是"手机号不匹配"的 400，不能是"旧密码错误"的 401。
        // 顺序反了这条就会变红；而"错号 + 对密码"的用例（上一个测试）两种顺序都是 400，不足以钉住顺序。
        ResponseEntity<String> resp = post("/user/changePassword",
                Map.of("phone", "13900000009", "oldPassword", "totallywrong1",
                        "newPassword", "newpass456"), token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ==================== helpers ====================

    private long countUsers() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return n == null ? 0 : n;
    }
}
