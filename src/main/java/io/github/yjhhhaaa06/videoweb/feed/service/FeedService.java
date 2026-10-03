package io.github.yjhhhaaa06.videoweb.feed.service;

import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import io.github.yjhhhaaa06.videoweb.follow.service.FollowService;
import io.github.yjhhhaaa06.videoweb.like.service.LikeService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 关注动态流读服务（承接 TV {@code content.service.FeedService}）：**两路读（有界窗口）为主，纯拉为降级**。
 *
 * <h2>入口唯一</h2>
 * {@code FeedController} 注入本类（{@code /feed} URL 与信封语义不变）。每次请求先问
 * {@link FeedReadService#readWindow}：
 * <ul>
 *   <li>{@code synced=false}（未同步）→ 走 {@link #purePull}（**切换前的实现整体保留**，
 *       分页 / 返回集 / 跳过 null / 异常语义逐字不变）；</li>
 *   <li>{@code synced=true} → 在**归并窗口**（≤ 读侧总窗口 M）上切片，越过窗口即空页（**深翻禁止**），
 *       {@code total} = 窗口实际条数。</li>
 * </ul>
 * 两条路径的**内容装载完全共用**（{@link #renderPage}）：事务外批量读内容 / 点赞，按原序跳过 null。
 *
 * <h2>★ 有意迁移位置（相对 TV）</h2>
 * TV 把本类放在 {@code com.itheima.content.service}（因它复用 content 的缓存），并形成
 * {@code content ⇄ feed} 双向依赖（content 侧另有 {@code FeedPushNotifier} 直连）。
 * 本实现把 {@code /feed} 的编排**整体落在 feed 域**，跨域只走对方的 **Service 契约**
 * （{@code ContentService} 批量内容 / {@code FollowService} 关注集 / {@code LikeService} 点赞态），
 * 于是 **content 不再依赖 feed**——"内容发布了"由 content 发事件、feed 订阅（`S6-B2` 范式）。
 * 见《决策留痕表》C-9。
 *
 * <h2>降级（纯拉）保留</h2>
 * 窗口不可信（未同步）时以纯拉作答——正确性优先、代价是慢。回退判定唯一来源 = {@link FeedReadService#readWindow}。
 */
@Service
public class FeedService {

    private final FollowService followService;
    private final ContentService contentService;
    private final LikeService likeService;
    private final FeedReadService feedReadService;
    private final FeedPullReader pullReader;

    public FeedService(FollowService followService,
                       ContentService contentService,
                       LikeService likeService,
                       FeedReadService feedReadService,
                       FeedPullReader pullReader) {
        this.followService = followService;
        this.contentService = contentService;
        this.likeService = likeService;
        this.feedReadService = feedReadService;
        this.pullReader = pullReader;
    }

    /**
     * 关注动态流（**切读**：两路读窗口为主、纯拉为降级）。
     *
     * <p>分页契约：每次请求**重算整个窗口**再切第 {@code page} 页 ⇒ 无需 offset 深翻；
     * {@code total} = 归并窗口实际条数（≤ M）；越过窗口返回空页且 {@code total} 不变。
     *
     * @param currentUserId 当前登录用户（由 {@code @CurrentUserId} 注入）
     * @param page          页码（≥1，由 Controller 归一）
     * @param pageSize      页大小（1..100，由 Controller 归一）
     */
    public PageResult<ContentVO> getFeed(long currentUserId, int page, int pageSize) {
        FeedReadService.FeedReadResult window = feedReadService.readWindow(currentUserId);
        if (!window.synced()) {
            // 读态闸门未过（窗口不可信）→ 既有纯拉（本任务内完成判定 + 回退）
            return purePull(currentUserId, page, pageSize);
        }
        return loadWindowPage(currentUserId, window.windowIds(), page, pageSize);
    }

    /**
     * 窗口路径：在归并窗口上切页（**深翻禁止**）。
     *
     * <p>窗口已按 contentId 降序、去重、截断到 M；越界（{@code offset >= total}）返回**空页**而非报错，
     * 且 {@code total} 保持窗口实际条数（前端据此自然停止翻页）。
     */
    private PageResult<ContentVO> loadWindowPage(long currentUserId, List<Long> windowIds,
                                                 int page, int pageSize) {
        int total = windowIds.size();
        long offset = (long) (page - 1) * pageSize;   // 用 long 防 int 溢出（page 极大时）
        List<Long> pageIds = (pageSize <= 0 || offset >= total)
                ? List.of()
                : windowIds.subList((int) offset, Math.min((int) offset + pageSize, total));
        return renderPage(currentUserId, pageIds, total, page, pageSize);
    }

    /**
     * 纯拉降级路径（**切换前的实现整体保留**，逐字不动）：关注 ids → 事务内计数 + 当页 id 查询。
     *
     * <p>对外正确性 = 全量可见（不受有界窗口约束）⇒ 未同步用户不会因切读而"看不到旧内容"。
     * 关注集走 {@code FollowService}（其缓存三态读 + miss 回填 + Redis 挂降级 DB）；
     * DB 两查在 {@link FeedPullReader} 的单只读事务内（total 与页同源快照）。
     */
    private PageResult<ContentVO> purePull(long currentUserId, int page, int pageSize) {
        List<Long> followedIds = followService.getFollowingIds(currentUserId);
        if (followedIds.isEmpty()) {
            return new PageResult<>(List.of(), 0, page, pageSize);
        }
        FeedPullReader.DbPage db = pullReader.pullForPage(followedIds, page, pageSize);
        if (db.total() == 0) {
            return new PageResult<>(List.of(), 0, page, pageSize);
        }
        return renderPage(currentUserId, db.pageIds(), db.total(), page, pageSize);
    }

    /**
     * 页内容装载（两路读共用）：**事务外**批量读内容 + 点赞，按原序跳过 null。
     *
     * <h2>⚠️ 只填 isLiked，**不填 isFollowed**（与 TV 逐字一致）</h2>
     * TV 的 feed 装载**只**调 {@code likeService.batchIsContentLiked}（不调关注态批量）。
     * 若此处"顺手"改用 {@code ContentStatusFiller.fillLikeAndFollowBatch}，会给 feed 条目的
     * {@code isFollowed} 填上值——那是**响应形状改变**（契约差异），故刻意只做点赞态。
     *
     * <p>⚠️ 跳过 null 会让返回页**短于 pageSize**（内容已软删 / 缓存空标记）——这不是"到底"，
     * 前端据此判耗尽会提前停住（前端已由 {@code shortPageMeansEnd:false} 处置）。
     */
    private PageResult<ContentVO> renderPage(long currentUserId, List<Long> pageIds, int total,
                                             int page, int pageSize) {
        List<ContentVO> contentList = new ArrayList<>(contentService.loadContentVOs(pageIds));
        if (!contentList.isEmpty()) {
            List<Long> ids = new ArrayList<>(contentList.size());
            for (ContentVO vo : contentList) {
                ids.add(vo.getId());
            }
            Map<Long, Boolean> likedMap = likeService.batchIsContentLiked(currentUserId, ids);
            if (likedMap != null) {
                for (ContentVO vo : contentList) {
                    Boolean liked = likedMap.get(vo.getId());
                    vo.setIsLiked(liked != null && liked);
                }
            }
        }
        return new PageResult<>(contentList, total, page, pageSize);
    }
}
