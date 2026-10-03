package io.github.yjhhhaaa06.videoweb.common.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * 统一单飞组件（第三批 T3 / 账 B1）——TV {@code cache.SingleFlight} 的承接。
 *
 * <h2>为什么需要它（缓存击穿）</h2>
 * 同一个热 key 过期/被清的**瞬间**，并发请求会全部 miss 并各自回源 DB——
 * 回源次数与并发数成正比，正是"缓存击穿"。单飞让同 key 的并发 miss **只触发一次 loader**，
 * 其余线程等待并共享同一结果。它只解决"同一瞬间重复打 DB"，与 TTL / 空标记 / 失效策略正交。
 *
 * <h2>TV 的三个配套约束（逐字沿用）</h2>
 * <ol>
 *   <li><b>loader 失败必须 remove</b>：否则把失败结果缓存给后续请求（LL 约束 ①）；</li>
 *   <li><b>无论成败都清理 inFlight 项</b>：防 key 无限增长内存泄漏（LL 约束 ②）；</li>
 *   <li><b>进程内锁只对单实例有效</b>：多实例需分布式锁（当前单实例够用，不过度设计）。</li>
 * </ol>
 *
 * <h2>实现（TV 原样：{@link FutureTask} + {@link ConcurrentHashMap#putIfAbsent}）</h2>
 * leader 线程 {@code run()} 执行 loader；follower 拿到同一个 {@link FutureTask} 并 {@code get()} 等结果。
 * 失败时对首个等待线程抛原异常（RuntimeException 原样、其余包装），其余线程拿到相同的失败后同样移除条目。
 *
 * <h2>接线位置</h2>
 * {@link CacheAside}（content 详情三态读）、{@code LikeCache} / {@code FollowCache}（各自三态机的
 * miss 回填）、{@code ContentCache.ensureIndex}（索引懒重建）。降级路径**不接**：
 * like/follow 批量降级是按请求 id 子集作答，用单一数据 key 做单飞 key 会让不同 id 子集**串结果**。
 * 批量读（{@code CacheAside.getBatch}）的 miss 也**不接**：它一趟批量装载，已消除逐 key 往返，
 * 无需再逐 key 单飞。
 */
@Component
public class SingleFlight {

    private final ConcurrentHashMap<String, FutureTask<?>> inFlight = new ConcurrentHashMap<>();

    /**
     * 执行单飞加载：同 key 并发只执行一次 {@code loader}，其余线程等待同一结果。
     *
     * @param key    单飞 key（通常 = 缓存数据 key；不同域的 key 形状不同，天然不冲突）
     * @param loader 实际加载动作（返回值语义由调用方定义，可返回 {@code null}）
     * @return loader 的结果
     * @throws RuntimeException loader 抛出的运行时异常**原样重抛**（如 DB 的
     *                          {@code DataAccessException} 不被吞掉、不会伪装成"无数据"）
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Callable<T> loader) {
        FutureTask<T> task = new FutureTask<>(loader);
        FutureTask<?> existing = inFlight.putIfAbsent(key, task);
        if (existing != null) {
            task = (FutureTask<T>) existing;
        } else {
            task.run();
        }
        try {
            T result = task.get();
            inFlight.remove(key, task);   // 成功清理，防 key 无限增长（约束 ②）
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            inFlight.remove(key, task);
            throw new RuntimeException("单飞加载被中断, key=" + key, e);
        } catch (ExecutionException e) {
            inFlight.remove(key, task);   // 失败也必须移除，防缓存失败结果（约束 ①）
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("单飞加载失败, key=" + key, cause);
        }
    }

    /** 当前在飞任务数（观测 / 测试用途）。 */
    public int inFlightCount() {
        return inFlight.size();
    }
}
