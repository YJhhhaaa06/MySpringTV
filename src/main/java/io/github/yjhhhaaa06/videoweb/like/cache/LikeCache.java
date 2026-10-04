package io.github.yjhhhaaa06.videoweb.like.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.cache.SingleFlight;
import io.github.yjhhhaaa06.videoweb.common.config.LikeCacheProperties;
import io.github.yjhhhaaa06.videoweb.like.dao.CommentLikeDao;
import io.github.yjhhhaaa06.videoweb.like.dao.ContentLikeDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * 点赞缓存（决策⑤「显式封装层」，**不用** {@code @Cacheable}）。
 *
 * <h2>骨架（与 TV {@code LikeCacheService} 同构）</h2>
 * <ol>
 *   <li><b>DB 是唯一真理源</b>：读 miss → 回源 DB → 回填；写先落 DB、**提交后**才碰缓存
 *       （提交时机由 {@code LikeChangedListener} 的 {@code AFTER_COMMIT} 保证，见决策表 L-6）。</li>
 *   <li><b>两个 key 族</b>（用户维度，沿袭 TV 的 T4 反转）：
 *       <ul>
 *         <li>{@code content:likeCount:{id}} / {@code comment:likeCount:{id}} —— String 整数</li>
 *         <li>{@code user:likeSet:{userId}} / {@code user:commentLikeSet:{userId}} —— Set&lt;id&gt;</li>
 *       </ul>
 *       成员 key 取<b>用户维度</b>而非内容维度：「我是否点过赞」的装载量与内容热度解耦
 *       （热门内容不会把全部点赞者拉进内存）。</li>
 *   <li><b>失败降级不抛</b>：Redis 出问题 → 读直查 DB、写失效 key 让读自愈；
 *       <b>Redis 挂掉不会让点赞接口 500</b>（TV NEEDS 4.2 / H5）。</li>
 * </ol>
 *
 * <h2>★ 两种失败必须区分（决策表 L-5 的待定项之二）</h2>
 * <table>
 *   <caption>失败分类与处置</caption>
 *   <tr><th>失败</th><th>处置</th><th>理由</th></tr>
 *   <tr><td>Redis 失败（{@link CacheUnavailableException}）</td><td>降级直查 DB，不影响结果</td>
 *       <td>缓存只是加速器</td></tr>
 *   <tr><td>DB 失败（{@code DataAccessException}）</td><td><b>原样上抛</b>（→ 500）</td>
 *       <td>"无答案可答"。降级成空值会把"读不到"伪装成"没有"，是更坏的语义</td></tr>
 * </table>
 * 实现上靠**只 catch 自己的异常类型**来保证：{@link CacheUnavailableException} 只从
 * {@link LikeRedisOps} 冒出，DB 异常不经过它。
 *
 * <h2>★ 空标记：S3 去掉、T4 补回（账 B2）</h2>
 * S3 曾整条去掉空标记（L-7 记的影响："从未点赞的用户每次读都回源 DB——读放大最明显的一条"），
 * 于是四域里**只有 like 没有负缓存**：content（经 {@code CacheAside} 的三态读）/ comment / follow
 * 都有各自的 {@code empty:} 断言。T4 按"四域统一"把 TV 口径搬回来
 * （{@code empty:user:likeSet:{userId}} / {@code empty:user:commentLikeSet:{userId}}，短 TTL 60s）。
 * <p>三态读因此变成：<b>空标记命中 → false / 全 false</b>；数据 set 命中 → {@code SISMEMBER}；
 * 两者皆无 → miss 回源（空结果写空标记）。
 * <p>⚠️ **读路径必须先看空标记、再看数据 key**（{@code existingOf} 一趟返回两者，
 * 判定顺序与 follow 侧一致）；写路径的点赞脚本会清掉空标记
 * （见 {@link LikeRedisOps#LIKE_LUA}），回填非空集合时也清（见 {@link LikeRedisOps#BACKFILL_SET_LUA}）
 * ——不清就是"真数据被 60 秒假否定挡住"。写失败的自愈路径同样把空标记纳入失效集
 * （见 {@link #conditionalWrite}）。
 * <p>⚠️ **残余竞态（照 TV 接受，如实登记）**："读 miss 回填的空集"与"并发点赞已提交"之间存在
 * 一个 milliseconds 级窗口：{@code markEmptyIfAbsent} 的守卫只查数据 key，而点赞脚本对**冷 set**
 * 只 {@code DEL} 空标记**不建 set** ⇒ 空标记可能盖在刚提交的赞上，表现为最长 60s 的假否定。
 * 与 TV {@code CacheAside.markEmpty} 同款权衡（靠空标记 60s 过期 + 下次业务写清标记自愈），
 * **不是本次改动引入、也未在此消除**。
 *
 * <h2>相对 TV 的有意精简（逐条记录在决策表 L-7，此处只列要点）</h2>
 * 保留：条件写防残缺缓存（Lua 原子）、失败降级、TTL 与命中续期、批量状态、三态读、空标记。
 * 去掉：TV 的通用 CacheAside/SetCache 框架层。
 * <b>单飞</b>（B1）已由第三批 T3 收回：miss 回填经 {@link SingleFlight}，同 key 并发只打一次 DB。
 * <b>空标记</b>（B2）已由第三批 T4 收回：见上。
 *
 * <p>另有一处**顺带的改进**（非偷懒，见 L-7 末段）：降级路径用**单条查询**作答，
 * 不把该用户的全量点赞史拉进内存——降级**不经单飞**（按请求 id 的批量子集作答，
 * 用单一数据 key 做单飞 key 会让不同 id 子集串结果），单条/按需查询更省。
 */
@Slf4j
@Component
public class LikeCache {

    /**
     * 内容点赞数 key。委托 {@link CacheKeys}（S5 起键工厂是**全仓唯一源**；键字符串逐字不变）。
     */
    static String contentLikeCountKey(long contentId) {
        return CacheKeys.contentLikeCount(contentId);
    }

    /** 评论点赞数 key。委托 {@link CacheKeys}。 */
    static String commentLikeCountKey(long commentId) {
        return CacheKeys.commentLikeCount(commentId);
    }

    /** 用户维度内容点赞成员 key（Set&lt;contentId&gt;）。委托 {@link CacheKeys}。 */
    static String userLikeSetKey(long userId) {
        return CacheKeys.userLikeSet(userId);
    }

    /** 用户维度评论点赞成员 key（Set&lt;commentId&gt;）。委托 {@link CacheKeys}。 */
    static String userCommentLikeSetKey(long userId) {
        return CacheKeys.userCommentLikeSet(userId);
    }

    /**
     * 空标记的短 TTL，60 秒（口径见 {@link CacheKeys#EMPTY_MARKER_TTL_SECONDS}）。
     * 刻意比成员 key 的 TTL（{@code video.cache.like-ttl}）短得多：它是"高置信度但短时效"的结论。
     * 命中读**不续期**空标记（与 content/follow/comment 同口径）。
     */
    static final Duration EMPTY_MARKER_TTL = Duration.ofSeconds(CacheKeys.EMPTY_MARKER_TTL_SECONDS);

    private final LikeRedisOps ops;
    private final ContentLikeDao contentLikeDao;
    private final CommentLikeDao commentLikeDao;
    private final LikeCacheProperties props;
    /** 单飞（B1）：同 key 并发 miss 只回源一次。 */
    private final SingleFlight singleFlight;

    public LikeCache(LikeRedisOps ops,
                     ContentLikeDao contentLikeDao,
                     CommentLikeDao commentLikeDao,
                     LikeCacheProperties props,
                     SingleFlight singleFlight) {
        this.ops = ops;
        this.contentLikeDao = contentLikeDao;
        this.commentLikeDao = commentLikeDao;
        this.props = props;
        this.singleFlight = singleFlight;
    }

    // ========================================================================
    // 写：由 LikeChangedListener 在**事务提交后**调用（AFTER_COMMIT）
    // ========================================================================

    public void likeContent(long userId, long contentId) {
        conditionalWrite(userLikeSetKey(userId), contentLikeCountKey(contentId), contentId, true);
    }

    public void unlikeContent(long userId, long contentId) {
        conditionalWrite(userLikeSetKey(userId), contentLikeCountKey(contentId), contentId, false);
    }

    public void likeComment(long userId, long commentId) {
        conditionalWrite(userCommentLikeSetKey(userId), commentLikeCountKey(commentId), commentId, true);
    }

    public void unlikeComment(long userId, long commentId) {
        conditionalWrite(userCommentLikeSetKey(userId), commentLikeCountKey(commentId), commentId, false);
    }

    /**
     * 条件写 + 失败兜底。
     *
     * <p>失败时**失效两个 key**（而不是像 TV 那样只失效 count key）：写失败意味着两个 key
     * 都可能陈旧（成员少了一个 SADD、计数少了一次 INCR），只失效其一会让另一个继续给出错答案。
     * 失效本身也是 Redis 操作，Redis 真挂时会再次失败——一并吞掉并记日志（不能让缓存操作
     * 影响已提交的业务）。
     */
    private void conditionalWrite(String setKey, String countKey, long memberId, boolean liked) {
        try {
            if (liked) {
                ops.applyLikeConditionalWrite(setKey, countKey, CacheKeys.empty(setKey), memberId);
            } else {
                ops.applyUnlikeConditionalWrite(setKey, countKey, memberId);
            }
        } catch (CacheUnavailableException e) {
            log.warn("点赞缓存写失败，失效 key 让读自愈: setKey={}, countKey={}", setKey, countKey, e);
            // ⚠️ 空标记**必须一起失效**：它是读路径的第一判定，残留会让"刚写的赞"在 60s 内
            //    读回 false 且**不回源 DB 自愈**——那正是本类要消除的假否定，
            //    也正是"写失败 ⇒ 失效让读自愈"这条纪律在空标记这一半的落点
            //    （对照 FollowCache.invalidateQuietly 的 dataKeyAndEmptyMarker）。
            invalidateQuietly(setKey, countKey, CacheKeys.empty(setKey));
        }
    }

    private void invalidateQuietly(String... keys) {
        try {
            ops.delete(keys);
        } catch (CacheUnavailableException e) {
            log.warn("缓存失效也失败（Redis 不可用），交由 TTL 自愈: keys={}", List.of(keys));
        }
    }

    /**
     * 内容被删/下架：失效其点赞**计数**缓存（S5 补入，承接 TV {@code LikeCacheService.deleteContentLike}）。
     *
     * <p>为什么只失效计数、不管成员：T4 反转后成员 key 是**用户维度**
     * （{@code user:likeSet:{userId}}），内容删除无法廉价反查"谁点过赞"逐个 SREM。
     * 残留成员指向已删除内容——而内容 id 自增**不复用**，且没有"对已删内容查点赞状态"的读路径，
     * 故永不外显（TV 原注释的完整论证）。DB 侧的点赞行由
     * {@code ContentLikeDao.deleteByContentId} 物理删除。
     */
    public void invalidateContentLikeCount(long contentId) {
        invalidateQuietly(contentLikeCountKey(contentId));
    }

    // ========================================================================
    // 读：内容
    // ========================================================================

    public boolean isContentLiked(long userId, long contentId) {
        return isLiked(userLikeSetKey(userId), contentId,
                () -> contentLikeDao.findLikedContentIdsByUser(userId),
                () -> contentLikeDao.isLiked(userId, contentId));
    }

    public int getContentLikeCount(long contentId) {
        return likeCount(contentLikeCountKey(contentId), () -> contentLikeDao.countByContentId(contentId));
    }

    public Map<Long, Boolean> batchIsContentLiked(long userId, List<Long> contentIds) {
        return batchIsLiked(userLikeSetKey(userId), contentIds,
                () -> contentLikeDao.findLikedContentIdsByUser(userId),
                ids -> contentLikeDao.findLikedContentIds(userId, ids));
    }

    // ========================================================================
    // 读：评论（与内容同构，只换 key 与 DAO）
    // ========================================================================

    public boolean isCommentLiked(long userId, long commentId) {
        return isLiked(userCommentLikeSetKey(userId), commentId,
                () -> commentLikeDao.findLikedCommentIdsByUser(userId),
                () -> commentLikeDao.isLiked(userId, commentId));
    }

    public int getCommentLikeCount(long commentId) {
        return likeCount(commentLikeCountKey(commentId), () -> commentLikeDao.countByCommentId(commentId));
    }

    public Map<Long, Boolean> batchIsCommentLiked(long userId, List<Long> commentIds) {
        return batchIsLiked(userCommentLikeSetKey(userId), commentIds,
                () -> commentLikeDao.findLikedCommentIdsByUser(userId),
                ids -> commentLikeDao.findLikedCommentIds(userId, ids));
    }

    // ========================================================================
    // 内部：三态读 / 回填 / 降级（内容与评论共用一个实现，靠 key 与 loader 参数化——沿袭 TV 的收敛手法）
    // ========================================================================

    /** 回源"该用户点赞的全量 id"（回填用）。 */
    @FunctionalInterface
    private interface FullLoader {
        Set<Long> load();
    }

    /** 回源"该用户在给定 id 子集中的命中"（降级用，避免为少量 id 拉全量）。 */
    @FunctionalInterface
    private interface BatchLoader {
        Set<Long> load(List<Long> ids);
    }

    /** 单条判定（降级用）。 */
    @FunctionalInterface
    private interface SingleChecker {
        boolean check();
    }

    /**
     * 三态读单条点赞状态。
     *
     * <pre>
     * 空标记命中 → false（已确认"该用户无点赞"，**不回源 DB**）
     * key 存在   → SISMEMBER 作答（并续期）
     * key 不存在 → miss：回源全量 → 原子回填 → 作答（空集写空标记）
     * Redis 失败 → 降级：单条直查 DB（不影响结果）
     * </pre>
     *
     * <p>判定顺序：**先空标记、后数据 key**（同 follow 侧）——空标记断言"什么都没有"，
     * 数据 key 断言"有什么"，前者更强。
     */
    private boolean isLiked(String setKey, long targetId, FullLoader fullLoader, SingleChecker checker) {
        String emptyKey = CacheKeys.empty(setKey);
        try {
            Set<String> existing = ops.existingOf(setKey, emptyKey);
            if (existing.contains(emptyKey)) {
                return false;                                  // hit-empty：已确认该用户无点赞
            }
            if (existing.contains(setKey)) {
                boolean liked = ops.setIsMember(setKey, targetId);
                ops.expire(setKey, props.likeTtl());
                return liked;
            }
        } catch (CacheUnavailableException e) {
            log.warn("点赞状态缓存读失败，降级 DB: key={}", setKey, e);
            return checker.check();
        }
        // miss：DB 是真理源，顺带回填（回填失败不影响本次结果）；单飞（B1）让同 key 并发只回源一次
        Set<Long> all = singleFlight.get(setKey, () -> {
            Set<Long> loaded = fullLoader.load();
            backfillQuietly(setKey, emptyKey, loaded);
            return loaded;
        });
        return all.contains(targetId);
    }

    /**
     * 三态读计数（cache-aside）。{@code 0} 是合法值，必须与"key 不存在"区分——靠
     * {@code GET} 返回 {@code null}（而非 {@code "0"}）识别 miss。
     */
    private int likeCount(String countKey, java.util.function.Supplier<Integer> dbLoader) {
        try {
            String cached = ops.getString(countKey);
            if (cached != null) {
                ops.expire(countKey, props.likeTtl());
                return Integer.parseInt(cached);
            }
        } catch (CacheUnavailableException e) {
            log.warn("点赞数缓存读失败，降级 DB: key={}", countKey, e);
            return dbLoader.get();
        } catch (NumberFormatException e) {
            // 脏值（非数字）当作 miss，并清掉它，避免每次读都解析失败
            log.warn("点赞数缓存值非法，按 miss 处理并清除: key={}", countKey);
            invalidateQuietly(countKey);
        }
        // miss：DB 是真理源，顺带回填；单飞（B1）让同 key 并发只回源一次
        return singleFlight.get(countKey, () -> {
            int count = dbLoader.get();
            backfillCountQuietly(countKey, count);
            return count;
        });
    }

    /**
     * 三态读批量点赞状态。
     *
     * <pre>
     * 空标记命中 → 全 false（已确认该用户无点赞，**不回源 DB**）
     * key 存在   → 一趟 pipeline 做 N×SISMEMBER（保持 TV T4"命令数与装载量双降"的性质）
     * key 不存在 → miss：回源全量 → 原子回填 → 作答（空集写空标记）
     * Redis 失败 → 降级：按请求 id 批量 DB 作答（IN + {@code <foreach>}）
     * </pre>
     *
     * <p>注意冷 miss 时**只打一次 DB**：TV 会打两次（IN 子查询出答案 + 全量 loader 回填），
     * 但既然 miss 必然回填，那次 IN 查询是冗余的（见 L-7 末段）。
     * IN 查询仍保留——它服务**降级路径**：Redis 挂掉时按请求 id 作答，不该为 3 个 id 拉全量。
     */
    private Map<Long, Boolean> batchIsLiked(String setKey, List<Long> targetIds,
                                            FullLoader fullLoader, BatchLoader degradeLoader) {
        if (targetIds == null || targetIds.isEmpty()) {
            return Map.of();
        }
        String emptyKey = CacheKeys.empty(setKey);
        try {
            Set<String> existing = ops.existingOf(setKey, emptyKey);
            if (existing.contains(emptyKey)) {
                return toResultMap(targetIds, Set.of());       // hit-empty：全 false
            }
            if (existing.contains(setKey)) {
                List<Boolean> hits = ops.setIsMemberBatch(setKey, targetIds);
                ops.expire(setKey, props.likeTtl());
                Map<Long, Boolean> result = new LinkedHashMap<>();
                for (int i = 0; i < targetIds.size(); i++) {
                    result.put(targetIds.get(i), hits.get(i));
                }
                return result;
            }
        } catch (CacheUnavailableException e) {
            log.warn("批量点赞状态缓存读失败，降级 DB: key={}", setKey, e);
            return toResultMap(targetIds, degradeLoader.load(targetIds));
        }
        // miss：单飞（B1）——同一 set 的并发批量读只回源一次（负载是"该用户全量"，与 targetIds 无关）
        Set<Long> all = singleFlight.get(setKey, () -> {
            Set<Long> loaded = fullLoader.load();
            backfillQuietly(setKey, emptyKey, loaded);
            return loaded;
        });
        return toResultMap(targetIds, all);
    }

    private Map<Long, Boolean> toResultMap(List<Long> targetIds, Set<Long> likedIds) {
        Map<Long, Boolean> result = new LinkedHashMap<>();
        for (Long id : targetIds) {
            result.put(id, likedIds.contains(id));
        }
        return result;
    }

    // ==================== 回填（best-effort：失败不影响本次读结果） ====================

    /**
     * 回填（best-effort：失败不影响本次读结果）。
     *
     * <p>空集**不建数据 key**（Redis 里空集合不存在），改写空标记——这是防穿透的落点（T4 / 账 B2）；
     * 非空集走原子替换（{@code DEL + SADD×N + EXPIRE}）并**清空标记**
     * （不清会让刚回填的真集合被 60s 假否定挡住，见 {@link LikeRedisOps#BACKFILL_SET_LUA}）。
     */
    private void backfillQuietly(String setKey, String emptyKey, Set<Long> members) {
        try {
            if (members.isEmpty()) {
                ops.markEmptyIfAbsent(setKey, emptyKey, EMPTY_MARKER_TTL);
            } else {
                ops.backfillSet(setKey, emptyKey, members, props.likeTtl());
            }
        } catch (CacheUnavailableException e) {
            log.warn("点赞成员回填失败（不影响本次读结果，下次读重试）: key={}", setKey);
        }
    }

    private void backfillCountQuietly(String countKey, int count) {
        try {
            ops.setString(countKey, String.valueOf(count), props.likeTtl());
        } catch (CacheUnavailableException e) {
            log.warn("点赞计数回填失败（不影响本次读结果，下次读重试）: key={}", countKey);
        }
    }
}
