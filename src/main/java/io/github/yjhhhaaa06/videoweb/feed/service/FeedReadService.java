package io.github.yjhhhaaa06.videoweb.feed.service;

import io.github.yjhhhaaa06.videoweb.common.config.FeedProperties;
import io.github.yjhhhaaa06.videoweb.follow.service.FollowService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 读侧两路读编排（承接 TV {@code feed.service.FeedReadService}）：
 * **读态闸门 → 收件箱腿 ∪ 大V发件箱腿 → 归并去重降序截断 M**。
 *
 * <h2>形态：有界窗口 + 两路读</h2>
 * ① 收件箱窗口（推的产物，见 {@link FeedInboxReader}）；② 大V发件箱窗口（读时拉，见 {@link FeedOutboxReader}）；
 * 两路归并去重后按 contentId 降序、**截断到读侧总窗口 M**（{@code video.feed.read-window-max}，默认 300）。
 *
 * <p><b>每次请求都重算整个窗口</b>（不引入"整窗缓存"新层——两腿各自有缓存，窗口本身不缓存），
 * 页级切片由调用方（{@code FeedService}）在结果上做，故**深翻天然只读窗口内**。
 *
 * <h2>未同步 ⇒ 回退信号</h2>
 * {@code feed_inbox_sync} 无该用户行时返回 {@code synced=false}，由调用方走既有纯拉。
 * **"未同步"是回退的充分必要条件**——**MQ 可用性不作触发**（读平面不依赖 MQ；MQ 故障期窗口陈旧
 * 落在"异步可见 / 最终一致 / 有界"的已登记语义内，而全域回退纯拉会把 push 平面故障放大成读平面 DB 负载尖峰）。
 *
 * <h2>归并口径单一来源</h2>
 * 直接复用重建侧的 {@link FeedWindows#mergeDedupSortTrim}（去重 + contentId 降序 + 截断），
 * 上限改传 M ⇒ "归并"这一口径全仓只有一份实现（只有**上限参数**按层不同）。
 *
 * <h2>失败面</h2>
 * 闸门 / 收件箱腿的 DB 失败**上抛**（⇒ 500，与切换前的纯拉一致——读路径 DB 失败不留半条时间线）；
 * 大V腿自带降级（fail-open，见 {@link FeedOutboxReader} 与 {@link FeedBigVRouter#isBigVBatch}），**不 500**。
 *
 * <h2>依赖方向</h2>
 * 本类在 feed 域。取关注集走 {@code follow.service.FollowService} **契约**（不得触碰 {@code follow.cache}，
 * ArchUnit 规则 2）——feed → follow.service 单向，不新增包环。
 */
@Service
public class FeedReadService {

    private final FollowService followService;
    private final FeedBigVRouter bigVRouter;
    private final FeedInboxReader inboxReader;
    private final FeedOutboxReader outboxReader;
    private final FeedProperties props;

    public FeedReadService(FollowService followService, FeedBigVRouter bigVRouter,
                           FeedInboxReader inboxReader, FeedOutboxReader outboxReader, FeedProperties props) {
        this.followService = followService;
        this.bigVRouter = bigVRouter;
        this.inboxReader = inboxReader;
        this.outboxReader = outboxReader;
        this.props = props;
    }

    /**
     * 读侧窗口结果。
     *
     * @param synced    {@code false} ⇒ 调用方**回退既有纯拉**（读态闸门未过）
     * @param windowIds 已归并 / 去重 / contentId 降序 / 截断到 M 的可见窗口（{@code synced=false} 时为空）
     */
    public record FeedReadResult(boolean synced, List<Long> windowIds) {
    }

    /**
     * 重算 {@code userId} 的可见窗口（每次调用都重算；见类注释）。
     *
     * @return {@code synced=false}（未同步 → 调用方回退纯拉）或 {@code synced=true} + 窗口
     *         （可能为空：无关注 / 两腿都没内容）
     */
    public FeedReadResult readWindow(long userId) {
        // ① 读态闸门：未同步连关注集都不必读（窗口不可信 ⇒ 交回纯拉）
        if (!inboxReader.isSynced(userId)) {
            return new FeedReadResult(false, List.of());
        }

        // ② 关注集（FollowService 三态读 + miss 回填 + Redis 挂则降级 DB）；空关注 = 空窗
        List<Long> following = followService.getFollowingIds(userId);
        if (following.isEmpty()) {
            return new FeedReadResult(true, List.of());
        }

        // ③ 收件箱腿（已按 contentId 降序）
        List<Long> inboxIds = inboxReader.readInbox(userId);

        // ④ 大V发件箱腿（单点批量判定 → 一次 pipeline 批读；无大V则整腿跳过）
        Set<Long> bigVAuthors = bigVRouter.isBigVBatch(following);

        // ⑤ 归并去重降序截断 M（复用重建侧实现，口径单一来源）
        List<Long> raw = new ArrayList<>(inboxIds);
        if (!bigVAuthors.isEmpty()) {
            raw.addAll(outboxReader.readOutbox(bigVAuthors));
        }
        return new FeedReadResult(true, FeedWindows.mergeDedupSortTrim(raw, props.readWindowMax()));
    }
}
