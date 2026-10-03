package io.github.yjhhhaaa06.videoweb.feed.service;

import io.github.yjhhhaaa06.videoweb.common.config.FeedProperties;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.model.AuthorContentId;
import io.github.yjhhhaaa06.videoweb.feed.cache.FeedCache;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 大V发件箱读腿（承接 TV {@code feed.service.FeedOutboxReader}）：把若干大V作者的「最近 N 条」
 * 合成一路窗口。
 *
 * <h2>为什么有这一腿</h2>
 * 大V的内容**不进粉丝收件箱**（{@code FeedInboxWriter} 命中大V即跳过写扩散），也**不落表** ⇒
 * 读时按作者现拉。数据面 = {@code content} 表走 {@code idx_user_id}（InnoDB 物理 {@code (user_id, id)}）
 * **反向索引扫描**取最近 N 条（免 filesort），与重建侧"{@code ORDER BY id DESC LIMIT K}"同一口径、不新增索引。
 *
 * <h2>缓存与失败面</h2>
 * 缓存形态 / 三态 / 回填 / fail-open 降级**全部在 {@link FeedCache#readOutbox}**；
 * 本类只负责：把作者集合交给缓存层、把"带归属的批量 DB 回源"+逐作者降序重排"作为 loader 传入、
 * 最后**拼成一路**（不去重 / 不排序 / 不截断——统一由 {@code FeedReadService} 的 {@code FeedWindows} 完成）。
 *
 * <p>⚠️ 规模口径（TV 登记）：本腿产出 id 数 ∝ **关注的大V作者数 × N**，可能远超读侧总窗口 M——
 * 按设计截断在归并后（由 {@code FeedReadService} 统一做）。
 *
 * <h2>事务边界（《事务边界决策表》§四·S9）</h2>
 * DB 回源（{@code findRecentContentIdsByAuthor}）⚠️ **去事务**——单条 SELECT。
 */
@Service
public class FeedOutboxReader {

    private final FeedCache feedCache;
    private final ContentDao contentDao;
    private final FeedProperties props;

    public FeedOutboxReader(FeedCache feedCache, ContentDao contentDao, FeedProperties props) {
        this.feedCache = feedCache;
        this.contentDao = contentDao;
        this.props = props;
    }

    /**
     * 读若干大V作者的"最近 N 条"并**拼成一路**（各作者内部降序；作者间顺序不影响后续归并）。
     *
     * <p>**不去重、不排序、不截断**——统一由 {@code FeedReadService} 的
     * {@link FeedWindows#mergeDedupSortTrim} 完成（口径单一来源）。
     *
     * @param authorIds 大V作者集合（空 / null → 空列表）
     * @return 所有作者 outbox id 的并集（可能含重复；降序在后段归并时统一处理）
     */
    public List<Long> readOutbox(Set<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return List.of();
        }
        List<Long> authors = new ArrayList<>(authorIds);
        Map<Long, List<Long>> idsByAuthor = feedCache.readOutbox(authors, this::loadFromDb);
        List<Long> merged = new ArrayList<>();
        for (Long authorId : authors) {
            merged.addAll(idsByAuthor.getOrDefault(authorId, List.of()));
        }
        return merged;
    }

    /**
     * 带归属批量回源：一次 SQL 取回各作者的最近 N 条，再按作者归组、**逐作者显式降序重排**
     * （{@code UNION ALL} 外层顺序不保证）。
     *
     * <p>**无内容的作者不出现在结果中**；{@link FeedCache} 负责把缺失作者补齐为空列表。
     * 其 {@code DataAccessException} 由 {@link FeedCache#readOutbox} 按 fail-open 吞掉（大V腿设计裁决）。
     */
    private Map<Long, List<Long>> loadFromDb(List<Long> authorIds) {
        List<AuthorContentId> rows = contentDao.findRecentContentIdsByAuthor(authorIds, props.outboxWindowSize());
        Map<Long, List<Long>> byAuthor = new LinkedHashMap<>();
        for (AuthorContentId row : rows) {
            byAuthor.computeIfAbsent(row.authorId(), k -> new ArrayList<>()).add(row.contentId());
        }
        for (List<Long> ids : byAuthor.values()) {
            ids.sort(Collections.reverseOrder());
        }
        return byAuthor;
    }
}
