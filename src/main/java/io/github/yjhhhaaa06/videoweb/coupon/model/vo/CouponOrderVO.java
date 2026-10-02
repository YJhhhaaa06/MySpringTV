package io.github.yjhhhaaa06.videoweb.coupon.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 我的优惠券（{@code /coupon/my} 的列表项）。
 *
 * <p>承接 TV {@code CouponDao.findOrdersByUserId} 手搭的 {@code Map}——
 * 键集合与取值口径逐字对齐（{@code id/couponId/couponCode/status/title/createTime}）。
 * 注意 {@code title} 来自 JOIN 的 {@code coupon} 表，不是订单自身字段；
 * {@code status}：1=成功 2=已使用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponOrderVO {

    private Long id;
    private Long couponId;
    private String couponCode;

    /** 1=成功 2=已使用 */
    private Integer status;

    private String title;

    /** 时间形状口径同 {@link CouponVO}：固定 pattern，不走 {@code toString()}。 */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createTime;
}
