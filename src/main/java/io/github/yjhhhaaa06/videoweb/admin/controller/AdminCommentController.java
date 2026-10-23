package io.github.yjhhhaaa06.videoweb.admin.controller;

import io.github.yjhhhaaa06.videoweb.comment.service.CommentService;
import io.github.yjhhhaaa06.videoweb.common.log.AuditLog;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresAdmin;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * 用户自删（{@code DELETE /api/v1/comments/{id}}）只允许删**自己的**评论（非本人 → 403）；
 * 管理员删除**可删任意评论**（{@code deleteCommentByAdmin(commentId)} 不接收 userId，
 * 内部以 {@code ownerId=0, isAdmin=true} 调私有 {@code doDeleteComment}）。
 *
 * <p>类级 {@link RequiresAdmin} 的理由见 {@code AdminContentController} 的类注释
 * （"仅管理员"蕴含"需登录"⇒ 匿名得 401 而非 403；判据不再依赖路径前缀）。
 *
 * <p>`T58` 契约变更（接口契约.md §2.3/§2.4）：删资源 ⇒ {@code DELETE /api/v1/admin/comments/{id}}，
 * commentId 进路径（原 {@code POST /api/admin/comment/delete?commentId=X}；缺参分支随进路径退役）。
 */
@RestController
@RequiresAdmin
public class AdminCommentController {

    private final CommentService commentService;

    public AdminCommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    /**
     * 管理员删除评论（{@code DELETE /api/v1/admin/comments/{id}}）：软删除 + 计数回减 + 缓存失效。
     *
     * <p>评论不存在 → 404。
     *
     * <p><b>T2 审计（B12）</b>：审计行在服务方法返回（= 事务已提交）之后写，
     * 口径与理由见 {@code AdminContentController#hide}。
     */
    @DeleteMapping("/api/v1/admin/comments/{id}")
    public ResponseEntity<Void> delete(@CurrentUserId long adminId, @PathVariable("id") long commentId) {
        commentService.deleteCommentByAdmin(commentId);
        AuditLog.success("admin.comment.delete", adminId, "commentId:" + commentId);
        return ResponseEntity.noContent().build();
    }
}
