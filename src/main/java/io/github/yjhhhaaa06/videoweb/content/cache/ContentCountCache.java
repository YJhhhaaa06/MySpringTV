package io.github.yjhhhaaa06.videoweb.content.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheMissSignal;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.cache.RedisCircuitBreaker;
import io.github.yjhhhaaa06.videoweb.common.cache.RedisOps;
import io.github.yjhhhaaa06.videoweb.common.cache.SingleFlight;
import io.github.yjhhhaaa06.videoweb.common.config.ContentCacheProperties;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 内容的**评论总数**缓存（T43）—— 独立 key {@code content:commentCount:{id}}。
 *
 * <h2>为什么它不在 {@link ContentCache} 里</h2>
 * {@link ContentCache} 管的是 {@code content:{id}}（整份 JSON + 索引），失效语义是"慢变字段变了"。
 * 评论数是**高频变化**的计数，生命周期与内容载荷相反（内容改一次、评论数改几千次）——
 * 混在一个类里会让"谁失效谁"重新变成一句注释。故单列一个小类，只做一件事。
 *
 * <h2>为什么归 **content** 域（而不是 comment 域）</h2>
 * 值就是本域 {@code content.comment_count} 这一列，且 {@code ArchitectureTests} 规则 4 禁止
 * {@code content} 依赖 {@code comment.service/model/cache} ⇒ 评论域**不可能**把这个数递给我们。
 * 反过来，评论域改列（同事务）、内容域订 {@code CommentCacheChangedEvent} 失效这个 key，
 * 与既有"各方订阅事件、失效自己的缓存"（S6-B2d）同形。
 *
 * <h2>三态读（无空标记）</h2>
 * <pre>
 * hit        → 返回值并续期
 * miss       → 回源 {@code SELECT comment_count FROM content WHERE id=?}（单列、主键）
 *              → 有行回填 / 无行**不写**（内容不存在是 404 路径，不能把 0 钉进 TTL）
 * Redis 失败 → 降级直查 DB（不写回）
 * DB 失败    → **上抛**（"读不到"不能伪装成"没有"，同 {@code LikeCache} 的口径）
 * </pre>
 * ★ **不自造空标记**：{@code 0} 是合法值，必须与"key 不存在"靠 {@code GET} 返回
 * {@code null} 区分（同 {@link CacheKeys#contentLikeCount(long)}）。
 *
 * <p>用 {@link SingleFlight} 防击穿：一个热内容的详情页在 key 过期瞬间会并发 miss，
 * 单飞把回源收敛成一次。
 */
@Slf4j
@Component
public class ContentCountCache extends RedisOps {

    private final ContentDao contentDao;
    private final ContentCacheProperties props;
    private final SingleFlight singleFlight;

    public ContentCountCache(StringRedisTemplate redis,
                             RedisCircuitBreaker breaker,
                             ContentDao contentDao,
                             ContentCacheProperties props,
                             SingleFlight singleFlight) {
        super(redis, breaker);
        this.contentDao = contentDao;
        this.props = props;
        this.singleFlight = singleFlight;
    }

    /**
     * 读内容评论总数（公共计数，与登录态无关）。
     *
     * @return 评论总数；内容行不存在 ⇒ {@code 0}（详情路径在此之前已 404）
     */
    public long getCommentCount(long contentId) {
        String key = CacheKeys.contentCommentCount(contentId);
        try {
            String cached = getString(key);
            if (cached != null) {
                expire(key, props.contentTtl());
                try {
                    return Long.parseLong(cached);
                } catch (NumberFormatException e) {
                    // 脏值按 miss 处理并清掉，避免每次读都解析失败（同 LikeCache / CoinCache 的计数读）
                    log.warn("评论数缓存值非法，按 miss 处理并清除: key={}", key);
                    invalidateQuietly(key);
                }
            }
        } catch (CacheUnavailableException e) {
            log.warn("评论数缓存读失败，降级 DB: key={}", key, e);
            CacheMissSignal.mark();                        // T26 信号②：降级 = 没走成缓存
            return nullToZero(contentDao.findCommentCount(contentId));
        }
        CacheMissSignal.mark();                            // T26 信号②：miss 回源
        return singleFlight.get(key, () -> {
            Integer dbCount = contentDao.findCommentCount(contentId);
            if (dbCount != null) {
                backfillQuietly(key, dbCount.longValue());
            }
            return dbCount == null ? 0L : dbCount.longValue();
        });
    }

    /**
     * 失效评论总数（评论增删 / 内容删除后调用）。
     *
     * <p>★ 为什么是 **DEL** 而不是增量：评论事件只说明"哪条内容变了"，不带可靠 delta
     * （删主楼会级联软删它的回复，条数由 DB 决定）⇒ 增量会漂移，DEL + 读自愈才是幂等的。
     * 一次评论只多一次"单列主键查询"，相比"整份内容 JSON 失效 + 媒体装配"便宜一个量级。
     */
    public void invalidateCommentCount(long contentId) {
        invalidateQuietly(CacheKeys.contentCommentCount(contentId));
    }

    private void invalidateQuietly(String key) {
        try {
            delete(key);
        } catch (CacheUnavailableException e) {
            // 失效失败只记日志：调用方的事务已经提交，缓存故障不能反过来让业务失败（TTL 兜底）
            log.warn("评论数缓存失效失败（Redis 不可用），交由 TTL 自愈: key={}", key, e);
        }
    }

    private void backfillQuietly(String key, long count) {
        try {
            setString(key, String.valueOf(count), props.contentTtl());
        } catch (CacheUnavailableException e) {
            log.warn("评论数回填失败（不影响本次读结果，下次读重试）: key={}", key, e);
        }
    }

    private static long nullToZero(Integer value) {
        return value == null ? 0L : value.longValue();
    }
}
