package io.github.yjhhhaaa06.videoweb.coin.model.vo;

import java.math.BigDecimal;

/**
 * 投币回执（{@code POST /coin/add} 的 {@code data}）。
 *
 * <p>形状按 {@code architecture/domain/coin/coin.md} §三 冻结：{@code { "balance": 1.5 }} ——
 * 写操作**回带新余额**，前端投币后无需再拉一次 {@code /user/me}。
 *
 * <p>与 {@code SignInVO} 刻意分开（而不是复用同一个 record 加个可空字段）：
 * 投币没有"本次是否到账"的幂等语义（投币不幂等，重复投是 409 而非 200），
 * 两个端点的 {@code data} 键集不同，合成一个只会让契约形状变模糊。
 *
 * @param balance 投币后的**硬币余额**，恒为一位小数的 {@link BigDecimal}
 *                （{@code 0.0} / {@code 1.0} / {@code 1.5}）。
 *                ⚠️ 不用 {@code double}：{@code 0.1} 这类值有二进制尾差，而余额要**精确相等**比较
 *                （设计 §四「读出：deci / 10 保留一位小数，精确、不舍入」）。
 */
public record CoinBalanceVO(BigDecimal balance) {
}
