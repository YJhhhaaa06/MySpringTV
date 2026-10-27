package io.github.yjhhhaaa06.videoweb.support;

import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.dockerclient.TransportConfig;

import java.net.URI;
import java.util.Locale;

/**
 * Windows 下 Docker Desktop 的 npipe 策略（绕过 Testcontainers 2.0.5 的坏预检）。
 *
 * <h2>为什么需要它（踩过的坑）</h2>
 * Testcontainers 2.0.5 的 {@code DockerClientProviderStrategy.test()} 对 {@code npipe://}
 * 地址先做 {@code new File(uri.getPath()).exists()} 预检。Windows 命名管道不是文件系统条目，
 * Java 的 {@code File.exists()} 对它恒为 {@code false}（本机 OpenJDK 25 实测；自建管道同样 false）。
 * 于是内置的 {@code NpipeSocketClientProviderStrategy} 永远过不了预检，报：
 *
 * <pre>
 * DOCKER_HOST socket file '//./pipe/docker_engine' does not exist
 * strategy NpipeSocketClientProviderStrategy did not pass the test
 * Could not find a valid Docker environment ... Attempted configurations were:
 * </pre>
 *
 * <p>本类只把这条不可用的预检短路掉；真正的连通性仍由 Testcontainers 紧随其后的
 * {@code infoCmd().exec()} 验证——Docker 没起来时它照样会失败并继续尝试其它策略。
 *
 * <h2>为什么用 ServiceLoader 注册</h2>
 * 在 {@code src/test/resources/META-INF/services/...DockerClientProviderStrategy} 中登记，
 * 由 {@code DockerClientFactory} 的 {@code ServiceLoader.load(...)} 自动发现：
 * 不依赖 {@code ~/.testcontainers.properties}（那是机器级配置，别的项目也会读到），
 * 也不需要 IDE/Maven 额外设环境变量。
 *
 * <p>仅 Windows 生效（{@link #isApplicable()}）；Linux/CI 会跳过本类，继续走正常的 unix socket 发现。
 * 不把策略写回 {@code ~/.testcontainers.properties}（{@link #isPersistable()} 返回 false），
 * 避免污染机器级配置。等 Testcontainers 官方修复后可整体删除本类与 service 登记文件。
 */
public final class WindowsDockerNpipeStrategy extends DockerClientProviderStrategy {

    private static final URI DOCKER_DESKTOP_NPIPE =
            URI.create("npipe:////./pipe/dockerDesktopLinuxEngine");

    @Override
    public TransportConfig getTransportConfig() {
        return TransportConfig.builder().dockerHost(DOCKER_DESKTOP_NPIPE).build();
    }

    @Override
    protected boolean test() {
        // 2.0.5 的 npipe 预检（File.exists）在 Windows 上必假；真实连通性由随后的 infoCmd() 验证。
        return true;
    }

    @Override
    protected boolean isApplicable() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    @Override
    protected boolean isPersistable() {
        return false;
    }

    @Override
    protected int getPriority() {
        return 100;
    }

    @Override
    public String getDescription() {
        return "Docker Desktop npipe (preflight bypassed)";
    }
}
