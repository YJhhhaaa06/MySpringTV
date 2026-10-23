package io.github.yjhhhaaa06.videoweb.admin.controller;

import io.github.yjhhhaaa06.videoweb.admin.model.audit.MediaAuditResult;
import io.github.yjhhhaaa06.videoweb.admin.model.audit.RestoreResult;
import io.github.yjhhhaaa06.videoweb.admin.service.MediaAuditService;
import io.github.yjhhhaaa06.videoweb.common.log.AuditLog;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresAdmin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 运维接口：媒体资源扫描与恢复（S8）。仅管理员可访问。
 *
 * <p>迁移自 TV {@code com.itheima.admin.controller.MediaAdminController}。
 * <table>
 *   <caption>TV → 新实现</caption>
 *   <tr><th>TV</th><th>新实现</th></tr>
 *   <tr><td>{@code @MultipartConfig(maxFileSize=50MB, maxRequestSize=100MB)}</td>
 *       <td>{@code spring.servlet.multipart.*}（S7 已配，值相同）</td></tr>
 *   <tr><td>{@code req.getPart("file")}</td><td>{@code @RequestPart(value="file", required=false) MultipartFile}</td></tr>
 *   <tr><td>{@code parseMediaId(req)} 抛 {@code ParamException("mediaId 格式错误")}</td>
 *       <td>{@code @PathVariable("id") long mediaId}（非数字 → 全局 400，见《决策留痕表》D-12）</td></tr>
 *   <tr><td>{@code AuditLog.success("admin.media.restore", …)}</td>
 *       <td>**T2 已补**（2026-10-03）：{@code common.log.AuditLog}（原记"不搬"随之作废，
 *           见《遗留台账》B12）</td></tr>
 * </table>
 *
 * <h2>★ 为什么 {@code file} 是 {@code required=false}</h2>
 * "缺文件"必须是 **400 且文案为"请选择要恢复的文件"**（TV 的口径），而不是 Spring 的缺 part 错误。
 * 故让 part 可缺省、由 {@code MediaAuditService.restoreMedia} 显式判
 * {@code null || size<=0} 抛 {@code ParamException} —— 这样文案与旧版逐字一致。
 *
 * <p>类级 {@link RequiresAdmin} 的理由见 {@code AdminContentController} 的类注释。
 *
 * <h2>两个端点的实现是同一个 {@code scanAll}</h2>
 * {@code GET /api/v1/admin/media} 与 {@code POST /api/v1/admin/media/scan} 都调 {@code scanAll}——TV 原样。
 * 两者**都会写库**（回写 {@code file_exists}）；用 GET 触发写操作不符合 HTTP 语义，
 * 但这是旧契约（旧 pytest 对两个端点各有断言），**有意保持**。
 *
 * <p>`T58` 契约变更（接口契约.md §2.3/§2.4）：媒体清单 = {@code GET /api/v1/admin/media}（原
 * {@code GET /api/admin/media/list}）；身份探测 = {@code GET /api/v1/admin/me}（原
 * {@code GET /api/admin/media/me}，media 段去掉——它探测的是"管理员身份"而非媒体资源）；
 * scan 为运维动作保留 POST（§2.3 例外清单，权重 10）；恢复是幂等设置 ⇒
 * {@code PUT /api/v1/admin/media/{id}}（mediaId 进路径，缺参分支随进路径退役）。
 */
@RestController
@RequiresAdmin
public class MediaAdminController {

    private final MediaAuditService mediaAuditService;

    public MediaAdminController(MediaAuditService mediaAuditService) {
        this.mediaAuditService = mediaAuditService;
    }

    /**
     * 管理员身份探测（{@code GET /api/v1/admin/me}）。
     *
     * <p>TV 直接 {@code writeSuccess(resp, true)}，**无 Service**——前端拿它当"我是不是管理员"用
     * （能到这里就说明 {@code role==1} 已过）。本实现同：恒返回 {@code data: true}。
     * 零业务逻辑，不建 Service 层（与 {@code /start} 的"不硬抽层"同款自律）。
     */
    @GetMapping("/api/v1/admin/me")
    public ApiResponse<Boolean> me() {
        return ApiResponse.success(true);
    }

    /** 媒体扫描（{@code GET /api/v1/admin/media}）：见类注释——与 {@code /scan} 同一实现。 */
    @GetMapping("/api/v1/admin/media")
    public ApiResponse<MediaAuditResult> list() {
        return ApiResponse.success(mediaAuditService.scanAll());
    }

    /** 媒体扫描（{@code POST /api/v1/admin/media/scan}）：同 {@link #list()}。 */
    @PostMapping("/api/v1/admin/media/scan")
    public ApiResponse<MediaAuditResult> scan() {
        return ApiResponse.success(mediaAuditService.scanAll());
    }

    /**
     * 媒体恢复（{@code PUT /api/v1/admin/media/{id}} + 文件 part {@code file}）：
     * 按 DB 里的原文件名写回磁盘。
     *
     * <p>缺文件 / 空文件 → 400「请选择要恢复的文件」；
     * 媒体不存在 → 404；URL 非法或扩展名不匹配 → 400。
     *
     * <p><b>T2 审计（B12）</b>：审计行在服务方法返回（= 写入已提交）之后写，
     * 口径与理由见 {@code AdminContentController#hide}。
     */
    @PutMapping("/api/v1/admin/media/{id}")
    public ApiResponse<RestoreResult> restore(@CurrentUserId long adminId,
                                             @PathVariable("id") long mediaId,
                                             @RequestPart(value = "file", required = false) MultipartFile file) {
        RestoreResult result = mediaAuditService.restoreMedia(mediaId, file);
        AuditLog.success("admin.media.restore", adminId, "mediaId:" + mediaId);
        return ApiResponse.success(result);
    }
}
