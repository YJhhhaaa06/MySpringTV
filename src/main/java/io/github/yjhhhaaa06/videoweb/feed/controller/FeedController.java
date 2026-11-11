package io.github.yjhhhaaa06.videoweb.feed.controller;

import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.common.web.PageParams;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.feed.service.FeedService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关注动态流接口（S9）。
 *
 * <p>迁移自 TV {@code com.itheima.content.controller.FeedController}——对照片段：
 *
 * <table>
 *   <caption>TV → 新实现</caption>
 *   <tr><th>TV</th><th>新实现</th></tr>
 *   <tr><td>{@code @WebServlet("/feed")} + {@code doGet}</td><td>{@code @RestController} + {@code @GetMapping("/api/v1/feed")}
 *       （`T58` 契约变更，依据《接口契约.md》§2.3）</td></tr>
 *   <tr><td>{@code (Long) req.getAttribute("userId")}</td><td>{@code @CurrentUserId long userId}</td></tr>
 *   <tr><td>{@code BaseServletUtil.parsePage / parsePageSize(req, 100, 100)}</td>
 *       <td>{@link PageParams}（口径逐字相同）</td></tr>
 * </table>
 *
 * <h2>鉴权：类级 {@code @RequiresLogin}</h2>
 * 依据 TV {@code AuthFilter} 的 {@code PROTECTED_PREFIXES}（**前缀**清单，见
 * {@code old-project/.../filter/AuthFilter.java:25}）——它含 <b>{@code "/feed"}</b>。
 * （2026-10-03 更正：此处原写 {@code PROTECTED_EXACT}，属误记；单端点场景下两种口径行为等价，
 * 故无契约差异，仅依据写错。）故本端点需登录，用**类级**注解声明（与 {@code /follow}、{@code /like} 同款；
 * 未来若 {@code /api/v1/feed/**} 加子路径，类级声明自动覆盖，不会漏）。
 *
 * <h2>分页契约（域级上限/信封均 100，与 follow/search/profile 同口径）</h2>
 * TV 的 {@code FEED_PAGE_SIZE_MAX=100} / {@code FEED_PAGE_SIZE_DEFAULT=100} 逐字承接
 * （T19 拍板：四域统一 100）。{@code page} / {@code pageSize} 收 {@code String} 以复刻
 * "非数字静默归一"（收 {@code Integer} 会变成 400，属契约改动——与 {@code FollowController} 同款理由）。
 *
 * <h2>★ 可选快照锚点 {@code beforeContentId}</h2>
 * 无限滚动时列表头部会插入新内容，纯 offset 分页会让第 2 页重复上一页末条（窗口整体后移一位）。
 * 客户端把**首屏第一条的 id** 作为锚点带给后续页，服务端只在该 id（含）以下切页 ⇒ 已加载区段冻结、
 * {@code total} 也只算锚点内条数。缺省 / 非数字 / ≤0 一律**静默忽略**（与 {@code page} 同口径），
 * 不带该参数时行为与旧契约**逐字一致**。语义与代价见 {@code architecture/domain/feed/feed.md} §3.4。
 */
@RestController
@RequiresLogin
public class FeedController {

    /** 域级页大小上限（TV T19：feed 与其他三域统一 100）。 */
    private static final int FEED_PAGE_SIZE_MAX = 100;

    /** 域级信封大小（TV T19：前端只传 {@code page} 时后端返回的条数）。 */
    private static final int FEED_PAGE_SIZE_DEFAULT = 100;

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    /**
     * 关注动态流（{@code GET /api/v1/feed}）。
     *
     * <p>返回 {@code PageResult<ContentVO>}，信封形状 {@code {list,total,page,pageSize,totalPages}}。
     *
     * @param beforeContentId 可选快照锚点（首屏第一条的 id）；缺省 / 非法 = 不启用（旧行为）
     */
    @GetMapping("/api/v1/feed")
    public ApiResponse<PageResult<ContentVO>> feed(@CurrentUserId long userId,
                                                   @RequestParam(required = false) String page,
                                                   @RequestParam(required = false) String pageSize,
                                                   @RequestParam(required = false) String beforeContentId) {
        return ApiResponse.success(feedService.getFeed(userId,
                PageParams.normalizePage(page),
                PageParams.normalizePageSize(pageSize, FEED_PAGE_SIZE_MAX, FEED_PAGE_SIZE_DEFAULT),
                normalizeBeforeContentId(beforeContentId)));
    }

    /**
     * 锚点归一：缺省 / 非数字 / ≤0 → {@code null}（= 不启用锚点）。
     *
     * <p>与 {@code page} 的"静默归一"同口径：非法值不 400、也不当成"过滤到 0 条"。
     */
    private static Long normalizeBeforeContentId(String raw) {
        Long parsed = PageParams.parseLongOrNull(raw);
        return (parsed == null || parsed <= 0) ? null : parsed;
    }
}
