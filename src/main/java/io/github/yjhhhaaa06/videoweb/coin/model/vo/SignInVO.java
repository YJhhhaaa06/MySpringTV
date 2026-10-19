package io.github.yjhhhaaa06.videoweb.coin.model.vo;

import java.math.BigDecimal;

/**
 * 签到回执（{@code POST /coin/signIn} 的 {@code data}）。
 *
 * <p>形状按 {@code architecture/domain/coin/coin.md} §三 冻结：
 * {@code { "balance": 1.0, "credited": true }}。
 *
 * @param balance  签到后的**硬币余额**，恒为一位小数的 {@link BigDecimal}
 *                 （{@code 1.0} / {@code 1.5}）。⚠️ 不用 {@code double}：{@code 0.1} 这类值
 *                 在二进制浮点下有尾差，而硬币余额是要**精确相等**比较的量
 *                 （设计 §四「读出：deci / 10 保留一位小数，精确、不舍入」）。
 * @param credited {@code true} = 本次真的到账（{@code +1} 枚）；
 *                 {@code false} = 今日已签过，调用幂等、余额不变。
 *                 —— 重复签到是合法结果（200），不是错误，故要靠它区分，而不是靠 HTTP 状态码。
 */
public record SignInVO(BigDecimal balance, boolean credited) {
}
