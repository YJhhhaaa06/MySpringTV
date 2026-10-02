package io.github.yjhhhaaa06.videoweb.common.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 统一 **Cache-Aside** 封装（JSON 值版）——承接 TV {@code cache.CacheAside}（624 行）的精简形态。
 *
 * <h2>三态读（TV NEEDS 4.3，逐字保留）</h2>
 * <pre>
 * hit-data   数据 key 命中 → 反序列化返回（并滑动续期）
 * hit-empty  空标记 {@code empty:{dataKey}} 命中 → 返回 null，**不回源 DB**（防穿透）
 * miss       两者皆无 → 回源 loader → 有值写数据 key / 无值写空标记
 * Redis 异常 → 降级：只调 loader 返回，**不写回**（TV 的 D4）
 * </pre>
 *
 * <h2>★ 核心原则：缓存只作加速器，任何缓存失败不得导致业务失败</h2>
 * <ul>
 *   <li><b>读路径</b>：miss / Redis 挂 / 脏 JSON → 查 DB 返回（降级，不报错）；</li>
 *   <li><b>写路径</b>：缓存写失败 → **失效（DEL）让读自愈**，绝不"忽略写失败留着旧缓存"
 *       （否则新数据长期不可见）；</li>
 *   <li><b>降级路径不写回</b>：Redis 异常时直接返回 DB 结果，写回交给下次 miss 自愈。</li>
 * </ul>
 *
 * <h2>★ 两种失败必须区分（G-4 / L-5 / F-5）</h2>
 * 本类**只 catch** {@link CacheUnavailableException}。loader 抛出的
 * {@code DataAccessException}（DB 失败）**原样上抛** → 全局出口 500。
 * 这是 S3/S4 定下的纪律，S5 沿用：**降级成空值会把"读不到"伪装成"没有"**。
 *
 * <h2>相对 TV 有意去掉的（逐条记在决策表 G-6，此处只列要点）</h2>
 * <b>去掉</b>：单飞 {@code SingleFlight}（并发 miss 各打一次 DB，正确性不变）、
 * {@code CacheStats} 打点、{@code CacheResult}/{@code CacheStatus} 三态枚举（本类只暴露
 * "值或 null"，够用）、{@code writeBatch}（它只服务 TV 的启动全量重建，而 {@code init()} 本切片不搬）。
 * <b>保留</b>：三态读、空标记（含"数据 key 已存在则不写空标记"的守卫）、写失败 DEL 自愈、
 * TTL 抖动、读命中续期（**空标记从不续期**）、批量读（含批量装载器）、降级不写回。
 *
 * <h2>为什么 {@code extends RedisOps}</h2>
 * 本类需要协议层的 {@code pipeline} / {@code delete} / {@code keyExists} / {@code setString}。
 * {@link RedisOps} 是**抽象基类**（不是 bean——标了 {@code @Component} 会导致按类型注入歧义，
 * 见其类注释），故这里以继承的方式取得协议能力，而不是注入一个共享实例。
 */
@Slf4j
@Component
public class CacheAside extends RedisOps {

    /** TTL 简单抖动 ±10%（TV 4.12），仅作用于数据 key；保底 1 秒。 */
    private static final double JITTER_RATIO = 0.1;
    private static final long MIN_TTL_SECONDS = 1;

    private final JsonCodec codec;

    public CacheAside(StringRedisTemplate redis, JsonCodec codec) {
        super(redis);
        this.codec = codec;
    }

    // ========================================================================
    // 单 key 读
    // ========================================================================

    /**
     * 三态 Cache-Aside 读（带回填）。
     *
     * @param loader 回源加载器：返回 {@code null} = **确认无数据**（允许写空标记）；
     *               抛异常 = **加载失败**（原样上抛，**不写空标记、不 DEL 数据 key**）
     * @return 缓存/DB 的值；{@code null} = 已确认无数据
     */
    public <T> T get(String dataKey, Class<T> type, Supplier<T> loader, Duration ttl) {
        Probe probe;
        try {
            probe = probeAndRenew(dataKey, ttl);
        } catch (CacheUnavailableException e) {
            log.warn("缓存读异常，降级直查 DB: key={}", dataKey, e);
            return loader.get();
        }
        if (probe.emptyMarker()) {
            return null;                                   // hit-empty：已确认无数据
        }
        if (probe.json() != null) {
            try {
                return codec.fromJson(probe.json(), type);  // hit-data
            } catch (CacheUnavailableException e) {
                log.warn("缓存值不可信（脏 JSON），本次降级直查 DB（不回填）: key={}", dataKey);
                return loader.get();
            }
        }
        // miss：回源 + 回填（loader 的 DB 异常原样上抛，见类注释）
        T value = loader.get();
        if (value != null) {
            writeOrInvalidate(dataKey, value, ttl);
        } else {
            markEmpty(dataKey);
        }
        return value;
    }

