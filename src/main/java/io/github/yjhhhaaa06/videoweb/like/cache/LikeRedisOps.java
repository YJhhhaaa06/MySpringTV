package io.github.yjhhhaaa06.videoweb.like.cache;

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
 * 本类只留**域内**命令：Set 成员判定、两次条件写 Lua、原子回填 Lua。
 *
 * <p>收敛是**纯搬迁**：命令语义、Lua 脚本、TTL 口径一字未改（既有 like 测试是回归网）。
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
     * 点赞条件写（原子，一趟往返）：set 存在才 SADD、count 存在才 INCR。
     * KEYS: {@code [setKey, countKey]}；ARGV: {@code [memberId]}。
     *
     * <p>对照 TV {@code LikeCacheService.LIKE_CONDITIONAL_SCRIPT}：**少了 {@code DEL KEYS[3]}**
     * ——那一行是清"空标记"（{@code empty:...}），而精简版有意不做空标记（见决策表 L-7）。
     */
    private static final String LIKE_LUA =
            "if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('SADD', KEYS[1], ARGV[1]) end "
            + "if redis.call('EXISTS', KEYS[2]) == 1 then redis.call('INCR', KEYS[2]) end "
            + "return 1";

    /**
     * 取消点赞条件写（原子）：set 存在才 SREM、count 存在才 DECR。
     * KEYS: {@code [setKey, countKey]}；ARGV: {@code [memberId]}。
     */
    private static final String UNLIKE_LUA =
            "if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('SREM', KEYS[1], ARGV[1]) end "
            + "if redis.call('EXISTS', KEYS[2]) == 1 then redis.call('DECR', KEYS[2]) end "
            + "return 1";

    /**
     * 回填整个用户维度 set（原子替换）。
     * KEYS: {@code [setKey]}；ARGV: {@code [ttlSeconds, member...]}。
     *
     * <p>用<b>原子替换</b>（DEL 后 SADD）而非 TV 的"SADD-union"：union 会让并发读者在回填过程中
     * 命中一个**部分填充**的 set，从而把"已点赞"误判成 false；DEL+SADD+EXPIRE 在脚本内一次执行完，
     * 读者要么看到"key 不存在"（→ 自己也回源 DB，答案正确），要么看到完整集合。
     *
     * <p>空集时**不创建 key**（Redis 里空集合不存在）——这正是"有意不做空标记"的后果：
     * 从未点赞的用户每次都回源 DB。影响已记录在 L-7。
     */
    private static final String BACKFILL_SET_LUA =
            "redis.call('DEL', KEYS[1]) "
            + "for i = 2, #ARGV do redis.call('SADD', KEYS[1], ARGV[i]) end "
            + "if #ARGV > 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
            + "return 1";

    private static final DefaultRedisScript<Long> LIKE_SCRIPT = new DefaultRedisScript<>(LIKE_LUA, Long.class);
    private static final DefaultRedisScript<Long> UNLIKE_SCRIPT = new DefaultRedisScript<>(UNLIKE_LUA, Long.class);
    private static final DefaultRedisScript<Long> BACKFILL_SET_SCRIPT =
            new DefaultRedisScript<>(BACKFILL_SET_LUA, Long.class);

    public LikeRedisOps(StringRedisTemplate redis) {
        super(redis);
    }

    // ==================== 读 ====================

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

    /** 条件写：点赞。{@code memberId} 是 contentId 或 commentId（成员写在用户维度的 set 里）。 */
    public void applyLikeConditionalWrite(String setKey, String countKey, long memberId) {
        eval(LIKE_SCRIPT, List.of(setKey, countKey), String.valueOf(memberId));
    }

    /** 条件写：取消点赞。 */
    public void applyUnlikeConditionalWrite(String setKey, String countKey, long memberId) {
        eval(UNLIKE_SCRIPT, List.of(setKey, countKey), String.valueOf(memberId));
    }

    /** 原子回填用户维度 set（含 TTL）。空集不创建 key。 */
    public void backfillSet(String setKey, Set<Long> members, Duration ttl) {
        List<String> args = new ArrayList<>(members.size() + 1);
        args.add(String.valueOf(ttl.toSeconds()));
        for (Long m : members) {
            args.add(String.valueOf(m));
        }
        eval(BACKFILL_SET_SCRIPT, List.of(setKey), args.toArray(new String[0]));
    }
}
