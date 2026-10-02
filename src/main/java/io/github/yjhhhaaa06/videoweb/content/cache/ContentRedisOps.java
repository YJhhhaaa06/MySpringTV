package io.github.yjhhhaaa06.videoweb.content.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.RedisOps;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 内容缓存的 **Redis 命令层** —— 只负责"把命令发出去"，不含缓存语义
 * （三态判断 / 回源 / 回填 / 降级都在 {@link ContentCache}）。
 *
 * <h2>为什么它存在（G-6：rule of three 的第 3 个使用方）</h2>
 * {@link io.github.yjhhhaaa06.videoweb.common.cache.RedisOps} 提供协议（连接、异常归一化、
 * pipeline/eval/scan/通用命令）；本类提供**内容索引**这一族命令——
 * 类型分区索引是 Redis **LIST**（{@code content:index:{type}:{category}}），
 * 读写形态与 like 的 Set、follow 的 ZSet 都不同，故不是在公共层里加分支，而是各域自持。
 *
 * <h2>索引是什么、为什么需要它</h2>
 * TV 的 {@code /start}（首页推荐）要"按 type/分区取一批内容随机展示"。直接从 DB 取会
 * 每次打一次全表过滤查询；TV 的做法是维护一条 Redis LIST（**新前序**：新内容 LPUSH 到头部），
 * 读时取全量 id → 去重 → shuffle → 惰性探测直到凑满 limit。
 * 一条内容同时进 **4 个**索引 key（本 type/cid + 两个通配维度 + 全通配），
 * 于是"只看视频 / 只看某分区 / 全都要"都能一次 LRANGE 命中。
 *
 * <h2>为什么用 SCAN 而不是 KEYS</h2>
 * {@code KEYS content:index:*} 是 O(N) 主线程阻塞（TV 的 T8/H13 记录过），
 * {@code SCAN} 是游标式增量遍历（协议层 {@code scanKeys} 已封装）。
 */
@Component
public class ContentRedisOps extends RedisOps {

    public ContentRedisOps(StringRedisTemplate redis) {
        super(redis);
    }

    /**
     * 读索引 LIST 的全部 id（{@code LRANGE 0 -1}）。
     *
     * <p>脏值（非数字）**忽略**——索引是纯加速结构，一个坏元素不应该让首页 500。
     */
    public List<Long> readIds(String listKey) {
        List<String> values = guarded(() -> redis.opsForList().range(listKey, 0, -1));
        List<Long> ids = new ArrayList<>(values.size());
        for (String v : values) {
            if (v == null) {
                continue;
            }
            try {
                ids.add(Long.parseLong(v));
            } catch (NumberFormatException ignored) {
                // 脏值忽略（TV 原样）
            }
        }
        return ids;
    }

    /** 全部索引 key（{@code content:index:*}，SCAN 遍历）。 */
    public List<String> indexKeys() {
        return scanKeys(CacheKeys.CONTENT_INDEX_PREFIX + "*");
    }

    /**
     * 从**全部**索引 key 里剔除该内容 id（{@code LREM} 幂等，重复 key 无害）。
     *
     * <p>用于"内容被删/下架"——TV 用 {@code forEachIndexKey(j -> j.lrem(k, 0, id))} 逐 key 遍历；
     * 这里先 SCAN 出 key 再一趟 pipeline 完成。
     */
    public void removeIdFromAllIndexes(long contentId) {
        List<String> keys = indexKeys();
        if (keys.isEmpty()) {
            return;
        }
        String id = String.valueOf(contentId);
        pipeline(ops -> {
            for (String key : keys) {
                ops.opsForList().remove(key, 0, id);
            }
        });
    }

    /**
     * 把该内容推到给定索引 key 组的**头部**（{@code LREM} 去重后 {@code LPUSH}）。
     *
     * <p>先 LREM 再 LPUSH ⇒ 重复写入同一内容不会产生重复元素，且**新序在前**
     * （对齐 TV {@code lremAndLpush}，也是"新前序"这个说法的来源）。
     */
    public void pushFront(String[] indexKeys, long contentId) {
        String id = String.valueOf(contentId);
        pipeline(ops -> {
            for (String key : indexKeys) {
                ops.opsForList().remove(key, 0, id);
                ops.opsForList().leftPush(key, id);
            }
        });
    }

    /** 删除给定 key 组（失败路径的"自愈 DEL"：让读路径触发懒重建）。 */
    public void deleteKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        delete(keys.toArray(new String[0]));
    }

    /**
     * 索引**全量重建**（一趟 pipeline）：先删掉全部旧索引 key，再按入参顺序逐条写入。
     *
     * <p>{@code entries} 的顺序 = {@code ContentDao.findAllContent} 的顺序
     * （{@code create_time DESC, id DESC}）；由于每条都是 LPUSH，最终 LIST 内顺序与入参**相反**。
     * TV 如此，且 {@code getRecommendByFilter} 读后必定 shuffle ⇒ 顺序不可观察。保持原样。
     */
    public void rebuildIndexes(List<String> staleKeys, List<IndexEntry> entries) {
        pipeline(ops -> {
            for (String key : staleKeys) {
                ops.delete(key);
            }
            for (IndexEntry entry : entries) {
                String id = String.valueOf(entry.contentId());
                for (String key : indexKeysOf(entry.type(), entry.categoryId())) {
                    ops.opsForList().remove(key, 0, id);
                    ops.opsForList().leftPush(key, id);
                }
            }
        });
    }

    /**
     * 一条内容所属的 **4 个**索引 key：{@code (type, categoryId)} + {@code (type, -1)} +
     * {@code (-1, categoryId)} + {@code (-1, -1)}（{@code -1} = 通配维度）。
     *
     * <p>写入与"自愈 DEL"必须同源（TV 的 {@code indexKeysOf} 就是为此收成一个方法）。
     */
    public static String[] indexKeysOf(int type, int categoryId) {
        return new String[]{
                CacheKeys.contentIndex(type, categoryId),
                CacheKeys.contentIndex(type, -1),
                CacheKeys.contentIndex(-1, categoryId),
                CacheKeys.contentIndex(-1, -1)
        };
    }

    /** 索引重建的入参载体：哪条内容、进哪一类索引。 */
    public record IndexEntry(long contentId, int type, int categoryId) {
    }
}
