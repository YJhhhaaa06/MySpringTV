package io.github.yjhhhaaa06.videoweb.comment.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.RedisOps;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 评论缓存的 **Redis 命令层** —— 只负责"把命令发出去"，不含缓存语义
 * （三态判断 / 窗口装载 / 回填 / 降级都在 {@link CommentCache}）。
 *
 * <h2>为什么它是第四个 {@code *RedisOps}（G-6）</h2>
 * 协议（连接、异常归一化、pipeline/eval/scan/通用命令）在
 * {@link RedisOps} 基类；本类只提供**评论两键组**这一族的命令：
 * <ul>
 *   <li><b>roots</b>：{@code LIST}（主楼序列）——{@code LLEN / LINDEX / LRANGE / RPUSH}；</li>
 *   <li><b>replies</b>：{@code HASH}（field = 主楼 id）——{@code HMGET / HSET}；</li>
 *   <li><b>count</b>：{@code String}（真实主楼总数）——复用基类的 {@code getString/setString}；</li>
 *   <li><b>空标记</b>：{@code empty:{rootsKey}} —— {@code raw 的存在性 + 条件写}。</li>
 * </ul>
 * 三种数据结构混在一族 key 里，是 TV 的 T10-A 设计（"两键组 + 主楼窗口装载"），
 * 目的是让"取第 N 页主楼"的成本**与该内容的评论总量弱相关**（治 U-20 的根因）。
 *
 * <h2>★ 窗口装载为什么用 LIST/HASH 而不是 Set/ZSet</h2>
 * 主楼天然有序（{@code comment_id} 升序）且按"前 N 条 / 尾部追加"访问 ⇒ 用 LIST 的
 * {@code RPUSH} 追加 + {@code LRANGE} 取窗口最自然；楼中楼按"主楼 id 分组"随机访问 ⇒ 用 HASH
 * 可以让 {@code HMGET} 只拉该页主楼那几组（页成本 ∝ 该页，而不是全部回复）。
 * 对照 follow 用 ZSet：那里要的是"按 score 排序的任意窗口"，两者诉求不同，故形态不同。
 */
@Component
public class CommentRedisOps extends RedisOps {

    public CommentRedisOps(StringRedisTemplate redis) {
        super(redis);
    }

    // ==================== 空标记 ====================

    /** roots 的空标记是否存在（命中 = 已确认"该内容无评论"）。 */
    public boolean emptyMarkerExists(String rootsKey) {
        return keyExists(CacheKeys.empty(rootsKey));
    }

    /**
     * 写空标记（**只在数据 key 不存在时**），短 TTL。
     *
     * <p>对照 TV {@code CommentCache.markEmpty}：{@code if (!exists(rootsKey)) setex(empty:rootsKey, 60, "1")}。
     * 守卫的用途：并发回填已经写出真数据时，不要把"无评论"的旧结论盖上去（否则读路径 60s 假空）。
     * TV 接受了"exists 与 setex 之间毫秒级并发窗口"的残余竞态；此处照搬（follow 侧改用 Lua 消窗口，
     * 那是 F-7 的另一处取舍）。
     */
    public void markEmptyIfAbsent(String rootsKey, Duration emptyTtl) {
        guardedVoid(() -> {
            if (!Boolean.TRUE.equals(redis.hasKey(rootsKey))) {
                redis.opsForValue().set(CacheKeys.empty(rootsKey), CacheKeys.EMPTY_MARKER_VALUE, emptyTtl);
            }
        });
    }

    // ==================== roots（LIST） ====================

    /** 已装载的主楼水位（{@code LLEN}）。 */
    public long rootsLength(String rootsKey) {
        Long length = guarded(() -> redis.opsForList().size(rootsKey));
        return length == null ? 0L : length;
    }

    /** 指定下标的主楼 JSON（{@code LINDEX}，水位 0 时调用方不会走到）。 */
    public String rootsAt(String rootsKey, long index) {
        return guarded(() -> redis.opsForList().index(rootsKey, index));
    }

    /**
     * 读全部已装载主楼（{@code LRANGE 0 -1}）并**顺带续期**（滑动过期：命中即续）。
     *
     * <p>一趟 pipeline 完成"读 + 续期"——它们的性质不同（一个返回数据、一个返回是否成功），
     * 但如果分两次调用，热路径就多一个往返。
     */
    public List<String> readRootsAll(String rootsKey, Duration ttl) {
        List<Object> raw = pipeline(ops -> {
            ops.opsForList().range(rootsKey, 0, -1);
            ops.expire(rootsKey, ttl);
        });
        List<String> values = new ArrayList<>();
        if (!raw.isEmpty() && raw.get(0) instanceof List<?> list) {
            for (Object o : list) {
                values.add((String) o);
            }
        }
        return values;
    }

    /**
     * 追加装载结果（一趟 pipeline）：
     * {@code [首装] DEL 空标记} → {@code RPUSH × N} → {@code EXPIRE} → {@code [首装] SETEX count}。
     *
     * <p>为什么"首装才写 count"：{@code count} 是"真实主楼总数"，由 {@code COUNT(*)} 得到；
     * 非首装轮次不必重复 COUNT（TV 的 `total = (len2 == 0) ? countMainComments : -1`）。
     *
     * @param existedBefore 装载前的水位；{@code 0} = 首装
     * @param total         首装时的真实主楼总数；非首装传 {@code -1}
     */    public void appendRoots(String rootsKey, String countKey, long existedBefore,
                            List<String> rootJsons, int total, Duration ttl) {
        pipeline(p -> {
            if (existedBefore == 0) {
                p.delete(CacheKeys.empty(rootsKey));
            }
            for (String json : rootJsons) {
                p.opsForList().rightPush(rootsKey, json);
            }
            p.expire(rootsKey, ttl);
            if (existedBefore == 0 && total >= 0) {
                p.opsForValue().set(countKey, String.valueOf(total), ttl);
            }
        });
    }

    // ==================== replies（HASH） ====================

    /**
     * 一趟 HMGET 取多个 field（{@code null} 元素 = 该 field 未装载）。
     *
     * <p>为什么 HMGET 而不是逐个 HGET：该页主楼可能几十个，逐个往返会把
     * "页成本 ∝ 该页"这个性质变成 "页成本 ∝ 该页 × 往返"。
     */
    public List<String> hashMultiGet(String repliesKey, List<String> fields) {
        List<Object> raw = guarded(() -> redis.opsForHash().multiGet(repliesKey, List.copyOf(fields)));
        List<String> values = new ArrayList<>(raw.size());
        for (Object o : raw) {
            values.add(o == null ? null : String.valueOf(o));
        }
        return values;
    }

    /** 批量 HSET（一趟 pipeline + EXPIRE）。{@code fieldValues} 为空时不发命令。 */
    public void hashPutAll(String repliesKey, Map<String, String> fieldValues, Duration ttl) {
        if (fieldValues.isEmpty()) {
            return;
        }
        guardedVoid(() -> pipeline(p -> {
            for (Map.Entry<String, String> entry : fieldValues.entrySet()) {
                p.opsForHash().put(repliesKey, entry.getKey(), entry.getValue());
            }
            p.expire(repliesKey, ttl);
        }));
    }

    /** 定向删一个 field（HDEL）：回复增删 / 评论点赞后让它"回到未装载"，下次读懒载刷新。 */
    public void hashDeleteField(String repliesKey, String field) {
        guardedVoid(() -> redis.opsForHash().delete(repliesKey, field));
    }
}
