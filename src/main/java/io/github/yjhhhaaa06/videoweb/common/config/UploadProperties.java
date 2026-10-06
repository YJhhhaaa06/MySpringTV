package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上传媒体**磁盘根目录**配置（S7）。
 *
 * <h2>它替代了 TV 的什么</h2>
 * TV 的 {@code AppConfig.getUploadPath()} ⟵ {@code app.properties} 的 {@code upload.path}
 * （默认 {@code D:/data/projects/VideoPlatform/stone}）。该目录是**上传落盘**与
 * **{@code /upload} 静态访问**的同一物理根——TV 侧后者由 Tomcat 的
 * {@code context.xml <PostResources base="${upload.path:-…}"/>} 挂载，是**另一份**配置，
 * 靠 {@code AppShutDownListener} 启动比对兜住"两份配置漂移"。
 *
 * <p>本项目中两者**读同一个属性**（落盘见 {@code upload.service.FileUploadService}，
 * 静态服务见 {@code common.config.WebMvcConfig#addResourceHandlers}）⇒
 * "挂载 = 落盘"的一致性校验**无标的**，不再需要（《决策留痕表》B-12）。
 *
 * <h2>⚠️ 与 {@link MediaProperties#baseUrl()} 严格分家</h2>
 * {@code video.media.base-url} 是**对外 URL 前缀**（默认空串，等价 TV 的空 context path）；
 * 本属性是**磁盘根**。两者语义不同、取值互不影响，**不要混为一谈**（《切片计划》§一 特别警告）。
 *
 * <h2>★ 分家已完成（2026-10-06，B-11"改一行"路线兑现）</h2>
 * DB 里存的是应用内相对 URL（{@code /upload/video/xxx.mp4}），目录结构
 * （{@code video/ image/ cover/}）不变时：**拷贝目录 → 改本属性**即可完成迁移，DB 无需改动。
 * ⚠️ 顺序必须是先拷贝后翻配置——反过来会让存量行在切换窗口里全部呈现为"文件缺失"。
 * 本次即按此顺序执行：源 {@code …/VideoPlatform/stone} → 新根
 * {@code D:/data/projects/MySpringTV/media}（96 文件 / 488,982,223 字节，逐字节一致）。
 *
 * @param root 媒体磁盘根目录（绝对路径；dev 默认指向本项目自己的 {@code MySpringTV/media}，见 {@code application.yaml}）
 */
@ConfigurationProperties(prefix = "video.upload")
public record UploadProperties(String root) {

    /**
     * 空值 fail-fast——口径沿袭 TV {@code AppShutDownListener}：
     * {@code "upload.path 未配置或为空，拒绝启动"}（那里是启动期检查，此处改为**绑定期**校验，
     * 与 {@link BigVProperties} 等本项目的配置纪律一致）。
     */
    public UploadProperties {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("video.upload.root 未配置或为空，拒绝启动");
        }
    }
}