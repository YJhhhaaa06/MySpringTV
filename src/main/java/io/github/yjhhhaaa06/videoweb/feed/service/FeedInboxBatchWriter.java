package io.github.yjhhhaaa06.videoweb.feed.service;

import io.github.yjhhhaaa06.videoweb.feed.dao.FeedInboxDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收件箱**单批落库**的事务边界载体（S9 抽出的独立 bean）。
 *
 * <h2>为什么必须抽成独立 bean</h2>
 * TV 的 {@code FeedInboxWriter} 里，每一次批量落库都是**独立的一次 {@code transactionTemplate.execute}**
 * ——"逐批各自事务"是 TV 的刻意口径（不是把整个 fanout 包成一个大事务）：
 * 理由是大 fanout 的逐批提交避免长事务持锁、且消费重试可幂等重放（{@code INSERT IGNORE}）。
 *
 * <p>在 Spring 里，"每批各自事务"若写成"{@code FeedInboxWriter} 的私有方法 + {@code @Transactional} 后
 * 由 {@code fanout} 循环调用"会**静默失效**（SOP 坑 12：同类自调用绕过代理）。故抽成本 bean，
 * 由它承载 {@code @Transactional}，{@code FeedInboxWriter} 跨 bean 调用——这样每批落库都在
 * **自己的一次事务**里（与 TV 逐批借还连接等价）。
 *
 * <h2>事务边界（《事务边界决策表》§四·S9）</h2>
 * {@link #insertBatch} / {@link #insertBackfillBatch} 两处均 ✅ **保持单事务**。
 * 失败让它 {@code DataAccessException} 原样上抛（由调用方补结论行后抛出，交消费容器重试）。
 */
@Service
public class FeedInboxBatchWriter {

    private final FeedInboxDao feedInboxDao;

    public FeedInboxBatchWriter(FeedInboxDao feedInboxDao) {
        this.feedInboxDao = feedInboxDao;
    }

    /**
     * 单批 fanout 落库：一条多行 {@code INSERT IGNORE}（{@code contentId} × 一批粉丝）。
     *
     * <p>{@code @Transactional} 包"这一条多行 INSERT"——单语句本身即原子，但显式事务边界
     * 与 TV 的"逐批借还连接"口径一致，且为将来可能的"一批多语句"留出正确的边界。
     *
     * @param fanIds 本批粉丝（**非空**——调用方在 {@code fanIds.isEmpty()} 时已早退）
     */
    @Transactional
    public void insertBatch(long contentId, List<Long> fanIds) {
        feedInboxDao.insertIgnoreBatch(contentId, fanIds);
    }

    /**
     * 单批补推落库：**同一事务内逐内容** {@code INSERT IGNORE}（K 条语句 / 批）。
     *
     * <p>按内容而非"粉丝 × 内容"对拼条，避免放大单条语句规模（TV 原注释）。任一条失败 ⇒
     * **整批回滚**（同"一批同生共死"口径）。
     */
    @Transactional
    public void insertBackfillBatch(long authorId, List<Long> contentIds, List<Long> fanIds) {
        for (Long contentId : contentIds) {
            feedInboxDao.insertIgnoreBatch(contentId, fanIds);
        }
    }
}
