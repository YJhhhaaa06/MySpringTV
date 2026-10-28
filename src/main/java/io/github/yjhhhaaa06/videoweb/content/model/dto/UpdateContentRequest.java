package io.github.yjhhhaaa06.videoweb.content.model.dto;

/**
 * 作者编辑作品请求（{@code PUT /api/v1/contents/{id}}）。
 *
 * <h2>★ {@code media} 的"出现即整份覆盖"</h2>
 * 媒体字段收在一个**可空的子对象**里，用"对象在不在"区分两种意图：
 * <ul>
 *   <li>{@code media} 缺省 / {@code null} ⇒ **不动媒体**（只改文案；兼容旧的只传
 *       {@code title}/{@code description} 的调用方）；</li>
 *   <li>{@code media} 出现（含 {@code {}}）⇒ 按 {@link MediaManifest} **整份重写**
 *       该内容的媒体行：清单里没有的旧图片/封面删除，数组顺序即图片 {@code sort} 1..n。</li>
 * </ul>
 * 因此"清空图片"必须显式提交 {@code "media": {}} 或 {@code "media": {"imageUrls": []}}，
 * 不能靠"不传"来表达（不传 = 不改）。
 *
 * <p>{@code categoryId} 同理：{@code null} = 不改；0~9 = 改成该分区。
 *
 * <p>标题 / 简介的校验在 {@code ContentService#updateContent}：
 * 标题去空白后非空且 ≤50；简介按**内容类型**取上限（视频 1000 / 图文 10000，修 GAP-31），
 * 空简介落空串（与发布侧落 {@code "-"} 的旧口径不同，见编辑页的展示规则）。
 */
public record UpdateContentRequest(String title, String description,
                                   Integer categoryId, MediaManifest media) {
}
