package io.github.yjhhhaaa06.videoweb.like.cache;

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
 * 点赞缓存的 **Redis 命令层** —— 只负责"把命令发出去"，不含任何缓存语义
 * （三态判断 / 回源 / 回填 / 降级都在 {@link LikeCache}）。
 *
 * <h2>S5 起的分工（决策表 G-6）</h2>
 * 通用协议（连接、异常归一化、{@code keyExists}/{@code getString}/{@code setString}/
 * {@code delete}/{@code expire}/pipeline/Lua 执行）已上移到 {@link RedisOps} **基类**——
 * S3/S4 时 like 与 follow 各持一份逐字重复的实现，S5 按 rule of three 收敛。
 * 本类只留**域内**命令：Set 成员判定、两次条件写 Lua、原子回填 Lua、空标记 Lua。
 *
 * <p>收敛是**纯搬迁**：命令语义、Lua 脚本、TTL 口径一字未改（既有 like 测试是回归网）。
 *
 * <h2>★ 空标记（第三批 T4 / 账 B2 补回）</h2>
 * S3 曾把空标记整条去掉（决策表 L-7："从未点赞的用户每次读都回源 DB——读放大最明显的一条"），
 * 于是四域里只有 like 没有负缓存，而 content（经 {@code CacheAside}）/ comment / follow 都有。
 * T4 按"四域统一"把 TV 口径搬回来：{@code empty:user:likeSet:{userId}} /
 * {@code empty:user:commentLikeSet:{userId}}（短 TTL 60s），承载"已确认该用户无点赞"。
 * <p>⚠️ 与 {@code FollowRedisOps.MARK_EMPTY_LUA} / {@code FeedRedisOps.MARK_EMPTY_LUA} 逐字相同——
 * 这两处**已知的小重复**是既定取舍（{@code markEmpty} 被视为"域内约定"而非纯协议，
 * 见 {@code FeedRedisOps} 类注释），本类沿用同一处置。
 *
 * <h2>条件写为什么用 Lua</h2>
 * "只在 key 已存在时才 INCR/SADD"是**防残缺缓存**的关键（冷 key 不创建半套数据，
 * 否则会被读命中而给出错的答案）。若写成"先 EXISTS 探一下、再写"，两步之间可能被并发
 * 失效命令插入（TV 三期 N7 记录过这个窗口：并发 DEL 后 INCR 会以 1 重建计数 key，
 * 该错误值会存活到 TTL 到期）。Lua 脚本在 Redis 服务端原子执行，窗口消失。
 */
@Component
public class LikeRedisOps extends RedisOps {

    /**
     * 点赞条件写（原子，一趟往返）：清空标记 + set 存在才 SADD、count 存在才 INCR。
     * KEYS: {@code [setKey, countKey, emptySetKey]}；ARGV: {@code [memberId]}。
     *
     * <p>对照 TV {@code LikeCacheService.LIKE_CONDITIONAL_SCRIPT}：**逐字相同**（T4 / 账 B2 把
     * S3 去掉的 {@code DEL KEYS[3]} 搬回来）。为什么必须清空标记：{@code empty:} 断言"该用户
     * 一个赞都没有"，而本方法刚刚断言"他赞了这一个"——不清就是**自相矛盾**，读路径会优先命中
     * 空标记而给出 false（假否定）。清掉之后 set 仍然可能不存在（冷 key），交给读 miss 回填。
     */
    private static final String LIKE_LUA =
            "redis.call('DEL', KEYS[3]) "
            + "if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('SADD', KEYS[1], ARGV[1]) end "
            + "if redis.call('EXISTS', KEYS[2]) == 1 then redis.call('INCR', KEYS[2]) end "
            + "return 1";

    /**
     * 取消点赞条件写（原子）：set 存在才 SREM、count 存在才 DECR。
     * KEYS: {@code [setKey, countKey]}；ARGV: {@code [memberId]}。
     *
     * <p>**不碰空标记**（TV 原口径）：空标记说的是"该用户从未点赞"，取关不改变这个事实；
     * 反过来说，取关把 set 清空后也不主动写空标记（写路径不写空标记，是读路径的职责）。
     */
    private static final String UNLIKE_LUA =
            "if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('SREM', KEYS[1], ARGV[1]) end "
            + "if redis.call('EXISTS', KEYS[2]) == 1 then redis.call('DECR', KEYS[2]) end "
            + "return 1";

