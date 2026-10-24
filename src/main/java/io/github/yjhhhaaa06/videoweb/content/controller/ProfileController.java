package io.github.yjhhhaaa06.videoweb.content.controller;

import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.Public;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.common.web.PageParams;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ProfileVO;
import io.github.yjhhhaaa06.videoweb.content.service.ProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户主页（`T58` 契约变更，依据《接口契约.md》§2.3：
 * {@code GET /api/v1/users/{id}?page=&pageSize=}——{@code userId} 进路径，分页留 query；S5）。
 *
 * <p>迁移自 TV {@code com.itheima.content.controller.ProfileController}——对照片段：
 * <table>
 *   <caption>TV → 新实现</caption>
 *   <tr><th>TV</th><th>新实现</th></tr>
 *   <tr><td>{@code @WebServlet("/profile")}</td><td>{@code @GetMapping("/api/v1/users/{id}")}
 *       （`T58` 起资源化；TV 的路径就是 {@code /profile}）</td></tr>
 *   <tr><td>{@code parseUserId} 手工：空 → {@code ParamException("缺少 userId")}；
 *       非数字 → {@code ParamException("userId 格式错误")}</td>
 *       <td>收 {@code String} 并保留「格式错误」文案（见下）；「缺少 userId」分支随
 *       {@code userId} 进路径而消失（少路径段 = 无路由匹配，`T58` §2.3 定性）</td></tr>
 *   <tr><td>{@code BaseServletUtil.parsePage / parsePageSize(req, 100, 100)}</td>
 *       <td>{@link PageParams}（T19 口径：上限 100 / 信封 100）</td></tr>
 * </table>
 *
 * <h2>★ 为什么 {@code userId} 收 {@code String} 而不是 {@code Long}</h2>
 * 若收 {@code Long}，"非数字"会由 Spring 抛 {@code MethodArgumentTypeMismatchException} →
 * 全局出口的**统一文案**（{@code "参数错误"}）。而 TV 对非数字的文案是**专门的**
 * {@code "userId 格式错误"}，且状态码同为 400。既然差别只在 msg，收 {@code String} 并
 * 复刻旧判定最省事、也最忠实。
 * <p>`T57` 复核：现在全局出口会给出 {@code errors[field=userId]} 与稳定文案
 * （{@code 缺少必填参数} / {@code 参数类型不正确}）——"能定位到参数"这件事已经由契约保证，
 * 但**TV 的原话仍未保留** ⇒ 本决策不变（要的是那两句文案，不是"能不能定位"）。
 * <p>★ `T58`：原"缺 userId → 400「缺少 userId」"分支随 {@code userId} 进路径而退役
 * （路径段缺失不会命中本 handler，而是 404——不可表达，见 §2.3）。
 */
@RestController
public class ProfileController {

    /** T19：profile 域 pageSize 上限。 */
    private static final int PROFILE_PAGE_SIZE_MAX = 100;

    /** T19：profile 域**信封大小**（原公共缺省 10）。 */
    private static final int PROFILE_PAGE_SIZE_DEFAULT = 100;

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * 用户主页（{@code GET /api/v1/users/{id}}）。
     *
     * <p>★ 鉴权口径不变（`T58` 只动路径）：{@code @CurrentUserId(required = false)} 的
     * "匿名可见 + 已登录个性化"端点（匿名 ⇒ {@code isFollowed} 为 {@code null}）。
     *
     * @param userId 被查看的用户 id（`T58` 起在路径上）
     */
    @Public
    @GetMapping("/api/v1/users/{id}")
    public ApiResponse<ProfileVO> profile(@CurrentUserId(required = false) Long currentUserId,
                                          @PathVariable("id") String userId,
                                          @RequestParam(required = false) String page,
                                          @RequestParam(required = false) String pageSize) {
        long profileUserId = parseUserId(userId);
        return ApiResponse.success(profileService.getProfile(profileUserId, currentUserId,
                PageParams.normalizePage(page),
                PageParams.normalizePageSize(pageSize, PROFILE_PAGE_SIZE_MAX, PROFILE_PAGE_SIZE_DEFAULT)));
    }

    /** TV {@code parseUserId} 逐字（`T58` 后只剩"格式"分支：非数字 → 400「userId 格式错误」）。 */
    private static long parseUserId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ParamException(ErrorCode.PARAM_INVALID, "userId 格式错误");
        }
    }
}
