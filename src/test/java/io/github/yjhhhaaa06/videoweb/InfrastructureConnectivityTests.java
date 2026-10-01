package io.github.yjhhhaaa06.videoweb;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 基础设施连通性验证（任务①的验收依据）。
 *
 * 为什么需要单独一个测试：在 Boot 中 DataSource / RedisConnectionFactory /
 * RabbitMQ ConnectionFactory 都是**惰性**的 —— 上下文加载成功只证明 bean 装配完成，
 * 并不证明真的连上了。本测试对三者做一次真实握手：
 *   - 数据源：真实 SELECT 1（Flyway 已另行证明 DDL 通道可用）
 *   - Redis：真实 PING（同时验证 Jedis 客户端确实就位，而非 Lettuce）
 *   - RabbitMQ：真实建连并开 channel
 */
@SpringBootTest
class InfrastructureConnectivityTests {

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private ConnectionFactory rabbitConnectionFactory;

    @Test
    void 数据源_可执行真实查询() throws Exception {
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(conn.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
    }

    @Test
    void Redis_可PING通且客户端为Jedis() {
        try (RedisConnection conn = redisConnectionFactory.getConnection()) {
            assertThat(conn.ping()).isEqualTo("PONG");
        }
        // 决策⑤核心：必须是 Jedis，不能是 Lettuce（Lettuce 已在 pom 排除）
        assertThat(redisConnectionFactory.getClass().getName()).contains("Jedis");
    }

    @Test
    void RabbitMQ_可建立连接() throws Exception {
        try (var conn = rabbitConnectionFactory.createConnection()) {
            assertThat(conn.isOpen()).isTrue();
            try (var ch = conn.createChannel(false)) {
                assertThat(ch.isOpen()).isTrue();
            }
        }
    }
}
