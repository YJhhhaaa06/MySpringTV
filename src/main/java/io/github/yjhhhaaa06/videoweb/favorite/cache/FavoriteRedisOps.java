package io.github.yjhhhaaa06.videoweb.favorite.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.RedisCircuitBreaker;
import io.github.yjhhhaaa06.videoweb.common.cache.RedisOps;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 收藏缓存的 **Redis 命令层** —— 只负责"把命令发出去"，不含任何缓存语义
 * （三态判断 / 回源 / 回填 / 降级都在 {@link FavoriteCache}）。
 *
 * <h2>形态与 {@code like.cache.LikeRedisOps} 同构（T11 明确要求"别自创"）</h2>
 * 通用协议（连接、异常归一化、熔断收口 {@code guarded}、{@code keyExists}/{@code getString}/
 * {@code setString}/{@code delete}/{@code expire}/pipeline/Lua 执行）已在 {@link RedisOps}
 * **基类**里（S5 按 rule of three 收敛）；本类只留**域内**命令：
 * Set 成员判定、一次条件写 Lua、原子回填 Lua、空标记 Lua。
 *
 * <h2>★ 为什么只有一个条件写 Lua（收藏与点赞最大的形态差异）</h2>
 * 点赞域的"点 / 取消"各有一个 Lua，因为它的一条关系行 ⟺ 一个赞：
 * {@code SADD + INCR} 与 {@code SREM + DECR} 都能精确表达"这次写带来的变化"。
 * <b>收藏不是</b>：{@code favorite_item} 的唯一键是 {@code uk_folder_content}（**夹内**唯一），
 * 同一用户可以同时把同一内容收在多个夹里 ⇒
 * <ul>
 *   <li>{@code add}（收进第二个夹）**不改变**收藏数、也**不改变**"我收藏过"；</li>
 *   <li>{@code remove}（从某个夹移出）**不必然**取消收藏（可能仍收在别的夹）；</li>
 *   <li>{@code move} 甚至可能是"源夹压根没这条"⇒ insert 成功、源侧删 0 行 = 一次新增收藏。</li>
 * </ul>
 * ⇒ 除 {@code add}（单调：收藏过 ⇒ 仍然收藏过，{@code SADD} 恒安全）之外，
 * 收藏域**不做增量条件写**，一律走**整体失效**让读回源自愈（{@link FavoriteCache#invalidateUserFavorites}）。
 * 详见 {@code favorite.md} §七 与**附录 A.3 I-11**。
 *
 * <h2>★ 空标记（仅成员侧）</h2>
 * {@code empty:user:favoriteSet:{userId}}（短 TTL 60s）承载"**已确认该用户一条收藏都没有**"——
 * 与 like / follow / content 同口径。⚠️ <b>计数 key 刻意不写空标记</b>：{@code 0} 是合法值
 * （{@code CacheKeys.contentFavoriteCount} 的类注释、缓存文档 §六.1）。
 *
 * <h2>条件写为什么用 Lua</h2>
 * "只在 key 已存在时才 SADD"是**防残缺缓存**的关键（冷 key 不创建半套数据，
 * 否则会被读命中而给出错的答案）。若写成"先 EXISTS 探一下、再写"，两步之间可能被并发失效命令插入
 * （TV 三期 N7 记录过这个窗口）。Lua 在 Redis 服务端原子执行，窗口消失。
 */
@Component
public class FavoriteRedisOps extends RedisOps {

    /**
     * 收藏条件写（原子，一趟往返）：清空标记 + 成员 set 存在才 {@code SADD}。
     * KEYS: {@code [setKey, emptySetKey]}；ARGV: {@code [contentId]}。
     *
     * <p>★ 为什么**必须**先 {@code DEL} 空标记：{@code empty:} 断言"该用户一条收藏都没有"，
     * 而本次写刚刚断言"他收藏了这一个" ⇒ 不清就是**自相矛盾**，读路径会优先命中空标记而答 false
     * （60 秒假否定）。清掉之后 set 仍可能不存在（冷 key）——那是**有意**的：
     * 冷 key 不写半套数据，交给读路径 miss 回源整体回填（缓存文档 §四.2）。
     *
     * <p>⚠️ 这里**没有**计数侧：见类注释"为什么只有一个条件写 Lua"。
     */
    private static final String ADD_LUA =
            "redis.call('DEL', KEYS[2]) "
            + "if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('SADD', KEYS[1], ARGV[1]) end "
            + "return 1";

    /**
     * 回填整个用户维度成员集（原子替换），并**清掉空标记**。
     * KEYS: {@code [setKey, emptySetKey]}；ARGV: {@code [ttlSeconds, contentId...]}。
     *
     * <p>用**原子替换**（{@code DEL} 后 {@code SADD}）而非"SADD-union"：union 会让并发读者在回填
     * 过程中命中一个**部分填充**的 set，把"已收藏"误判成 false；{@code DEL+SADD+EXPIRE} 在脚本内
     * 一次执行完，读者要么看到"key 不存在"（自己也回源 DB，答案正确），要么看到完整集合。
     *
     * <p>★ 必须 {@code DEL} 空标记：读路径**先看空标记**，残留会把刚回填的真集合挡在 60 秒假否定之后。
     * <p>空集**不走本脚本**（Redis 里空集合不存在），改由 {@link #markEmptyIfAbsent} 写空标记。
     */
    private static final String BACKFILL_SET_LUA =
            "redis.call('DEL', KEYS[1]) "
            + "for i = 2, #ARGV do redis.call('SADD', KEYS[1], ARGV[i]) end "
            + "if #ARGV > 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
            + "redis.call('DEL', KEYS[2]) "
            + "return 1";

    /**
     * 写空标记（原子）：只在**数据 key 不存在**时才写，避免覆盖并发写入的真数据。
     * KEYS: {@code [dataKey, emptyKey]}；ARGV: {@code [emptyTtlSeconds]}。
     *
     * <p>守卫的用途：并发回填已经写出真集合时，不要把"无收藏"的旧结论盖上去。
     * ⚠️ 与 {@code LikeRedisOps} / {@code FollowRedisOps} 的 {@code MARK_EMPTY_LUA} 逐字相同 ——
     * 这处**已知的小重复**是既定取舍（{@code markEmpty} 被视为"域内约定"而非纯协议，
     * 见 {@code FeedRedisOps} 类注释），本类沿用同一处置。
     */
    private static final String MARK_EMPTY_LUA =
            "if redis.call('EXISTS', KEYS[1]) == 0 then "
            + "  redis.call('SETEX', KEYS[2], ARGV[1], '1') "
            + "  return 1 "
            + "end "
            + "return 0";

    private static final DefaultRedisScript<Long> ADD_SCRIPT = new DefaultRedisScript<>(ADD_LUA, Long.class);
    private static final DefaultRedisScript<Long> BACKFILL_SET_SCRIPT =
            new DefaultRedisScript<>(BACKFILL_SET_LUA, Long.class);
    private static final DefaultRedisScript<Long> MARK_EMPTY_SCRIPT =
            new DefaultRedisScript<>(MARK_EMPTY_LUA, Long.class);

    public FavoriteRedisOps(StringRedisTemplate redis, RedisCircuitBreaker breaker) {
        super(redis, breaker);
    }

    // ==================== 读 ====================

    /**
     * 一趟 pipeline 判断两个 key 是否存在，返回**存在**的那些 key（顺序为入参顺序）。
     *
     * <p>三态读要同时知道"数据 key 在不在"与"空标记在不在"；两次独立往返会把判定成本翻倍。
     * 保留**定长两参**签名（理由同 {@code LikeRedisOps.existingOf}：调用面只有这一种形态，
     * 定长签名让桩与断言无歧义）。
     */
    public Set<String> existingOf(String key1, String key2) {
        return existingKeys(key1, key2);
    }

    public boolean setIsMember(String key, long member) {
        return guarded(() -> Boolean.TRUE.equals(redis.opsForSet().isMember(key, String.valueOf(member))));
    }

    /**
     * 一趟 pipeline 判定多个成员是否在集合内，返回与入参**同序**的布尔列表。
     *
     * <p>为什么必须 pipeline：列表路径（首页 / 搜索 / 主页，`T58` 后为 /api/v1/contents/recommended、
     * /api/v1/contents、/api/v1/users/{id}）一条响应里可能问 N 个
     * contentId 的收藏态；退化成 N 次独立 {@code SISMEMBER} 就是 N 个往返，
     * "命令数与装载量双降"这个性质会被悄悄破坏（同 {@code LikeRedisOps.setIsMemberBatch}）。
     */
    public List<Boolean> setIsMemberBatch(String key, List<Long> members) {
        List<Object> raw = pipeline(ops -> {
            for (Long member : members) {
                ops.opsForSet().isMember(key, String.valueOf(member));
            }
        });
        return raw.stream().map(o -> Boolean.TRUE.equals(o)).toList();
    }

    // ==================== 写 ====================

    /**
     * 条件写：收藏。{@code emptySetKey} 是 {@code empty:{setKey}}（见 {@link #ADD_LUA}：脚本先清它）。
     */
    public void applyAddConditionalWrite(String setKey, String emptySetKey, long contentId) {
        eval(ADD_SCRIPT, List.of(setKey, emptySetKey), String.valueOf(contentId));
    }

    /** 原子回填用户维度成员集（含 TTL，并清空标记）。{@code members} 必须**非空**（空集走 {@link #markEmptyIfAbsent}）。 */
    public void backfillSet(String setKey, String emptySetKey, Set<Long> members, Duration ttl) {
        List<String> args = new ArrayList<>(members.size() + 1);
        args.add(String.valueOf(ttl.toSeconds()));
        for (Long member : members) {
            args.add(String.valueOf(member));
        }
        eval(BACKFILL_SET_SCRIPT, List.of(setKey, emptySetKey), args.toArray(new Object[0]));
    }

    /**
     * 写空标记（原子，见 {@link #MARK_EMPTY_LUA}）：只在数据 key 不存在时才写。
     * {@code emptyTtl} 是**短 TTL**（{@code CacheKeys.EMPTY_MARKER_TTL_SECONDS} = 60s），
     * 与数据 key 的 TTL（{@code video.cache.favorite-ttl}）刻意不同。
     */
    public void markEmptyIfAbsent(String dataKey, String emptyKey, Duration emptyTtl) {
        eval(MARK_EMPTY_SCRIPT, List.of(dataKey, emptyKey), String.valueOf(emptyTtl.toSeconds()));
    }
}
