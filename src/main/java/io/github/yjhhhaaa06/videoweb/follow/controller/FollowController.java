package io.github.yjhhhaaa06.videoweb.follow.controller;

import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.common.web.PageParams;
import io.github.yjhhhaaa06.videoweb.follow.model.vo.FollowUserVO;
import io.github.yjhhhaaa06.videoweb.follow.service.FollowService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关注关系接口（S4）。
 *
 * <p>迁移自 TV {@code com.itheima.follow.controller.FollowController}——对照片段：
 *
 * <table>
 *   <caption>TV → 新实现</caption>
 *   <tr><th>TV</th><th>新实现</th></tr>
 *   <tr><td>{@code @WebServlet("/follow/*")} + {@code switch(pathInfo)}（doPost 管 add/remove、doGet 管 following/followers）</td>
 *       <td>{@code @RestController} + {@code @PutMapping}/{@code @DeleteMapping}/{@code @GetMapping}（方法即端点）</td></tr>
 *   <tr><td>{@code (Long) req.getAttribute("userId")}</td>
 *       <td>{@code @CurrentUserId}（userId 由 token 解析，客户端不得自报）</td></tr>
 *   <tr><td>{@code parseFollowedUserId(req)} 手工 parseLong + 空判 + 抛 {@code ParamException}</td>
 *       <td>{@code @PathVariable("id") long}（非数字由 Spring 参数绑定抛给 {@code GlobalExceptionHandler} → 400；
 *           <b>"缺 id" 不再是 400</b>——id 进路径后少一段就没有路由匹配 ⇒ 404，见 {@code 接口契约.md} §2.3/§2.5）</td></tr>
 *   <tr><td>{@code BaseServletUtil.parsePage/parsePageSize} 手工归一</td>
 *       <td>仍是手工归一（见下方"为什么分页参数收 {@code String}"）</td></tr>
 * </table>
 *
 * <h2>路径（`T58` 资源化，依据 {@code 接口契约.md} §2.3 / §2.4）</h2>
 * 关系本身是 {@code follows} 资源：{@code PUT /api/v1/follows/{id}} = "我关注了 {id} 这个人"（幂等设置），
 * {@code DELETE /api/v1/follows/{id}} = 取消关注（§2.8 的 {@code cancel} 语义，但路径上就是删关系）。
 * 两个列表是 user 的子资源：{@code /api/v1/users/{id}/following} 与
 * {@code /api/v1/users/{id}/followers}（被查看的 userId 进路径，不再走 query）。
 * 载荷形状与状态码语义**不变**（归 `T59`）。
 *
 * <h2>鉴权：类级 {@code @RequiresLogin}</h2>
 * 依据 TV {@code AuthFilter} 的 {@code PROTECTED_PREFIXES}——它含 <b>{@code "/follow"}</b>（前缀匹配），
 * 故 **4 个端点全部需登录**（与 S3 的 {@code /like} 同款）。旧清单不迁移，
 * 但它**是本次鉴权口径的事实来源**，逐条固化为测试（{@code FollowFlowTests} + {@code SecurityContractTests}）。
 *
 * <p>用**类级**注解而非逐方法标：TV 的保护是前缀语义（整个 {@code /follow/*}），
 * 类级声明与该语义一一对应，且新增端点时不会漏（这正是决策③要根治的"清单易漏"）。
 * `T58` 把路径搬到 {@code /api/v1/follows} 与 {@code /api/v1/users/{id}/*} 后，
 * "前缀清单"这一事实来源已不适用新路径——但**结论不变**（四个端点都需登录、本域没有匿名端点），
 * 仍用类级表达；若将来本域出现匿名端点（例如"谁关注了我"对外可见），才需要改方法级。
 *
 * <h2>分页契约（T7 B2 / T11-A / T19 口径，**逐字节保真**）</h2>
 * <ul>
 *   <li>域级常量：上限 {@value #FOLLOW_PAGE_SIZE_MAX}、缺省 {@value #FOLLOW_PAGE_SIZE_DEFAULT}（T19 由 200 调为 100）；</li>
 *   <li>缺省（不传分页参数）归一为 {@code page=1&pageSize=100}，与显式传参**逐字节一致**；</li>
 *   <li>{@code page < 1} → 1；{@code pageSize} 缺省 / ≤0 / 超上限 → 100；{@code pageSize=51} **原样回显**；</li>
 *   <li>越界页 → {@code list == []} 但 **{@code total} 保留**（前端据此判末页）。</li>
 * </ul>
 *
 * <h2>⚠️ 为什么分页参数收 {@code String} 而不是 {@code Integer}</h2>
 * TV 的 {@code BaseServletUtil.parsePage} 对**非数字**的 {@code page} 是**静默归一为 1**
 * （{@code catch (NumberFormatException) { return 1; }}），而 {@code parseUserId} 对非数字
 * **抛 400**——这个不对称是有意的。若这里收 {@code Integer}，非数字会被 Spring 抛成
 * {@code MethodArgumentTypeMismatchException} → **400**，把"静默归一"变成"报错"，
 * 属**契约改动**。故收 {@code String} 并复刻旧归一逻辑（{@link #normalizePage} / {@link #normalizePageSize}）。
 *
 * <p>（与 S5 的分页处置刻意不同：那里的旧归一化曾反复重构四轮，《切片计划》§五 要求另起写法；
 * 本域的口径**一次成型且有逐字断言**，属冻结契约，因此照搬。）
 */
@RestController
@RequiresLogin
public class FollowController {

    /** 域级页大小上限。T11-A 引入；**T19 由 200 调整为 100**（用户拍板：200 单次成本过高；与 feed/search/profile 三域同口径）。 */
    private static final int FOLLOW_PAGE_SIZE_MAX = 100;

    /** 域级信封大小。T11-A 引入；**T19 由 200 调整为 100**——前端只传 {@code page} 时后端返回的条数。 */
    private static final int FOLLOW_PAGE_SIZE_DEFAULT = 100;

    private final FollowService followService;

    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    /**
     * 关注：幂等设置"我关注了 {id} 这个人" ⇒ {@code PUT}（{@code 接口契约.md} §2.4）。
     * 成功响应 {@code data} 是字符串 {@code "关注成功"}——沿袭 TV {@code writeSuccess(resp, "关注成功")}。
     *
     * <p>关注自己 / 重复关注 → 409。
     */
    @PutMapping("/api/v1/follows/{id}")
    public ApiResponse<String> add(@CurrentUserId long userId, @PathVariable("id") long followedUserId) {
        followService.follow(userId, followedUserId);
        return ApiResponse.success("关注成功");
    }

    /**
     * 取关：{@code cancel} = 撤销动作/状态（{@code 接口契约.md} §2.8），路径上就是删除这条关系 ⇒ {@code DELETE}。
     * 成功响应 {@code data} 是字符串 {@code "已取关"}——沿袭 TV。
     *
     * <p>取关自己 / 未关注 → 409。
     */
    @DeleteMapping("/api/v1/follows/{id}")
    public ApiResponse<String> remove(@CurrentUserId long userId, @PathVariable("id") long followedUserId) {
        followService.unfollow(userId, followedUserId);
        return ApiResponse.success("已取关");
    }

    /** 关注列表（路径上的 {@code id} 关注了谁）。{@code id} 非数字 → 400。 */
    @GetMapping("/api/v1/users/{id}/following")
    public ApiResponse<PageResult<FollowUserVO>> following(@CurrentUserId long currentUserId,
                                                          @PathVariable("id") long userId,
                                                          @RequestParam(required = false) String page,
                                                          @RequestParam(required = false) String pageSize) {
        return ApiResponse.success(followService.getFollowingList(
                userId, currentUserId, normalizePage(page), normalizePageSize(pageSize)));
    }

    /** 粉丝列表（谁关注了路径上的 {@code id}）。{@code id} 非数字 → 400。 */
    @GetMapping("/api/v1/users/{id}/followers")
    public ApiResponse<PageResult<FollowUserVO>> followers(@CurrentUserId long currentUserId,
                                                          @PathVariable("id") long userId,
                                                          @RequestParam(required = false) String page,
                                                          @RequestParam(required = false) String pageSize) {
        return ApiResponse.success(followService.getFollowerList(
                userId, currentUserId, normalizePage(page), normalizePageSize(pageSize)));
    }

    // ========================================================================
    // 分页参数归一（口径逐字沿袭 TV BaseServletUtil.normalizePage / normalizePageSize）
    // ========================================================================

    /**
     * S5 起委托 {@link PageParams}（第三个使用方出现，按 rule of three 抽公共）
     * ——**口径零变化**：S4 时这三个私有方法就是照它写的，其测试（FollowFlowTests
     * 的分页用例）是这次委托的回归网。
     */
    private static int normalizePage(String raw) {
        return PageParams.normalizePage(raw);
    }

    private static int normalizePageSize(String raw) {
        return PageParams.normalizePageSize(raw, FOLLOW_PAGE_SIZE_MAX, FOLLOW_PAGE_SIZE_DEFAULT);
    }
}
