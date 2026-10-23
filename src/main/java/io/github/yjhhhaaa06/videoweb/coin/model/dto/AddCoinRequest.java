package io.github.yjhhhaaa06.videoweb.coin.model.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 投币请求（{@code POST /api/v1/contents/{id}/coins}，`T59` 载荷面）。
 *
 * <p>`T59` 前是 query/form 参数 {@code count}；载荷面按《接口契约.md》§2.5 统一为
 * JSON body {@code {"count": 1|2}}。
 *
 * <p>只约束"必须给"；取值口径（只允许 1 或 2）仍由 {@code CoinService} 判定
 * （业务拒绝只有 {@code msg}，`T57` 边界）。
 */
public record AddCoinRequest(
        @NotNull(message = "投币数不能为空")
        Integer count
) {
}
