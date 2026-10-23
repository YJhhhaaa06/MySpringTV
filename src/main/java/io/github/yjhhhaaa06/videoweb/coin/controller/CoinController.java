package io.github.yjhhhaaa06.videoweb.coin.controller;

import io.github.yjhhhaaa06.videoweb.coin.model.vo.CoinBalanceVO;
import io.github.yjhhhaaa06.videoweb.coin.model.vo.SignInVO;
import io.github.yjhhhaaa06.videoweb.coin.service.CoinService;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投币接口（`T58` 起资源化）：签到 check-in（T37）+ 投币（T38）。
 *
 * <h2>★ 鉴权：类级 {@code @RequiresLogin}（与 {@code LikeController} 同族）</h2>
 * 本域**没有匿名面**：签到看的是"我的余额"、投币花的是"我的硬币"，
 * 两个端点都只对当前登录用户有意义。
 * 把"这一族整体受保护"写在一处，比逐端点重复更难漏。
 *
 * <p>★ <b>绝不照抄 {@code FavoriteController} 的逐端点声明</b>——那不是"更细"，而是那里
 * **必须**如此：收藏域有 {@code GET /api/v1/favorite-folders/{id}/items} 等多条
 * **匿名可读**端点（看他人公开夹 / 公开展示数），类级注解会把它们误拦成 401，
 * 而且"自己的私密夹自己照样看得见"会让这个误拦长期无人发现。
 * 本域没有这种端点 ⇒ 类级更不漏；反过来，照抄逐端点声明只会把"本域全部需登录"
 * 这个事实拆散到多处，新增端点时容易漏标（漏标在本仓 = 默认拒绝，行为仍对，
 * 但"显式声明"这层可读性没了）。判定口径由 {@code SecurityContractTests} 守着。
 *
 * <h2>路径（`T58` 契约变更，依据《接口契约.md》§2.3/§2.4/§2.8）</h2>
 * <ul>
 *   <li>签到：{@code POST /api/v1/users/me/check-ins}（check-in = 每日签到，与 login/session 拆名）；</li>
 *   <li>投币：{@code POST /api/v1/contents/{id}/coins}（{@code contentId} 进路径，
 *       {@code count} 留 form/query）。</li>
 * </ul>
 *
 * <h2>请求形态</h2>
 * 写端点保持 {@code POST}（创建语义，§2.4）。
 * 签到**无入参**（用户来自 token，日期来自 {@code SiteTime}）；
 * 投币收路径上的 {@code contentId} 与 form/query 里的 {@code count}（枚数 1 或 2）。
 *
 * <h2>响应</h2>
 * <ul>
 *   <li>签到：{@code msg} 承载幂等语义——首次「签到成功」（{@code credited=true}），
 *       今日重复「今日已签到」（{@code credited=false}），余额均回带；</li>
 *   <li>投币：成功恒为「投币成功」+ 新余额；拒绝走 400/404/409（文案见
 *       {@code architecture/domain/coin/coin.md} §七）。</li>
 * </ul>
 * 两个端点都回带新余额 ⇒ 前端签到 / 投币后无需再拉一次 {@code /api/v1/users/me}。
 */
@RequiresLogin
@RestController
public class CoinController {

    private final CoinService coinService;

    public CoinController(CoinService coinService) {
        this.coinService = coinService;
    }

    /** 每日签到 check-in（幂等）。无入参；返回新余额 + 本次是否到账。 */
    @PostMapping("/api/v1/users/me/check-ins")
    public ApiResponse<SignInVO> signIn(@CurrentUserId long userId) {
        SignInVO vo = coinService.signIn(userId);
        return vo.credited()
                ? ApiResponse.success("签到成功", vo)
                : ApiResponse.success("今日已签到", vo);
    }

    /**
     * 给内容投币（一枚或两枚）。
     *
     * <p>{@code contentId} 进路径（{@code @PathVariable}，`T58` 契约变更）；
     * {@code count} 走 {@code @RequestParam}（form 或 query 均可）。
     * 缺参 / 类型不符由全局异常出口归 400；业务拒因（参数 / 404 / 409）由 Service 抛出。
     *
     * @param contentId 内容 id（路径）
     * @param count     投出的枚数（只能是 1 或 2）
     * @return 投币后的余额（一位小数）
     */
    @PostMapping("/api/v1/contents/{id}/coins")
    public ApiResponse<CoinBalanceVO> add(@CurrentUserId long userId,
                                          @PathVariable("id") long contentId,
                                          @RequestParam int count) {
        CoinBalanceVO vo = coinService.add(userId, contentId, count);
        return ApiResponse.success("投币成功", vo);
    }
}
