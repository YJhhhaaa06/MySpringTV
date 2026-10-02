package io.github.yjhhhaaa06.videoweb.content.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 媒体行类型（承接 TV {@code com.itheima.content.model.entity.ContentMedia}）。
 *
 * <p>列：{@code id / content_id / url / type / sort}（{@code type}: 1 视频 / 2 图片 / 3 封面）。
 *
 * <p>三个用途：
 * <ol>
 *   <li>{@code ContentCache} 把内容全部媒体行按 {@code type} 分组，拼出 coverUrl / videoUrl / imageUrls；</li>
 *   <li>{@code deleteMedia} 按 {@code (contentId, type, sort)} 定位一行并删；</li>
 *   <li>{@code deleteContent} 取全部行拿到 url 列表（交给调用方清理物理文件——
 *       S5 曾**有意裁剪**该清理，S7 已补回：见 {@code ContentController.delete}）。</li>
 * </ol>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContentMedia {

    /** 主键，列名 {@code id}。 */
    private Long mediaId;

    private Long contentId;

    /** 应用内相对路径（如 {@code /upload/video/xxx.mp4}）。对外 URL 由 base-url 前缀拼出。 */
    private String url;

    /** 1 视频 / 2 图片 / 3 封面。 */
    private Integer type;

    /** 同 type 内的序号（图片连续 1..n，删图后由 {@code compactImageSort} 重排）。 */
    private Integer sort;
}
