package io.github.yjhhhaaa06.videoweb.common.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 缓存打点（第三批 T2 / 账 B3）——TV {@code cache.CacheStats} 的 Micrometer 形态。
 *
 * <h2>TV → 新实现</h2>
 * TV 是自研 {@code CacheStats}：六类事件计数器 + 每 N 次访问打一行摘要日志。它随缓存框架一起
 * 被裁掉（《迁移参照系》§三 原计划"收敛为 Actuator"，但**从未发生**，故登记为 B3）。
 * 本项目把"计数"交给 Micrometer、"读取"交给 Actuator ——不写摘要日志（那是 TV 在没有
 * 指标系统时的替代品），指标本身即可被抓取、聚合、告警。
 *
 * <h2>事件口径（与 TV {@code CacheStats.Event} 一一对应，名字逐字沿用便于对照）</h2>
 * <table>
 *   <caption>六事件</caption>
 *   <tr><th>tag 值</th><th>含义</th></tr>
 *   <tr><td>{@code hit_data}</td><td>数据 key 命中且反序列化成功（脏 JSON 归降级）</td></tr>
 *   <tr><td>{@code hit_empty}</td><td>空标记命中 ⇒ 不回源 DB（防穿透生效）</td></tr>
 *   <tr><td>{@code miss}</td><td>两者皆无 ⇒ 回源</td></tr>
 *   <tr><td>{@code load}</td><td>回源装载（含批量装载）</td></tr>
 *   <tr><td>{@code degrade}</td><td>Redis 不可用 / 值不可信 ⇒ 降级查 DB</td></tr>
 *   <tr><td>{@code write_fail}</td><td>缓存写失败 ⇒ 失效让读自愈</td></tr>
 * </table>
 *
 * <h2>指标形态</h2>
 * 单指标 {@code cache.events}，维度是 tag {@code event}（六值）。**不**加域维度：
 * 打点接入点在共享引擎 {@link CacheAside}，它不知道自己正被哪个域使用——
 * 硬加一个"猜测的域标签"只会给出错的维度，比没有维度更坏。
 *
 * <h2>★ 覆盖边界（如实声明，勿当成"全仓缓存都打点了"）</h2>
 * 六事件接在 {@link CacheAside}（content 域的共享三态引擎）。like / follow / comment / feed
 * 四域在 S3–S9 各自实现了自己的三态机（有意未抽公共框架，见各域类注释），
 * 本任务**未逐个接线**——打点是"低严重度、无正确性含义"的观测项（B3），
 * 为此改动四个域的读路径属范围外扩张。该边界已登记在《决策留痕表》。
 *
 * <h2>失败不得影响业务</h2>
 * 打点只是加一，本身不会抛；调用点仍不 try/catch ——Micrometer 的 registry 是进程内对象，
 * 不存在"打点失败"的正常路径（若将来接了抛异常的注册表，须在此收口，不要在调用点各写一遍）。
 */
@Component
public class CacheMetrics {

    /** 指标名（唯一源；测试与 Actuator 路径都引用它）。 */
    public static final String METRIC_NAME = "cache.events";

    /** 维度键。 */
    public static final String TAG_EVENT = "event";

    private final MeterRegistry registry;

    public CacheMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 记一次事件。{@code counter(name, tags)} 对同一组 tag 返回同一个计数器（幂等），无预热成本。 */
    public void record(Event event) {
        registry.counter(METRIC_NAME, TAG_EVENT, event.tag()).increment();
    }

    /** 六类事件（tag 值即 TV {@code CacheStats.Event} 的名字，逐字沿用）。 */
    public enum Event {
        HIT_DATA("hit_data"),
        HIT_EMPTY("hit_empty"),
        MISS("miss"),
        LOAD("load"),
        DEGRADE("degrade"),
        WRITE_FAIL("write_fail");

        private final String tag;

        Event(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }
}
