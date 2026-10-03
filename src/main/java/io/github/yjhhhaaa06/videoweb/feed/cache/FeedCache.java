package io.github.yjhhhaaa06.videoweb.feed.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.config.FeedProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * feed 读缓存（决策⑤「显式封装层」，**不用** {@code @Cacheable}）——承接 TV 的
 * {@code FeedInboxReader} / {@code FeedOutboxReader} / {@code FeedRebuildService} 里
 * **属于缓存**的那部分语义（三态读 / 回源回填 / 降级 / 写后失效）。
 *
 * <h2>两个 key 族（都是**可降级读缓存**，真相源分别是 {@code feed_inbox} / {@code content}）</h2>
 * <pre>
 * feed:inbox:{userId}     ZSet，score = contentId，写者只有读路径（fanout/重建只 DEL 不写）
 * feed:outbox:{authorId}  ZSet，score = contentId，存该作者最近 N 条（大V腿读时拉）
 * </pre>
 * 两键族各自两件套（数据 key + {@code empty:}）——见 {@link CacheKeys#feedInbox} / {@link CacheKeys#feedOutbox}。
 *
 * <h2>★ 两种失败必须区分（G-4 / L-5 / F-5）——但**大V发件箱腿是**有判据的例外</h2>
 * <table>
 *   <caption>失败分类与处置</caption>
 *   <tr><th>失败</th><th>收件箱腿</th><th>大V发件箱腿</th></tr>
 *   <tr><td>Redis 失败（{@link CacheUnavailableException}）</td><td>降级 DB，不影响结果</td>
 *       <td>降级 DB、**不写回**（降级不放量）</td></tr>
 *   <tr><td>DB 失败（{@code DataAccessException}）</td><td><b>原样上抛</b>（→ 500，不留半条时间线）</td>
 *       <td><b>按空处理、不 500</b>（fail-open，TV 原样口径）</td></tr>
 * </table>
 * 大V腿为什么可以 fail-open 而收件箱腿不行：大V腿是**追加的一路**（收件箱腿仍是主路），
 * 其缺席退化为"少显示一批大V内容"（下次请求自愈）；而收件箱腿缺席等于**时间线本身读不出来**。
 * 这与"楼中楼 children 可降级为空数组"是同一判据——**缺席能被响应如实表达**（少一批作者的内容，
 * 用户看到的是"没有新的"，而非错误的"没有"）。这是 TV 明写的裁决，非本实现放松。
 *
 * <h2>有意精简（相对 TV）</h2>
 * <b>去掉</b>：单飞、{@code CacheStats} 打点、{@code ZSetCache}/{@code CacheAside} 通用框架层、
 * {@code partial:} 前缀标记（本仓 S4 起不再使用，见 {@link CacheKeys#feedInbox}）。
 * <b>保留</b>：三态读、空标记（含"数据 key 在则不写"守卫）、写失败失效自愈、TTL 滑动续期、
 * 一趟 pipeline 批量、降级路径的"不写回"。
 */
@Slf4j
@Component
public class FeedCache {

    /** 空标记短 TTL（60s，口径见 {@link CacheKeys#EMPTY_MARKER_TTL_SECONDS}）。命中读**不续期**空标记。 */
    static final Duration EMPTY_MARKER_TTL = Duration.ofSeconds(CacheKeys.EMPTY_MARKER_TTL_SECONDS);

    private final FeedRedisOps ops;
    private final FeedProperties props;

    public FeedCache(FeedRedisOps ops, FeedProperties props) {
        this.ops = ops;
        this.props = props;
    }

    // ========================================================================
    // 收件箱腿（读态闸门通过后才读）
    // ========================================================================

    /**
     * 三态读收件箱窗口，返回 **contentId 降序**。
     *
     * <pre>
     * 空标记命中   → 空列表（"已同步但确实没内容"）
     * 数据 key 命中 → ZRANGE 全量升序 → 内存反序 → 降序
     * miss         → 回源 DB（loader，升序）→ 升序回填 → 反序
     * Redis 失败   → 降级直查 DB（loader），不写回
     * </pre>
     *
     * <p><b>方向一致性</b>：miss 与降级路径返回 loader 原序（SQL 为 {@code ORDER BY content_id ASC}），
     * 命中路径返回 {@code ZRANGE} 升序——两者**同向**，故反序后必为 contentId 降序。
     *
     * @param dbLoader DB 装载器（{@code FeedInboxDao.findInboxContentIds}，升序）。
     *                 <b>其 DB 异常原样上抛</b>（读路径 DB 失败 = 500，不留"半条时间线"）。
     */
    public List<Long> readInbox(long userId, Supplier<List<Long>> dbLoader) {
        String dataKey = CacheKeys.feedInbox(userId);
        String emptyKey = CacheKeys.empty(dataKey);
        try {
            Set<String> existing = ops.existingOf(dataKey, emptyKey);
            if (existing.contains(emptyKey)) {
                return List.of();
            }
            if (existing.contains(dataKey)) {
                return descending(ops.zRangeAll(dataKey, props.inboxTtl()));
            }
        } catch (CacheUnavailableException e) {
            log.warn("收件箱缓存读失败，降级 DB: userId={}", userId, e);
            // DB 失败必须上抛（不被此 catch 吞掉）：loader 在本 catch 块内执行，异常直接冒泡
            return descending(dbLoader.get());
        }
        // miss：DB 是真理源，顺带回填（回填失败不影响本次结果）
        List<Long> ascending = ascending(dbLoader.get());
        backfillInbox(dataKey, emptyKey, ascending);
        return descending(ascending);
    }

    /**
     * 单批失效收件箱读缓存：**一次 DEL 多键**（每用户两件套）——一趟往返覆盖整批粉丝
     * （TV 原口径："一轮批内失效借一次 Redis 连接，不逐粉丝各借还一次"）。
     *
     * @return {@code true} = 命令已发出；{@code false} = Redis 不可用（已记录，调用方应停止后续失效尝试）
     */
    public boolean invalidateInboxes(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return true;
        }
        String[] keys = new String[userIds.size() * 2];
        for (int i = 0; i < userIds.size(); i++) {
            String dataKey = CacheKeys.feedInbox(userIds.get(i));
            keys[2 * i] = dataKey;
            keys[2 * i + 1] = CacheKeys.empty(dataKey);
        }
        try {
            ops.delete(keys);
            return true;
        } catch (CacheUnavailableException e) {
            // 本链唯一捕获点 → 持栈（"DEL 失败不停写"：DB 真相一路继续，残留缓存由 TTL / 重建 / 下次写兜底）
            log.warn("写扩散收件箱缓存失效失败（读自愈兜底，DB 真相不受影响）, fanCount={}", userIds.size(), e);
            return false;
        }
    }

    /**
     * 大V发件箱写后失效（**无条件**，对普通作者也执行）：一次 DEL 两件套。
     *
     * <p>为什么无条件：outbox 缓存的读者只有"大V腿"，但**大V身份会变**（涨粉 / 掉粉）；
     * 若只在大V分支里失效，作者从大V降为普通后其 outbox 不再被发布刷新，而读缓存又
     * "命中即滑动续期" ⇒ 重新升为大V时可能读到陈旧窗口。无条件失效把 key 的**内容新鲜度**
     * 与"作者当前是否大V"解耦（代价 = 每次发布多一条 DEL，量级可忽略）。
     *
     * @return {@code true} = 命令已发出；{@code false} = Redis 不可用（已记录，调用方应停止后续失效尝试）
     */
    public boolean invalidateOutbox(long authorId) {
        String dataKey = CacheKeys.feedOutbox(authorId);
        try {
            ops.delete(dataKey, CacheKeys.empty(dataKey));
            return true;
        } catch (CacheUnavailableException e) {
            log.warn("写扩散大V发件箱缓存失效失败（读自愈兜底，DB 真相不受影响）, authorId={}", authorId, e);
            return false;
        }
    }

    // ========================================================================
    // 收件箱重建去重锁
    // ========================================================================

    /** 重建锁 TTL（秒）：仅兜"进程猝死未释放"，到点即视为可重入。TV 包内常量 60s。 */
    static final Duration REBUILD_LOCK_TTL = Duration.ofSeconds(60);

    /**
     * 取重建锁（{@code SET key token NX EX ttl}）。
     *
     * @return {@code true} = 本次真正执行重建；{@code false} = 已被并发持有 / Redis 不可用（降级跳过）
     */
    public boolean tryAcquireRebuildLock(long userId, String token) {
        try {
            return ops.tryLock(CacheKeys.feedRebuildLock(userId), token, REBUILD_LOCK_TTL);
        } catch (CacheUnavailableException e) {
            // 该链唯一捕获点 → 持栈；重建整体依赖 Redis，降级跳过
            log.warn("收件箱重建跳过（Redis 不可用，降级）, userId={}", userId, e);
            return false;
        } catch (RuntimeException e) {
            // 契约"绝不抛"的兜底（RedisOps 还有非 CacheUnavailableException 的出口）
            log.error("收件箱重建取锁异常（已兜底，跳过本次重建）, userId={}", userId, e);
            return false;
        }
    }

    /**
     * 释放重建锁（Lua CAS，只删自己的 token）。失败只记 WARNING、**不抛**（TTL 兜底）。
     *
     * <p>此处**不带栈**：释放失败不改变本次结果（TTL 到点自动可重入），且同链若发生 Redis 故障
     * 已在前段持过栈——避免"一次失败两条带堆栈记录"。
     */
    public void releaseRebuildLock(long userId, String token) {
        try {
            ops.releaseLock(CacheKeys.feedRebuildLock(userId), token);
        } catch (RuntimeException e) {
            log.warn("收件箱重建锁释放失败（TTL 兜底，无影响）, userId={}", userId);
        }
    }

    // ========================================================================
    // 大V发件箱腿（读谁的发件箱）
    // ========================================================================

    /**
     * 批量读若干大V作者的"最近 N 条"（**作者 → 降序 ids**）。
     *
     * <pre>
     * 一趟 pipeline：每作者 EXISTS empty / EXISTS key / ZREVRANGE 0 N-1 / EXPIRE
     *   empty 命中 → 空
     *   data  命中 → ZREVRANGE 结果（已降序）
     *   miss       → 一次带归属的批量 DB 回源 → 一趟 pipeline 回填（非空 ZADD / 空 markEmpty）
     * Redis 失败   → 全量 DB 回源、**不写回**
     * 回源失败     → 本批**按空处理**、**不写空标记**（fail-open，避免把一次失败固化成"该作者没内容"）
     * </pre>
     *
     * @param authorIds 大V作者集合（非空；调用方保证）
     * @param dbLoader  带归属的批量回源（{@code ContentDao.findRecentContentIdsByAuthor} +
     *                  逐作者降序重排）。<b>其失败被本方法按 fail-open 吞掉</b>（大V腿的设计裁决，见类注释）。
     * @return 作者 → 最近 N 条（降序）；**每个请求作者都有条目**（无内容 → 空列表）
     */
    public Map<Long, List<Long>> readOutbox(List<Long> authorIds,
                                            Function<List<Long>, Map<Long, List<Long>>> dbLoader) {
        if (authorIds == null || authorIds.isEmpty()) {
            return Map.of();
        }
        int windowSize = props.outboxWindowSize();
        if (windowSize <= 0) {
            // 防御：N<=0 时 ZREVRANGE 0 N-1 会退化成读全量——与回源侧(limit<=0 返回空)语义相反，
            // 故取空，不放行"读全量"这条静默放大路径。FeedProperties 已在绑定期拒，此处是第二道守卫。
            log.warn("大V发件箱窗口大小配置非法（按空处理）, windowSize={}", windowSize);
            return Map.of();
        }
        Duration ttl = props.outboxTtl();

        Map<Long, List<Long>> perAuthor = new LinkedHashMap<>();
        List<Long> misses = new ArrayList<>();
        try {
            for (FeedRedisOps.OutboxProbe probe : ops.probeOutbox(authorIds, windowSize, ttl)) {
                if (probe.empty()) {
                    perAuthor.put(probe.authorId(), List.of());
                } else if (probe.exists()) {
                    perAuthor.put(probe.authorId(), probe.ids());
                } else {
                    misses.add(probe.authorId());
                }
            }
        } catch (CacheUnavailableException e) {
            // 本链 Redis 读的唯一捕获点 → 持栈；降级不放量：全量 DB 回源、不写回
            log.warn("大V发件箱缓存读失败，降级 DB, authorCount={}", authorIds.size(), e);
            Map<Long, List<Long>> loaded = loadQuietly(authorIds, dbLoader);
            return coalesce(authorIds, loaded);
        }

        if (!misses.isEmpty()) {
            Map<Long, List<Long>> loaded = loadQuietly(misses, dbLoader);
            for (Long authorId : misses) {
                perAuthor.put(authorId, (loaded == null) ? List.of() : loaded.getOrDefault(authorId, List.of()));
            }
            if (loaded != null) {
                backfillOutbox(loaded, misses, ttl);   // 回源成功才回填（失败不写空标记）
            }
        }
        return coalesce(authorIds, perAuthor);
    }

    // ========================================================================
    // 内部：回填 / 降级
    // ========================================================================

    /** 收件箱回填：空集写空标记，非空原子替换（{@code DEL + ZADD×N + EXPIRE}）。失败不影响本次读。 */
    private void backfillInbox(String dataKey, String emptyKey, List<Long> ascending) {
        try {
            if (ascending.isEmpty()) {
                ops.markEmpty(dataKey, emptyKey, EMPTY_MARKER_TTL);
            } else {
                ops.backfillZSet(dataKey, emptyKey, ascending, props.inboxTtl());
            }
        } catch (CacheUnavailableException e) {
            log.warn("收件箱回填失败（不影响本次读结果，下次读重试）: key={}", dataKey);
        }
    }

    /**
     * 发件箱回填（一趟 pipeline）：非空 → {@code ZADD + EXPIRE}；空 → {@code markEmpty}。
     * 失败只记 WARNING、**不抛出**（缓存写失败不得让读失败；残留由 TTL / 下次发布失效兜底）。
     */
    private void backfillOutbox(Map<Long, List<Long>> loaded, List<Long> authorIds, Duration ttl) {
        try {
            ops.backfillOutbox(authorIds, loaded, ttl);
        } catch (CacheUnavailableException e) {
            log.warn("大V发件箱回填失败，读自愈, authorCount={}", authorIds.size(), e);
            return;   // 一次性写失败 ⇒ 不再尝试空标记（同一依赖故障，不放大）
        }
        for (Long authorId : authorIds) {
            List<Long> ids = loaded.get(authorId);
            if (ids == null || ids.isEmpty()) {
                markEmptyQuietly(CacheKeys.feedOutbox(authorId));
            }
        }
    }

    private void markEmptyQuietly(String dataKey) {
        try {
            ops.markEmpty(dataKey, CacheKeys.empty(dataKey), EMPTY_MARKER_TTL);
        } catch (CacheUnavailableException e) {
            log.warn("空标记写入失败（读路径将视同 miss 走 DB 自愈）: key={}", dataKey);
        }
    }

    /**
     * 大V腿的 DB 回源（**fail-open**）：失败 → 结论行 WARNING + 返回 {@code null}（= 降级信号：
     * 调用方按空处理且不回填）。这是**有判据的例外**（见类注释），不违反"DB 失败上抛"的一般纪律。
     */
    private Map<Long, List<Long>> loadQuietly(List<Long> authorIds,
                                              Function<List<Long>, Map<Long, List<Long>>> dbLoader) {
        try {
            Map<Long, List<Long>> loaded = dbLoader.apply(authorIds);
            return (loaded == null) ? Map.of() : loaded;
        } catch (RuntimeException e) {
            log.warn("大V发件箱回源降级（本次按空处理，不回填）, authorCount={}", authorIds.size());
            return null;
        }
    }

    /** 把"作者 → ids"补齐为**每个请求作者都有条目**（缺失 → 空列表；{@code loaded==null} → 全空）。 */
    private static Map<Long, List<Long>> coalesce(List<Long> authorIds, Map<Long, List<Long>> loaded) {
        Map<Long, List<Long>> merged = new LinkedHashMap<>();
        for (Long authorId : authorIds) {
            List<Long> ids = (loaded == null) ? null : loaded.get(authorId);
            merged.put(authorId, (ids == null) ? List.of() : ids);
        }
        return merged;
    }

    /** 反序（ZRANGE 升序 = contentId 升序 → 内容倒序）。 */
    private static List<Long> descending(List<Long> ascending) {
        List<Long> copy = new ArrayList<>(ascending);
        java.util.Collections.reverse(copy);
        return copy;
    }

    /** 统一升序（回填前排序，保证"miss 那次应答"与"命中缓存那次应答"同序）。 */
    private static List<Long> ascending(List<Long> ids) {
        List<Long> copy = new ArrayList<>(ids);
        copy.sort(Long::compareTo);
        return copy;
    }
}
