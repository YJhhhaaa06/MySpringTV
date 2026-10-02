package io.github.yjhhhaaa06.videoweb.upload.model;

/**
 * 单次文件落盘的结果（承接 TV {@code com.itheima.upload.model.vo.UploadResult}）。
 *
 * <p>两个值各有用途，**不可互相推导**：
 * <ul>
 *   <li>{@code url} —— 应用内相对路径（{@code /upload/{dir}/{uuid}{ext}}），写进
 *       {@code content_media.url}；</li>
 *   <li>{@code absolutePath} —— 磁盘绝对路径，**仅供失败回滚时删除**（{@code deleteFileQuietly}）。
 *       它是"落盘与 DB 提交无法原子"这一旧口径的补偿依据（见《事务边界决策表》§四·S7）。</li>
 * </ul>
 *
 * @param url          应用内相对路径（对外 URL 由 {@code video.media.base-url} 前缀拼出）
 * @param absolutePath 磁盘绝对路径（正斜杠归一，便于跨平台比较/删除）
 */
public record UploadResult(String url, String absolutePath) {
}