    // ========================================================================
    // 批量读
    // ========================================================================

    /**
     * 批量 Cache-Aside 读：一趟 pipeline 拉全部 key（EXISTS 空标记 + GET + 续期），
     * 三态语义与单 key {@link #get} **完全一致**。
     *
     * <p>为什么必须批量：内容列表（`/start` / `/search` / `/profile`）一次要读十到上百条内容，
     * 逐条往返会让"页成本 ∝ 条数 × 3"；TV 的 T8 引 {@code getBatch} 就是为了这一条。
     *
     * <p><b>装载合并</b>（TV 第五期 T2）：miss 子集与整批降级子集走一次
     * {@code batchLoader}（常量趟数，而不是逐 key 各查一次 DB）。
     * {@code batchLoader == null} 时退化为逐 key 装载。
     *
     * @param loader      key → 单 key 加载器（**也用于 batchLoader 缺失时的逐 key 装载**）
     * @param batchLoader 批量装载器；实现约定见 {@link BatchLoader}
     * @return {@code dataKey → value} 全量 Map（**null 值合法** = hit-empty / 加载为空）
     */
    public <T> Map<String, T> getBatch(List<String> dataKeys, Class<T> type,
                                       Function<String, T> loader, BatchLoader<T> batchLoader,
                                       Duration ttl) {
        if (dataKeys == null || dataKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, T> result = new HashMap<>();
        List<String> missed = new ArrayList<>();
        boolean cacheUnavailable = false;
        try {
            List<Object> raw = pipeline(o -> {
                for (String key : dataKeys) {
                    o.hasKey(CacheKeys.empty(key));
                    o.opsForValue().get(key);
                    // 续期无条件入列：hit-data 时生效；hit-empty / miss 时数据 key 不存在、
                    // EXPIRE 返回 0 无效果。**空标记 key 从不续期**（防"假空"窗口延长）。
                    o.expire(key, jitter(ttl));
                }
            });
            for (int i = 0; i < dataKeys.size(); i++) {
                String key = dataKeys.get(i);
                boolean empty = Boolean.TRUE.equals(raw.get(i * 3));
                String json = (String) raw.get(i * 3 + 1);
                if (empty) {
                    result.put(key, null);
                    continue;
                }
                if (json == null) {
                    missed.add(key);
                    continue;
                }
                try {
                    result.put(key, codec.fromJson(json, type));
                } catch (CacheUnavailableException e) {
                    log.warn("批量缓存反序列化失败（该 key 按 miss 处理并自愈回填）: key={}", key);
                    missed.add(key);
                }
            }
        } catch (CacheUnavailableException e) {
            log.warn("批量缓存读异常，整批降级直查 DB（不写回）: keys={}", dataKeys.size(), e);
            cacheUnavailable = true;
        }

        if (cacheUnavailable) {
            // 降级不写回（D4）；DB 异常原样上抛
            Map<String, T> loaded = load(dataKeys, loader, batchLoader);
            for (String key : dataKeys) {
                result.put(key, loaded.get(key));
            }
            return result;
        }

        if (!missed.isEmpty()) {
            Map<String, T> loaded = load(missed, loader, batchLoader);
            for (String key : missed) {
                T value = loaded.get(key);
                boolean missingEntry = !loaded.containsKey(key);
                if (missingEntry) {
                    // 契约违规（批量 loader 漏 key）⇒ 按"加载失败"处理：宁可多一次 DB 查询，
                    // 绝不写假空标记（对齐 markEmpty 守卫的"宁可少写，绝不误写"）。
                    log.warn("批量装载未覆盖该 key（按加载失败处理，不写空标记）: key={}", key);
                    result.put(key, null);
                    continue;
                }
                if (value != null) {
                    writeOrInvalidate(key, value, ttl);
                } else {
                    markEmpty(key);
                }
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * 批量装载器契约：一次装载多个 key 的值，替代逐 key 调 loader。
     *
     * <p>实现约定：
     * <ul>
     *   <li><b>必须为每个请求 key 给出条目</b>——无数据用 {@code null}
     *       （= 确认无数据，允许写空标记）；漏 key 视为契约违规，按"加载失败"处理；</li>
     *   <li>整批加载失败（如 {@code DataAccessException}）直接抛出。</li>
     * </ul>
     */
    @FunctionalInterface
    public interface BatchLoader<T> {
        Map<String, T> load(List<String> dataKeys);
    }

    /** 逐 key 或批量装载（{@code batchLoader == null} 时退化为逐 key）。 */
    private <T> Map<String, T> load(List<String> keys, Function<String, T> loader,
                                    BatchLoader<T> batchLoader) {
        if (batchLoader != null) {
            Map<String, T> loaded = batchLoader.load(keys);
            return loaded == null ? Map.of() : loaded;
        }
        Map<String, T> loaded = new HashMap<>(keys.size());
        for (String key : keys) {
            loaded.put(key, loader.apply(key));
        }
        return loaded;
    }

    // ========================================================================
    // 写
    // ========================================================================

    /**
     * 写数据 key（带 TTL 抖动）并**同步清除空标记**（防"假空"窗口：若此前读不到写过
     * {@code empty:}，不清除会让读路径最长 60s 返回空）。失败 → 失效让读自愈，**不抛出**。
     */
    public void writeOrInvalidate(String dataKey, Object value, Duration ttl) {
        try {
            String json = codec.toJson(value);
            Duration effective = jitter(ttl);
            pipeline(o -> {
                o.opsForValue().set(dataKey, json, effective);
                o.delete(CacheKeys.empty(dataKey));
            });
        } catch (CacheUnavailableException e) {
            log.warn("缓存写失败，失效 key 让读自愈: key={}", dataKey, e);
            invalidate(dataKey);
        }
    }

    /**
     * 写空标记（独立 key + 短 TTL）。
     *
     * <p><b>存在守卫</b>：数据 key 已存在（并发回填 / 业务刚写入真数据）时**跳过**，
     * 防止空标记把真数据固化成 60s 假空（TV 的 T3/N3 修复）。
     * 守卫检查失败同样归入"不写"分支——**宁可少写空标记（多一次 DB 查询），绝不误写（假空）**。
     *
     * <p>残余竞态（TV 已接受）：{@code exists} 检查与 {@code setex} 之间的毫秒间隙内并发写入时，
     * 空标记可能覆盖其上——数据 key 未被删，空标记 60s 过期或下次业务写即自愈，无真数据丢失。
     * TV 的 follow 侧后来用 Lua 消掉了这个窗口（{@code FollowRedisOps.MARK_EMPTY_LUA}）；
     * 本类沿用 TV {@code CacheAside} 的简单形态，因为内容/评论侧的失效点都会 DEL 空标记。
     */
    public void markEmpty(String dataKey) {
        try {
            if (!keyExists(dataKey)) {
                setString(CacheKeys.empty(dataKey), CacheKeys.EMPTY_MARKER_VALUE,
                        Duration.ofSeconds(CacheKeys.EMPTY_MARKER_TTL_SECONDS));
            }
        } catch (CacheUnavailableException e) {
            log.warn("空标记写入失败（读路径将视同 miss 走 DB 自愈）: key={}", dataKey, e);
        }
    }

    /**
     * 业务显式失效：逐个 DEL 数据 key 及其空标记。全程 best-effort **不抛出**。
     *
     * <p>用于"写后失效让读自愈"（如改文案 / 开关评论区 / 点赞数变化 / 删内容级联）。
     */
    public void invalidate(String... dataKeys) {
        for (String dataKey : dataKeys) {
            try {
                delete(dataKey, CacheKeys.empty(dataKey));
            } catch (CacheUnavailableException e) {
                // DEL 也失败（Redis 挂）⇒ 读路径整体降级走 DB，仍然一致，不产生永久不可见窗口
                log.warn("缓存失效也失败（疑似 Redis 异常），读路径将降级走 DB: key={}", dataKey, e);
            }
        }
    }

    // ========================================================================
    // 内部
    // ========================================================================

    /** 一趟 pipeline 探测 `[空标记, 数据 JSON]` 并顺带续期数据 key。 */
    private Probe probeAndRenew(String dataKey, Duration ttl) {
        List<Object> raw = pipeline(o -> {
            o.hasKey(CacheKeys.empty(dataKey));
            o.opsForValue().get(dataKey);
            o.expire(dataKey, jitter(ttl));
        });
        boolean empty = raw.size() > 0 && Boolean.TRUE.equals(raw.get(0));
        String json = raw.size() > 1 ? (String) raw.get(1) : null;
        return new Probe(empty, json);
    }

    /** 数据 key TTL 应用 ±10% 简单抖动并以 1 秒保底。 */
    private static Duration jitter(Duration base) {
        long seconds = base.toSeconds();
        if (seconds <= 0) {
            return base;
        }
        long delta = (long) (seconds * JITTER_RATIO * ThreadLocalRandom.current().nextDouble());
        long ttl = seconds + (ThreadLocalRandom.current().nextBoolean() ? delta : -delta);
        return Duration.ofSeconds(Math.max(ttl, MIN_TTL_SECONDS));
    }

    /** 单 key 探测结果。 */
    private record Probe(boolean emptyMarker, String json) {
    }
}
