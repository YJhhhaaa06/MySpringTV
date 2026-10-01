package io.github.yjhhhaaa06.videoweb.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.util.function.Function;

/**
 * 需要发真实 HTTP 的测试基类。
 *
 * <p>用 {@link RestClient}（Boot 4 中 {@code TestRestTemplate} 已移除）指向
 * {@code RANDOM_PORT} 的真实端口——这是决策⑦所说的「集成测试 = 新的端到端」：
 * 真容器 + 真 HTTP + 真 DB，不再有 pytest 平行体系。
 *
 * <h2>为什么统一走 {@code exchange()} 而不是 {@code retrieve()}</h2>
 * {@code retrieve()} 会执行状态处理器：4xx/5xx 直接抛 {@code HttpClientErrorException}。
 * 但本切片的用例**大量就是要断言错误状态码与错误信封**，用异常断言既别扭又拿不到响应体。
 * {@link RestClient.RequestHeadersSpec#exchange} 返回原始 {@link ResponseEntity}，
 * 不经状态处理器，因此 {@link #send} 统一用它——让每个用例自己断言 status + 信封。
 *
 * <p>每个测试前清空 {@code users} 表，保证用例之间不通过共享库状态隐性耦合（T3 债：
 * 废除"全局种子库"，每测自建数据）。
 */
public abstract class AbstractHttpIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected RestClient client;

    @BeforeEach
    void setUpHttpClient() {
        this.client = RestClient.create("http://localhost:" + port);
        // 每测自建数据：清掉上一个用例留下的用户
        jdbcTemplate.update("DELETE FROM users");
    }

    /** 应用请求定制并发送，返回**未经状态处理器**的原始响应。 */
    protected ResponseEntity<String> send(Function<RestClient.RequestBodyUriSpec,
            RestClient.RequestBodySpec> customizer) {
        return customizer.apply(client.post())
                .exchange((request, response) -> ResponseEntity
                        .status(response.getStatusCode())
                        .headers(response.getHeaders())
                        .body(response.bodyTo(String.class)), false);
    }

    /** GET 版本。 */
    protected ResponseEntity<String> sendGet(Function<RestClient.RequestHeadersUriSpec<?>,
            RestClient.RequestHeadersSpec<?>> customizer) {
        return customizer.apply(client.get())
                .exchange((request, response) -> ResponseEntity
                        .status(response.getStatusCode())
                        .headers(response.getHeaders())
                        .body(response.bodyTo(String.class)), false);
    }
}
