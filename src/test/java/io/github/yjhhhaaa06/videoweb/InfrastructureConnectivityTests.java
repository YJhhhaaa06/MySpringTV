package io.github.yjhhhaaa06.videoweb;

import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Containers;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 基础设施连通性验证 —— 由**测试自带容器**提供环境。
 *
 * <p>本类取代了切片 0 之前的版本：旧版要求本机手工跑着 mysqld/redis/rabbitmq，
 * 停掉任一容器即失败（违反 T1「环境即代码」）。现在继承
 * {@link AbstractIntegrationTest}，三个依赖全部来自 Testcontainers。
 *
 * <p>存在理由：Boot 中 DataSource / RedisConnectionFactory / RabbitMQ ConnectionFactory
 * 都是**惰性**的——上下文加载成功只证明 bean 装配完成，不证明真的连上。
 * 故这里对三者做真实握手。
 */
class InfrastructureConnectivityTests extends AbstractIntegrationTest {

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private ConnectionFactory rabbitConnectionFactory;

    @Test
    void 数据源_可执行真实查询_且连的是容器而非宿主开发库() throws Exception {
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(conn.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");

            // ⚠️ 防误连：证明读写的是容器实例，而不是 application.yaml 默认指向的宿主 3306 开发库。
            // 连错库不会报错，只会静默读写真实数据——所以这条断言必须存在。
            String url = conn.getMetaData().getURL();
            Containers.assertConnectedToContainer(url);
            assertThat(url).doesNotContain(":3306/tvdatabase");
        }
    }

    @Test
    void Flyway_在空库上跑V1建出全部15张业务表() throws Exception {
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables "
                             + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'")) {
            assertThat(rs.next()).isTrue();
            // 15 张业务表 + flyway_schema_history
            assertThat(rs.getInt(1)).isEqualTo(16);
        }
        // Flyway 元数据：空库路径应当**直接应用 V1**（baseline-on-migrate 只对非空 schema 生效）
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery(
                     "SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("version")).isEqualTo("1");
            assertThat(rs.getString("type")).isEqualTo("SQL");
            assertThat(rs.getBoolean("success")).isTrue();
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
