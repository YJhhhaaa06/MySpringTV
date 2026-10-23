package io.github.yjhhhaaa06.videoweb.comment.controller;

import io.github.yjhhhaaa06.videoweb.comment.model.dto.AddCommentRequest;
import io.github.yjhhhaaa06.videoweb.comment.model.vo.CommentVO;
import io.github.yjhhhaaa06.videoweb.comment.service.CommentService;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.model.dto.CursorResult;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.Public;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.common.web.PageParams;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论接口。
 *
 * <p>迁移自 TV {@code com.itheima.comment.controller.CommentController}——对照片段：
 *
 * <table>
 *   <caption>TV → 新实现</caption>
 *   <tr><th>TV</th><th>新实现</th></tr>
 *   <tr><td>{@code @WebServlet("/comment/*")} + {@code switch(pathInfo)}（doPost 管 add/delete、doGet 管 show/replies）</td>
 *       <td>{@code @RestController} + {@code @PostMapping}/{@code @GetMapping}（方法即端点）</td></tr>
 *   <tr><td>{@code RequestParser.parse(req, CommentDTO.class)} + {@code dto.setUserId(req.getAttribute("userId"))}</td>
 *       <td>{@code @RequestBody @Valid} + {@code @CurrentUserId}（userId 不再由客户端传）</td></tr>
 *   <tr><td>{@code CommandConverter.commentToCommand(dto)} 再手写 if 校验</td>
 *       <td>删除：Command 层无必要，校验改声明式（见 {@link AddCommentRequest}）</td></tr>
 *   <tr><td>{@code req.getParameter("commentId")} + 手工 parseLong + 空判</td>
 *       <td>{@code @RequestParam long}（缺参/非数字由 Spring 抛给 {@code GlobalExceptionHandler} → 400）</td></tr>
 *   <tr><td>{@code hasPagingParams(req)} 判"是否传了 page/pageSize"</td>
 *       <td>同样的判据（收 {@code String} 的 {@code @RequestParam(required = false)}，
 *           用 {@code == null} 区分"没传"与"传了空串"）</td></tr>
 * </table>
 *
 * <p><b>路径与参数位置沿袭 TV</b>（决策⑧「路径沿袭控制迁移变量」）：
 * {@code POST /comment/add}（JSON body）、{@code POST /comment/delete}（**query 参数** {@code commentId}）。
 * 后者刻意保持 query 参数而非改成 body —— 旧 pytest 就是这么调的，改位置属无谓的契约破坏。
 *
 * <p><b>为什么 {@code commentId} 不加 {@code @Positive}</b>：① TV 对 {@code commentId=0}
 * 的行为是走到 SQL 后 404（不是 400），加了会把 404 变成 400，属行为改动；
 * ② 加了它会多一条与 TV 不一致的拒绝路径（TV 没有这个校验）——不如不加，
 * 依赖 Spring 自带的"缺参 → {@code MissingServletRequestParameterException} → 400"（这条路必定生效）。
 *
 * <p>⚠️ 历史注释更正（`T57` 复核）：这里曾写"约束注解标在 {@code @RequestParam} 上需要类级
 * {@code @Validated} 才生效"。那是 Spring 6.1 之前的 AOP 路线；本仓（Boot 4.1 / Framework 7）
 * 走的是**内置方法校验**，注解不加 {@code @Validated} 也生效，失败抛
 * {@code HandlerMethodValidationException}（由 {@code GlobalExceptionHandler} 收口成 400 + {@code errors[]}）。
 * 实例见 {@code UserController#changeUserName}，契约见
 * {@code ValidationErrorContractTests#请求参数上的约束生效}。
 *
 * <p><b>鉴权（SOP 步骤 6：用 {@code @RequiresLogin} 声明，不去改任何路径清单）</b>——
 * 依据 TV {@code AuthFilter} 复核：{@code PROTECTED_EXACT} 含
 * {@code /comment/add} 与 {@code /comment/delete}（**精确项**，故用方法级注解）；
 * 而 {@code /comment/show}、{@code /comment/replies} **不在任何清单里** ⇒ 公开可访问
 * （带 token 则填充 {@code isLiked}）。旧清单不迁移，但它**是鉴权口径的事实来源**，
 * 故在 {@code CommentFlowTests} 与 {@code SecurityContractTests} 里逐条固化为测试。
 *
 * <h2>S5 补上的两个读端点（CM-3 的交付项）</h2>
 * {@code GET /comment/show}：**S6-B2d 起实现在 {@code CommentService}**（此前在 ContentService）——
 * 门禁"能不能看评论"仍由内容域回答（{@code ContentService.isCommentReadable}），
 * 但评论树与 VO 组装回到本域，消除了 {@code content⇄comment} 双向环。
 * {@code GET /comment/replies}：实现在 {@code CommentService.getRepliesForRoot}
 * （★ <b>T14 起为游标（keyset）分页</b>：入参 {@code cursor}、出参加 {@code nextCursor}，见方法注释）。
 */
@RestController
public class CommentController {

    /** T10-B：评论域 pageSize 上限（大 chunk 前端承载；T19 起各域自持常量）。 */
    private static final int COMMENT_PAGE_SIZE_MAX = 500;

    /**
     * T11-B：评论域**信封大小**——前端只传 {@code page} 时后端返回的条数。
     *
     * <p>取值 200（沿用 T10-B 前端原本显式传的 200）⇒ 前端行为不变，只是"要多少条"的决定权
     * 从请求参数挪到后端（镜像 follow 域的先例）。刻意**大于** search/profile 的 100：
     * 评论的一屏主楼比内容列表多得多。
     */
    private static final int COMMENT_PAGE_SIZE_DEFAULT = 200;

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
    @PostMapping("/api/v1/comments")
    public ApiResponse<String> add(@CurrentUserId long userId,
                                   @RequestBody @Valid AddCommentRequest request) {
        commentService.addComment(userId, request);
        return ApiResponse.success("评论成功");
    }

    /**
     * 用户自删评论（软删除，不可恢复）。
     *
     * <p>`T59` 起成功 = <b>204 无体</b>。他人的评论 → 403；不存在/已删 → 404
     * （重复删除已软删评论仍是 404：既有"已软删亦视为不存在"口径，不改成 204 —— 见 §2.4 注）。
     */
    @RequiresLogin
    @DeleteMapping("/api/v1/comments/{id}")
    public ResponseEntity<Void> delete(@CurrentUserId long userId,
                                       @PathVariable("id") long commentId) {
        commentService.deleteCommentByUser(commentId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 评论列表（{@code GET /comment/show?contentId=[&page][&pageSize]}）。
     *
     * <h2>★ 缺省与分页是**两种响应形状**（T8 的冻结兼容契约）</h2>
     * <pre>
     * 不传 page 也不传 pageSize  → data = **全量数组**（children 全量随行；逐字节兼容改造前）
     * 传了任一个               → data = **分页信封** {list,total,page,pageSize,totalPages}
     *                            total = **主楼条数**；每主楼 children ≤ K=2 + replyCount
     * </pre>
     * 判据不能用"page 是否等于 1"（无法区分"没传"与"显式传 page=1"）
     * ——故两个参数都收 {@code String} 并逐个判 {@code null}（TV 的 {@code hasPagingParams} 同款）。
     *
     * <p>分页归一：{@code page} 缺省/非数字/≤0 → 1；{@code pageSize} 缺省/非数字/≤0 → **200**；
     * 超上限 → **500**；其它原样回显。
     *
     * <p>缺 {@code contentId} / 非法 → 400；内容不存在 / 评论区已关 → **200 + 空**（不是 404/409，
     * 见 {@code CommentService.getCommentsForContent} 的门禁说明）。
     */
    @Public
    @GetMapping("/api/v1/contents/{id}/comments")
    public ApiResponse<?> show(@CurrentUserId(required = false) Long userId,
                               @PathVariable("id") Long contentId,
                               @RequestParam(required = false) String page,
                               @RequestParam(required = false) String pageSize) {
        if (page == null && pageSize == null) {
            return ApiResponse.success(commentService.getCommentsForContent(contentId, userId));
        }
        return ApiResponse.success(commentService.getCommentsForContent(contentId, userId,
                PageParams.normalizePage(page),
                PageParams.normalizePageSize(pageSize, COMMENT_PAGE_SIZE_MAX, COMMENT_PAGE_SIZE_DEFAULT)));
    }

    /**
     * 展开某主楼的全部回复（{@code GET /comment/replies?rootId=[&cursor][&pageSize]}，T10-B / ★T14）。
     *
     * <h2>★ T14 起为**游标分页**（有意破 {@code page} 契约，见 F-20 ②）</h2>
     * <pre>
     * 旧：?page=2&pageSize=2  → 信封 {list,total,page,pageSize,totalPages}（DB 返 page*pageSize 行）
     * 新：?cursor=<上页末条 commentId>&pageSize=2
     *     → 信封 {list,total,pageSize,nextCursor}（DB 只取本页）
     * </pre>
     * {@code cursor} 缺省 = 0（从楼头开始，即旧的第 1 页）；{@code nextCursor = null} ⇒ 已到底。
     * ⚠️ 旧的 {@code page} 参数**不再被识别**（传了会被忽略 ⇒ 退化为第 1 页）——
     * 前端 {@code detail.js} 已同批改为游标翻页（本次契约变更**同批交付前端**，见 `.docs/INDEX.md` §一 ①）。
     *
     * <p>**总是**返回信封（没有"缺省全量"的兼容包袱——它是新接口）；
     * {@code total} = 该主楼 {@code reply_count}（语义**不变**）。
     *
     * <p>缺 / 非法 {@code rootId} → 400；主楼不存在或已删 → 404；
     * 非法 / 越界 {@code cursor} → 静默归一到 0 或空页（**不报错**，见 {@code PageParams.normalizeCursor}）。
     */
    @Public
    @GetMapping("/api/v1/comments/{id}/replies")
    public ApiResponse<CursorResult<CommentVO>> replies(@CurrentUserId(required = false) Long userId,
                                                        @PathVariable("id") Long rootId,
                                                        @RequestParam(required = false) String cursor,
                                                        @RequestParam(required = false) String pageSize) {
        return ApiResponse.success(commentService.getRepliesForRoot(rootId, userId,
                PageParams.normalizeCursor(cursor),
                PageParams.normalizePageSize(pageSize, COMMENT_PAGE_SIZE_MAX, COMMENT_PAGE_SIZE_DEFAULT)));
    }
}
