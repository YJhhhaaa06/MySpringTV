package io.github.yjhhhaaa06.videoweb.coupon.controller;

import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.coupon.model.dto.GrabCouponRequest;
import io.github.yjhhhaaa06.videoweb.coupon.model.vo.CouponOrderVO;
import io.github.yjhhhaaa06.videoweb.coupon.model.vo.CouponVO;
import io.github.yjhhhaaa06.videoweb.coupon.service.CouponService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 优惠券接口。
 *
 * <p>迁移自 TV {@code com.itheima.coupon.controller.CouponController}——对照片段：
 *
 * <table>
 *   <caption>TV → 新实现</caption>
 *   <tr><th>TV</th><th>新实现</th></tr>
 *   <tr><td>{@code @WebServlet("/coupon/*")} + {@code switch(pathInfo)} 分派
 *           （{@code doGet} 管 list/my、{@code doPost} 管 grab）</td>
 *       <td>{@code @RestController} + {@code @GetMapping}/{@code @PostMapping}（方法即端点，HTTP 方法由注解表达）</td></tr>
 *   <tr><td>{@code RequestParser.parse(req, GrabCouponRequest.class)}</td>
 *       <td>{@code @RequestBody @Valid}</td></tr>
 *   <tr><td>{@code (Long) req.getAttribute("userId")}（无 null 检查，靠 AuthFilter 兜住）</td>
 *       <td>{@code @CurrentUserId} + {@code @RequiresLogin}</td></tr>
 *   <tr><td>{@code switch} 的 {@code default} 分支手写 {@code NOT_FOUND}</td>
 *       <td>删除（未映射路径由 DispatcherServlet 产出 404）</td></tr>
 * </table>
 *
 * <p><b>路径与信封形状沿袭 TV</b>（决策⑧「路径沿袭控制迁移变量」）：
 * {@code GET /coupon/list}、{@code GET /coupon/my}、{@code POST /coupon/grab}，响应体形状不变。
 *
 * <p><b>鉴权（SOP 步骤 6：用 {@code @RequiresLogin} 声明，不去改任何路径清单）</b>——
 * 依据 TV {@code AuthFilter} 的两份静态集合复核：
 * {@code PROTECTED_EXACT} 含 {@code /coupon/grab} 与 {@code /coupon/my}（需登录），
 * {@code PROTECTED_PREFIXES} 无 coupon 项、且 {@code /coupon/list} 未列入 ⇒ 公开。
 * 旧清单不迁移，但它是本次鉴权口径的**事实来源**，故在 {@code CouponFlowTests} 里逐条固化为测试。
 */
@RestController
@RequestMapping("/coupon")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    /** 未结束的优惠券列表。**公开端点**（TV AuthFilter 未保护 {@code /coupon/list}）。 */
    @GetMapping("/list")
    public ApiResponse<List<CouponVO>> list() {
        return ApiResponse.success(couponService.listAvailableCoupons());
    }

    /** 我的优惠券。TV 中该路径在 {@code PROTECTED_EXACT} 清单内。 */
    @RequiresLogin
    @GetMapping("/my")
    public ApiResponse<List<CouponOrderVO>> my(@CurrentUserId long userId) {
        return ApiResponse.success(couponService.listMyCoupons(userId));
    }

    /**
     * 抢券，成功返回 16 位券码（{@code data} 是一个**纯字符串**，不是一个对象——
     * 形状与 TV {@code writeSuccess(resp, couponCode)} 一致）。
     *
     * <p>TV 中该路径在 {@code PROTECTED_EXACT} 清单内。
     */
    @RequiresLogin
    @PostMapping("/grab")
    public ApiResponse<String> grab(@CurrentUserId long userId,
                                    @RequestBody @Valid GrabCouponRequest request) {
        return ApiResponse.success(couponService.grabCoupon(request.couponId(), userId));
    }
}
