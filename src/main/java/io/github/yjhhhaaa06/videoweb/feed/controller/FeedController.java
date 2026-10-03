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
 *   <tr><td>{@code @WebServlet("/feed")} + {@code doGet}</td><td>{@code @RestController} + {@code @GetMapping("/feed")}</td></tr>
 *   <tr><td>{@code (Long) req.getAttribute("userId")}</td><td>{@code @CurrentUserId long userId}</td></tr>
 *   <tr><td>{@code BaseServletUtil.parsePage / parsePageSize(req, 100, 100)}</td>
 *       <td>{@link PageParams}（口径逐字相同）</td></tr>
 * </table>
 *
 * <h2>鉴权：类级 {@code @RequiresLogin}</h2>
 * 依据 TV {@code AuthFilter} 的 {@code PROTECTED_EXACT}（**精确匹配**清单，非前缀）——它含
 * <b>{@code "/feed"}</b>。故本端点需登录，用**类级**注解声明（与 {@code /follow}、{@code /like} 同款；
 * 未来若 {@code /feed/**} 加子路径，类级声明自动覆盖，不会漏）。
 *
 * <h2>分页契约（域级上限/信封均 100，与 follow/search/profile 同口径）</h2>
 * TV 的 {@code FEED_PAGE_SIZE_MAX=100} / {@code FEED_PAGE_SIZE_DEFAULT=100} 逐字承接
 * （T19 拍板：四域统一 100）。{@code page} / {@code pageSize} 收 {@code String} 以复刻
 * "非数字静默归一"（收 {@code Integer} 会变成 400，属契约改动——与 {@code FollowController} 同款理由）。
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
     * 关注动态流（{@code GET /feed}）。
     *
     * <p>返回 {@code PageResult<ContentVO>}，信封形状 {@code {list,total,page,pageSize,totalPages}}。
     */
    @GetMapping("/feed")
    public ApiResponse<PageResult<ContentVO>> feed(@CurrentUserId long userId,
                                                   @RequestParam(required = false) String page,
                                                   @RequestParam(required = false) String pageSize) {
        return ApiResponse.success(feedService.getFeed(userId,
                PageParams.normalizePage(page),
                PageParams.normalizePageSize(pageSize, FEED_PAGE_SIZE_MAX, FEED_PAGE_SIZE_DEFAULT)));
    }
}
