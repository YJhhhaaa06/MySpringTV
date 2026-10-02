package io.github.yjhhhaaa06.videoweb.coupon.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 优惠券（{@code /coupon/list} 的列表项）。
 *
 * <p>承接 TV {@code CouponDao.rowToMap} 手搭的 {@code Map<String,Object>}——
 * 键集合与取值口径逐字对齐（{@code id/title/stock/beginTime/endTime/createTime}），
 * 只是把「靠字符串键约定形状」换成编译期可校验的具名类型。
 *
 * <p>本类同时充当 <b>Mapper 行类型</b>与 <b>API 载荷形状</b>两个角色。
 * 旧实现用 {@code Map} 正是为了"一个形状两处用"，这里保留该设计，
 * 不让它引入一层多余的 row→VO 拷贝。
 *
 * <h2>为什么显式声明 {@code @JsonFormat} 而不是让它走 {@code LocalDateTime.toString()}</h2>
 * 旧代码把时间写成 {@code rs.getTimestamp(...).toLocalDateTime().toString()}，
 * 形状是 ISO 但**长度可变**（秒为 0 时会省略 {@code :00}）。新实现固定为
 * {@code yyyy-MM-dd'T'HH:mm:ss}：同一形状，但不再随秒位漂移——
 * 显式优于隐式。{@code LocalDateTime} 无时区，该 pattern 不触发任何时区换算。
 * （库表列是 {@code datetime} 精度 0，无小数秒，故不存在精度损失。）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponVO {

    private Long id;
    private String title;
    private Integer stock;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime beginTime;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime endTime;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createTime;
}
