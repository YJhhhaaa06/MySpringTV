package io.github.yjhhhaaa06.videoweb.observability;

import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * 访问日志端到端（第三批 T2 / 账 B12）——翻译老项目 {@code test_access_log.py} 5 条 e2e 的**意图**。
 *
 * <h2>★ 核心判据：三态可区分</h2>
 * <table>
 *   <caption>(status, code) 三态</caption>
 *   <tr><th>场景</th><th>status</th><th>code</th><th>含义</th></tr>
 *   <tr><td>正常业务请求</td><td>200</td><td>200</td><td>产生了业务信封</td></tr>
 *   <tr><td>未登录访问受保护接口</td><td>401</td><td>401</td><td>鉴权过滤器短路，也记了业务码</td></tr>
 *   <tr><td>静态资源（{@code /upload/**}）</td><td>200</td><td><b>0</b></td><td>**从未产生业务信封** ⇒ 缺省 0</td></tr>
 * </table>
 * 第三态是旧用例 {@code test_static_resource_gets_code_zero} 的**原话**口径
 * （TV 访问日志 {@code code} 字段缺省 0 = 未走业务统一出口）。
 *
 * <h2>另外两条（也来自旧用例）</h2>
 * <ul>
 *   <li>{@code test_error_path_request_id_correlates_with_app_logs} →
 *       {@link #异常请求的请求标识贯穿访问行与应用日志()}（同名 reqId 串三路）；</li>
 *   <li>脱敏口径 → {@link #访问行不记query串与请求头()}（旧 AccessLogFilter 的 D7"绝不记"）。</li>
 * </ul>
 */
@Tag("observability")
class AccessLogTests extends AbstractObservabilityTest {

    private static final String STATIC_PROBE = "observability-probe.txt";
    private static final String STATIC_URL = "/upload/" + STATIC_PROBE;

    /** ERROR 路径的确定触发点（见 {@link #异常请求的请求标识贯穿访问行与应用日志}）。 */
    @MockitoSpyBean
    private UserDao userDao;

    @BeforeEach
    void resetSpies() {
        reset(userDao);
    }

    @Test
    @DisplayName("正常请求：访问行含方法/路径/状态码/业务码/耗时，且同一 req 贯穿")
    void 正常请求_访问行完整() {
        clearLogs();
        ResponseEntity<String> response = get("/start", null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        String line = awaitAccessLine("/start", "status=200");
        assertThat(line).contains("method=GET ");
        assertThat(line).contains("code=200 ");
        assertThat(line).contains("userId=- ");           // 公开端点无身份
        assertThat(line).contains("slow=0");
        assertThat(costOf(line))
                .as("cost 字段必须存在且可解析（`> 0` 会被机器负载影响：短路由实测出现过 cost=0ms）")
                .isGreaterThanOrEqualTo(0);
        assertThat(reqIdOf(line)).matches("[0-9a-f]{16}");
    }

    @Test
    @DisplayName("未登录访问受保护接口：status=401 且业务码也是 401")
    void 未登录_访问行记401() {
        clearLogs();
        ResponseEntity<String> response = post("/user/changePassword", Map.of(), null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);

        String line = awaitAccessLine("/user/changePassword", "status=401");
        assertThat(line)
                .as("鉴权拒绝的业务码由 JwtAuthFilter 写入——缺它这里会退化成 0、与静态资源混为一谈")
                .contains("code=401 ");
        assertThat(line).contains("userId=- ");
    }

    @Test
    @DisplayName("★ 静态资源：业务码缺省 0（test_static_resource_gets_code_zero 的意图）")
    void 静态资源_业务码为零() {
        Path probe = Path.of(AbstractIntegrationTest.testMediaRoot()).resolve(STATIC_PROBE);
        try {
            Files.createDirectories(probe.getParent());
            Files.writeString(probe, "observability probe", StandardCharsets.UTF_8);

            clearLogs();
            ResponseEntity<String> response = get(STATIC_URL, null);
            assertThat(response.getStatusCode().value()).isEqualTo(200);

            String line = awaitAccessLine(STATIC_URL, "status=200");
            assertThat(line)
                    .as("静态资源未进 DispatcherServlet ⇒ 没有业务信封 ⇒ code 缺省 0")
                    .contains("code=0 ");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // 清理尽力而为
            }
        }
    }

    @Test
    @DisplayName("★ 三态可区分：正常 / 401 / 静态资源 的 (status,code) 两两不同")
    void 访问日志三态可区分() {
        Path probe = Path.of(AbstractIntegrationTest.testMediaRoot()).resolve(STATIC_PROBE);
        try {
            Files.createDirectories(probe.getParent());
            Files.writeString(probe, "observability probe", StandardCharsets.UTF_8);

            clearLogs();
            get("/start", null);
            post("/user/changePassword", Map.of(), null);
            get(STATIC_URL, null);

            String normal = awaitAccessLine("/start", "status=200");
            String unauthorized = awaitAccessLine("/user/changePassword", "status=401");
            String staticResource = awaitAccessLine(STATIC_URL, "status=200");

            List<String> states = new ArrayList<>(List.of(
                    stateOf(normal), stateOf(unauthorized), stateOf(staticResource)));
            assertThat(states).as("三态的 (status,code) 必须互不相同，否则'可区分'不成立").doesNotHaveDuplicates();
            assertThat(states).containsExactlyInAnyOrder("200/200", "401/401", "200/0");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // 清理尽力而为
            }
        }
    }

    @Test
    @DisplayName("异常请求：同一 req 贯穿访问行与应用日志，且访问行结果码收口为 500")
    void 异常请求的请求标识贯穿访问行与应用日志() {
        String token = registerAndGetToken("13800009001", "obs-error-user", "abc123456");
        // ERROR 触发点：让 `updateUsername` 抛 DataAccessException ⇒ GlobalExceptionHandler 的
        // `handleDataAccess` 记 ERROR（带堆栈）+ 回 500。
        // ★ 为什么打桩而不是"传超长用户名撞 DB 列宽"：后者隐式依赖 `username varchar(50)` 与
        //   MySQL strict mode——列加宽或 strict 关闭就变红，属"跨 schema 的隐式耦合"。
        //   打桩点确定、且仍走真实的 ERROR 出口（口径同 AdminTransactionTests 的打桩纪律）。
        doThrow(new DataIntegrityViolationException("boom: 模拟写库失败"))
                .when(userDao).updateUsername(anyLong(), anyString());
        clearLogs();

        ResponseEntity<String> response =
                post("/user/changeUserName?userName=obs-error-probe", null, token);
        assertThat(response.getStatusCode().value())
                .as("数据访问异常 ⇒ 500（HTTP 状态码正确化后不再是 200）")
                .isEqualTo(500);

        String accessLine = awaitAccessLine("/user/changeUserName", "status=500");
        assertThat(accessLine).contains("code=500 ");
        String requestId = reqIdOf(accessLine);

        String appLine = awaitSystemLine("req=" + requestId + " ");
        assertThat(appLine)
                .as("同一次请求的应用日志必须带同一个 req（否则'跨三路关联'无从谈起）")
                .contains("level=ERROR")
                .contains("msg=数据访问异常");
    }

    @Test
    @DisplayName("访问行不记 query 串与请求头（D7「绝不记」：token/手机号/密码一律不落盘）")
    void 访问行不记query串与请求头() {
        clearLogs();
        get("/search/IdSearch?contentId=1&keyword=secret-query-token", null);

        String line = awaitAccessLine("/search/IdSearch", "status=");
        assertThat(line)
                .as("path 取自 getRequestURI()，servlet 规范保证不含 query 串")
                .doesNotContain("?")
                .doesNotContain("keyword")
                .doesNotContain("secret-query-token");
    }

    private static String stateOf(String accessLine) {
        return fieldOf(accessLine, "status=") + "/" + fieldOf(accessLine, "code=");
    }

    /** 按 {@code prefix=<数字>} 取字段值（不用正则：日志行可能带行尾空白，正则的 {@code .*}
     *  会把它一起留下，第一版就是这么踩的）。 */
    private static String fieldOf(String line, String prefix) {
        int start = line.indexOf(prefix);
        assertThat(start).as("访问行缺少字段 %s: %s", prefix, line).isGreaterThanOrEqualTo(0);
        int index = start + prefix.length();
        int end = index;
        while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
        }
        return line.substring(index, end);
    }
}
