package io.github.yjhhhaaa06.videoweb.like.controller;

import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.like.service.LikeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 点赞接口。
 *
 * <p>迁移自 TV {@code com.itheima.like.controller.LikeController}——对照片段：
 *
 * <table>
 *   <caption>TV → 新实现</caption>
 *   <tr><th>TV</th><th>新实现</th></tr>
 *   <tr><td>{@code @WebServlet("/like/*")} + {@code switch(pathInfo)} 分派</td>
 *       <td>{@code @RestController} + {@code @PutMapping}/{@code @DeleteMapping}/{@code @GetMapping}</td></tr>
 *   <tr><td>{@code parseContentId(req)} / {@code parseCommentId(req)}：手写 isBlank + parseLong + catch</td>
 *       <td>{@code @PathVariable("id") long}（非数字 → 400；缺路径段 → 404，见 §2.3）</td></tr>
 *   <tr><td>{@code (Long) req.getAttribute("userId")}</td>
 *       <td>{@code @CurrentUserId}</td></tr>
 * </table>
 *
 * <p><b>请求形态</b>（`T58` 起按 {@code 接口契约.md} §2.4 定性）：
 * 点赞是幂等设置 ⇒ {@code PUT}，取消是幂等取消 ⇒ {@code DELETE}，读 ⇒ {@code GET}；
 * 被操作对象 id 一律在路径上，写端点**没有请求体**。
 * 鉴权仍整类需登录（类级 {@code @RequiresLogin}）。
 *
 * <p><b>★ 鉴权：整类需登录（类级 {@code @RequiresLogin}）</b>——
 * TV {@code AuthFilter.PROTECTED_PREFIXES} 里是 <b>{@code "/like"}（前缀）</b>，
 * 即 **8 个 like 端点全部需登录**，包括不消费 userId 的 {@code /like/content/count}。
 * 这一"过宽"的保护口径是 TV 既有行为（旧 pytest 断言无 token 一律 401），故**原样保留**，
 * 并用类级注解表达（而不是逐个方法打）——它把"这一族整体受保护"这件事写在一处，比 8 处重复更难漏。
 *
 * <p><b>未交付</b>：TV {@code LikeService.deleteContentLike}（供 {@code ContentService} 删内容时级联失效）
 * 在本切片无调用方（内容删除属 S5），按"不搬无主代码"的纪律未搬——补回位置见决策表 L-7 的失效节。
 */
@RequiresLogin
@RestController
public class LikeController {

    private final LikeService likeService;

    public LikeController(LikeService likeService) {
        this.likeService = likeService;
    }

    // ==================== 内容点赞（`T58`：/contents/{id}/like） ====================

    /** 点赞内容：幂等设置"我赞了这条" ⇒ PUT（§2.4）；重复 / 并发点赞 = 200 no-op（`T59`）。 */
    @PutMapping("/api/v1/contents/{id}/like")
    public ApiResponse<Void> likeContent(@CurrentUserId long userId, @PathVariable("id") long contentId) {
        likeService.likeContent(userId, contentId);
        return ApiResponse.success();
    }

    /**
     * 取消内容点赞：{@code cancel} = 撤销动作/状态（§2.8）——
     * ★ {@code unlike} 全站退役（本仓没有"点踩"，对外只说"取消点赞"）。
     * `T59` 起重复取消 = 204 no-op（不报 409、不扣负计数）。
     */
    @DeleteMapping("/api/v1/contents/{id}/like")
    public ResponseEntity<Void> cancelContentLike(@CurrentUserId long userId, @PathVariable("id") long contentId) {
        likeService.cancelContentLike(userId, contentId);
        return ResponseEntity.noContent().build();
    }

    /** 点赞评论：同内容侧，PUT 幂等设置（`T59`：重复 = 200 no-op）。 */
    @PutMapping("/api/v1/comments/{id}/like")
    public ApiResponse<Void> likeComment(@CurrentUserId long userId, @PathVariable("id") long commentId) {
        likeService.likeComment(userId, commentId);
        return ApiResponse.success();
    }

    /** 取消评论点赞（`T59`：重复取消 = 204 no-op）。 */
    @DeleteMapping("/api/v1/comments/{id}/like")
    public ResponseEntity<Void> cancelCommentLike(@CurrentUserId long userId, @PathVariable("id") long commentId) {
        likeService.cancelCommentLike(userId, commentId);
        return ResponseEntity.noContent().build();
    }

    // ==================== 读（GET + 单例关系 / 计数投影） ====================

    /** 当前用户是否点赞了该内容。{@code data} 是**布尔**，不是对象。 */
    @GetMapping("/api/v1/contents/{id}/like")
    public ApiResponse<Boolean> contentStatus(@CurrentUserId long userId, @PathVariable("id") long contentId) {
        return ApiResponse.success(likeService.isContentLiked(userId, contentId));
    }

    /**
     * 内容点赞数。★ 计数是**标量投影**（{@code like-count}），不是 {@code likes/count} 子资源——
     * 后者会造出第 4 级路径（§2.3 层级 ≤ 3）且暗示"该端点返回集合"，与事实不符。
     */
    @GetMapping("/api/v1/contents/{id}/like-count")
    public ApiResponse<Integer> contentCount(@PathVariable("id") long contentId) {
        return ApiResponse.success(likeService.getContentLikeCount(contentId));
    }

    /** 当前用户是否点赞了该评论。{@code data} 是**布尔**。 */
    @GetMapping("/api/v1/comments/{id}/like")
    public ApiResponse<Boolean> commentStatus(@CurrentUserId long userId, @PathVariable("id") long commentId) {
        return ApiResponse.success(likeService.isCommentLiked(userId, commentId));
    }

    /** 评论点赞数（口径同内容侧；本端点不消费 userId，但按类级口径仍需登录）。 */
    @GetMapping("/api/v1/comments/{id}/like-count")
    public ApiResponse<Integer> commentCount(@PathVariable("id") long commentId) {
        return ApiResponse.success(likeService.getCommentLikeCount(commentId));
    }
}
