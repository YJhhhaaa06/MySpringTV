package io.github.yjhhhaaa06.videoweb.feed.service;

import io.github.yjhhhaaa06.videoweb.feed.cache.FeedCache;
import io.github.yjhhhaaa06.videoweb.follow.dao.FollowDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 收件箱重建（承接 TV {@code feed.service.FeedRebuildService}，342 行）：关注 / 取关后重算该用户
 * 收件箱的**有界窗口**。
 *
 * <h2>职责与产物</h2>
 * 产物 = DB 真相表 {@code feed_inbox} 的整窗替换 + 同步状态 {@code feed_inbox_sync} 落库；
 * Redis（{@code feed:inbox:{id}}）**只失效、不写**（单写 DB 真相 + 写后失效，与 fanout 同范式）。
 * 窗口口径 = **每关注作者最近 K 条** → 归并去重 → contentId 降序 → **裁剪到 C**。
 *
 * <h2>步骤（① 取锁 → ② 读关注集 → ③ 排除大V → ④ 单事务替换窗口 + 写同步状态 → ⑤ 提交后失效缓存 → ⑥ 释放锁）</h2>
 * ④ 在 {@link FeedWindowWriter}（独立 bean，事务边界）；本类是**编排**（自身无事务）。
 * 顺序红线的论证见 {@link FeedWindowWriter} 类注释。
 *
 * <h2>并发去重</h2>
 * 锁只是**去重优化**，不承担正确性：TTL（60s）到点后若真有并发重建交叉执行，两次都是同一份
 * {@code content} 真相的窗口快照（{@code INSERT IGNORE} 幂等）⇒ **并集、只多不丢**。
 *
 * <h2>失败面（口径不变：**任何失败都不抛出**）</h2>
 * Redis / DB 失败一律**降级吞掉并 ACK**（**不抛给消费容器转死信**；消费容器具备本地有限重试能力，
 * 但**拍板重试不接管重建链**——本链失败已有读态闸门「未同步 ⇒ 回退纯拉」与下次关注 / 取关重建兜底；
 * 只有**载荷非法**才在消费者侧抛出、重试耗尽后转死信）。
 *
 * <h2>依赖方向</h2>
 * feed 域 → follow.dao（读关注集）/ content.dao（读内容，经 {@link FeedWindowWriter}）——
 * 均为**单向 DAO 薄依赖**，不新增包环。关注集走 DB 真值（{@code getAllFollowedUserIds}），
 * **不读 {@code FollowCache}**——与"核对 oracle 同源"，且窗口成本 ∝ 关注数 × K，
 * 缓存窗口读会引入与当前快照不同的时序。
 */
@Slf4j
@Service
public class FeedRebuildService {

    private final FollowDao followDao;
    private final FeedBigVRouter bigVRouter;
    private final FeedWindowWriter windowWriter;
    private final FeedCache feedCache;

    public FeedRebuildService(FollowDao followDao, FeedBigVRouter bigVRouter,
                              FeedWindowWriter windowWriter, FeedCache feedCache) {
        this.followDao = followDao;
        this.bigVRouter = bigVRouter;
        this.windowWriter = windowWriter;
        this.feedCache = feedCache;
    }

    /**
     * 重建 {@code userId} 的收件箱窗口（= 每关注作者最近 K → 归并裁剪 C，落 DB 真相 + 同步状态，
     * 并失效其 Redis 读缓存）。**任何失败都不抛出。**
     *
     * @param userId 收件箱归属者（关注 / 取关的发起方）
     */
    public void rebuildInbox(long userId) {
        String token = UUID.randomUUID().toString();
        if (!feedCache.tryAcquireRebuildLock(userId, token)) {
            // 已有并发重建在跑（或 Redis 不可用）：本次跳过。锁是去重优化，跳过不影响正确性。
            log.debug("收件箱重建跳过（已有并发重建或 Redis 不可用）, userId={}", userId);
            return;
        }
        try {
            List<Long> authors = excludeBigV(loadFollowedUserIds(userId));   // ② + ③
            windowWriter.replaceWindow(userId, authors);                     // ④ 单事务
            feedCache.invalidateInboxes(List.of(userId));                    // ⑤ 提交后失效（失败在 FeedCache 内已记）
        } catch (DataAccessException e) {
            // DB 阶段失败：源头（连接池 / 框架）已记 → 此处只记结论行、不带栈；降级 ACK
            log.warn("收件箱重建中止（DB 窗口重算失败，降级）, userId={}", userId);
        } catch (RuntimeException e) {
            // 契约"不抛"的最后兜底（需人介入 → error + 栈）：不得让异常穿透消费容器去转死信
            log.error("收件箱重建异常（已兜底，降级）, userId={}", userId, e);
        } finally {
            feedCache.releaseRebuildLock(userId, token);
        }
    }

    // ==================== 步骤 ② ====================

    /**
     * ② 读关注集（DB 口径，**不读缓存**——见类注释"依赖方向"）。
     *
     * <p>事务边界：⚠️ **去事务**（单条 SELECT；旧事务只承载"取连接"）。
     */
    private List<Long> loadFollowedUserIds(long userId) {
        return followDao.findAllFollowedUserIds(userId);
    }

    /**
     * ③ 排除大V作者（判定单点 {@link FeedBigVRouter#isBigVBatch}，与 fanout / 读同源）。
     *
     * <p>为什么排除：fanout 对大V作者**跳过写扩散**（其内容由"大V发件箱"读时拉），重建若收录
     * ⇒ 同一内容在"收件箱窗口 ∪ 大V发件箱"两路都出（仅靠读侧按 contentId 去重兜底）
     * ⇒ 排除即"不与发件箱重复"。
     *
     * <p>失败取向：{@code isBigVBatch} 内部对 SQL 失败已 **fail-open 只保留名单项** ⇒ 补集 = 全部非名单作者，
     * 按普通作者收录（"宁可多收录"，与 fanout"宁可多写"同向，残影由读侧去重兜底）。
     */
    private List<Long> excludeBigV(List<Long> followedIds) {
        if (followedIds.isEmpty()) {
            return new ArrayList<>();                        // 空关注集：不进批量判定
        }
        Set<Long> bigVs = bigVRouter.isBigVBatch(followedIds);
        if (bigVs.isEmpty()) {
            return new ArrayList<>(followedIds);             // 无大V：原序直接返回
        }
        List<Long> authors = new ArrayList<>(followedIds.size());
        for (Long authorId : followedIds) {
            if (!bigVs.contains(authorId)) {
                authors.add(authorId);                       // 补集，保持原序
            }
        }
        return authors;
    }
}
