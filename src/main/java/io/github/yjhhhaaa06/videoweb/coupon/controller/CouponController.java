package io.github.yjhhhaaa06.videoweb.coupon.controller;

import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.Public;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.coupon.model.vo.CouponClaimVO;
import io.github.yjhhhaaa06.videoweb.coupon.model.vo.CouponOrderVO;
import io.github.yjhhhaaa06.videoweb.coupon.model.vo.CouponVO;
import io.github.yjhhhaaa06.videoweb.coupon.service.CouponService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 优惠券接口（`T58` 起资源化）。
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
 *       <td>领券 {@code couponId} 进路径（{@code @PathVariable}，`T58` 契约变更，§2.8：claim = 领券）</td></tr>
 *   <tr><td>{@code (Long) req.getAttribute("userId")}（无 null 检查，靠 AuthFilter 兜住）</td>
 *       <td>{@code @CurrentUserId} + {@code @RequiresLogin}</td></tr>
 *   <tr><td>{@code switch} 的 {@code default} 分支手写 {@code NOT_FOUND}</td>
 *       <td>删除（未映射路径由 DispatcherServlet 产出 404）</td></tr>
 * </table>
 *
 * <p><b>路径（`T58` 资源化，依据 {@code 接口契约.md} §2.3 / §2.4 / §2.8）</b>：
 * {@code GET /api/v1/coupons}、{@code GET /api/v1/users/me/coupons}、
 * {@code POST /api/v1/coupons/{id}/claims}（claim = 领券，动词 grab 退役；
 * 创建语义 ⇒ 仍为 POST）。响应体形状不变。
 *
 * <p><b>鉴权（SOP 步骤 6：用 {@code @RequiresLogin} 声明，不去改任何路径清单）</b>——
 * 依据 TV {@code AuthFilter} 的两份静态集合复核：
 * 原 {@code /coupon/grab} 与 {@code /coupon/my} 需登录、{@code /coupon/list} 公开。
 * 旧清单不迁移，但它是本次鉴权口径的**事实来源**，故在 {@code CouponFlowTests} 里逐条固化为测试；
 * 新路径沿用同一结论（list 公开、my/claims 需登录）。
 */
@RestController
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    /** 未结束的优惠券列表。**公开端点**（TV AuthFilter 未保护原 {@code /coupon/list}）。 */
    @Public
    @GetMapping("/api/v1/coupons")
    public ApiResponse<List<CouponVO>> list() {
        return ApiResponse.success(couponService.listAvailableCoupons());
    }

    /** 我的优惠券。TV 中原 {@code /coupon/my} 在 {@code PROTECTED_EXACT} 清单内。 */
    @RequiresLogin
    @GetMapping("/api/v1/users/me/coupons")
    public ApiResponse<List<CouponOrderVO>> my(@CurrentUserId long userId) {
        return ApiResponse.success(couponService.listMyCoupons(userId));
    }

    /**
     * 领券（claim，`T58` 起取代动词 grab）。`T59` 起 {@code data} 是对象
     * {@code {"couponCode": "…"}}（不再回**裸字符串**券码 —— 载荷面 §2.5）。
     *
     * <p>{@code couponId} 进路径（{@code @PathVariable}）：非数字由参数绑定归 400，
     * 不存在的 id 落到业务拒因（409 {@code COUPON_UNAVAILABLE}，deductStock 匹配不到行）。
     * TV 中原 {@code /coupon/grab} 在 {@code PROTECTED_EXACT} 清单内。
     */
    @RequiresLogin
    @PostMapping("/api/v1/coupons/{id}/claims")
    public ApiResponse<CouponClaimVO> grab(@CurrentUserId long userId,
                                           @PathVariable("id") long couponId) {
        return ApiResponse.success(new CouponClaimVO(couponService.grabCoupon(couponId, userId)));
    }
}
