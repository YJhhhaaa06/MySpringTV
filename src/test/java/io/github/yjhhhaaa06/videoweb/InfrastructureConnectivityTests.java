package io.github.yjhhhaaa06.videoweb;

import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Containers;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.ArrayList;
import java.util.List;

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
            // 断言写成 `:3306/`（宿主的那个端口）而非旧库名：库名会随"分家"再变（2026-10-06 已
            // 由 TVDatabase 改为 spring_tv），而"宿主开发库在 3306"是不变的事实。
            String url = conn.getMetaData().getURL();
            Containers.assertConnectedToContainer(url);
            assertThat(url).doesNotContain(":3306/");
        }
    }

    @Test
    void Flyway_在空库上跑V1至V5建出全部19张业务表() throws Exception {
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables "
                             + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'")) {
            assertThat(rs.next()).isTrue();
            // 19 张业务表（V1 的 15 张 + V2 的 favorite_folder / favorite_item + V4 的 watch_later
            // + V5 的 content_coin）
            // + flyway_schema_history
            assertThat(rs.getInt(1)).isEqualTo(20);
        }
        // Flyway 元数据：空库路径按版本号顺序应用**全部**脚本（baseline-on-migrate 只对非空 schema 生效）。
        // ⚠️ 断言全部版本而非只看首行：只看首行时，"新增的 V2/V3/V4/V5 没被应用"是**察觉不到的**
        //（首行永远是 V1）—— 那正是这条锚点最该拦住的情形。
        // V3 = favorite_count 存量回填（数据迁移，无结构变化 ⇒ 表计数不含它）；
        // V4 = 收藏三期 T17 的 watch_later 建表（结构性 ⇒ 表计数 +1）；
        // V5 = 投币专项 T36 的两条 ALTER + content_coin 建表（结构性 ⇒ 表计数 +1）。
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery(
                     "SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank")) {
            List<String> versions = new ArrayList<>();
            List<String> types = new ArrayList<>();
            List<Boolean> successes = new ArrayList<>();
            while (rs.next()) {
                versions.add(rs.getString("version"));
                types.add(rs.getString("type"));
                successes.add(rs.getBoolean("success"));
            }
            assertThat(versions).as("脚本清单（每加一个 V<n> 都要在这里显式认账）")
                    .containsExactly("1", "2", "3", "4", "5");
            assertThat(types).containsOnly("SQL");
            assertThat(successes).containsOnly(true);
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
