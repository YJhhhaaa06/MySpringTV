package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 媒体资源 URL 配置（S5）。
 *
 * <h2>它替代了 TV 的什么</h2>
 * TV 的 {@code ContentCache.jointUrl(url)} = {@code RequestContext.getContextPath() + url}——
 * 其中 {@code RequestContext} 是一个 {@code ThreadLocal} 持有"当前请求的 Servlet context path"，
 * 非请求线程（启动刷新）回退到应用启动时记录的默认值。存在的理由是：TV 把应用部署在
 * {@code /MyApp} 这样的 context path 下，而库里存的 {@code content_media.url} 是
 * {@code /upload/video/xxx.mp4} 这样的**应用内相对路径**，对外要拼上前缀。
 *
 * <p>本项目是 Boot 内嵌容器、默认无 context path ⇒ 旧前缀恒为 {@code ""}。
 * 与其搬一个 {@code ThreadLocal} + Filter（净增两个类，且需要正确的 set/clear 纪律，
 * 真实用途却是恒空串），不如把它变成一个**配置项**：语义等价（都是"对外可见的 URL 前缀"），
 * 而部署形态变化时无需改代码。
 *
 * <p>⚠️ 默认值必须保持 {@code ""}：改它会让 {@code /search/IdSearch} 的 {@code videoUrl} /
 * {@code imageUrls} 形状变化（旧 pytest {@code S-09} 会去磁盘找 {@code <STONE_DIR>/video/xxx.mp4}，
 * 即断言 URL 以 {@code /upload/} 开头）。
 *
 * @param baseUrl 媒体 URL 前缀，默认空串（等价 TV 的空 context path）
 */
@ConfigurationProperties(prefix = "video.media")
public record MediaProperties(String baseUrl) {

    public MediaProperties {
        if (baseUrl == null) {
            baseUrl = "";
        }
    }
}
