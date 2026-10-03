package io.github.yjhhhaaa06.videoweb.feed.service;

import io.github.yjhhhaaa06.videoweb.common.config.FeedProperties;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.model.AuthorContentId;
import io.github.yjhhhaaa06.videoweb.feed.cache.FeedCache;
import io.github.yjhhhaaa06.videoweb.follow.dao.FollowDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 写扩散落库（承接 TV {@code feed.service.FeedInboxWriter}，423 行）：把一条新内容写进作者每个
 * 粉丝的收件箱 **DB 真相表** {@code feed_inbox}，并对这些粉丝的收件箱 **Redis 读缓存**做**写后失效**。
 *
 * <h2>一致性口径</h2>
 * = **单写 DB 真相 + 写后失效 + 读 miss 回源回填**。fanout **不写 Redis**，只做失效——
 * 故本批粉丝的 {@code feed:inbox:{fanId}} 两件套（数据 key + {@code empty:}）一并 DEL。
 *
 * <h2>每批三步（顺序不可交换：先 DB 真相、后缓存失效）</h2>
 * ① 游标读一批粉丝（DB，keyset 迭代）→ ② DB 批量 {@code INSERT IGNORE}（幂等）
 * → ③ 一次 DEL 多键（两件套）。
 *
 * <h2>游标并发口径（TV 原样登记）</h2>
 * 游标严格递增（{@code user_id > cursor}）⇒ 同一遍历**不重**、不受行位移影响；遍历期间**新增关注**
 * 若其 {@code user_id} ≤ 当前游标则本轮可能漏——由其关注动作触发的收件箱重建兜底；
 * 遍历期间**取关者**可能仍被写入——由下次重建清理（"只多不丢"不变量不破）。
 *
 * <h2>大V路由（单点）</h2>
 * 判定走 {@link FeedBigVRouter}（fanout / 重建 / 读三处同源）——命中即**跳过本次写扩散**
 * （大V内容由"大V发件箱"读时拉），只记 debug（常态路由，无需人介入）。
 *
 * <h2>失败面（★ 与 TV 一致的"可重试失败抛出"）</h2>
 * 粉丝游标读失败 / DB 落库失败 / 意外异常**抛出**——由消费容器本地有限重试（幂等重放：
 * 重试从方法入口整体重跑）；重试耗尽转死信留证。缓存失效 DEL 失败**不停写**
 * （DB 真相优先、读自愈兜底）：首次记 WARNING 后停用后续批次的失效尝试（一次 Redis 故障只留一条记录）。
 *
 * <h2>为什么本类**没有** {@code @Transactional}</h2>
 * 事务边界在 {@link FeedInboxBatchWriter}（每批各自事务）。本类是**编排 + 游标迭代**，
 * 与 TV 的 {@code doFanout}（自身无事务、只调 `transactionTemplate.execute`）同构。
 */
@Slf4j
@Service
public class FeedInboxWriter {

    private final FollowDao followDao;
    private final ContentDao contentDao;
    private final FeedBigVRouter bigVRouter;
    private final FeedCache feedCache;
    private final FeedInboxBatchWriter batchWriter;
    private final FeedProperties props;

    public FeedInboxWriter(FollowDao followDao, ContentDao contentDao, FeedBigVRouter bigVRouter,
                           FeedCache feedCache, FeedInboxBatchWriter batchWriter, FeedProperties props) {
        this.followDao = followDao;
        this.contentDao = contentDao;
        this.bigVRouter = bigVRouter;
        this.feedCache = feedCache;
        this.batchWriter = batchWriter;
        this.props = props;
    }

