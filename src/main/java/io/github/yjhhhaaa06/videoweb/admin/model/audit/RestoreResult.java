package io.github.yjhhhaaa06.videoweb.admin.model.audit;

import lombok.Data;

/**
 * 媒体恢复结果（承接 TV {@code com.itheima.admin.model.audit.RestoreResult}）。
 *
 * <p>JSON 键集逐字保留；旧 pytest 断言 {@code data.fileExists is True}。
 */
@Data
public class RestoreResult {

    /** {@code content_media.id}。 */
    private long mediaId;

    /** 该媒体的应用内相对 URL（**原样**，未变）。 */
    private String url;

    /** 写回目标的磁盘绝对路径（反斜杠统一转正斜杠，TV 原样）。 */
    private String targetPath;

    /** 写回后的文件字节数。 */
    private long size;

    /** 恒为 {@code true}（恢复成功的定义就是文件已写回）。 */
    private boolean fileExists;
}
