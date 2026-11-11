package io.github.yjhhhaaa06.videoweb.user.service;

import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import io.github.yjhhhaaa06.videoweb.user.model.AvatarRefs;
import io.github.yjhhhaaa06.videoweb.user.model.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 头像 URL 的**进程内**解析缓存（`R-37`）。
 *
 * <h2>它解决什么</h2>
 * 作者快照面（评论 / 动态 / 图文 / 关注列表）以前每次进页都要为"这批 userId"跑一次
 * {@code select id, avatar_ref from users where id in (...)}。本类把
 * {@code userId → avatar_ref} 留在 JVM 里，让同一批作者在 5 分钟内只回源一次。
 *
 * <h2>★ 缓存的是 ref，不是最终 URL</h2>
 * DB 的 {@code users.avatar_ref} 只有两种值（{@code default:N} / {@code /upload/avatar/...}），
 * 最终 URL 由纯函数 {@link AvatarRefs#resolveUrl(long, String)} 现算。这样：
 * <ul>
 *   <li>默认头像前缀将来改名 / 迁移，**不用清缓存**；</li>
 *   <li>缓存条目只装一个短字符串，10k 用户的量级不到 1MB。</li>
 * </ul>
 *
 * <h2>★ 空值也是缓存值</h2>
 * 绝大多数用户从没设过头像（{@code avatar_ref IS NULL}），其 URL 按 {@code userId % 6} 回落。
 * 如果只缓存非空值，这批人每次都会穿透到 DB —— 缓存等于没做。故用空串
 * {@link #KNOWN_NULL} 表示"已确认 NULL"，与"没查过"（key 不存在）区分。
 *
 * <h2>★ 为什么是 JVM 缓存而不是 Redis</h2>
 * 本仓单实例部署；一次 {@code IN} 查询与一次 Redis 往返同量级，Redis 还多一次序列化、
 * 一条"Redis 挂 ⇒ 降级 DB"的故障分支，而它的价值在**跨实例共享**——本仓现在没有。
 * 多实例时本类的代价是"别的实例最多旧 {@link #TTL}"，不是错误；前提是旧文件不立即删
 * （见 `R-37` ⑤，{@code AvatarService} 不再写完就删）。
 *
 * <h2>★ 命中不续期</h2>
 * TTL 从**写入时刻**起算，命中不延长。理由：多实例下别的实例收不到本实例的
 * {@link #invalidate(long)}，若再滑动续期，一个热作者可以让旧值**无限延长**，
 * 就不是"最多旧 5 分钟"了。不续期的代价只是每个热作者每 5 分钟回源一次
 * （一批一次 {@code IN}），在本仓量级可忽略。
 *
 * <h2>★ 回填的写纪元守卫</h2>
 * 经典 cache-aside 竞态：读线程查到旧 ref → 写线程改库并 {@code remove} → 读线程把旧 ref
 * 回填。本类用 {@link #writeEpoch} 兜住：回源前取纪元，回填前再比一次；期间发生过任何
 * 头像写就**放弃本次回填**（本次读仍返回查询时刻的值，下次读重新回源）。
 *
 * <h2>放在 {@code user.service} 而不是 {@code user.cache}</h2>
 * comment / content / follow 三个域都要调它。{@code ArchitectureTests} 规则 2 禁止任何域
 * 触碰**别域的 cache 包**；放在 service 包并只暴露 {@link #resolveBatch} / {@link #resolve}
 * 这类窄契约，才是"跨域只走 Service"的既有口径（先例：{@code ContentService#loadContentVOs}）。
 */
@Slf4j
@Service
public class AvatarUrlResolver implements AvatarReadModel {

    /** 条目有效期。从写入时刻起算，命中不续期（见类注释）。 */
    static final Duration TTL = Duration.ofMinutes(5);

    /** 条目上限。10k 用户约 0.8MB；上限只为防"被扫号"式无界增长。 */
    static final int MAX_ENTRIES = 100_000;

    /** 已确认 {@code avatar_ref IS NULL} 的占位值；与"key 不存在 = 没查过"区分。 */
    private static final String KNOWN_NULL = "";

    private final UserDao userDao;
    private final Clock clock;
    private final int maxEntries;

    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();

    /** 头像写纪元：每次写后失效 +1；回填时用它判断"回源期间有没有人写过"。 */
    private final AtomicLong writeEpoch = new AtomicLong();

    @Autowired
    public AvatarUrlResolver(UserDao userDao) {
        this(userDao, Clock.systemUTC(), MAX_ENTRIES);
    }

    /** 测试用：可注入可控时钟。 */
    AvatarUrlResolver(UserDao userDao, Clock clock) {
        this(userDao, clock, MAX_ENTRIES);
    }

    /** 测试用：可注入更小的容量上限，避免为了测淘汰真的塞 10 万条。 */
    AvatarUrlResolver(UserDao userDao, Clock clock, int maxEntries) {
        this.userDao = userDao;
        this.clock = clock;
        this.maxEntries = maxEntries;
    }

    /**
     * 批量解析生效头像 URL。
     *
     * <p>返回的 Map **对每个合法 id 都有条目**（查无此人的 id 也按 NULL 回落默认），
     * 调用方可以无条件 {@code map.get(userId)}。非法 id（null / ≤0）被忽略，不产生查询。
     *
     * <p>一次调用最多回源一次 {@code IN} 查询；命中与未命中混合时只查未命中的子集。
     */
    @Override
    public Map<Long, String> resolveBatch(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Set<Long> unique = new LinkedHashSet<>(ids);
        long now = clock.millis();
        Map<Long, String> urls = new HashMap<>(unique.size() * 2);
        List<Long> misses = new ArrayList<>();

        for (Long id : unique) {
            if (id == null || id <= 0) {
                continue;
            }
            Entry entry = cache.get(id);
            if (entry != null && now < entry.expiresAtMillis()) {
                urls.put(id, urlOf(id, entry.ref()));
            } else {
                if (entry != null) {
                    cache.remove(id, entry);
                }
                misses.add(id);
            }
        }

        if (!misses.isEmpty()) {
            urls.putAll(loadAndFill(misses));
        }

        // 查无此人的 id 也要给一个可渲染的 URL（与 AvatarRefs 的 NULL 口径一致）。
        for (Long id : unique) {
            if (id != null && id > 0) {
                urls.putIfAbsent(id, AvatarRefs.fallbackUrl(id));
            }
        }
        return urls;
    }

    /** 单条解析（详情 / 关注列表的零散调用）。 */
    @Override
    public String resolve(long userId) {
        return resolveBatch(List.of(userId)).get(userId);
    }

    /**
     * 写后失效：{@code AvatarService} 在上传 / 切默认**写库成功之后**调用。
     *
     * <p>同时推进写纪元：即使有读线程正拿着旧 ref 准备回填，也会因纪元变化而放弃。
     */
    public void invalidate(long userId) {
        writeEpoch.incrementAndGet();
        cache.remove(userId);
    }

    /** 测试用：清空缓存与纪元，避免 Spring 上下文里的条目跨用例泄漏。 */
    void clear() {
        writeEpoch.incrementAndGet();
        cache.clear();
    }

    private Map<Long, String> loadAndFill(List<Long> misses) {
        long epochBefore = writeEpoch.get();
        List<User> rows = userDao.findAvatarRefsByIds(misses);

        Map<Long, String> refs = new HashMap<>(rows.size() * 2);
        for (User row : rows) {
            refs.put(row.getId(), row.getAvatarRef() == null ? KNOWN_NULL : row.getAvatarRef());
        }

        long expiresAt = clock.millis() + TTL.toMillis();
        boolean stable = writeEpoch.get() == epochBefore;
        Map<Long, String> urls = new HashMap<>(misses.size() * 2);
        for (Long id : misses) {
            String ref = refs.getOrDefault(id, KNOWN_NULL);
            if (stable) {
                Entry entry = new Entry(ref, expiresAt);
                put(id, entry);
                // ★ 写入后再比一次：堵住 [stable 判定, put] 之间的窄窗口。
                //   若期间有写，按**值相等**删回（不会误删别的线程刚写入的新值）。
                if (writeEpoch.get() != epochBefore) {
                    cache.remove(id, entry);
                }
            }
            urls.put(id, urlOf(id, ref));
        }
        return urls;
    }

    private void put(long id, Entry entry) {
        if (cache.size() >= maxEntries && !cache.containsKey(id)) {
            evictExpired();
            if (cache.size() >= maxEntries) {
                // 简单粗暴但**有界**：宁可整体清空后重新回源，也不让 Map 无界增长。
                cache.clear();
                log.warn("头像缓存达到上限 {} 条，已整体清空", maxEntries);
            }
        }
        cache.put(id, entry);
    }

    private void evictExpired() {
        long now = clock.millis();
        cache.entrySet().removeIf(e -> now >= e.getValue().expiresAtMillis());
    }

    private static String urlOf(long userId, String ref) {
        return AvatarRefs.resolveUrl(userId, ref);
    }

    private record Entry(String ref, long expiresAtMillis) {
    }
}
