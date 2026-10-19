package io.github.yjhhhaaa06.videoweb.feed.service;

import io.github.yjhhhaaa06.videoweb.coin.service.CoinService;
import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import io.github.yjhhhaaa06.videoweb.follow.service.FollowService;
import io.github.yjhhhaaa06.videoweb.interaction.model.InteractionDimension;
import io.github.yjhhhaaa06.videoweb.interaction.model.InteractionTarget;
import io.github.yjhhhaaa06.videoweb.interaction.model.vo.InteractionStatusVO;
import io.github.yjhhhaaa06.videoweb.interaction.service.InteractionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * 两条路径的**内容装载完全共用**（{@link #renderPage}）：事务外批量读内容 + 互动态，按原序跳过 null。
 *
 * <h2>★ 有意迁移位置（相对 TV）</h2>
 * TV 把本类放在 {@code com.itheima.content.service}（因它复用 content 的缓存），并形成
 * {@code content ⇄ feed} 双向依赖（content 侧另有 {@code FeedPushNotifier} 直连）。
 * 本实现把 {@code /feed} 的编排**整体落在 feed 域**，跨域只走对方的 **Service 契约**
 * （{@code ContentService} 批量内容 / {@code FollowService} 关注集 / ~~{@code LikeService} 点赞态~~），
 * 于是 **content 不再依赖 feed**——"内容发布了"由 content 发事件、feed 订阅（`S6-B2` 范式）。
 * 见《决策留痕表》C-9。
 * ⚠️ 划掉的那条已于 **T13** 移除：条目互动态改走**互动域**（{@link #renderPage}），
 * 本域不再 import like 域 —— 保留划痕是为了让"这里少过一条边"可追溯。
 *
 * <h2>降级（纯拉）保留</h2>
 * 窗口不可信（未同步）时以纯拉作答——正确性优先、代价是慢。回退判定唯一来源 = {@link FeedReadService#readWindow}。
 */
@Service
public class FeedService {

    /**
     * ★ 动态流带哪几维互动态（R-13 的**编译期常量**）—— 只带 {@code LIKE}。
     *
     * <p>与 TV 逐字一致（feed 装载**只**做过点赞态）；关注态在动态流里没有展示位。
     * ⚠️ 这是**常量**不是契约：{@code interaction} 键集仍恒四维（未选中的两维是 {@code null}，
     * {@code coin} 作为第四维本面**恒 {@code null}**）。
     */
    private static final Set<InteractionDimension> FEED_DIMENSIONS = Set.of(InteractionDimension.LIKE);

    private final FollowService followService;
    private final ContentService contentService;
    private final InteractionService interactionService;
    private final FeedReadService feedReadService;
    private final FeedPullReader pullReader;
    /**
     * 公共计数 {@code coinCount} 的读取入口（T40）。
     *
     * <p>★ **只调它的 Service 公开方法**（{@code CoinService#batchGetCoinCount}），不碰
     * {@code coin.cache}（ArchUnit 规则 2）；也**不依赖** {@code content} 的装配组件
     * {@code ContentStatusFiller} —— 那会把 {@code feed → content} 的边再宽一层，
     * 而 feed 只需要"id 列表 → 计数映射"这一条窄契约。
     */
    private final CoinService coinService;

    public FeedService(FollowService followService,
                       ContentService contentService,
                       InteractionService interactionService,
                       FeedReadService feedReadService,
                       FeedPullReader pullReader,
                       CoinService coinService) {
        this.followService = followService;
        this.contentService = contentService;
        this.interactionService = interactionService;
        this.feedReadService = feedReadService;
        this.pullReader = pullReader;
        this.coinService = coinService;
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
     * 页内容装载（两路读共用）：**事务外**批量读内容 + 互动态 + 公共投币计数，按原序跳过 null。
     *
     * <h2>★ T40：{@code coinCount} 自带一趟批量，不共用 {@code ContentStatusFiller}</h2>
     * 那是 content 域的装配组件；feed 依赖它会加宽 {@code feed → content} 的边。
     * 本域只经 {@code CoinService#batchGetCoinCount} 取"id → 计数"，保持窄契约。
     *
     * <h2>★ T13（R-13）：互动态改走**互动域**，只带 {@code LIKE} 一维</h2>
     * T13 之前这里**直连** {@code LikeService.batchIsContentLiked}，而条目的关注态
     * （当时是 {@code ContentVO} 的顶层 {@code isFollowed}，T13 已删）只能留着 Java 默认的
     * {@code false} —— ★ **那是一个谎言**（动态流里明明都是已关注作者的内容，却填 {@code false}）。
     * 根因是"互动域只会三维一起答 + 当时 VO 是 {@code boolean}（三维必填）"：
     * 一旦改走互动域，{@code isFollowed} 就会被填上真值 ⇒ **响应形状改变**，
     * 故当初刻意绕开（本方法的旧注释逐字记着这段）。
     * <p>T13 的三态 {@code Boolean} 把这个死结解开了：未选中的维度是 {@code null}
     * （= "本面不查这一维"），于是 feed 既能走互动域（省掉跨域直连），
     * 又**不再撒谎** —— 条目的 {@code interaction.isFollowed} 从"恒 false"变成"恒 null"。
     *
     * <p>⚠️ 目标映射（{@link #targets}）与 {@code ContentStatusFiller} 里那份**同形**，
     * 刻意不抽公共：抽了会让 feed 依赖 content 的装配组件，且本域要自持自己的维度常量。
     *
     * <p>⚠️ 跳过 null 会让返回页**短于 pageSize**（内容已软删 / 缓存空标记）——这不是"到底"，
     * 前端据此判耗尽会提前停住（前端已由 {@code shortPageMeansEnd:false} 处置）。
     */
    private PageResult<ContentVO> renderPage(long currentUserId, List<Long> pageIds, int total,
                                             int page, int pageSize) {
        List<ContentVO> contentList = new ArrayList<>(contentService.loadContentVOs(pageIds));
        if (!contentList.isEmpty()) {
            // ★ 一趟批量（feed 必登录 ⇒ currentUserId 非 null ⇒ 互动域不会返回 null）
            Map<Long, InteractionStatusVO> statuses = interactionService.batchGetStatus(
                    currentUserId, targets(contentList), FEED_DIMENSIONS);
            for (ContentVO vo : contentList) {
                vo.setInteraction(statuses.get(vo.getId()));
            }
            // ★ T40：公共计数 coinCount —— 一趟批量（MGET + 一趟 DB 回填），不逐条、不共用 filler。
            //   与登录态无关（feed 页面本身必登录，但这条填充语义上仍是"公共计数"）。
            Map<Long, Long> coinCounts = coinService.batchGetCoinCount(ids(contentList));
            for (ContentVO vo : contentList) {
                vo.setCoinCount(coinCounts.getOrDefault(vo.getId(), 0L));
            }
        }
        return new PageResult<>(contentList, total, page, pageSize);
    }

    /** 页内 VO → 互动域目标（与 {@code ContentStatusFiller#targets} 同形；id / 作者 / 类型都来自 VO）。 */
    private static List<InteractionTarget> targets(List<ContentVO> list) {
        List<InteractionTarget> targets = new ArrayList<>(list.size());
        for (ContentVO vo : list) {
            targets.add(new InteractionTarget(vo.getId(), vo.getAuthorId(), vo.getType()));
        }
        return targets;
    }

    /** 页内 VO → 内容 id（公共计数的批量入参；`CoinService#batchGetCoinCount` 要求唯一即可去重）。 */
    private static List<Long> ids(List<ContentVO> list) {
        List<Long> ids = new ArrayList<>(list.size());
        for (ContentVO vo : list) {
            ids.add(vo.getId());
        }
        return ids;
    }
}
