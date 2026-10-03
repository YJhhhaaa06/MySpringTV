package io.github.yjhhhaaa06.videoweb.feed.service;

import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 纯拉（降级）路径的 DB 查询载体（S9 抽出的独立 bean）。
 *
 * <h2>为什么必须抽成独立 bean</h2>
 * {@code FeedService.getFeed}（无事务）在窗口不可信时回退纯拉，纯拉里"命中总数 + 当页 id"两条查询
 * **必须在同一个只读事务里**（{@code total} 与页内容**同源快照**——否则会出现"页里有第 11 条但 total=10"
 * 这类自相矛盾的信封）。若把它写成 {@code FeedService} 的私有方法 + {@code @Transactional}，
 * 自调用会让事务静默失效（SOP 坑 12）。抽成本 bean 跨 bean 调用，事务真实生效。
 *
 * <h2>事务边界（《事务边界决策表》§四·S9）</h2>
 * ✅ **保持单事务（{@code readOnly}）**——TV 用 {@code transactionTemplate} 把两条纯读包在一起，
 * 这里照此用 {@code @Transactional(readOnly = true)}。这不是"取连接的手段"，而是"total 与页同源"的语义。
 */
@Service
public class FeedPullReader {

    private final ContentDao contentDao;

    public FeedPullReader(ContentDao contentDao) {
        this.contentDao = contentDao;
    }

    /**
     * 纯拉的当页数据：命中总数 + 当页 id（同一只读事务 / 同一 read view）。
     *
     * @param followedIds 关注作者集合（**非空**——调用方在空关注时已早退）
     * @param page        页码（≥1）
     * @param pageSize    页大小
     */
    @Transactional(readOnly = true)
    public DbPage pullForPage(List<Long> followedIds, int page, int pageSize) {
        int total = contentDao.countContentByUsers(followedIds);
        if (total == 0) {
            return new DbPage(List.of(), 0);
        }
        int offset = (page - 1) * pageSize;
        List<Long> pageIds = contentDao.findContentIdsByUsers(followedIds, offset, pageSize);
        return new DbPage(pageIds, total);
    }

    /** 纯拉的 DB 查询结果（事务内产出，缓存读在事务外进行）。 */
    public record DbPage(List<Long> pageIds, int total) {
    }
}