    /**
     * 回填整个用户维度 set（原子替换），并**清掉空标记**。
     * KEYS: {@code [setKey, emptySetKey]}；ARGV: {@code [ttlSeconds, member...]}。
     *
     * <p>用<b>原子替换</b>（DEL 后 SADD）而非 TV 的"SADD-union"：union 会让并发读者在回填过程中
     * 命中一个**部分填充**的 set，从而把"已点赞"误判成 false；DEL+SADD+EXPIRE 在脚本内一次执行完，
     * 读者要么看到"key 不存在"（→ 自己也回源 DB，答案正确），要么看到完整集合。
     *
     * <p>★ 必须 {@code DEL} 空标记：读路径**先看空标记**，若它残留，刚回填的真集合会被它挡在
     * 后面 ⇒ 60 秒的**假否定**（"点过赞却显示未点赞"）。这与 follow 侧 {@code BACKFILL_ZSET_LUA}
     * 清空标记是同一处置。
     *
     * <p>空集**不走本脚本**（Redis 里空集合不存在），改由 {@link #markEmptyIfAbsent} 写空标记
     * ——这正是 T4 补回的那条路径（S3 之前空集什么都不写，于是每次都回源）。
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
     * <p>守卫的用途：并发回填已经写出真集合时，不要把"无点赞"的旧结论盖上去（否则读路径 60s 假否定）。
     * 与 {@code FollowRedisOps}/{@code FeedRedisOps} 的 {@code MARK_EMPTY_LUA} 逐字相同。
     */
    private static final String MARK_EMPTY_LUA =
            "if redis.call('EXISTS', KEYS[1]) == 0 then "
            + "  redis.call('SETEX', KEYS[2], ARGV[1], '1') "
            + "  return 1 "
            + "end "
            + "return 0";

    private static final DefaultRedisScript<Long> LIKE_SCRIPT = new DefaultRedisScript<>(LIKE_LUA, Long.class);
    private static final DefaultRedisScript<Long> UNLIKE_SCRIPT = new DefaultRedisScript<>(UNLIKE_LUA, Long.class);
    private static final DefaultRedisScript<Long> BACKFILL_SET_SCRIPT =
            new DefaultRedisScript<>(BACKFILL_SET_LUA, Long.class);
    private static final DefaultRedisScript<Long> MARK_EMPTY_SCRIPT =
            new DefaultRedisScript<>(MARK_EMPTY_LUA, Long.class);

    public LikeRedisOps(StringRedisTemplate redis, RedisCircuitBreaker breaker) {
        super(redis, breaker);
    }

    // ==================== 读 ====================

    /**
     * 一趟 pipeline 判断两个 key 是否存在，返回**存在**的那些 key（顺序为入参顺序）。
     *
     * <p>三态读的判定要同时知道"数据 key 在不在"与"空标记在不在"；两次独立往返会把判定成本翻倍。
     * 委托基类的 {@link RedisOps#existingKeys(String...)}（协议层已收敛）。
     *
     * <p>保留**定长两参**签名（而非直接用 varargs）：调用面只有这一种形态，
     * 定长签名让桩与断言无歧义。口径同 {@code FollowRedisOps.existingOf}。
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
     * <p>为什么必须 pipeline：TV 三期 T4 把成员 key 从内容维度反转为用户维度，
     * 目标就是"命令数与装载量双降"。若这里退化成 N 次独立 SISMEMBER，页面上 200 条评论
     * 就是 200 个往返，那个性质会被悄悄破坏。
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
     * 条件写：点赞。{@code memberId} 是 contentId 或 commentId（成员写在用户维度的 set 里）。
     * {@code emptySetKey} 是 {@code empty:{setKey}}（见 {@link #LIKE_LUA}：脚本会先清掉它）。
     */
    public void applyLikeConditionalWrite(String setKey, String countKey, String emptySetKey, long memberId) {
        eval(LIKE_SCRIPT, List.of(setKey, countKey, emptySetKey), String.valueOf(memberId));
    }

    /** 条件写：取消点赞（不碰空标记，见 {@link #UNLIKE_LUA}）。 */
    public void applyUnlikeConditionalWrite(String setKey, String countKey, long memberId) {
        eval(UNLIKE_SCRIPT, List.of(setKey, countKey), String.valueOf(memberId));
    }

    /** 原子回填用户维度 set（含 TTL，并清空标记）。{@code members} 必须**非空**（空集走 {@link #markEmptyIfAbsent}）。 */
    public void backfillSet(String setKey, String emptySetKey, Set<Long> members, Duration ttl) {
        List<String> args = new ArrayList<>(members.size() + 1);
        args.add(String.valueOf(ttl.toSeconds()));
        for (Long m : members) {
            args.add(String.valueOf(m));
        }
        eval(BACKFILL_SET_SCRIPT, List.of(setKey, emptySetKey), args.toArray(new String[0]));
    }

    /**
     * 写空标记（原子，见 {@link #MARK_EMPTY_LUA}）：只在数据 key 不存在时才写。
     * {@code emptyTtl} 是**短 TTL**（口径见 {@code CacheKeys.EMPTY_MARKER_TTL_SECONDS}），
     * 与数据 key 的 TTL 不同。
     */
    public void markEmptyIfAbsent(String dataKey, String emptyKey, Duration emptyTtl) {
        eval(MARK_EMPTY_SCRIPT, List.of(dataKey, emptyKey), String.valueOf(emptyTtl.toSeconds()));
    }
}
