package io.github.yjhhhaaa06.videoweb.content.model.dto;

import io.github.yjhhhaaa06.videoweb.content.model.ContentType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建作品请求（{@code POST /api/v1/contents}，JSON 清单）。
 *
 * <p>与原 multipart 发布端点的区别只有一处：**文件不在这里**。先调
 * {@code POST /api/v1/uploads/{type}} 拿到 URL，再把整份 {@link MediaManifest} 提交到这里；
 * 服务端校验"资源存在 + 类型匹配 + 未被别的作品占用"后，在一个事务里建内容行 + 媒体行。
 *
 * <p>校验分两层（与旧发布路径同口径）：
 * <ul>
 *   <li><b>声明式</b>：{@code type} / {@code title} / {@code categoryId} / {@code media} 必填，
 *       标题 ≤50，分区 0~9；</li>
 *   <li><b>业务</b>：简介长度按类型（视频 1000 / 图文 10000）、空简介落 {@code "-"}、
 *       媒体清单的类型约束与占用校验；文案由 {@code UploadController} 与
 *       {@code ContentMediaManifestService} 给出。</li>
 * </ul>
 *
 * @param type        内容类型（JSON 用枚举名 {@code "VIDEO"} / {@code "POST"}）
 * @param title       标题（去首尾空白后落库）
 * @param description 简介；{@code null} / 空白 ⇒ 落 {@code "-"}（与旧发布口径一致）
 * @param categoryId  分区（旧项目的 0~9 单数字分区体系）
 * @param media       整份媒体引用清单
 */
public record CreateContentRequest(

        @NotNull(message = "内容类型不能为空")
        ContentType type,

        @NotBlank(message = "标题不能为空")
        @Size(max = 50, message = "标题不得超过50字")
        String title,

        String description,

        @NotNull(message = "请选择分区")
        @Min(value = 0, message = "暂时没有这个分区")
        @Max(value = 9, message = "暂时没有这个分区")
        Integer categoryId,

        @NotNull(message = "媒体清单不能为空")
        MediaManifest media
) {
}
