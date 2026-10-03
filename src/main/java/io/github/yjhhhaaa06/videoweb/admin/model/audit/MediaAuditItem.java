package io.github.yjhhhaaa06.videoweb.admin.model.audit;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 媒体审计的**单条明细**（承接 TV {@code com.itheima.admin.model.audit.MediaAuditItem}）。
 *
 * <p>它不是一行 DB 记录的直接映射——{@code contentTitle} / {@code expectedPath} / {@code status}
 * 都是 {@code MediaAuditService.scanAll} 在扫描时算出来的（JOIN 标题、URL→路径解析、存在性判定）。
 * JSON 键集逐字保留（旧 pytest 对 {@code mediaId} / {@code status} / {@code url} /
 * {@code expectedPath} 有读取）。
 */
@Data
public class MediaAuditItem {

    /** {@code content_media.id}。 */
    private long mediaId;

    /** 所属内容 id（**可能是孤儿**——该 id 在 {@code content} 表里不存在）。 */
    private long contentId;

    /** 所属内容标题；孤儿媒体为 {@code null}。 */
    private String contentTitle;

    /** 1 视频 / 2 图片 / 3 封面。 */
    private int type;

    /** 应用内相对 URL（如 {@code /upload/video/xxx.mp4}）。 */
    private String url;

    /** 文件是否存在于磁盘。 */
    private boolean fileExists;

    /** 本次校验时间（= 扫描时刻）。 */
    private LocalDateTime lastVerifyTime;

    /** URL 解析出的**期望磁盘绝对路径**；URL 非法（不匹配形状 / 含 {@code ..}）时为 {@code null}。 */
    private String expectedPath;

    /** {@code EXISTS} / {@code MISSING} / {@code INVALID_URL}（TV 的三个字面量，逐字保留）。 */
    private String status;
}
