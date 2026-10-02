package io.github.yjhhhaaa06.videoweb.content.controller;

import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.common.web.PageParams;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ProfileVO;
import io.github.yjhhhaaa06.videoweb.content.service.ProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户主页（{@code GET /profile?userId=&page=&pageSize=}，S5）。
 *
 * <p>迁移自 TV {@code com.itheima.content.controller.ProfileController}——对照片段：
 * <table>
 *   <caption>TV → 新实现</caption>
 *   <tr><th>TV</th><th>新实现</th></tr>
 *   <tr><td>{@code @WebServlet("/profile")}</td><td>{@code @GetMapping("/profile")}（**没有类级前缀**——路径就是 {@code /profile}）</td></tr>
 *   <tr><td>{@code parseUserId} 手工：空 → {@code ParamException("缺少 userId")}；
 *       非数字 → {@code ParamException("userId 格式错误")}</td>
 *       <td>收 {@code String} 并**逐字保留两条文案**（见下）</td></tr>
 *   <tr><td>{@code BaseServletUtil.parsePage / parsePageSize(req, 100, 100)}</td>
 *       <td>{@link PageParams}（T19 口径：上限 100 / 信封 100）</td></tr>
 * </table>
 *
 * <h2>★ 为什么 {@code userId} 收 {@code String} 而不是 {@code Long}</h2>
 * 若收 {@code Long}，"非数字"会由 Spring 抛 {@code MethodArgumentTypeMismatchException} →
 * 全局出口的**统一文案**（{@code "参数错误"}）。而 TV 这里的两条文案是**区分开的**
 * （缺参 {@code "缺少 userId"} / 格式错 {@code "userId 格式错误"}），且**状态码都是 400**。
 * 既然差别只在 msg，收 {@code String} 并复刻旧判定最省事、也最忠实。
 * （与同域 {@code /search/IdSearch} 的处置不同：那里的 contentId **没有**专用文案
 * ——TV 对它是"空判写 PARAM_ERROR + 未捕获的 parseLong"，故那边直接用了参数绑定。）
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
     * 用户主页。
     *
     * @param userId 被查看的用户 id（必传）
     */
    @GetMapping("/profile")
    public ApiResponse<ProfileVO> profile(@CurrentUserId(required = false) Long currentUserId,
                                          @RequestParam(required = false) String userId,
                                          @RequestParam(required = false) String page,
                                          @RequestParam(required = false) String pageSize) {
        long profileUserId = parseUserId(userId);
        return ApiResponse.success(profileService.getProfile(profileUserId, currentUserId,
                PageParams.normalizePage(page),
                PageParams.normalizePageSize(pageSize, PROFILE_PAGE_SIZE_MAX, PROFILE_PAGE_SIZE_DEFAULT)));
    }

    /** TV {@code parseUserId} 逐字：缺参 / 空白 → 400「缺少 userId」；非数字 → 400「userId 格式错误」。 */
    private static long parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ParamException("缺少 userId");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ParamException("userId 格式错误");
        }
    }
}
