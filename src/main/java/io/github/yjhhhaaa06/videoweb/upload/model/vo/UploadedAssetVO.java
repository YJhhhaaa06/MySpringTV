package io.github.yjhhhaaa06.videoweb.upload.model.vo;

/**
 * 单文件上传结果（{@code POST /api/v1/uploads/{type}} 的 {@code data}）。
 *
 * <p>本端点**只落盘、不写 {@code content_media}**：返回的 {@code url} 是"尚未绑定到任何内容"
 * 的资源标识，由后续的创建 / 编辑清单提交（{@code POST /api/v1/contents}、
 * {@code PUT /api/v1/contents/{id}}）决定它归谁。未被引用的文件是**孤儿**，
 * 由离线清理脚本按"磁盘存在但库内无引用"回收，不进任何请求事务。
 *
 * @param url             应用内相对 URL（{@code /upload/{video|image|cover}/{uuid}{ext}}）；
 *                        对外完整 URL 由详情响应拼 {@code video.media.base-url}
 * @param durationSeconds 视频时长（秒，向下取整）；**只有 {@code type=video} 有值**，
 *                        其他类型为 {@code null}。解析失败 / 未知同样为 {@code null}
 *                        （≠ 0，见 {@code architecture/domain/history/history.md} §六.2）；
 *                        服务端提交清单时会重新解析，不信任客户端回传值
 */
public record UploadedAssetVO(String url, Integer durationSeconds) {
}
