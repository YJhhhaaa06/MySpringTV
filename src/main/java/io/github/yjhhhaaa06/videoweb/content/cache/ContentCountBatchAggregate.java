package io.github.yjhhhaaa06.videoweb.content.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheMissSignal;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.cache.RedisCircuitBreaker;
import io.github.yjhhhaaa06.videoweb.common.cache.RedisOps;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.model.ContentCountField;
import io.github.yjhhhaaa06.videoweb.content.model.ContentCountRow;
import io.github.yjhhhaaa06.videoweb.interaction.service.InteractionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 列表**计数批量聚合读**（GAP-01，2026-10-26）——详情聚合 {@link ContentCountAggregate} 的列表面平行实现。
 *
 * <h2>与详情聚合的三处刻意差异</h2>
 * <ol>
 *   <li><b>只读被请求的维度</b>：调用方给 {@link ContentCountField} 集合，本类**只拼这些键**
 *       （首页 12 条只拼 12 个 play 键；feed 一页 100 条拼 300 个键），不为"统一形状"多读。</li>
 *   <li><b>不做单飞</b>：批量路径已把逐 key 往返收敛成一次 pipeline + 一条 IN；
 *       按列表 id 再做单飞会把不同请求的结果串在一起（对齐 {@code CoinCache#batchGetCoinCount}
 *       的既有纪律）。并发两次批量读 ⇒ DB 至多 2 次，这是**预期**，不是缺陷。</li>
 *   <li><b>一趟 pipeline 覆盖 N × 维度数</b>：详情是固定 5 键；列表的键数随页长与维度集合变，
 *       但**读趟数恒定**（1 趟 Redis + 至多 1 条 IN）。回填不在此列，见下条代价。</li>
 * </ol>
 *
 * <h2>已知代价（有意接受，逐条可讲清）</h2>
 * <ul>
 *   <li><b>回填是逐键 SET，不是一趟 pipeline</b>：每个 miss 的 `(id, field)` 各走一次域契约
 *       （play / comment → {@link ContentCountCache}，like → {@link InteractionService}），
 *       各自带自己的 TTL；冷 feed 页最坏 300 次 SET。合成一趟要跨域拼 TTL 语义、
 *       或把写键收进聚合层（破"各域自持缓存"），两条都不值得为几毫秒的冷启动付。</li>
 *   <li><b>不做 TTL 续期</b>：各域 TTL 不同，一趟 pipeline 无法带多种 EXPIRE 语义
 *       （同详情聚合）。</li>
 * </ul>
 *
 * <h2>正确性核心：有选择回填</h2>
 * 只回填**自己 miss 的 (id, field)**，命中键一个都不碰 —— 否则会用本次列快照覆盖别域
 * "未失效的键"，重开已消除的旧值复活窗口（口径同详情聚合与 {@code 缓存与数据一致性.md §5.2}）。
 *
 * <h2>失败 / 缺行 / 脏值</h2>
 * <ul>
 *   <li>Redis 失败 ⇒ 一趟 IN 直查、**零回填**、{@link CacheMissSignal#mark()}；</li>
 *   <li>DB 失败 ⇒ 原样上抛（"读不到"不伪装成"没有"）；</li>
 *   <li>脏值（非数字）⇒ 该 (id, field) 按 miss，回填覆盖即自愈，不先 DEL；</li>
 *   <li>列快照缺行（防御分支，正常列表不会发生）⇒ 该 id 的 miss 维按 0 作答、**不缓存**。</li>
 * </ul>
 *
 * <h2>回填通道（域边界）</h2>
 * play / comment 是内容域自持键，直调 {@link ContentCountCache}；like 是点赞域的键，
 * 经 {@link InteractionService#backfillLikeCount} 转发 —— 内容域不直写他域缓存
 * （{@code 模块边界与依赖.md} §六 登记例外）。键名源头是 {@link CacheKeys}。
 *
 * <p>TTL 续期刻意不做：各域 TTL 不同，一趟 pipeline 无法带上多种 EXPIRE 语义
 * （同详情聚合的已知代价）。
 */
@Slf4j
@Component
public class ContentCountBatchAggregate extends RedisOps {

    private final ContentDao contentDao;
    private final ContentCountCache contentCountCache;
    private final InteractionService interactionService;

    public ContentCountBatchAggregate(StringRedisTemplate redis,
                                      RedisCircuitBreaker breaker,
                                      ContentDao contentDao,
                                      ContentCountCache contentCountCache,
                                      InteractionService interactionService) {
        super(redis, breaker);
        this.contentDao = contentDao;
        this.contentCountCache = contentCountCache;
        this.interactionService = interactionService;
    }

    /**
     * 批量读一页内容的指定计数维度。
     *
     * @param contentIds 该页内容 id（重复 id 自动去重；空 / 全 null ⇒ 空结果）
     * @param fields     要读的维度（调用点的编译期常量；空集 ⇒ 空结果，不发起任何连接）
     * @return id → 行；**每个入参 id 恰好一条**（缺失行按 0 作答），未请求的列为 {@code null}
     */
    public Map<Long, ContentCountRow> readCounts(Collection<Long> contentIds, Set<ContentCountField> fields) {
        if (contentIds == null || contentIds.isEmpty() || fields == null || fields.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = contentIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<ContentCountField> ordered = Arrays.stream(ContentCountField.values())
                .filter(fields::contains)
                .toList();
        if (ordered.isEmpty()) {
            return Map.of();
        }

        List<String> keys = new ArrayList<>(ids.size() * ordered.size());
        for (Long id : ids) {
            for (ContentCountField field : ordered) {
                keys.add(keyOf(id, field));
            }
        }

        String[] cached = new String[keys.size()];
        try {
            List<Object> raw = pipeline(o -> {
                for (String key : keys) {
                    o.opsForValue().get(key);
                }
            });
            for (int i = 0; i < keys.size(); i++) {
                cached[i] = raw.size() > i ? (String) raw.get(i) : null;
            }
        } catch (CacheUnavailableException e) {
            log.warn("列表计数缓存读失败，降级一趟 IN 直查（不回填）: size={}", ids.size(), e);
            CacheMissSignal.mark();
            return fromDb(ids, ordered);
        }

        Map<Long, Map<ContentCountField, Long>> values = new LinkedHashMap<>();
        Map<Long, Set<ContentCountField>> missed = new LinkedHashMap<>();
        int index = 0;
        for (Long id : ids) {
            Map<ContentCountField, Long> row = new EnumMap<>(ContentCountField.class);
            for (ContentCountField field : ordered) {
                String key = keys.get(index);
                Long value = parse(cached[index], key);
                index++;
                if (value == null) {
                    missed.computeIfAbsent(id, ignored -> EnumSet.noneOf(ContentCountField.class)).add(field);
                } else {
                    row.put(field, value);
                }
            }
            values.put(id, row);
        }

        if (missed.isEmpty()) {
            return toRows(ids, values);
        }
        CacheMissSignal.mark();

        Map<Long, ContentCountRow> snapshot = new HashMap<>();
        for (ContentCountRow row : contentDao.findCountColumnsByIds(missed.keySet())) {
            snapshot.put(row.id(), row);
        }
        for (Map.Entry<Long, Set<ContentCountField>> entry : missed.entrySet()) {
            long id = entry.getKey();
            ContentCountRow row = snapshot.get(id);
            if (row == null) {
                log.warn("列表计数回源无行（防御分支，正常列表不含缺失行）: contentId={}", id);
                for (ContentCountField field : entry.getValue()) {
                    values.get(id).put(field, 0L);
                }
                continue;
            }
            for (ContentCountField field : entry.getValue()) {
                long value = valueOf(row, field);
                values.get(id).put(field, value);
                backfill(id, field, value);
            }
        }
        return toRows(ids, values);
    }

    /** Redis 不可用时的降级：一趟 IN 直查，**不回填**；缺行按 0。 */
    private Map<Long, ContentCountRow> fromDb(List<Long> ids, List<ContentCountField> ordered) {
        Map<Long, ContentCountRow> snapshot = new HashMap<>();
        for (ContentCountRow row : contentDao.findCountColumnsByIds(ids)) {
            snapshot.put(row.id(), row);
        }
        Map<Long, ContentCountRow> out = new LinkedHashMap<>(ids.size());
        for (Long id : ids) {
            ContentCountRow row = snapshot.get(id);
            Map<ContentCountField, Long> values = new EnumMap<>(ContentCountField.class);
            for (ContentCountField field : ordered) {
                values.put(field, row == null ? 0L : valueOf(row, field));
            }
            out.put(id, rowOf(id, values));
        }
        return out;
    }

    private static Map<Long, ContentCountRow> toRows(List<Long> ids,
                                                     Map<Long, Map<ContentCountField, Long>> values) {
        Map<Long, ContentCountRow> out = new LinkedHashMap<>(ids.size());
        for (Long id : ids) {
            out.put(id, rowOf(id, values.get(id)));
        }
        return out;
    }

    /** 把"被请求维度的值"装成 {@link ContentCountRow}；未请求的列保持 {@code null}。 */
    private static ContentCountRow rowOf(long id, Map<ContentCountField, Long> values) {
        return new ContentCountRow(id,
                values.get(ContentCountField.LIKE),
                values.get(ContentCountField.COMMENT),
                null,
                null,
                values.get(ContentCountField.PLAY));
    }

    private void backfill(long contentId, ContentCountField field, long value) {
        switch (field) {
            case LIKE -> interactionService.backfillLikeCount(contentId, value);
            case COMMENT -> contentCountCache.backfillCommentCount(contentId, value);
            case PLAY -> contentCountCache.backfillPlayCount(contentId, value);
        }
    }

    private static String keyOf(long contentId, ContentCountField field) {
        return switch (field) {
            case LIKE -> CacheKeys.contentLikeCount(contentId);
            case COMMENT -> CacheKeys.contentCommentCount(contentId);
            case PLAY -> CacheKeys.contentPlayCount(contentId);
        };
    }

    private static long valueOf(ContentCountRow row, ContentCountField field) {
        return switch (field) {
            case LIKE -> nvl(row.likeCount());
            case COMMENT -> nvl(row.commentCount());
            case PLAY -> nvl(row.playCount());
        };
    }

    /** 缓存值解析：缺失 / 非数字 ⇒ {@code null}（按 miss 处理；回填覆盖自愈，不先 DEL）。 */
    private static Long parse(String cached, String key) {
        if (cached == null) {
            return null;
        }
        try {
            return Long.parseLong(cached);
        } catch (NumberFormatException e) {
            log.warn("列表计数缓存值非法，按 miss 处理（回填将覆盖）: key={}", key);
            return null;
        }
    }

    /** 列理论上可空（DEFAULT 0 的 unsigned 列）⇒ 读侧统一 null → 0。 */
    private static long nvl(Long value) {
        return value == null ? 0L : value;
    }
}
