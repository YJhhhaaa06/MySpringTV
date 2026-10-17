package io.github.yjhhhaaa06.videoweb.common.cache;

/**
 * 缓存回源信号（限流专项 <b>T26</b> 的"信号②"）—— **本线程**自上次清理以来是否发生过
 * "没走成缓存"的读回源。
 *
 * <h2>它解决什么</h2>
 * 限流的成本信号②（"这一次查询有没有命中缓存"）是**本请求运行期事实**：缓存读发生在请求
 * 处理中，而限流闸门在 {@code DispatcherServlet} 之前 ⇒ 门时取不到，只能在请求处理完后
 * **结算**。本类就是"请求处理期间是否发生过回源"的运行期载体：各缓存类在自己的 DB 回源点
 * 调 {@link #mark()}，限流器在请求收尾处 {@link #consume()}。结算口径（门时按基础价扣、
 * 门后按差额 {@code base × (multiplier − 1)} 补扣）见 {@code architecture/tech/限流.md} §四。
 *
 * <h2>★ 三条设计口径</h2>
 * <ul>
 *   <li><b>二值，不计数</b>：只记录"发生过"，不累计次数。次数加权需要"次数 → 系数"的换算
 *       函数与封顶值，而它们与本仓所有限流数值一样**无依据可拍**；二值保留"这次走了贵路径"
 *       这条稳定信息，并把惩罚天然封顶在一次乘数内（冷启动/深翻不会把正常用户 ×(3n)）。
 *       升级为计数 = 各调用点改累计 + 差额公式改函数，**接线位置不变**。</li>
 *   <li><b>线程封闭</b>：{@link ThreadLocal} 天然不跨线程 —— MQ 消费线程 / 去抖线程上的回源
 *       **不会**被算进任何请求的账（红线："回传机制不得泄漏到别的线程"）。后台线程上残留的
 *       标记无人读取，且每线程只有一个布尔，不增长、无泄漏。</li>
 *   <li><b>清理点唯一</b>：{@link #clear()} / {@link #consume()} 只由 {@code RateLimitFilter}
 *       驱动（进入闸门先清、请求收尾取走）——同 {@code RequestIdFilter} 对 MDC 的
 *       "唯一 set/clear 点"纪律。跳过闸门的路径（静态资源等）不读也不清，
 *       闸门入口的 clear 保证线程池复用不串味到下一个请求。</li>
 * </ul>
 *
 * <h2>★ 接线纪律（新增读回源点必须一起接线）</h2>
 * 口径 = "**凡没走成缓存的读都算**"：miss 回源、Redis 降级回源、脏值按 miss 回源、
 * 部分态的 DB 兜底、以及缺省全量这类不经过缓存的读路径。新加缓存读回源点时，
 * 必须在**发起 DB 查询的那一行**前补 {@link #mark()}，否则该回源在信号②里会静默消失。
 */
public final class CacheMissSignal {

    /** 每线程一个布尔：true = 自上次清理以来发生过回源。后台线程上的残留值无人读取（无害）。 */
    private static final ThreadLocal<Boolean> MISSED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private CacheMissSignal() {
    }

    /** 标记：本线程发生了"没走成缓存"的读回源（幂等，同一请求内可重复调用）。 */
    public static void mark() {
        MISSED.set(Boolean.TRUE);
    }

    /**
     * 取走并清零：返回自上次清理以来是否发生过回源。
     *
     * <p>限流器的请求收尾处是**唯一**调用点；{@code remove()} 而不是置 false——下次
     * {@code get()} 走 {@code withInitial} 重新起 FALSE，不给"残留脏读"留路径。
     */
    public static boolean consume() {
        boolean missed = Boolean.TRUE.equals(MISSED.get());
        MISSED.remove();
        return missed;
    }

    /** 清零（不读）：限流器进入闸门时先清，保证线程池复用不把上一请求的标记带进本请求。 */
    public static void clear() {
        MISSED.remove();
    }
}
