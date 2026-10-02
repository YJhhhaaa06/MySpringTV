package io.github.yjhhhaaa06.videoweb.coupon.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 抢券请求。
 *
 * <p>承接 TV {@code com.itheima.coupon.model.dto.GrabCouponRequest}（单字段 {@code couponId}）。
 * TV 由 {@code RequestParser.parse(req, GrabCouponRequest.class)} 解析，**无任何校验**——
 * 缺字段时 {@code long} 取默认 0，一路走到 SQL 得到 "库存不足"（409），
 * 把"参数缺失"误报成"业务冲突"。
 *
 * <p>故此处补声明式约束：缺失/非正数 → 400 参数错误（由 {@code @Valid} +
 * {@code GlobalExceptionHandler} 收口）。这是 SOP 步骤 6「手写校验 → 声明式约束」
 * 的落地，属**有意改进**（同 U-3 口径），记于提交信息。
 *
 * <p>用包装类型 {@code Long} 而非 TV 的 {@code long}：只有包装类型才表达得出"字段缺失"，
 * 原始类型会让 null 静默变成 0，正是上段那个误报的成因。
 */
public record GrabCouponRequest(

        @NotNull(message = "优惠券ID不能为空")
        @Positive(message = "优惠券ID必须为正数")
        Long couponId
) {
}
