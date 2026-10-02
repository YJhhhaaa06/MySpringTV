package io.github.yjhhhaaa06.videoweb.comment.controller;

import io.github.yjhhhaaa06.videoweb.comment.model.dto.AddCommentRequest;
import io.github.yjhhhaaa06.videoweb.comment.service.CommentService;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论接口（S2：**写路径**）。
 *
 * <p>迁移自 TV {@code com.itheima.comment.controller.CommentController}——对照片段：
 *
 * <table>
 *   <caption>TV → 新实现</caption>
 *   <tr><th>TV</th><th>新实现</th></tr>
 *   <tr><td>{@code @WebServlet("/comment/*")} + {@code switch(pathInfo)}（doPost 管 add/delete、doGet 管 show/replies）</td>
 *       <td>{@code @RestController} + {@code @PostMapping}（方法即端点）</td></tr>
 *   <tr><td>{@code RequestParser.parse(req, CommentDTO.class)} + {@code dto.setUserId(req.getAttribute("userId"))}</td>
 *       <td>{@code @RequestBody @Valid} + {@code @CurrentUserId}（userId 不再由客户端传）</td></tr>
 *   <tr><td>{@code CommandConverter.commentToCommand(dto)} 再手写 if 校验</td>
 *       <td>删除：Command 层无必要，校验改声明式（见 {@link AddCommentRequest}）</td></tr>
 *   <tr><td>{@code req.getParameter("commentId")} + 手工 parseLong + 空判</td>
 *       <td>{@code @RequestParam long}（缺参/非数字由 Spring 抛给 {@code GlobalExceptionHandler} → 400）</td></tr>
 * </table>
 *
 * <p><b>路径与参数位置沿袭 TV</b>（决策⑧「路径沿袭控制迁移变量」）：
 * {@code POST /comment/add}（JSON body）、{@code POST /comment/delete}（**query 参数** {@code commentId}）。
 * 后者刻意保持 query 参数而非改成 body —— 旧 pytest 就是这么调的，改位置属无谓的契约破坏。
 *
 * <p><b>为什么 {@code commentId} 不加 {@code @Positive}</b>：① TV 对 {@code commentId=0}
 * 的行为是走到 SQL 后 404（不是 400），加了会把 404 变成 400，属行为改动；
 * ② 约束注解标在 {@code @RequestParam} 上需要类级 {@code @Validated} 才生效，
 * 缺了就是**看着像保证、实际不校验**的隐式失效——不如不加，依赖 Spring 自带的
 * "缺参 → {@code MissingServletRequestParameterException} → 400"（这条路必定生效）。
 *
 * <p><b>鉴权（SOP 步骤 6：用 {@code @RequiresLogin} 声明，不去改任何路径清单）</b>——
 * 依据 TV {@code AuthFilter} 的 {@code PROTECTED_EXACT} 复核：它同时含
 * {@code /comment/add} 与 {@code /comment/delete}，两个端点都需登录。
 * 旧清单不迁移，但它**是本次鉴权口径的事实来源**，故在 {@code CommentFlowTests} 里逐条固化为测试。
 *
 * <p><b>未交付的两个端点</b>：{@code GET /comment/show} 与 {@code GET /comment/replies}。
 * 它们属读路径，依赖 CommentCache/ContentCache/LikeService，随 S5——理由与补回位置见
 * 《事务边界决策表》CM-3。
 */
@RestController
@RequestMapping("/comment")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    /**
     * 发表评论（主楼或楼中楼回复）。
     *
     * <p>成功响应 {@code data} 是字符串 {@code "评论成功"}——沿袭 TV {@code writeSuccess(resp, "评论成功")}。
     */
    @RequiresLogin
    @PostMapping("/add")
    public ApiResponse<String> add(@CurrentUserId long userId,
                                   @RequestBody @Valid AddCommentRequest request) {
        commentService.addComment(userId, request);
        return ApiResponse.success("评论成功");
    }

    /**
     * 用户自删评论（软删除，不可恢复）。
     *
     * <p>成功响应 {@code data} 是字符串 {@code "删除成功"}——沿袭 TV。
     * 他人的评论 → 403；不存在/已删 → 404。
     */
    @RequiresLogin
    @PostMapping("/delete")
    public ApiResponse<String> delete(@CurrentUserId long userId,
                                      @RequestParam long commentId) {
        commentService.deleteCommentByUser(commentId, userId);
        return ApiResponse.success("删除成功");
    }
}