    /**
     * 写扩散一条内容：向作者的所有粉丝收件箱落库 {@code contentId}，并失效其收件箱缓存。
     *
     * <p>幂等（{@code INSERT IGNORE}；同一内容重复投递 / 重试重放无副作用）。
     *
     * @param contentId 新内容 id
     * @param authorId  作者 id（大V判定 + 粉丝列表来源）
     */
    public void fanout(long contentId, long authorId) {
        // ① 大V发件箱写后失效：**无条件、且必须先于大V早退**——下面那个分支命中即 return，
        //    若把本步骤挪到收件箱失效段里，大V发布将永不动它（读侧一直读到旧发件箱）。
        //    顺带把"Redis 是否可用"传给收件箱失效段，避免同一故障刷两条带栈记录。
        boolean delAbandoned = !feedCache.invalidateOutbox(authorId);
        if (bigVRouter.isBigV(authorId)) {
            log.debug("写扩散跳过大V, contentId={}, authorId={}", contentId, authorId);
            return;
        }
        final int fanoutBatch = props.fanoutBatch();
        long cursor = 0L;     // keyset 游标：users.id 为正 ⇒ user_id > 0 覆盖全体粉丝
        while (true) {
            List<Long> fanIds;
            try {
                fanIds = followDao.getFollowerUserIdsAfter(authorId, cursor, fanoutBatch);
            } catch (RuntimeException e) {
                log.warn("写扩散失败（粉丝游标读取失败，交消费重试）, contentId={}, authorId={}, cursor={}",
                        contentId, authorId, cursor);
                throw e;
            }
            if (fanIds.isEmpty()) {
                return;
            }
            try {
                batchWriter.insertBatch(contentId, fanIds);
            } catch (RuntimeException e) {
                log.warn("写扩散失败（收件箱落库失败，交消费重试）, contentId={}, fanCount={}",
                        contentId, fanIds.size());
                throw e;
            }
            if (!delAbandoned) {
                // 首次 DEL 失败即视为"缓存不可用"，后续批次不再尝试（DB 真相优先，落库一路继续到底）
                delAbandoned = !feedCache.invalidateInboxes(fanIds);
            }
            if (fanIds.size() < fanoutBatch) {
                return;   // 不足一批 = DB 已到底（不依赖 total，防计数漂移）
            }
            cursor = fanIds.get(fanIds.size() - 1);
        }
    }

    // ==================== 降级补推（大V → 普通） ====================

    /**
     * 降级补推：把作者（由大V降为普通）的**最近 K 条**内容补写进其**现任粉丝**收件箱。
     *
     * <p><b>形态 = "复用 fanout"</b>：同一条粉丝游标迭代 + {@code INSERT IGNORE} + 写后失效两件套；
     * K = {@code video.feed.inbox-window-per-author}（与重建 / 发件箱窗口 N 同量级 ⇒ 降级前后可见性范围一致）。
     *
     * <p><b>零删除</b>：只追增，不触碰"fanout 只追增 ⇒ 只多不丢"不变量；{@code INSERT IGNORE} 幂等。
     *
     * <p><b>消费时复查大V</b>：若消费时刻作者**已重新升级**（降级后又被关注回线）⇒ 跳过——
     * 可见性由"大V发件箱腿"保证，不产生无谓的上行残影行。
     *
     * @param authorId 降级的作者（现任粉丝 = 消费时刻 {@code follow} 表中其关注者集合）
     */
    public void backfillAuthor(long authorId) {
        if (bigVRouter.isBigV(authorId)) {
            log.debug("降级补推跳过（消费时作者已是大V，由发件箱腿保证可见）, authorId={}", authorId);
            return;
        }
        List<Long> contentIds;
        try {
            contentIds = loadRecentContentIds(authorId);
        } catch (RuntimeException e) {
            log.warn("降级补推失败（存量内容读取失败，交消费重试）, authorId={}", authorId);
            throw e;
        }
        if (contentIds.isEmpty()) {
            return;   // 无存量内容 ⇒ 无行可补（不读粉丝、不发 SQL、不失效缓存）
        }
        final int fanoutBatch = props.fanoutBatch();
        long cursor = 0L;
        boolean delAbandoned = false;
        while (true) {
            List<Long> fanIds;
            try {
                fanIds = followDao.getFollowerUserIdsAfter(authorId, cursor, fanoutBatch);
            } catch (RuntimeException e) {
                log.warn("降级补推失败（粉丝游标读取失败，交消费重试）, authorId={}, cursor={}", authorId, cursor);
                throw e;
            }
            if (fanIds.isEmpty()) {
                return;
            }
            try {
                batchWriter.insertBackfillBatch(authorId, contentIds, fanIds);
            } catch (RuntimeException e) {
                log.warn("降级补推失败（收件箱落库失败，交消费重试）, authorId={}, fanCount={}",
                        authorId, fanIds.size());
                throw e;
            }
            if (!delAbandoned) {
                delAbandoned = !feedCache.invalidateInboxes(fanIds);
            }
            if (fanIds.size() < fanoutBatch) {
                return;
            }
            cursor = fanIds.get(fanIds.size() - 1);
        }
    }

    /**
     * 取作者最近 K 条内容 id（{@code is_deleted = 0}，降序；K = 重建 / 收件箱窗口口径）。
     *
     * <p>单作者 ⇒ 无需按作者归组；DAO 每分支已 {@code ORDER BY id DESC LIMIT K}，此处再显式降序一次
     * （防御 {@code UNION ALL} 外层顺序不保证——虽然单分支不受影响）。
     */
    private List<Long> loadRecentContentIds(long authorId) {
        List<AuthorContentId> rows = contentDao.findRecentContentIdsByAuthor(
                List.of(authorId), props.inboxWindowPerAuthor());
        return rows.stream()
                .map(AuthorContentId::contentId)
                .sorted(Comparator.reverseOrder())
                .toList();
    }
}
