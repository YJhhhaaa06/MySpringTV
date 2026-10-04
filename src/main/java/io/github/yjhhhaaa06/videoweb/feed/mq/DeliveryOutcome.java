package io.github.yjhhhaaa06.videoweb.feed.mq;

/**
 * 单次投递的**三态结果**（第三批 T5 / 账 B9 引入）：{@link FeedDeliveryBuffer} 据此区分
 * "该入补偿缓冲等恢复后重放"与"重放也没有意义、就地丢弃"。
 *
 * <h2>为什么必须三态（二态的 boolean 不够）</h2>
 * 补偿缓冲成立的前提是"**重放会成功**"。若把两种失败混成一个 {@code false}：
 * <ul>
 *   <li><b>{@link #UNAVAILABLE}</b>（broker 不可达 / 连接级故障）：**重放有意义** ⇒ 入缓冲；</li>
 *   <li><b>{@link #UNSENDABLE}</b>（载荷不可序列化）：**重放必然同样失败** ⇒ 若也入缓冲，
 *       该消息会永久占住缓冲位（并在每次探测里重复失败一次），既是内存泄漏也是噪声。
 *       它是**编程错误**，就地丢弃 + WARNING 才是正确处置。</li>
 * </ul>
 *
 * <p>注意 {@link #UNAVAILABLE} 覆盖"连接确定不可达"与"发送中途连接断开"两类——
 * 后者严格说状态不明（可能已投出），本仓按 **至少一次** 口径重放，**依赖消费侧幂等**
 * （收件箱 {@code INSERT IGNORE} / 重建整窗替换），与 TV 同款依赖（《遗留台账》B9 原文）。
 */
public enum DeliveryOutcome {

    /** 已交给客户端发送（写 socket 成功，**不等于** broker 已确认——本仓不启用 publisher-confirm）。 */
    SENT,

    /** broker 不可达 / 连接级故障：本次**确定或可能**未投出，可入补偿缓冲重放。 */
    UNAVAILABLE,

    /** 载荷无法序列化（编程错误）：重放必然同样失败，不入缓冲。 */
    UNSENDABLE
}
