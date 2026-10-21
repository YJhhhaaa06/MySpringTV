package io.github.yjhhhaaa06.videoweb.content.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheMissSignal;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.cache.RedisCircuitBreaker;
import io.github.yjhhhaaa06.videoweb.common.cache.RedisOps;
import io.github.yjhhhaaa06.videoweb.common.cache.SingleFlight;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.model.ContentCountColumns;
import io.github.yjhhhaaa06.videoweb.interaction.service.InteractionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 详情**计数聚合读**（T44-2）——五个公共计数（赞 / 评 / 藏 / 币 / 播放）的合并读通道，详情路径专用。
 *
 * <h2>形态（为什么是"2 趟 Redis + 单飞合一 + 全量查库 + 有选择回填"）</h2>
 * <ol>
 *   <li><b>一趟 pipeline GET 五键</b>（载荷键 {@code content:{id}} 仍走 {@link ContentCache} 的
 *       三态读，与本类正交——详情 Redis 读共 2 趟）；</li>
 *   <li><b>miss 非空 ⇒ 进程内单飞</b>（key = {@code detail:counts:{id}}，同内容并发 miss
 *       只一次回源）：loader = {@link ContentDao#findCountColumns}——五列同在 content 行上，
     * <b>一趟主键点查取齐</b>（冷内容全 miss 的 DB 成本从 6 条 SQL 收敛为 1 条）；</li>
 *   <li><b>有选择回填</b>：只对**自己 miss 的键**回填，命中键一个都不碰——否则会用本次快照
 *       覆盖未失效的键，重开"旧值复活"窗口（写事务 DEL 后、快照回填前的时间差）。</li>
 * </ol>
 * 口径与代价的活文档 = {@code 缓存与数据一致性.md §五.2}。
 *
 * <h2>回填的通道纪律（域边界）</h2>
 * 五个计数键分属三域（like / favorite / coin）+ 本域（comment / play）：本类**不直接写他域键**——
 * 外域回填经 {@link InteractionService} 的转发方法（{@code backfillLikeCount} 等）落到各域
 * 自己的 cache（各自 TTL、各自的 best-effort 语义）；comment / play 键是本域自己的，直调
 * {@link ContentCountCache#backfillCommentCount} / {@link ContentCountCache#backfillPlayCount}。
 * 键名源头是 {@code CacheKeys}（全仓唯一源），
 * 本类只"读"他域键、不"写"——写侧永远经由域契约（{@code 模块边界与依赖.md} §六 登记例外）。
 *
 * <h2>已知代价（有意接受，逐条可讲清）</h2>
 * <ul>
 *   <li><b>聚合路径不做命中续期</b>：五键各域 TTL 不同（likeTtl/favoriteTtl/coinTtl/contentTtl），
 *       一趟 pipeline 无法带上多种 EXPIRE 语义；放弃滑动续期 ⇒ 热键每 TTL 周期重回源一次
 *       （单飞合一后成本 = 1 条点查）。载荷键的续期不受影响（走 {@link ContentCache} 原样）。</li>
 *   <li><b>单飞不跨载荷/计数合并</b>：载荷 miss 的装载（含媒体装配）仍走 {@link ContentCache}
 *       自己的单飞；同请求"载荷 + 计数全 miss"时 DB 共 2 条（1 列查 + 1 载荷两件套），而非理论最优
 *       的 1 条——省下的那点差别不值得把 CacheAside 三态语义复刻进本类（漂移风险 &gt; 常数 SQL）。</li>
 *   <li><b>并发击穿下重复 SET</b>：follower 在拿到列快照后也会回填自己的 miss 键（与 leader
 *       幂等同值）——比"leader 回填、follower 免"多几次便宜的 SET，换取代码对称、无闭包纠缠。</li>
 * </ul>
 *
 * <h2>失败处置（与 {@code CacheAside} 同口径）</h2>
 * <ul>
 *   <li>Redis 失败（{@link CacheUnavailableException}）→ 整趟降级直查 DB（经单飞、不回填）；
 *       计 {@code CacheMissSignal}（限流信号②）；</li>
 *   <li>DB 失败（{@code DataAccessException}）→ 原样上抛（→ 500），"读不到"不伪装成"没有"；</li>
 *   <li>脏值（非数字）→ 该键按 miss 处理并记 WARN；回填（SET 覆盖）即自愈，无需先 DEL。</li>
 * </ul>
 */
@Slf4j
@Component
public class ContentCountAggregate extends RedisOps {

    /** 进程内单飞 key 前缀（非 Redis 键；同 {@code ContentCache.INDEX_REBUILD_KEY} 的字面量形态）。 */
    private static final String FLIGHT_KEY_PREFIX = "detail:counts:";

    /** 五键序（与 {@link #readCounts} 的解析、合并一一对应）。 */
    private static final int LIKE = 0;
    private static final int COMMENT = 1;
    private static final int FAVORITE = 2;
    private static final int COIN = 3;
    private static final int PLAY = 4;

    private final ContentDao contentDao;
    private final ContentCountCache contentCountCache;
    private final InteractionService interactionService;
    private final SingleFlight singleFlight;

    public ContentCountAggregate(StringRedisTemplate redis,
                                 RedisCircuitBreaker breaker,
                                 ContentDao contentDao,
                                 ContentCountCache contentCountCache,
                                 InteractionService interactionService,
                                 SingleFlight singleFlight) {
        super(redis, breaker);
        this.contentDao = contentDao;
        this.contentCountCache = contentCountCache;
        this.interactionService = interactionService;
        this.singleFlight = singleFlight;
    }

    /**
     * 读详情五计数：命中直用，miss 单飞回源一趟 {@code findCountColumns} 并**有选择**回填。
     *
     * @return 五计数值；列快照为 null（防御分支，正常时序不可达）⇒ miss 位按 0 作答、
     *         命中位保留缓存值
     */
    public ContentCountColumns readCounts(long contentId) {
        String likeKey = CacheKeys.contentLikeCount(contentId);
        String commentKey = CacheKeys.contentCommentCount(contentId);
        String favoriteKey = CacheKeys.contentFavoriteCount(contentId);
        String coinKey = CacheKeys.contentCoinCount(contentId);
        String playKey = CacheKeys.contentPlayCount(contentId);
        List<String> keys = List.of(likeKey, commentKey, favoriteKey, coinKey, playKey);

        long[] values = new long[5];
        boolean[] missed = new boolean[5];
        try {
            List<Object> raw = pipeline(o -> {
                for (String key : keys) {
                    o.opsForValue().get(key);
                }
            });
            for (int i = 0; i < keys.size(); i++) {
                String cached = raw.size() > i ? (String) raw.get(i) : null;
                if (cached == null) {
                    missed[i] = true;
                    continue;
                }
                try {
                    values[i] = Long.parseLong(cached);
                } catch (NumberFormatException e) {
                    // 脏值按 miss 处理；不先 DEL——回填是覆盖写，天然清掉脏值
                    log.warn("计数缓存值非法，按 miss 处理（回填将覆盖）: key={}", keys.get(i));
                    missed[i] = true;
                }
            }
        } catch (CacheUnavailableException e) {
            log.warn("计数聚合读缓存失败，降级直查 DB（不回填）: contentId={}", contentId, e);
            CacheMissSignal.mark();                        // T26 信号②：整趟降级 = 没走成缓存
            return singleFlight.get(flightKey(contentId), () -> {
                ContentCountColumns cols = contentDao.findCountColumns(contentId);
                return cols == null ? ZERO : cols;
            });
        }

        boolean anyMissed = missed[LIKE] || missed[COMMENT] || missed[FAVORITE] || missed[COIN] || missed[PLAY];
        if (!anyMissed) {
            return new ContentCountColumns(values[LIKE], values[COMMENT], values[FAVORITE],
                    values[COIN], values[PLAY]);
        }
        CacheMissSignal.mark();                            // T26 信号②：miss 回源（含脏值）
        ContentCountColumns cols = singleFlight.get(flightKey(contentId),
                () -> contentDao.findCountColumns(contentId));
        // 列快照为 null（行不存在，防御分支——正常时序 404 已短路）⇒ miss 键按 0 作答、不回填
        if (cols == null) {
            log.warn("计数聚合回源无行（防御分支，正常时序不可达）: contentId={}", contentId);
            return merged(values, missed, ZERO);
        }
        // 有选择回填：只回填自己 miss 的键（comment / play 直调本域；外域经互动域转发到各域 cache）
        if (missed[COMMENT]) {
            contentCountCache.backfillCommentCount(contentId, cols.commentCount());
        }
        if (missed[PLAY]) {
            contentCountCache.backfillPlayCount(contentId, cols.playCount());
        }
        if (missed[LIKE]) {
            interactionService.backfillLikeCount(contentId, cols.likeCount());
        }
        if (missed[FAVORITE]) {
            interactionService.backfillFavoriteCount(contentId, cols.favoriteCount());
        }
        if (missed[COIN]) {
            interactionService.backfillCoinCount(contentId, cols.coinCount());
        }
        return merged(values, missed, cols);
    }

    /** 命中值与列快照按 miss 集合并（miss 位取快照值，命中位保留缓存值；列理论上可空 ⇒ nvl 防御）。 */
    private static ContentCountColumns merged(long[] cached, boolean[] missed, ContentCountColumns cols) {
        return new ContentCountColumns(
                missed[LIKE] ? nvl(cols.likeCount()) : cached[LIKE],
                missed[COMMENT] ? nvl(cols.commentCount()) : cached[COMMENT],
                missed[FAVORITE] ? nvl(cols.favoriteCount()) : cached[FAVORITE],
                missed[COIN] ? nvl(cols.coinCount()) : cached[COIN],
                missed[PLAY] ? nvl(cols.playCount()) : cached[PLAY]);
    }

    /** 列理论上可空（DEFAULT 0 的 unsigned 列）⇒ 读侧统一 null → 0。 */
    private static long nvl(Long value) {
        return value == null ? 0L : value;
    }

    private static String flightKey(long contentId) {
        return FLIGHT_KEY_PREFIX + contentId;
    }

    /** 行不存在（防御分支）时的全 0 列。 */
    private static final ContentCountColumns ZERO = new ContentCountColumns(0L, 0L, 0L, 0L, 0L);
}
