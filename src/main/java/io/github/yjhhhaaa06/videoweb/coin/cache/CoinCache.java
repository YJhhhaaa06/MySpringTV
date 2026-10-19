package io.github.yjhhhaaa06.videoweb.coin.cache;

import io.github.yjhhhaaa06.videoweb.coin.dao.ContentCoinDao;
import io.github.yjhhhaaa06.videoweb.coin.model.ContentCoinAmount;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheMissSignal;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.cache.SingleFlight;
import io.github.yjhhhaaa06.videoweb.common.config.CoinCacheProperties;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.model.ContentCoinCount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 投币缓存（投币专项 T39 / {@code architecture/domain/coin/coin.md} §六）——
 * 决策⑤「显式封装层」，**不用** {@code @Cacheable}。骨架与 {@code like.cache.LikeCache} /
 * {@code favorite.cache.FavoriteCache} 同构（任务书要求"别自创"）。
 *
 * <h2>两个 key，且**只碰这两个**</h2>
 * <ul>
 *   <li>{@code user:coinAmount:{userId}}（**HASH**，field = contentId，value = amount）——
 *       我投过币的内容及枚数；miss 时**全量回填**（{@code amount > 0}）。</li>
 *   <li>{@code content:coinCount:{id}}（String int）—— 内容累计被投币数（公共计数）。</li>
 * </ul>
 * ★ <b>绝不触碰 {@code content:{id}}</b>：本类只负责 coin 域自己的两个 key。
 * 这是 {@code LikeChangedListener} 瘦身（S6-B2b）之后的纪律 —— 点赞域不再顺手改
 * 内容缓存，投币域同样不。需要失效 {@code content:{id}} 时由内容域自己的监听器做。
 *
 * <h2>★ {@code content:coinCount} 为什么是独立 key（对 F-25 的站边）</h2>
 * 仓内计数缓存有两种形态：like / comment 的计数**内联**进 {@code content:{id}} JSON
 * （代价 = 每次点赞/评论连坐失效整份内容缓存），favorite（T11）**只走独立 key**。
 * 本域选独立 key（先例 = favorite）：不把"计数变化连坐失效整份内容缓存"复制到新域；
 * 接受的代价 = 列表面多一趟 {@code MGET}（常量往返，不随条数增长）。
 *
 * <h2>★ 内容删除：只失效缓存，**不物理删 {@code content_coin} 行**（与 like 有意不同）</h2>
 * like 的内容删除会删关系行（{@code ContentLikeDao.deleteByContentId}）；投币**不能**这么做：
 * <ul>
 *   <li>投币**不可取消**（`architecture/domain/coin/coin.md` §一 / §二），行是已发生的硬币流转凭据；</li>
 *   <li>不变量 {@code content.coin_count == SUM(content_coin.amount)} 要求行留存 ——
 *       删行会让"计数列"失去可复算的真相源（本仓的缓存/计数都靠独立 oracle 复算，删掉就复算不了）；</li>
 *   <li>内容 id 自增不复用 ⇒ 留下的行不会指向"另一条内容"。</li>
 * </ul>
 * 故内容删除只调 {@link #invalidateCoinCount(long)}（{@code DEL content:coinCount:{id}}）。
 * 成员 HASH（用户维度）同样无法廉价反查"谁给这条投过币"逐个 {@code HDEL}，残留 field 指向
 * 已删内容且没有读路径 ⇒ 永不外显（与 like 的成员集同一处置）。
 *
 * <h2>读：三态（与 like 逐项同构）</h2>
 * <pre>
 * 空标记命中 → 0（已确认"该用户一枚币都没投过"，**不回源 DB**）
 * HASH 在    → HGET；field 缺失 ⇒ 0，并滑动续期
 * 两者皆无   → miss：**单飞**回源 → 原子回填 → 作答（空集写空标记）
 * Redis 失败 → 降级直查 DB（单条点查 / 批量一趟），**不写回**
 * </pre>
 * 判定顺序 **先空标记、后数据 key**（与 like / follow / favorite 一致）：空标记断言
 * "什么都没有"，数据 key 断言"有什么"，前者更强。
 *
 * <p>{@code content:coinCount} **无空标记**：{@code 0} 是合法值（没人投过币的内容真实存在），
 * 必须与"key 不存在"靠 {@code GET} 返回 {@code null} / {@code "0"} 区分（同
 * {@code content:likeCount}）。miss 回源读 {@code content.coin_count} 那一列，
 * **回源为 null 时不写缓存**：内容不存在是 404 路径，把 {@code 0} 钉进 TTL 内就是假值。
 *
 * <h2>★ 两种失败必须区分（缓存文档 §四.1）</h2>
 * Redis 失败（{@link CacheUnavailableException}）⇒ 降级直查 DB、接口不 500；
 * DB 失败（{@code DataAccessException}）⇒ **原样上抛**（→ 500）。实现上靠**只 catch 自己的
 * 异常类型**：{@link CacheUnavailableException} 只从 {@link CoinRedisOps} 冒出，
 * DB 异常不经过它。把"读不到"降级成"没有"是更坏的语义。
 *
 * <h2>写：提交后条件写（冷 key 不建半套）</h2>
 * 由 {@code coin.event.CoinChangedListener} 在 {@code AFTER_COMMIT} 调用
 * {@link #onCoinChanged}：HASH 写**新总量**（精确回放、幂等），计数 key 条件 {@code INCRBY}
 * （单调只增）。两者都只在 key **已加载**时执行 —— 冷 key 交给读 miss 回填，
 * 绝不创建"看起来对、其实缺项/缺历史"的残缺缓存。
 *
 * <p>批量读**不做单飞**（同 {@code LikeCache} 的批量纪律）：它一趟 {@code MGET} + 一趟
 * {@code IN} 已经消除了逐 key 往返，逐 key 单飞只会把"一趟"拆回 N 次。
 */
@Slf4j
@Component
public class CoinCache {

    /**
     * 空标记的短 TTL，60 秒（口径见 {@link CacheKeys#EMPTY_MARKER_TTL_SECONDS}）。
     * 刻意比 HASH 的 TTL（{@code video.cache.coin-ttl}）短得多：它是"高置信度但短时效"的结论。
     * 命中读**不续期**空标记（与 content / follow / like / favorite 同口径）。
     */
    static final Duration EMPTY_MARKER_TTL = Duration.ofSeconds(CacheKeys.EMPTY_MARKER_TTL_SECONDS);

    private final CoinRedisOps ops;
    private final ContentCoinDao contentCoinDao;
    /** 计数回源的唯一来源（{@code content.coin_count} 单列窄查询）。 */
    private final ContentDao contentDao;
    private final CoinCacheProperties props;
    /** 单飞：同一用户 HASH 的并发 miss 只回源一次（批量路径**不接**，见类注释）。 */
    private final SingleFlight singleFlight;

    public CoinCache(CoinRedisOps ops,
                     ContentCoinDao contentCoinDao,
                     ContentDao contentDao,
                     CoinCacheProperties props,
                     SingleFlight singleFlight) {
        this.ops = ops;
        this.contentCoinDao = contentCoinDao;
        this.contentDao = contentDao;
        this.props = props;
        this.singleFlight = singleFlight;
    }

    // ========================================================================
    // 写：由 CoinChangedListener 在**事务提交后**调用（AFTER_COMMIT）
    // ========================================================================

    /**
     * 投币已提交：条件写两个 key。
     *
     * <p>① {@code user:coinAmount} —— {@code HSET} **新总量**（事件带出的
     * {@code newAmount}）：投币是"单行聚合"（唯一键 {@code uk_user_content}），一次投 1 或 2
     * 后该用户对该内容的累计枚数是确定的；写总量 ⇒ **精确回放**、幂等，重复投递同一事件
     * 不会多算（设计 §六 ①）。
     * <p>② {@code content:coinCount} —— 条件 {@code INCRBY delta}：投币不可取消 ⇒
     * 本计数单调只增，增量写是精确的。
     *
     * <p>★ <b>冷 key 一律不写半套</b>：两个脚本各自带 {@code if EXISTS} 守卫；未加载的 key
     * 只清空标记、不创建 —— 交给读 miss 回填 DB 真相。
     *
     * <p>★ <b>失败只记日志、不抛</b>：缓存写失败不能影响**已经提交**的业务事务
     * （投币接口不该因为 Redis 抖动而 500）。同时尽力失效相关 key（数据 key + 空标记），
     * 让下一次读走 miss 自愈 —— "写失败 ⇒ 失效让读自愈"这条纪律在空标记这一半同样成立：
     * 若残留"该用户无投币"的空标记，刚提交的币会被 60 秒假否定挡住。
     * 失效本身也可能失败（Redis 真挂时必然失败），一并吞掉，交由 TTL 自愈。
     */
    public void onCoinChanged(long userId, long contentId, int newAmount, int delta) {
        String hashKey = CacheKeys.userCoinAmount(userId);
        String emptyKey = CacheKeys.empty(hashKey);
        String countKey = CacheKeys.contentCoinCount(contentId);
        try {
            ops.applyCoinAmountConditionalWrite(hashKey, emptyKey, contentId, newAmount);
            ops.applyCoinCountConditionalIncr(countKey, delta);
        } catch (CacheUnavailableException e) {
            log.warn("投币缓存条件写失败，失效相关 key 让读自愈: hashKey={}, countKey={}",
                    hashKey, countKey, e);
            invalidateQuietly(hashKey, emptyKey, countKey);
        }
    }

    /**
     * 内容被删/下架：失效其投币**计数**缓存（{@code content:coinCount:{id}}）。
     *
     * <p>★ **不触碰 {@code content_coin} 行**（与 like 的 {@code deleteByContentId} 有意不同）：
     * 投币不可取消 + 不变量 {@code coin_count == SUM(amount)} 要求行留存，理由见类注释。
     * 成员 HASH 是用户维度的，无法廉价反查"谁投过这条"逐个 {@code HDEL}；残留 field 指向
     * 已删内容且无读路径 ⇒ 永不外显（同 like 的成员集）。
     *
     * <p>失效失败只记日志（内容删除事务**已经提交**，缓存故障不能让删除表现成失败），
     * 交由 TTL 自愈。
     */
    public void invalidateCoinCount(long contentId) {
        invalidateQuietly(CacheKeys.contentCoinCount(contentId));
    }

    private void invalidateQuietly(String... keys) {
        try {
            ops.delete(keys);
        } catch (CacheUnavailableException e) {
            log.warn("投币缓存失效也失败（Redis 不可用），交由 TTL 自愈: keys={}", List.of(keys));
        }
    }

    // ========================================================================
    // 读：我投了几枚（用户维度 HASH）
    // ========================================================================

    /**
     * 我在该内容上投了几枚（{@code 0/1/2}）。
     *
     * <pre>
     * 空标记命中 → 0（已确认"该用户一枚都没投过"，**不回源 DB**）
     * HASH 在    → HGET；field 缺失 ⇒ 0（"没投过这一条"）
     * 两者皆无   → miss：**单飞**回源该用户全量（已只含 {@code amount > 0}）→ 原子回填 → 作答
     * Redis 失败 → 降级单条直查 DB（{@code findAmount}），**不写回**
     * </pre>
     *
     * <p>★ 回源的 {@link ContentCoinDao#findAmountsByUser} 是**全量装载**（同 like / favorite 的
     * miss 回填）：单飞 key 用**数据 key**（装载负载与具体 contentId 无关）；降级才按单条点查。
     *
     * <p>★ <b>DB 异常必须上抛</b>：{@code catch} 只接 {@link CacheUnavailableException}，
     * 回源里的 {@code DataAccessException} 不经过它（单飞原样重抛）⇒ 不会被降级吞成 0。
     */
    public int getCoinAmount(long userId, long contentId) {
        String hashKey = CacheKeys.userCoinAmount(userId);
        String emptyKey = CacheKeys.empty(hashKey);
        try {
            Set<String> existing = ops.existingOf(hashKey, emptyKey);
            if (existing.contains(emptyKey)) {
                return 0;                                   // hit-empty：已确认该用户无投币
            }
            if (existing.contains(hashKey)) {
                String cached = ops.hget(hashKey, contentId);
                ops.expire(hashKey, props.coinTtl());
                if (cached == null) {
                    return 0;                               // key 已加载、field 不存在 = 没投过这条
                }
                try {
                    return Integer.parseInt(cached);
                } catch (NumberFormatException e) {
                    // 脏值（非数字）当 miss 处理并清掉它，避免每次读都解析失败（同 LikeCache 的计数读）
                    log.warn("投币枚数缓存值非法，按 miss 处理并清除: hashKey={}, field={}", hashKey, contentId);
                    invalidateQuietly(hashKey, emptyKey);
                }
            }
        } catch (CacheUnavailableException e) {
            log.warn("投币枚数缓存读失败，降级 DB: hashKey={}", hashKey, e);
            CacheMissSignal.mark();                         // 降级 = 没走成缓存
            return nullToZero(contentCoinDao.findAmount(userId, contentId));
        }
        // miss：DB 是真理源，顺带回填（回填失败不影响本次结果）；单飞让同 key 并发只回源一次
        CacheMissSignal.mark();
        Map<Long, Integer> all = singleFlight.get(hashKey, () -> {
            Map<Long, Integer> loaded = loadAmounts(userId);
            backfillAmountsQuietly(hashKey, emptyKey, loaded);
            return loaded;
        });
        return all.getOrDefault(contentId, 0);
    }

    // ========================================================================
    // 读：内容累计投币数（独立计数 key） / 批量
    // ========================================================================

    /**
     * 某内容的累计投币数（公共计数）。
     *
     * <p>{@code 0} 是**合法值**：必须与"key 不存在"区分——靠 {@code GET} 返回 {@code null}
     * （而非 {@code "0"}）识别 miss。miss 回源读 {@code content.coin_count} 单列；
     * **DB 返回 null（内容不存在）时不写缓存**：内容不存在是 404 路径，缓存 0 会把
     * "不存在"在 TTL 内钉成假值。
     *
     * <p>Redis 失败 ⇒ 降级直查 DB（null ⇒ 0），不写回。
     */
    public long getCoinCount(long contentId) {
        String countKey = CacheKeys.contentCoinCount(contentId);
        try {
            String cached = ops.getString(countKey);
            if (cached != null) {
                ops.expire(countKey, props.coinTtl());
                try {
                    return Long.parseLong(cached);
                } catch (NumberFormatException e) {
                    log.warn("投币计数缓存值非法，按 miss 处理并清除: key={}", countKey);
                    invalidateQuietly(countKey);
                }
            }
        } catch (CacheUnavailableException e) {
            log.warn("投币计数缓存读失败，降级 DB: key={}", countKey, e);
            CacheMissSignal.mark();
            return nullToZero(contentDao.findCoinCount(contentId));
        }
        CacheMissSignal.mark();
        return singleFlight.get(countKey, () -> {
            Integer dbCount = contentDao.findCoinCount(contentId);
            if (dbCount != null) {
                backfillCountQuietly(countKey, dbCount.longValue());
            }
            return dbCount == null ? 0L : dbCount.longValue();
        });
    }

    /**
     * 批量读多条内容的累计投币数，返回**完整**映射（入参每个 id 都有一条，DB 里没有的记 0）。
     *
     * <p>趟数恒定：一趟 {@code MGET} → 缺失子集**一趟** {@code IN} 批量查询 →
     * 逐个 {@code setString} 回填（回填 best-effort，失败不影响本次结果）。
     *
     * <p>★ **不做单飞**（同 {@code LikeCache} 的批量纪律）：批量路径本身已经消除了逐 key 往返，
     * 用单一数据 key 做单飞 key 还会让不同 id 子集**串结果**。降级态一趟 DB、**不写缓存**。
     *
     * <p>DB 里没有的 id **不写缓存**：内容不存在是 404 路径，写 0 会把它钉成假值
     * （与单条 {@link #getCoinCount} 同口径）。
     */
    public Map<Long, Long> batchGetCoinCount(Collection<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(contentIds));  // 去重但保持入参顺序
        List<String> keys = new ArrayList<>(ids.size());
        for (Long id : ids) {
            keys.add(CacheKeys.contentCoinCount(id));
        }
        try {
            List<String> values = ops.mget(keys);
            Map<Long, Long> result = new LinkedHashMap<>();
            List<Long> missing = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                String cached = i < values.size() ? values.get(i) : null;
                if (cached == null) {
                    missing.add(ids.get(i));
                    continue;
                }
                try {
                    result.put(ids.get(i), Long.parseLong(cached));
                } catch (NumberFormatException e) {
                    log.warn("投币计数缓存值非法，按 miss 处理并清除: key={}", keys.get(i));
                    invalidateQuietly(keys.get(i));
                    missing.add(ids.get(i));
                }
            }
            if (!missing.isEmpty()) {
                // 一趟 DB；无匹配行的 id 不在返回里 ⇒ 由下面的 putIfAbsent 补 0
                Map<Long, Long> loaded = loadCounts(missing);
                result.putAll(loaded);
                for (Map.Entry<Long, Long> e : loaded.entrySet()) {
                    backfillCountQuietly(CacheKeys.contentCoinCount(e.getKey()), e.getValue());
                }
            }
            for (Long id : ids) {
                result.putIfAbsent(id, 0L);                 // DB 里没有的 id 记 0（不缓存）
            }
            return result;
        } catch (CacheUnavailableException e) {
            log.warn("批量投币计数缓存读失败，降级 DB（一趟）: ids={}", ids, e);
            CacheMissSignal.mark();
            Map<Long, Long> result = new LinkedHashMap<>(loadCounts(ids));
            for (Long id : ids) {
                result.putIfAbsent(id, 0L);
            }
            return result;
        }
    }

    // ========================================================================
    // 内部：回源 / 回填 / 小工具
    // ========================================================================

    /** 回源该用户投过币的全部内容及枚数（{@code amount > 0}，DAO 已过滤）。 */
    private Map<Long, Integer> loadAmounts(long userId) {
        Map<Long, Integer> amounts = new LinkedHashMap<>();
        for (ContentCoinAmount row : contentCoinDao.findAmountsByUser(userId)) {
            amounts.put(row.contentId(), row.amount());
        }
        return amounts;
    }

    /** 一趟批量回源多条内容的计数（DB 里没有的 id 不出现在返回里）。 */
    private Map<Long, Long> loadCounts(Collection<Long> contentIds) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (ContentCoinCount row : contentDao.findCoinCountsByIds(contentIds)) {
            counts.put(row.contentId(), row.coinCount() == null ? 0L : row.coinCount().longValue());
        }
        return counts;
    }

    /**
     * 回填用户投币 HASH（best-effort：失败不影响本次读结果）。
     *
     * <p>空集**不建数据 key**（Redis 里空 HASH 不存在），改写空标记 —— 防穿透的落点；
     * 非空集走原子替换（{@code DEL + HSET×N + EXPIRE}）并**清空标记**。
     */
    private void backfillAmountsQuietly(String hashKey, String emptyKey, Map<Long, Integer> amounts) {
        try {
            if (amounts.isEmpty()) {
                ops.markEmptyIfAbsent(hashKey, emptyKey, EMPTY_MARKER_TTL);
            } else {
                ops.backfillCoinAmountHash(hashKey, emptyKey, amounts, props.coinTtl());
            }
        } catch (CacheUnavailableException e) {
            log.warn("投币枚数回填失败（不影响本次读结果，下次读重试）: hashKey={}", hashKey);
        }
    }

    private void backfillCountQuietly(String countKey, long count) {
        try {
            ops.setString(countKey, String.valueOf(count), props.coinTtl());
        } catch (CacheUnavailableException e) {
            log.warn("投币计数回填失败（不影响本次读结果，下次读重试）: key={}", countKey);
        }
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }
}
