package io.github.yjhhhaaa06.videoweb.content.model.dto;

import java.util.List;

/**
 * 媒体引用清单：一次创建 / 编辑提交的**整份目标状态**。
 *
 * <p>不是文件本身，而是 upload-only 端点返回的**不可变相对 URL**（{@code /upload/{dir}/{uuid}}）。
 * 因此"保存"不重传字节，只覆盖引用关系；未被清单选中的旧 URL 在事务提交后删除物理文件。
 *
 * <h2>★ 出现即整份覆盖（不是增量）</h2>
 * 请求体里出现 {@code media} 对象（哪怕只是 {@code {}}）= "按本清单重写该内容的全部媒体行"：
 * <ul>
 *   <li>{@code imageUrls} 缺省 / {@code null} / {@code []} ⇒ 图文没有图片（清空）；</li>
 *   <li>{@code coverUrl} 缺省 / {@code null} ⇒ 没有显式封面（图文允许；视频不允许）；</li>
 *   <li>{@code videoUrl} 缺省 / {@code null} ⇒ 没有视频（图文必须如此；视频不允许）。</li>
 * </ul>
 * 只想改标题 / 简介时必须**不传** {@code media}（{@code null} 与缺省同义）——两者语义不同，
 * 这是"改文案"与"清空媒体"的唯一区分方式。
 *
 * <p>字段在类型维度上的硬约束由 {@code ContentMediaManifestService#normalize} 校验：
 * 视频必须恰好有视频 + 封面、不能带图片；图文恰好相反。
 *
 * @param videoUrl 视频相对 URL（可带 {@code video.media.base-url} 前缀，服务端会先剥前缀）
 * @param coverUrl 封面相对 URL
 * @param imageUrls 正文图片相对 URL，**数组顺序即展示顺序**（落库后重写为 sort 1..n）
 */
public record MediaManifest(String videoUrl, String coverUrl, List<String> imageUrls) {

    public MediaManifest {
        // 请求体里 imageUrls 缺省（null）与空数组同义：都是"没有图片"。
        // ⚠️ 不在这里过滤 null 元素：那属于参数错误，由 normalize 给出可定位的 400。
        imageUrls = imageUrls == null ? List.of() : imageUrls;
    }
}
