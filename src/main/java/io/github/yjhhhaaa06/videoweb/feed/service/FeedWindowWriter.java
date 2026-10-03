package io.github.yjhhhaaa06.videoweb.feed.service;

import io.github.yjhhhaaa06.videoweb.common.config.FeedProperties;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.feed.dao.FeedInboxDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 收件箱窗口**整窗替换**的事务边界载体（S9 抽出的独立 bean）。
 *
 * <h2>为什么必须抽成独立 bean</h2>
 * TV 的 {@code FeedRebuildService.rebuildInbox}（取锁 → 读关注集 → 排除大V → **单事务替换窗口** →
 * 失效缓存 → 释放锁）里，只有"替换窗口"这一步在事务内。若把它写成
 * {@code FeedRebuildService.replaceWindow}（{@code @Transactional}）由 {@code rebuildInbox} 自调用
 * ——**事务静默失效**（SOP 坑 12：同类自调用绕过代理）。抽成本 bean 后跨 bean 调用，事务真实生效。
 *
 * <h2>★ 红线顺序（TV 原样，挪动即丢消息）</h2>
 * 事务内顺序 = **① 先清 → ② 窗口重查 → ③ 归并裁剪 → ④ {@code INSERT IGNORE} → ⑤ upsert 同步状态**，
 * 且**此后不得再有任何删除动作**。理由（TV 的顺序论证，锚在 {@code feed_inbox} 这个 DB 真相层）：
 * <ul>
 *   <li>某条内容的 fanout 行若在本事务 DELETE **之前**提交 ⇒ 该行虽被删，但其 {@code content} 行必已
 *       提交于 DELETE 之前，而窗口快照读（一致性读）发生在 DELETE **之后** ⇒ **必被本次快照收录**；</li>
 *   <li>若在 DELETE **之后**到达 ⇒ 落在"已清空区"且此后无删除 ⇒ **保留**。</li>
 * </ul>
 * 即 **最终窗口 ⊇ 本次快照，多出的成员只来自 DELETE 之后到达的 fanout**——**只多不丢**。
 * 故 **DEL（DB 清空）必须早于窗口重查、且其后不得再删**（先查后清会在"查完 → 清"窗口里丢掉 fanout 增量）。
 *
 * <h2>同步状态必须与窗口替换同事务</h2>
 * 否则"同步态已写但窗口回滚"会让读侧（读态闸门）误信一个陈旧窗口。**空窗口同样写**
 * （"空但已同步" = 该用户确实没有可看的内容）。
 */
@Slf4j
@Service
public class FeedWindowWriter {

    private final FeedInboxDao feedInboxDao;
    private final ContentDao contentDao;
    private final FeedProperties props;

    public FeedWindowWriter(FeedInboxDao feedInboxDao, ContentDao contentDao, FeedProperties props) {
        this.feedInboxDao = feedInboxDao;
        this.contentDao = contentDao;
        this.props = props;
    }

    /**
     * 单事务「替换窗口 + 写同步状态」。
     *
     * @param userId  收件箱归属者
     * @param authors 已排除大V的关注作者（**保持关注集原序**——按 {@code rebuild-author-batch} 切片，
     *                作者顺序影响分块但不影响最终并集）
     */
    @Transactional
    public void replaceWindow(long userId, List<Long> authors) {
        final int authorBatch = props.rebuildAuthorBatch();
        final int perAuthor = props.inboxWindowPerAuthor();
        final int windowMax = props.inboxWindowMax();

        feedInboxDao.deleteByUser(userId);                     // ① 先清（事务内唯一删除）
        List<Long> raw = new ArrayList<>();
        for (int from = 0; from < authors.size(); from += authorBatch) {
            int to = Math.min(from + authorBatch, authors.size());
            raw.addAll(contentDao.findRecentContentIdsByUsers(authors.subList(from, to), perAuthor));  // ② 逐作者最近 K
        }
        List<Long> window = FeedWindows.mergeDedupSortTrim(raw, windowMax);   // ③ 归并去重降序裁剪
        if (!window.isEmpty()) {
            feedInboxDao.insertBatch(userId, window);          // ④ 空窗不发 SQL
        }
        feedInboxDao.upsertSync(userId);                       // ⑤ 空窗也写（"空但已同步"）
    }
}
