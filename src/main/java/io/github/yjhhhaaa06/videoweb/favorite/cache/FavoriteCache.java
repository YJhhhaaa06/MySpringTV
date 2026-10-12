package io.github.yjhhhaaa06.videoweb.favorite.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.cache.SingleFlight;
import io.github.yjhhhaaa06.videoweb.common.config.FavoriteCacheProperties;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.favorite.dao.FavoriteItemDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 收藏缓存（收藏二期 T11 / {@code 收藏功能-分期与设计.md} §3.7）——决策⑤「显式封装层」，
 * **不用** {@code @Cacheable}。形态与 {@code like.cache.LikeCache} 同构（任务书要求"别自创"），
 * 差异只在**写路径**：见下"★ 与 LikeCache 的唯一形态差异"。
 *
 * <h2>两个 key 族</h2>
 * <ol>
 *   <li>{@code user:favoriteSet:{userId}}（**Set&lt;contentId&gt;**）——"我收藏过哪些内容"。
 *       成员 key 取**用户维度**（同 like 的装载反转）：装载量 = 该用户收藏数，与内容热度解耦；</li>
 *   <li>{@code content:favoriteCount:{id}}（**String 整数**）——"这条内容被多少人收藏"（按人去重）。</li>
 * </ol>
 * ⚠️ **没有 {@code partial:}**：Set 无偏移语义，收藏域也没有"按序分页读大集合"的路径
 * （{@code /favorite/list} 分页的是**夹内记录行**、走 DB，见 §3.7），不存在前缀态。
 * ⚠️ **没有 {@code folders} 缓存**（§3.7 拍板）：低频 + "未收藏时短路"⇒ 正常路径几乎不打 DB；
 * 给它加缓存的失效点有 add / remove / move / 删夹 / 改名五个，换到的只是省一次亚毫秒点查。
 *
 * <h2>读路径（三态，与 like 逐项同构）</h2>
 * <pre>
 * 空标记命中 → false / 全 false（已确认该用户无收藏，**不回源 DB**）
 * 数据 key 在 → SISMEMBER / N×SISMEMBER（pipeline 一趟），并滑动续期
 * 两者皆无   → miss：单飞回源 → 原子回填 → 作答（空集写空标记）
 * Redis 失败 → 降级直查 DB（单条 checker / 按请求 id 子集批量），**不写回**
 * </pre>
 * 判定顺序 **先空标记、后数据 key**（与 like / follow 一致）：空标记断言"什么都没有"，
 * 数据 key 断言"有什么"，前者更强。
 *
 * <h2>★ 两种失败必须区分（缓存文档 §四.1）</h2>
 * Redis 失败（{@link CacheUnavailableException}）⇒ 降级直查 DB、接口不 500；
 * DB 失败（{@code DataAccessException}）⇒ **原样上抛**（→ 500）。实现上靠**只 catch 自己的
 * 异常类型**：{@link CacheUnavailableException} 只从 {@link FavoriteRedisOps} 冒出，
 * DB 异常不经过它。把"读不到"降级成"没有"是更坏的语义。
 *
 * <h2>★ 与 {@code LikeCache} 的唯一形态差异：写路径不做增量条件写</h2>
 * 点赞的一条关系行 ⟺ 一个赞，故 like 的写路径可以精确 {@code SADD+INCR} / {@code SREM+DECR}。
 * 收藏**不行**：{@code favorite_item} 的唯一键是 {@code uk_folder_content}（**夹内**唯一），
 * 同一用户可把同一内容收在多个夹里（合法态，需求篇 §三）⇒
 * <ul>
 *   <li>{@code add}（收进第二个夹）**不改变**收藏数；</li>
 *   <li>{@code remove}（从某夹移出）**不必然**取消收藏（可能仍收在别的夹）；</li>
 *   <li>{@code move} 甚至可能"源夹本没有这条"⇒ insert 成功 + 源侧删 0 行 = **一次新增收藏**。</li>
 * </ul>
 * ⇒ 写路径分两档（{@code CURRENT_ISSUES.md} I-11 已登记、用户 2026-10-12 拍板）：
 * <table>
 *   <caption>写路径动作</caption>
 *   <tr><th>写点</th><th>成员侧</th><th>计数侧</th></tr>
 *   <tr><td>{@code add}</td><td>{@link #favoriteAdded}：条件 {@code SADD}（**单调 ⇒ 恒安全**）+ 清空标记</td>
 *       <td>{@link #invalidateFavoriteCount}（`DEL`）</td></tr>
 *   <tr><td>{@code remove} / {@code deleteFolder}</td><td>{@link #invalidateUserFavorites}（整体失效）</td>
 *       <td>{@link #invalidateFavoriteCounts}（`DEL` 各受影响内容）</td></tr>
 *   <tr><td>{@code move}</td><td>{@link #invalidateUserFavorites}</td>
 *       <td>{@link #invalidateFavoriteCount}</td></tr>
 * </table>
 * ⚠️ {@code move} **不做"净效应为零 ⇒ 不写"**：常态下它确实不改收藏态，但"源夹不含该内容"时
 * 等价于一次新增收藏 ⇒ 保守走整体失效。代价是移动后该用户下次读要全量回源一次，
 * 与"move 是低频操作"相称（这条偏离了 T11 任务书那句"四个写点都条件写"，理由见 I-11）。
 *
 * <h2>★ 计数的回源（T12 起读冗余列）</h2>
 * 一期回源走实时 {@code COUNT(DISTINCT user_id)}（≈17ms / 2 万行基线，
 * {@code 收藏功能-分期与设计.md} §3.2）；T12 落地后改读 {@code content.favorite_count} 列
 * （主键点查，{@code ContentDao.findFavoriteCountById}）—— 列由收藏域四条写路径的
 * 「事务内批量重算」（{@code FavoriteService.recountFavoriteCounts}）维护，不变量
 * {@code 列 == COUNT(DISTINCT user_id)} 由 T12 的独立 oracle 钉住。
 * 回源收在 {@link #loadFavoriteCount} **一个调用点**里；本类的 {@code DEL} 策略在
 * 列读下更便宜（miss 从 17ms 聚合降到点查），这也是"计数侧一律 DEL 而不是硬做 ±1"的经济理由。
 *
 * <h2>代价（如实登记，G14）</h2>
 * 本仓**无真实流量**：这层缓存的可测收益拿不到基线证据（{@code SISMEMBER} vs
 * {@code idx_user_content} 点查在本机是同一量级）。做它的理由是"展示决策 + 它是 T12 的对照面"
 * ——属**已知代价后选择做**（回写里同样登记）。缓存引入的三态 / 空标记 / 降级 / 失效四套机制
 * 与相应测试成本，是这笔收益的对价。
 */
@Slf4j
@Component
public class FavoriteCache {

    /** 我收藏过的内容（Set&lt;contentId&gt;）。委托 {@link CacheKeys}（键命名全仓唯一源）。 */
    static String userFavoriteSetKey(long userId) {
        return CacheKeys.userFavoriteSet(userId);
    }

    /** 某内容的收藏数（String 整数）。委托 {@link CacheKeys}。 */
    static String contentFavoriteCountKey(long contentId) {
        return CacheKeys.contentFavoriteCount(contentId);
    }

    /**
     * 空标记的短 TTL，60 秒（口径见 {@link CacheKeys#EMPTY_MARKER_TTL_SECONDS}）。
     * 刻意比成员 key 的 TTL（{@code video.cache.favorite-ttl}）短得多：它是"高置信度但短时效"的结论。
     * 命中读**不续期**空标记（与 content / follow / like 同口径）。
     */
    static final Duration EMPTY_MARKER_TTL = Duration.ofSeconds(CacheKeys.EMPTY_MARKER_TTL_SECONDS);

    private final FavoriteRedisOps ops;
    private final FavoriteItemDao itemDao;
    /** content 列读（T12：计数回源从实时聚合切到 {@code content.favorite_count} 点查）。 */
    private final ContentDao contentDao;
    private final FavoriteCacheProperties props;
    /** 单飞（B1）：同 key 并发 miss 只回源一次。 */
    private final SingleFlight singleFlight;

    public FavoriteCache(FavoriteRedisOps ops,
                         FavoriteItemDao itemDao,
                         ContentDao contentDao,
                         FavoriteCacheProperties props,
                         SingleFlight singleFlight) {
        this.ops = ops;
        this.itemDao = itemDao;
        this.contentDao = contentDao;
        this.props = props;
        this.singleFlight = singleFlight;
    }

    // ========================================================================
    // 写：由 FavoriteService 的写点在**事务提交后**调用（AfterCommit）
    // ========================================================================

    /**
     * 收藏了一条内容（{@code add}）——**唯一**的条件写。
     *
     * <p>为什么 {@code add} 可以条件写而其余不能：它的变化是**单调**的
     * （"收藏过" ⇒ 写后仍"收藏过"；同一内容收进第二个夹时 {@code SADD} 是幂等 no-op）
     * ⇒ {@code SADD} 恒安全，不需要知道"他以前是否收藏过这条"。
     * 冷 key（成员集未加载）时脚本只清空标记、**不建集合**（不写半套数据），
     * 交给读路径 miss 回源整体回填。
     */
    public void favoriteAdded(long userId, long contentId) {
        String setKey = userFavoriteSetKey(userId);
        String emptyKey = CacheKeys.empty(setKey);
        try {
            ops.applyAddConditionalWrite(setKey, emptyKey, contentId);
        } catch (CacheUnavailableException e) {
            log.warn("收藏缓存条件写失败，整体失效让读自愈: setKey={}", setKey, e);
            // ⚠️ 空标记**必须一起失效**：它是读路径的第一判定，残留会让"刚写的收藏"在 60s 内
            //    读回 false 且**不回源 DB 自愈** —— 正是"写失败 ⇒ 失效让读自愈"这条纪律在
            //    空标记这一半的落点（对照 LikeCache.conditionalWrite 的同一处置）。
            invalidateQuietly(setKey, emptyKey);
        }
    }

    /**
     * 该用户的成员集**整体失效**（{@code remove} / {@code move} / {@code deleteFolder}）。
     *
     * <p>为什么不逐条 {@code SREM}：见类注释"与 LikeCache 的唯一形态差异"——
     * 移出某个夹不代表取消收藏（可能仍收在别的夹），逐条删会给出**错的答案**；
     * 而"这个人现在到底还收不收着这条"要额外查一次 DB（T12 的批量重算形态），
     * 对低频的删除操作不值当。整体失效 = 读下次 miss 回源一次，代价明确、答案永远对。
     *
     * <p>★ 失效集 = **数据 key + 空标记**（缓存文档 §三.1 红线）：
     * 只 DEL 数据 key 而留着空标记，下次读会命中空标记答 false 且不回源（60s 假否定）。
     */
    public void invalidateUserFavorites(long userId) {
        String setKey = userFavoriteSetKey(userId);
        invalidateQuietly(setKey, CacheKeys.empty(setKey));
    }

    /** 某内容的收藏数失效（{@code add} / {@code remove} / {@code move}）。计数 key **无空标记**（`0` 是合法值）。 */
    public void invalidateFavoriteCount(long contentId) {
        invalidateQuietly(contentFavoriteCountKey(contentId));
    }

    /**
     * 批量失效若干内容的收藏数（{@code deleteFolder}：一个夹里可能有上千条）。
     *
     * <p>一趟 {@code DEL key...} 发完（Redis 的 {@code DEL} 原生支持多 key），**不循环往返**。
     */
    public void invalidateFavoriteCounts(Collection<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return;
        }
        String[] keys = new String[contentIds.size()];
        int i = 0;
        for (Long contentId : contentIds) {
            keys[i++] = contentFavoriteCountKey(contentId);
        }
        invalidateQuietly(keys);
    }

    /** 失效动作本身也在 Redis 上；失败只记日志（不能因为缓存失效失败让已提交的业务表现成失败），交由 TTL 自愈。 */
    private void invalidateQuietly(String... keys) {
        try {
            ops.delete(keys);
        } catch (CacheUnavailableException e) {
            log.warn("收藏缓存失效也失败（Redis 不可用），交由 TTL 自愈: keys={}", List.of(keys));
        }
    }

    // ========================================================================
    // 读：判存在（单条 / 批量）
    // ========================================================================

    /**
     * 当前用户是否收藏过某内容（供 {@code FavoriteService.isFavorited} 与互动域单条契约）。
     *
     * <p>签名与 {@code FavoriteService.isFavorited(userId, contentId)} 一致（返回 {@code boolean}）：
     * 缓存是**域内实现细节**，互动域只依赖 Service 契约、对它无感
     * （{@code 模块边界与依赖.md} §四）。
     */
    public boolean isFavorited(long userId, long contentId) {
        String setKey = userFavoriteSetKey(userId);
        String emptyKey = CacheKeys.empty(setKey);
        try {
            Set<String> existing = ops.existingOf(setKey, emptyKey);
            if (existing.contains(emptyKey)) {
                return false;                                  // hit-empty：已确认该用户无收藏
            }
            if (existing.contains(setKey)) {
                boolean favorited = ops.setIsMember(setKey, contentId);
                ops.expire(setKey, props.favoriteTtl());
                return favorited;
            }
        } catch (CacheUnavailableException e) {
            log.warn("收藏状态缓存读失败，降级 DB: key={}", setKey, e);
            return itemDao.existsByUserAndContent(userId, contentId);
        }
        // miss：DB 是真理源，顺带回填（回填失败不影响本次结果）；单飞让同 key 并发只回源一次
        Set<Long> all = singleFlight.get(setKey, () -> {
            Set<Long> loaded = itemDao.findAllFavoritedContentIds(userId);
            backfillQuietly(setKey, emptyKey, loaded);
            return loaded;
        });
        return all.contains(contentId);
    }

    /**
     * 批量判存在（供 {@code FavoriteService.batchIsFavorited} 与互动域批量契约）——
     * 入参每个 id 都回一条，缺失补 {@code false}（完整映射，不重不漏）。
     *
     * <p>★ 保持"命令数与装载量双降"的性质：命中态用**一趟 pipeline** 做 N×{@code SISMEMBER}，
     * 降级态用**一趟** {@code IN} 批量查询按请求 id 子集作答（不为 3 个 id 拉全量）。
     */
    public Map<Long, Boolean> batchIsFavorited(long userId, List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Map.of();
        }
        String setKey = userFavoriteSetKey(userId);
        String emptyKey = CacheKeys.empty(setKey);
        try {
            Set<String> existing = ops.existingOf(setKey, emptyKey);
            if (existing.contains(emptyKey)) {
                return toResultMap(contentIds, Set.of());      // hit-empty：全 false
            }
            if (existing.contains(setKey)) {
                List<Boolean> hits = ops.setIsMemberBatch(setKey, contentIds);
                ops.expire(setKey, props.favoriteTtl());
                Map<Long, Boolean> result = new LinkedHashMap<>();
                for (int i = 0; i < contentIds.size(); i++) {
                    result.put(contentIds.get(i), hits.get(i));
                }
                return result;
            }
        } catch (CacheUnavailableException e) {
            log.warn("批量收藏状态缓存读失败，降级 DB: key={}", setKey, e);
            return toResultMap(contentIds, itemDao.findFavoritedContentIds(userId, contentIds));
        }
        // miss：单飞 key 用**数据 key**（负载是"该用户全量收藏"，与 targetIds 无关 —— 降级才按子集）
        Set<Long> all = singleFlight.get(setKey, () -> {
            Set<Long> loaded = itemDao.findAllFavoritedContentIds(userId);
            backfillQuietly(setKey, emptyKey, loaded);
            return loaded;
        });
        return toResultMap(contentIds, all);
    }

    private Map<Long, Boolean> toResultMap(List<Long> targetIds, Set<Long> favoritedIds) {
        Map<Long, Boolean> result = new LinkedHashMap<>();
        for (Long id : targetIds) {
            result.put(id, favoritedIds.contains(id));
        }
        return result;
    }

    // ========================================================================
    // 读：收藏数
    // ========================================================================

    /**
     * 某内容的收藏数（供 {@code FavoriteService.getFavoriteCount}）。
     *
     * <p>{@code 0} 是**合法值**：必须与"key 不存在"区分——靠 {@code GET} 返回 {@code null}
     * （而非 {@code "0"}）识别 miss。脏值（非数字）当 miss 处理并清掉它，避免每次读都解析失败。
     */
    public long getFavoriteCount(long contentId) {
        String countKey = contentFavoriteCountKey(contentId);
        try {
            String cached = ops.getString(countKey);
            if (cached != null) {
                ops.expire(countKey, props.favoriteTtl());
                return Long.parseLong(cached);
            }
        } catch (CacheUnavailableException e) {
            log.warn("收藏数缓存读失败，降级 DB: key={}", countKey, e);
            return loadFavoriteCount(contentId);
        } catch (NumberFormatException e) {
            log.warn("收藏数缓存值非法，按 miss 处理并清除: key={}", countKey);
            invalidateQuietly(countKey);
        }
        return singleFlight.get(countKey, () -> {
            long count = loadFavoriteCount(contentId);
            backfillCountQuietly(countKey, count);
            return count;
        });
    }

    /**
     * 收藏数的**回源唯一调用点**（T12 已切换：实时聚合 → 冗余列点查）。
     *
     * <p>T12 之前：实时 {@code COUNT(DISTINCT user_id)}（按人去重，R-06；≈17ms / 2 万行基线，
     * {@code 收藏功能-分期与设计.md} §3.2 —— 该查询已随切换退役删除）。
     * T12 之后：读 {@code content.favorite_count} 列（主键点查，
     * {@code ContentDao.findFavoriteCountById}）—— 列由四条写路径**事务内批量重算**维护
     * （{@code FavoriteService.recountFavoriteCounts}），不变量
     * {@code 列 == COUNT(DISTINCT user_id)} 由 T12 的独立 oracle 钉住。
     * ⚠️ 回源点只此一处，别把列名或 COUNT 散到别处。
     */
    private long loadFavoriteCount(long contentId) {
        return contentDao.findFavoriteCountById(contentId);
    }

    // ==================== 回填（best-effort：失败不影响本次读结果） ====================

    /**
     * 回填成员集（best-effort）。空集**不建数据 key**（Redis 里空集合不存在），改写空标记
     * ——防穿透的落点；非空集走原子替换（{@code DEL + SADD×N + EXPIRE}）并**清空标记**
     * （不清会让刚回填的真集合被 60s 假否定挡住）。
     */
    private void backfillQuietly(String setKey, String emptyKey, Set<Long> members) {
        try {
            if (members.isEmpty()) {
                ops.markEmptyIfAbsent(setKey, emptyKey, EMPTY_MARKER_TTL);
            } else {
                ops.backfillSet(setKey, emptyKey, members, props.favoriteTtl());
            }
        } catch (CacheUnavailableException e) {
            log.warn("收藏成员回填失败（不影响本次读结果，下次读重试）: key={}", setKey);
        }
    }

    private void backfillCountQuietly(String countKey, long count) {
        try {
            ops.setString(countKey, String.valueOf(count), props.favoriteTtl());
        } catch (CacheUnavailableException e) {
            log.warn("收藏数回填失败（不影响本次读结果，下次读重试）: key={}", countKey);
        }
    }
}
