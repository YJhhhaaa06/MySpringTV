package io.github.yjhhhaaa06.videoweb.coupon.model.vo;

/**
 * 领券成功后的出参（{@code POST /api/v1/coupons/{id}/claims}）。
 *
 * <p>`T59` 前出参是**裸字符串券码**（{@code data: "ABC…"}）；载荷面按
 * 《接口契约.md》§2.5「出参不得是裸 {@code String}」改为具名对象：
 * {@code data: {"couponCode": "…"}}。
 *
 * <p>字段名与「我的优惠券」列表里的 {@code CouponOrderVO.couponCode} 同名 —— 同一个概念
 * 全站一个键，避免"领券回 {@code data}、列表回 {@code couponCode}"这种两套读法。
 *
 * @param couponCode 16 位券码
 */
public record CouponClaimVO(String couponCode) {
}
