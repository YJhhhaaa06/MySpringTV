package io.github.yjhhhaaa06.videoweb.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
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

    /** 真实测试素材目录（沿用老项目的外部目录；环境变量 `TV_TEST_RESOURCE_DIR` 可覆盖）。 */
    public static String testResourceDir() {
        return System.getenv().getOrDefault("TV_TEST_RESOURCE_DIR",
                "D:\\dev\\WorkSpace\\VideoPlatform\\TestResource");
    }

    /**
     * 取真实测试素材文件；**缺失即失败，不降级**。
     *
     * <h2>为什么用外部目录 + 显式失败</h2>
     * 素材是**真实可解码**的媒体（mp4/png/jpg，共约 9MB），供将来的**转码切片**使用。
     * 大二进制不入 git（git 对二进制不做 diff，替换即全量新 blob 且永久留在历史 ⇒ 仓库膨胀），
     * 故沿用老项目的外部目录约定。老项目在缺失时会**静默降级成 1KB 合成素材**——
     * 本仓刻意去掉该降级：合成素材在转码场景会让用例**假绿**，宁可在此显式失败
     * （见《决策留痕表》B-15）。
     *
     * @throws AssertionError 素材缺失（错误信息直接给出修复方式）
     */
    public static Path testResource(String fileName) {
        Path path = Path.of(testResourceDir()).resolve(fileName);
        if (!Files.isRegularFile(path)) {
            throw new AssertionError("测试素材缺失: " + path
                    + "。请设置环境变量 TV_TEST_RESOURCE_DIR 指向含 "
                    + "{test_video.mp4, test_cover.png, test_image.jpg} 的目录"
                    + "（本仓刻意不把大二进制素材入库，见《决策留痕表》B-15）");
        }
        return path;
    }
}
