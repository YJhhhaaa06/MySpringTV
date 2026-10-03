package io.github.yjhhhaaa06.videoweb.admin.controller;

import io.github.yjhhhaaa06.videoweb.comment.service.CommentService;
import io.github.yjhhhaaa06.videoweb.common.log.AuditLog;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维接口：评论删除（S8）。仅管理员可访问。
 *
 * <p>迁移自 TV {@code com.itheima.admin.controller.AdminCommentController}。
 * <b>零新逻辑</b>——{@code CommentService.deleteCommentByAdmin} 在 S2 就交付了
 * （当时无端点暴露，理由见该方法的 Javadoc：它是 {@code doDeleteComment} 的 {@code isAdmin}
 * 分支唯一入口，砍掉即砍掉分支语义）。本切片只补端点。
 *
 * <h2>与用户自删的区别</h2>
 * 用户自删（{@code /comment/delete}）只允许删**自己的**评论（非本人 → 403）；
 * 管理员删除**可删任意评论**（{@code deleteCommentByAdmin(commentId)} 不接收 userId，
 * 内部以 {@code ownerId=0, isAdmin=true} 调私有 {@code doDeleteComment}）。
 *
 * <p>类级 {@link RequiresLogin} 的理由见 {@code AdminContentController} 的类注释
 * （缺它时匿名请求会得到 403 而非契约要求的 401）。
 */
@RestController
@RequestMapping("/api/admin/comment")
@RequiresLogin
public class AdminCommentController {

    private final CommentService commentService;

    public AdminCommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    /**
     * 管理员删除评论（{@code POST /api/admin/comment/delete?commentId=X}）：软删除 + 计数回减 + 缓存失效。
     *
     * <p>评论不存在 → 404；缺 {@code commentId} → 400。
     *
     * <p><b>T2 审计（B12）</b>：审计行在服务方法返回（= 事务已提交）之后写，
     * 口径与理由见 {@code AdminContentController#hide}。
     */
    @PostMapping("/delete")
    public ApiResponse<String> delete(@CurrentUserId long adminId, @RequestParam long commentId) {
        commentService.deleteCommentByAdmin(commentId);
        AuditLog.success("admin.comment.delete", adminId, "commentId:" + commentId);
        return ApiResponse.success("删除成功");
    }
}
