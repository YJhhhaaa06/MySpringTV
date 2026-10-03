package io.github.yjhhhaaa06.videoweb.feed.service;

import io.github.yjhhhaaa06.videoweb.feed.cache.FeedCache;
import io.github.yjhhhaaa06.videoweb.feed.dao.FeedInboxDao;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 收件箱读腿（承接 TV {@code feed.service.FeedInboxReader}）：**读态闸门** + 「DB 真相 → 可降级读缓存」
 * 的窗口读取。
 *
 * <h2>① 读态闸门（{@link #isSynced(long)}）</h2>
 * {@code feed_inbox_sync} 存在即已同步。窗口里那批行只有**重建**（关注 / 取关触发）才会按窗口口径写全
 * ——fanout 只追增新行、从不写同步状态 ⇒ 没有同步行 = 该用户**从没成功重建过**（老用户 / 重建失败 /
 * 消息丢失），其窗口要么空、要么只有零散增量，**不可作为读源**（调用方据此回退既有纯拉）。
 * 成本 = 一条 {@code uk_user} 唯一键点查（每请求一次，不做缓存）。
 *
 * <h2>② 收件箱腿（{@link #readInbox(long)}）</h2>
 * 读 {@code feed:inbox:{userId}} 这个**可降级读缓存**（miss → 回源 {@code feed_inbox} 并回填；
 * Redis 异常 → DB 直查、不写回），返回 **contentId 降序**窗口。缓存语义在 {@link FeedCache}。
 *
 * <h2>失败面</h2>
 * DB 失败一律**上抛**（{@code DataAccessException} ⇒ 500），与切换前的纯拉语义一致
 * （读路径 DB 失败不留"半条时间线"）；Redis 失败由 {@link FeedCache} 内部降级吸收、不上抛。
 *
 * <h2>事务边界（《事务边界决策表》§四·S9）</h2>
 * 两处读（{@code isSynced} / 收件箱 DB 装载）均 ⚠️ **去事务**——单条 SELECT，旧事务只承载"取连接"。
 */
@Service
public class FeedInboxReader {

    private final FeedInboxDao feedInboxDao;
    private final FeedCache feedCache;

    public FeedInboxReader(FeedInboxDao feedInboxDao, FeedCache feedCache) {
        this.feedInboxDao = feedInboxDao;
        this.feedCache = feedCache;
    }

    /** 读态闸门：该用户的收件箱窗口是否已同步（= 曾成功重建过）。 */
    public boolean isSynced(long userId) {
        return feedInboxDao.existsSync(userId);
    }

    /**
     * 读收件箱窗口（**contentId 降序**）：缓存三态读 + miss 回源回填 + Redis 降级走 DB。
     *
     * <p>空窗口返回空列表（含"已同步但确实没内容"——空标记 absorbing 该情形，不再打 DB）。
     */
    public List<Long> readInbox(long userId) {
        return feedCache.readInbox(userId, () -> feedInboxDao.findInboxContentIds(userId));
    }
}
