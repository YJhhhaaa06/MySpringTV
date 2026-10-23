package io.github.yjhhhaaa06.videoweb.admin.controller;

import io.github.yjhhhaaa06.videoweb.common.log.AuditLog;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresAdmin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.content.model.vo.AdminContentVO;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运维接口：内容审核下架 / 恢复（S8）。仅管理员可访问。
 *
 * <p>迁移自 TV {@code com.itheima.admin.controller.AdminContentController}。
 * <table>
 *   <caption>TV → 新实现</caption>
 *   <tr><th>TV</th><th>新实现</th></tr>
 *   <tr><td>{@code @WebServlet("/api/admin/content/*")} + {@code "list".equals(pathInfo)} 等分支</td>
 *       <td>{@code @RestController} + 三个映射方法（`T58` 起方法上写全路径，见下）</td></tr>
 *   <tr><td>{@code parseContentId(req, resp)}：手工空判 / parseLong / 手写错误响应</td>
 *       <td>{@code @PathVariable("id") long contentId}（非数字由全局出口 → 400，见《决策留痕表》D-12）</td></tr>
 *   <tr><td>{@code AuditLog.success("admin.content.hide", …)}</td>
 *       <td>**T2 已补**（2026-10-03）：{@code common.log.AuditLog}（原记"不搬"随之作废——
 *           那正是《遗留台账》B12 说的"只写了动作没写后果"）</td></tr>
 *   <tr><td>{@code AuthFilter} 对 {@code /api/admin} 判 non-null + {@code role==1}</td>
 *       <td>**类级** {@link RequiresAdmin}（登录 + 角色，一个注解两件事）</td></tr>
 * </table>
 *
 * <h2>★ 为什么是类级 {@link RequiresAdmin}（一个注解顶替原先的两处判据）</h2>
 * 2026-10-18 起"仅管理员"由本注解一处表达，{@code JwtAuthFilter} 的三段变为：
 * <pre>
 * ① 解析 token
 * ② {@code @RequiresAdmin} 蕴含"需要登录" ⇒ 匿名 ⇒ 401
 * ③ {@code @RequiresAdmin} ⇒ 非管理员 ⇒ 403
 * </pre>
 * 旧实现是「{@code /api/admin} **路径前缀**判角色」＋「额外标 {@code @RequiresLogin} 让匿名先得 401」——
 * 两个判据分散在两处，且有陷阱：漏标 {@code @RequiresLogin} 时匿名请求会跳过登录段、
 * 掉进角色段得 **403**（契约要求 **401**，"拦得对但状态码错"）。
 * 现在"仅管理员"在语义上必然包含"需登录"，401/403 的分工**不再依赖两个注解同时写对**；
 * 前缀判据已删除（授权规则与路由匹配是同一个事实，见 {@link RequiresAdmin} 类注释）。
 * 匿名 401 / 非管理员 403 的成对契约仍由 {@code AdminSecurityTests} 钉住。
 *
 * <p>`T58` 契约变更（接口契约.md §2.3/§2.4/§2.8）：内容 = 资源、下架 = 设 {@code hidden} 状态、
 * 恢复 = 撤状态 —— {@code POST /hide|/unhide} 退役为
 * {@code PUT|DELETE /api/v1/admin/contents/{id}/hidden}（contentId 进路径，
 * 缺参分支随进路径退役；动词 hide/unhide 由名词状态 hidden 取代）。
 */
@RestController
@RequiresAdmin
public class AdminContentController {

    private final ContentService contentService;

    public AdminContentController(ContentService contentService) {
        this.contentService = contentService;
    }

    /**
     * 管理端内容清单（{@code GET /api/v1/admin/contents}）：含正常与已下架，不含已删除。
     *
     * <p>返回 {@code List<AdminContentVO>}，每个条目固定五键
     * {@code {id, title, type, authorName, hidden}}（旧 pytest 的 shape 断言）。
     */
    @GetMapping("/api/v1/admin/contents")
    public ApiResponse<List<AdminContentVO>> list() {
        return ApiResponse.success(contentService.listContentForAdmin());
    }

    /**
     * 下架内容（{@code PUT /api/v1/admin/contents/{id}/hidden}）：{@code is_deleted} 0→2。
     *
     * <p>不存在 → 404「内容不存在」；已删除 → 409「内容已删除，无法下架」；
     * ★ `T59` 起已下架 → <b>200 no-op</b>（幂等设置；不再 409「内容已下架」）。
     * 提交后内容/评论/点赞三处缓存失效（由既有订阅方承接，见 {@code ContentService.hideContent}）。
     *
     * <p><b>T2 审计（B12）</b>：审计行写在 `contentService.hideContent` **返回之后**——
     * 服务方法是 {@code @Transactional}，调用返回即已提交 ⇒ 审计记录**不会**先于事实产生
     * （若把审计写进事务方法体内，提交失败会留下一条"改过"的假留痕，那比没有留痕更坏）。
     */
    @PutMapping("/api/v1/admin/contents/{id}/hidden")
    public ApiResponse<Void> hide(@CurrentUserId long adminId, @PathVariable("id") long contentId) {
        contentService.hideContent(contentId);
        AuditLog.success("admin.content.hide", adminId, "contentId:" + contentId);
        return ApiResponse.success();
    }

    /**
     * 恢复内容（{@code DELETE /api/v1/admin/contents/{id}/hidden}）：{@code is_deleted} 2→0。
     *
     * <p>不存在 → 404；已删除 → 409「内容已删除，无法恢复」；
     * ★ `T59` 起未下架 → <b>204 no-op</b>（幂等取消；不再 409「内容未下架」）。
     * 提交后重载内容缓存 + 索引（前台立即重新可见）。
     */
    @DeleteMapping("/api/v1/admin/contents/{id}/hidden")
    public ResponseEntity<Void> unhide(@CurrentUserId long adminId, @PathVariable("id") long contentId) {
        contentService.unhideContent(contentId);
        AuditLog.success("admin.content.unhide", adminId, "contentId:" + contentId);
        return ResponseEntity.noContent().build();
    }
}
