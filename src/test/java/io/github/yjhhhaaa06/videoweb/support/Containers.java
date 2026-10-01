package io.github.yjhhhaaa06.videoweb.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 测试容器的**进程级单例**。
 *
 * <h2>为什么必须是单例（踩过的坑）</h2>
 * 最初把容器写成 {@code AbstractIntegrationTest} 的 {@code static} 字段 + {@code @Testcontainers}。
 * 结果：每个测试类都会**重新初始化该字段**（抽象基类的 static 字段并非按子类共享），
 * 于是每个测试类都起一个**新容器、新映射端口**；而 Spring 的上下文是缓存的，
 * {@code @DynamicPropertySource} 只在上下文首次创建时执行一次，
 * 后续测试类拿到的是**旧端口**，连接全部超时：
 *
 * <pre>
 * Caused by: java.sql.SQLTransientConnectionException:
 *   MySpringTV-Hikari - Connection is not available, request timed out after 5001ms
 * Caused by: com.mysql.cj.jdbc.exceptions.CommunicationsException: Communications link failure
 * </pre>
 *
 * <p>改为本类的静态持有者后，容器在**整个测试 JVM 内只启动一次**、端口恒定，
 * 与缓存的 Spring 上下文一致。同时容器**不再随测试类结束而停止**（由 Ryuk 在 JVM 退出时回收）——
 * 这正是我们要的：多个测试类共享同一套容器。
 *
 * <h2>容器版本</h2>
 * 与开发约定一致：mysql 8.4、rabbitmq 4.3-management、redis 7-alpine。
 * Redis 无官方 Testcontainers 2.0 模块，用 {@link GenericContainer}（见 pom 注释）。
 */
public final class Containers {

    public static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("tvdatabase")
                    .withUsername("test")
                    .withPassword("test");

    public static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3-management-alpine"));

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        // 显式启动：不依赖 @Testcontainers 的按类生命周期（见类注释）
        MYSQL.start();
        RABBITMQ.start();
        REDIS.start();
    }

    private Containers() {
    }

    public static int mysqlPort() {
        return MYSQL.getMappedPort(3306);
    }

    public static int rabbitPort() {
        return RABBITMQ.getAmqpPort();
    }

    public static int redisPort() {
        return REDIS.getMappedPort(6379);
    }

    /**
     * 断言"确实连在测试容器上，而不是宿主开发库"。
     *
     * <p>开发态 {@code application.yaml} 的数据源默认指向宿主原生 mysqld:3306。
     * 测试若因故没覆盖成功，会**静默**读到真实数据、甚至写坏它——不报错，只是结果诡异。
     * 故把它变成一条可执行的断言。
     */
    public static void assertConnectedToContainer(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.contains(":" + mysqlPort())) {
            throw new AssertionError(
                    "测试未连到 Testcontainers 实例！实际 URL=" + jdbcUrl
                            + "，期望包含容器映射端口 " + mysqlPort()
                            + "。这会静默读写宿主开发库，必须修复。");
        }
    }
}
