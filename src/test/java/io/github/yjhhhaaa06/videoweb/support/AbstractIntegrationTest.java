package io.github.yjhhhaaa06.videoweb.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

/**
 * 全栈测试基类：**环境即代码**（决策⑦ / T1+E2 债）。
 *
 * <p>目标：测试自带全部外部依赖，不再要求"本机恰好跑着 docker 容器"。
 * 容器由 {@link Containers} 以**进程级单例**提供（原因见那里的注释——按类生命周期会踩端口漂移的坑）。
 *
 * <h2>⚠️ 防误连</h2>
 * 开发态 {@code application.yaml} 的数据源指向**宿主原生 mysqld:3306**。
 * 若测试未成功覆盖数据源，会静默连到开发库——不报错，只会"意外通过"或写坏真实数据。
 * 因此这里用 {@link DynamicPropertySource} **无条件覆盖** {@code spring.datasource.*}
 * （唯一入口，{@code application.yaml} 里配了什么都会被盖掉），
 * 并提供 {@link Containers#assertConnectedToContainer} 供子类做显式断言。
 *
 * <h2>Flyway 在空库上的行为</h2>
 * 容器里的库是空的。Flyway 的 {@code baseline-on-migrate} 只在**非空 schema** 上生效，
 * 空库会直接应用 V1（{@code CREATE TABLE IF NOT EXISTS} 正常建表）。测试环境因此天然走
 * "V1 建结构"这条路，与开发库的"baseline 0 + V1 成为 no-op"路径等价（详见 V1 脚本头部）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        // MySQL：用容器 JDBC URL 覆盖（含 host/port/db），单入口、无歧义
        registry.add("spring.datasource.url", Containers.MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", Containers.MYSQL::getUsername);
        registry.add("spring.datasource.password", Containers.MYSQL::getPassword);

        // Redis：GenericContainer 无 @ServiceConnection 元数据，手工指向映射端口
        registry.add("spring.data.redis.host", Containers.REDIS::getHost);
        registry.add("spring.data.redis.port", Containers::redisPort);

        // RabbitMQ：同上，手工指向映射端口（避免依赖 @ServiceConnection 的隐式行为）
        registry.add("spring.rabbitmq.host", Containers.RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", Containers::rabbitPort);
        registry.add("spring.rabbitmq.username", Containers.RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", Containers.RABBITMQ::getAdminPassword);

        // 上传媒体根（S7）：**无条件覆盖**到项目内 target/test-media——与数据源覆盖同理。
        // 理由：默认值指向真实媒体根（stone），测试上传/删除会往里面写真实文件；
        //       覆盖后测试的落盘、静态访问、物理删除断言全部发生在 target/ 内
        //       （gitignore 覆盖、mvn clean 可回收、沙箱可写）。见《决策留痕表》B-14。
        registry.add("video.upload.root", AbstractIntegrationTest::testMediaRoot);
    }

    /** 测试媒体根：`<基于仓库根的工作目录>/target/test-media`（surefire 的工作目录 = 项目根）。 */
    public static String testMediaRoot() {
        return Path.of("target", "test-media").toAbsolutePath().toString();
    }
}
