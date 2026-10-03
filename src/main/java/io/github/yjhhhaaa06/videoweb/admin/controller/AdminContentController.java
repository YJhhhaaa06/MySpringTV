package io.github.yjhhhaaa06.videoweb.admin.controller;

import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.content.model.vo.AdminContentVO;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
 *       <td>{@code @RestController} + {@code @RequestMapping("/api/admin/content")} + 三个映射方法</td></tr>
 *   <tr><td>{@code parseContentId(req, resp)}：手工空判 / parseLong / 手写错误响应</td>
 *       <td>{@code @RequestParam long contentId}（缺参 / 非数字由全局出口 → 400，见《决策留痕表》D-12）</td></tr>
 *   <tr><td>{@code AuditLog.success("admin.content.hide", …)}</td>
 *       <td>**不搬**——《迁移参照系》§三「不迁移清单」（《决策留痕表》B-18）</td></tr>
 *   <tr><td>{@code AuthFilter} 对 {@code /api/admin} 判 non-null + {@code role==1}</td>
 *       <td>**类级** {@link RequiresLogin}（登录）+ 既有 {@code JwtAuthFilter.isAdminPath}（角色）</td></tr>
 * </table>
 *
 * <h2>★ 为什么必须加类级 {@link RequiresLogin}（不能省）</h2>
 * 角色判定（{@code role == 1}）早在 S6-B1 就落到了 {@code JwtAuthFilter.isAdminPath → AdminChecker}，
 * 本切片**零鉴权改动**。但 {@code JwtAuthFilter} 的三段是**有序**的：
 * <pre>
 * ① 解析 token
 * ② 需要登录却无身份 ⇒ 401        ← 依据 @RequiresLogin
 * ③ /api/admin 前缀   ⇒ 非管理员 / 匿名 ⇒ 403
 * </pre>
 * 若不加 {@link RequiresLogin}，**匿名**请求会在第 ② 段被跳过、直接掉进第 ③ 段判成 **403**，
 * 而 TV 是 **401**（{@code /api/admin} 在 {@code PROTECTED_PREFIXES} 里，先触发登录判定）。
 * 即"不用加也能拦住"是假象——**拦得对，但给错了状态码**。
 * 旧 pytest 对匿名 401 / 非管理员 403 有**成对**断言，故这一步是契约要求，不是保险。
 */
@RestController
@RequestMapping("/api/admin/content")
@RequiresLogin
public class AdminContentController {

    private final ContentService contentService;

    public AdminContentController(ContentService contentService) {
        this.contentService = contentService;
    }

    /**
     * 管理端内容清单（{@code GET /api/admin/content/list}）：含正常与已下架，不含已删除。
     *
     * <p>返回 {@code List<AdminContentVO>}，每个条目固定五键
     * {@code {id, title, type, authorName, hidden}}（旧 pytest 的 shape 断言）。
     */
    @GetMapping("/list")
    public ApiResponse<List<AdminContentVO>> list() {
        return ApiResponse.success(contentService.listContentForAdmin());
    }

    /**
     * 下架内容（{@code POST /api/admin/content/hide?contentId=X}）：{@code is_deleted} 0→2。
     *
     * <p>不存在 → 404「内容不存在」；已删除 → 409「内容已删除，无法下架」；
     * 已下架 → 409「内容已下架」；缺 {@code contentId} → 400。
     * 提交后内容/评论/点赞三处缓存失效（由既有订阅方承接，见 {@code ContentService.hideContent}）。
     */
    @PostMapping("/hide")
    public ApiResponse<String> hide(@RequestParam long contentId) {
        contentService.hideContent(contentId);
        return ApiResponse.success("下架成功");
    }

    /**
     * 恢复内容（{@code POST /api/admin/content/unhide?contentId=X}）：{@code is_deleted} 2→0。
     *
     * <p>不存在 → 404；已删除 → 409「内容已删除，无法恢复」；未下架 → 409「内容未下架」；
     * 缺 {@code contentId} → 400。提交后重载内容缓存 + 索引（前台立即重新可见）。
     */
    @PostMapping("/unhide")
    public ApiResponse<String> unhide(@RequestParam long contentId) {
        contentService.unhideContent(contentId);
        return ApiResponse.success("恢复成功");
    }
}
