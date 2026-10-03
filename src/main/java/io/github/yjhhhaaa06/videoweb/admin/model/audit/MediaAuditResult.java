package io.github.yjhhhaaa06.videoweb.admin.model.audit;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 媒体扫描**汇总结果**（承接 TV {@code com.itheima.admin.model.audit.MediaAuditResult}）。
 *
 * <p>JSON 键集逐字保留——旧 pytest 对 8 个键全部有断言
 * （{@code test_admin.py::test_media_list_admin_200} 断言前 5 个，
 * {@code ::test_scan_admin_200_structure} 断言 8 个）。
 */
@Data
public class MediaAuditResult {

    /** 扫描时刻。 */
    private LocalDateTime scanTime;

    /** 扫描到的媒体行总数。 */
    private int total;

    /** {@code EXISTS} 条数。 */
    private int existing;

    /** {@code MISSING} 条数。 */
    private int missing;

    /** {@code INVALID_URL} 条数。 */
    private int invalid;

    /**
     * **恒为 0**——TV 的字段保留（旧实现是"分批扫描时的未扫数"，本实现一次扫完）。
     * 保留它不是遗漏：它是响应契约的键之一（旧 pytest 断言它存在）。
     */
    private int notScanned;

    /** 孤儿媒体 id（其 {@code content_id} 在 {@code content} 表里不存在）。 */
    private List<Long> orphanMediaIds;

    /** 无任何媒体行的内容 id（纯文字内容，视为完整）。 */
    private List<Long> contentsWithoutMedia;

    /** 每条媒体行的明细。 */
    private List<MediaAuditItem> items;
}
