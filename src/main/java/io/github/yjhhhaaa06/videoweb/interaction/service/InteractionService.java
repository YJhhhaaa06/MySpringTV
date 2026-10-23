package io.github.yjhhhaaa06.videoweb.interaction.service;

import io.github.yjhhhaaa06.videoweb.coin.service.CoinService;
import io.github.yjhhhaaa06.videoweb.favorite.service.FavoriteService;
import io.github.yjhhhaaa06.videoweb.follow.service.FollowService;
import io.github.yjhhhaaa06.videoweb.interaction.model.InteractionDimension;
import io.github.yjhhhaaa06.videoweb.interaction.model.InteractionTarget;
import io.github.yjhhhaaa06.videoweb.interaction.model.vo.InteractionStatusVO;
import io.github.yjhhhaaa06.videoweb.like.service.LikeService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 互动域：把"**当前用户对内容的互动态**"（点过赞吗 / 收藏了吗 / 关注作者了吗 / 投了几枚）
 * 聚合成一处（R-10，详细设计见 {@code favorite.md} §六；第四维投币见
 * {@code architecture/domain/coin/coin.md} §六）。
 *
 * <h2>它解决什么（依赖方向，不是边数）</h2>
 * 内容详情 / 列表要展示这些"与我有关"的状态，它们分别属于 like / favorite / follow / coin 四个域。
 * 本域只做**组装**：content 域依赖本域（**1 条边**，见 T10），不再直接 import 那四个域；
 * 四个域的数据归属不变（本域不是数据所有者）。
 * ⚠️ 总跨域边数其实**增加**了（3 → 1+3）——本方案的收益是"方向变单向 / 分层清晰"，
 * 别记成"依赖变少"（R-10 结论原话，见 {@code favorite.md 附录「编号速查」} R-10）。
 *
 * <h2>三条边界（写改动前先读）</h2>
 * <ol>
 *   <li>★ <b>本域不得依赖 content</b>：否则 {@code content → interaction → content} 成环、
 *       R-10 §六 的"无环"收益落空。"哪些维度适用"由调用方把 {@code type} 传入、
 *       作者由 {@link InteractionTarget} 带入，**不反查内容**。可复算判据：
 *       {@code grep -rn "import.*videoweb.content" src/main/java/.../interaction/} 必须零命中
 *       （T9 的结论与归宿见 {@code favorite.md 附录「编号速查」} T9）。</li>
 *   <li>★ <b>刻意不拆 HTTP 接口</b>：本域**没有** {@code controller} —— "不拆接口"是 R-10 §六 的
 *       核心约束，互动态是收进既有详情响应的内嵌子对象。也**没有** {@code dao} / {@code cache} /
 *       {@code event}：本域是聚合器、不是数据所有者，三份数据分别在 like / favorite / follow 域，
 *       跨域只走它们的 Service 公开契约（{@code 模块边界与依赖.md} §三 通道①）。</li>
 *   <li>★ <b>边界排除「评论点赞」</b>：like 域同时含内容赞与评论赞，本域只用它的**内容赞**
 *       契约（{@link LikeService#isContentLiked} / {@link LikeService#batchIsContentLiked}），
 *       评论点赞不进来。</li>
 * </ol>
 *
 * <h2>两种契约（单条 + 批量）与各自的消费面</h2>
 * <ul>
 *   <li>{@link #getStatus} —— 单条（详情路径）。★ R-13 **刻意不给它加维度入参**：
 *       只有详情用它、且详情恒要**四维**，不为它扩大 T9 刚钉死的契约面；</li>
 *   <li>{@link #batchGetStatus} —— 批量（列表路径），**带维度集合**入参（R-13）。
 *       ★ 它的存在理由：只给单条会让列表退回"逐条调用"（100 条推荐页 = 200 次往返，
 *       正是 {@code ContentStatusFiller} 当年要治的问题）；故批量必须**原样透传批量语义**，
 *       每个被选中的维度各一趟批量、**不得循环调单条**。</li>
 * </ul>
 *
 * <h2>★ 第四维为什么只在这一条契约上（T40）</h2>
 * 详情要"我投了几枚"（投币按钮的 0/1/2 展示态），列表面**没有任何展示位** ——
 * 故 {@code coin} 只在 {@link #getStatus} 里按 {@code type} 分流填充，
 * {@link #batchGetStatus} **恒不查该维**（返回对象里它保持 {@code null}）。
 * <ul>
 *   <li>★ **不给 {@link InteractionDimension} 加 {@code COIN} 常量**：没有列表面需要它（YAGNI），
 *       而给批量契约加维度入参只会扩大 R-13 刚钉死的契约面；</li>
 *   <li>★ 单条契约**本来就恒四维**（它没有维度入参，详情永远要全部四维），
 *       所以"加一维"在这里不破"单条契约不扩面"的初衷。</li>
 * </ul>
 *
 * <h2>★ 维度选择（R-13，2026-10-13）</h2>
 * 批量契约的 {@code dimensions} 由调用方给（编译期常量，见 {@link InteractionDimension}）：
 * **没被选中的维度不查**（对应 {@code InteractionStatusVO} 里那一位是 {@code null}）。
 * 四个列表面今天的取值：首页 {@code /api/v1/contents/recommended} = {@code {FOLLOW}}、搜索
 * {@code /api/v1/contents} = **空集**、{@code /api/v1/feed} 与主页 {@code /api/v1/users/{id}} = {@code {LIKE}}
 * （`T58` 资源化后的路径；各面常量写在各自调用点上）。
 * ⚠️ **空集（0 维）是合法入参**：一趟都不查，但**仍返回对象**（四维全 {@code null}），
 * 不是返回 {@code null} —— 后者是"匿名"的语义（见下），两者必须可区分。
 *
 * <h2>匿名语义（userId == null）</h2>
 * **不查任何互动域，直接返回 {@code null}**（不是四个 {@code false}、不是空 Map、
 * 也不是"四维全 null 的空壳对象"）—— "没有登录态"与"登录但无互动"必须可区分（R-10 §六）。
 * 两个契约同款。
 *
 * <p>本类的读方法全是**纯读**（四趟缓存 / DB 读）⇒ 不加 {@code @Transactional}（事务边界篇 §四）；
 * T44-2 起另有三个**回填转发**方法（best-effort 写各域缓存、内部不抛，见
 * {@code backfillLikeCount}）——它们不构成业务事务边界，同样不需要 {@code @Transactional}。
 */
@Service
public class InteractionService {

    private final LikeService likeService;
    private final FavoriteService favoriteService;
    private final FollowService followService;
    private final CoinService coinService;

    /**
     * <h2>★ 为什么 {@code favoriteService} 是 {@code @Lazy}（装配环的唯一断点，T10 发现）</h2>
     * T10 起 {@code content} 域依赖本域（详情 / 列表取互动态），而
     * {@code favorite → content}（T6-5 的 {@code ContentService.loadContentVOs} 装载契约）
     * 与本域 {@code → favorite}（T9 的聚合边）一起闭成**装配期 Bean 环**：
     * <pre>
     *   contentService → interactionService → favoriteService → contentService
     * </pre>
     * 三条边**全是运行期读依赖**（装配期谁都不需要对方实体）⇒ 在**本域这条边**（T9 引入的
     * 聚合边）用 {@code @Lazy} 断环：注入解析代理、首次调用才取真实 Bean（行为不变，
     * 只多一次代理跳转）。⚠️ 环只需断一处；**本注解若被删**，Spring 启动期会直接
     * {@code BeanCurrentlyInCreationException}——响亮失败，不是静默故障。
     * <p>⚠️ 别把"无环"读成传递闭包：R-10 的"无环"只成立在**直连边**上（本域不 import content）；
     * 上面那个环在传递闭包上真实存在（第二锚点：favorite.md §六 的"T10 落地实测"；登记
     * {@code favorite.md 附录「编号速查」} I-10；备选的根治方案见 {@code FURTHER_ISSUES.md} F-18）。
     *
     * <h2>★ 为什么 {@code coinService} **不需要** {@code @Lazy}（T40 判断）</h2>
     * 判据是它有没有指回来的边：{@code coin.service} 只依赖 {@code content.dao} /
     * {@code user.dao} / 自己的 cache（{@code CoinCache}），**没有任何指回
     * interaction / content.service 的依赖** ⇒ 把本域注入它**不会**多出装配期环
     * （上面的 {@code contentService → interactionService → favoriteService → contentService}
     * 一格都没变）。{@code @Lazy} 只在"本域指出去的那条边"（T9 的 favorite 聚合边）需要断环；
     * 给 coin 也加只会多一层没人需要的代理，并把"环在哪"这个信息稀释掉。
     */
    public InteractionService(LikeService likeService, @Lazy FavoriteService favoriteService,
                              FollowService followService, CoinService coinService) {
        this.likeService = likeService;
        this.favoriteService = favoriteService;
        this.followService = followService;
        this.coinService = coinService;
    }

    /**
     * 单条互动状态（T10 之后详情路径改调本方法）。
     *
     * <p>四维各一次**单条**读：内容赞（{@code LikeService.isContentLiked}）、
     * 判收藏存在（{@code FavoriteService.isFavorited}，不 JOIN 的轻量点查）、
     * 关注作者（{@code FollowService.isFollowing}）、我投了几枚（{@code CoinService.getCoinAmount}）。
     *
     * <p>⚠️ {@code authorId <= 0}（作者未知）⇒ 关注维度不查询、按 false 作答
     * （与既有列表填充跳过非正作者 id 的口径一致）；赞 / 藏 / 投币维度不受影响。
     *
     * <p>⚠️ {@code type} 是**第四维的适用判据**（T40 起真正参与分支）：投币只适用于视频
     * （{@code type == 1}）；图文帖该维**不适用** ⇒ {@code coin} 保持 {@code null}。
     * 判据由调用方传入而**不反查内容类型**（反查即依赖 content、即成环），
     * 详见 {@link InteractionTarget}。
     *
     * @param userId    当前登录用户；{@code null} = 匿名 ⇒ 返回 {@code null}（不查任何互动域）
     * @param contentId 内容 id
     * @param authorId  内容作者 id（{@code <= 0} = 未知）
     * @param type      内容类型（1=视频、2=图文；决定投币维是否适用）
     * @return 四维状态；匿名 ⇒ {@code null}
     */
    public InteractionStatusVO getStatus(Long userId, long contentId, long authorId, int type) {
        if (userId == null) {
            return null;
        }
        InteractionStatusVO status = new InteractionStatusVO();
        status.setIsLiked(likeService.isContentLiked(userId, contentId));
        status.setIsFavorited(favoriteService.isFavorited(userId, contentId));
        status.setIsFollowed(authorId > 0 && followService.isFollowing(userId, authorId));
        // 第四维（T40）：只有视频（type == 1）适用投币；图文帖保持 null（"该维不适用"）。
        // 与前三态共用 null —— 客户端语义都是"不显示该维"，不另造表示（见 InteractionStatusVO 类注释）。
        status.setCoin(type == 1 ? coinService.getCoinAmount(userId, contentId) : null);
        return status;
    }

    /**
     * ★ 公共计数的**回填转发**（T44-2）：详情计数聚合读（content 域 {@code ContentCountAggregate}）
     * 在计数键 miss 时，把一趟 {@code findCountColumns} 刚查出的列快照经这里转发给数据所属的域，
     * 由各域自己的 cache 写各自的键（like/favorite/coin 三键分属三域，content 不得直写）。
     *
     * <h2>它取代了什么</h2>
     * 取代本域原 {@code getPublicCounts}（T43 引入的三数聚合读）：T44-2 起详情路径的四个计数
     * 由聚合层一趟 MGET + miss 单飞回源提供，本域的读聚合失去调用方；**写侧转发**是聚合的
     * 对称形态——本域仍不是数据所有者，只做通道。
     *
     * <h2>★ 三个参数的纪律（有选择回填的落点）</h2>
     * 聚合层**只对 miss 的键**调用对应转发（命中键传不进来）：回填未失效的键会用本次快照
     * 覆盖它，重开"旧值复活"窗口。值为各域真理源（列），各域 cache 用自己的 TTL 落写。
     * <p>⚠️ 三次调用彼此独立、任一失败不影响其余（各 cache 内部 best-effort，不抛）——
     * 回填失败交由下次 miss 自愈，与单键回填的失败语义一致。
     */
    public void backfillLikeCount(long contentId, long count) {
        likeService.backfillContentLikeCount(contentId, Math.toIntExact(count));
    }

    /** 收藏数回填转发（T44-2，见 {@link #backfillLikeCount} 的纪律说明）。 */
    public void backfillFavoriteCount(long contentId, long count) {
        favoriteService.backfillFavoriteCount(contentId, count);
    }

    /** 累计投币数回填转发（T44-2，见 {@link #backfillLikeCount} 的纪律说明）。 */
    public void backfillCoinCount(long contentId, long count) {
        coinService.backfillCoinCount(contentId, count);
    }

    /**
     * 批量互动状态（T10 之后列表路径改调本方法；T13 起带维度集合）—— 入参 N 个目标，
     * 返回 **N 条映射，不重不漏**。
     *
     * <h2>★ 批量语义：每个被选中的维度各一趟，绝不循环调单条</h2>
     * 不论入参多少条，本方法对**被选中的**维度、各只调一次批量契约：
     * {@code LikeService.batchIsContentLiked} / {@code FavoriteService.batchIsFavorited} /
     * {@code FollowService.batchIsFollowing}（关注维度按**去重后的作者集**查，多个内容同作者只查一次）。
     * ⚠️ 这是本域存在的核心约束（原 {@code ContentStatusFiller} 类注释的既有纪律，
     * T10 后由本域与 content 侧的薄壳共同承接）：
     * 改成"逐条调单条"会让 100 条推荐页变 200+ 次往返。判别力见
     * {@code InteractionStatusContractTests} 的调用次数断言。
     *
     * <h2>★ 维度选择（R-13）</h2>
     * 只有 {@code dimensions} 里的维度会去查；**没选中的维度在返回的
     * {@link InteractionStatusVO} 里是 {@code null}**（= "本面不查该维"，见
     * {@link InteractionDimension} 的三态口径）。
     * <ul>
     *   <li>⚠️ **空集（0 维）⇒ 一趟都不查**，但**仍为每个入参 id 返回一个对象**
     *       （四维全 {@code null}）—— 不是返回 {@code null}（那是匿名语义）；</li>
     *   <li>⚠️ {@code dimensions == null} 按**全量三维**处理（枚举只有赞 / 藏 / 关注三维；
     *       {@code coin} 不在枚举里 ⇒ 批量**恒不查**该维）：漏传的代价是"多查"，
     *       而不是"少给"（与本域"匿名 ⇒ 不查"同款取向：宁可保守、不静默丢信息）。
     *       调用点应当显式传常量，不要依赖这个兜底。</li>
     * </ul>
     *
     * <h2>键集口径（不重不漏）</h2>
     * <ul>
     *   <li><b>不漏</b>：入参每个 contentId 都有一条 —— 底层批量返回"缺失 / 未知"的按
     *       {@code false} 处理（{@code FollowService.batchIsFollowing} 的缺失语义即此）；</li>
     *   <li><b>不重</b>：入参出现重复 contentId 时按**首次出现**去重（同一内容只查一次、
     *       只回一条）；同一内容携带不同作者 / 类型属调用方数据不自洽，不作校验。</li>
     * </ul>
     *
     * @param userId     当前登录用户；{@code null} = 匿名 ⇒ 返回 {@code null}（不查任何互动域）
     * @param targets    目标列表（可为空 ⇒ 空 Map；{@code null} 同空处理）
     * @param dimensions 要查的维度集合（{@code null} ⇒ 全量三维；空集 ⇒ 一趟不查但对象仍在；
     *                   {@code coin} 不在枚举里 ⇒ 本契约恒不查该维）
     * @return contentId → 互动状态（每个入参 id 恰好一条；前三维按选中查，{@code coin} 恒 {@code null}）；
     *         匿名 ⇒ {@code null}
     */
    public Map<Long, InteractionStatusVO> batchGetStatus(Long userId, List<InteractionTarget> targets,
                                                        Set<InteractionDimension> dimensions) {
        if (userId == null) {
            return null;
        }
        if (targets == null || targets.isEmpty()) {
            return Map.of();
        }
        Set<InteractionDimension> dims = (dimensions == null)
                ? EnumSet.allOf(InteractionDimension.class)
                : dimensions;
        // 去重：contentId 唯一（首次出现的作者 / 类型为准），另收去重后的非正作者集
        Map<Long, InteractionTarget> byContentId = new LinkedHashMap<>();
        Set<Long> authorIdSet = new LinkedHashSet<>();
        for (InteractionTarget target : targets) {
            byContentId.putIfAbsent(target.contentId(), target);
            if (target.authorId() > 0) {
                authorIdSet.add(target.authorId());
            }
        }
        List<Long> contentIds = new ArrayList<>(byContentId.keySet());

        // ★ 被选中的维度各一趟批量（见方法注释"批量语义"）——不得改成循环调单条；
        //   未选中的维度**一趟都不发**（这正是 R-13 要省的"分类性浪费"）。
        Map<Long, Boolean> likedMap = dims.contains(InteractionDimension.LIKE)
                ? likeService.batchIsContentLiked(userId, contentIds)
                : Map.of();
        Map<Long, Boolean> favoritedMap = dims.contains(InteractionDimension.FAVORITE)
                ? favoriteService.batchIsFavorited(userId, contentIds)
                : Map.of();
        Map<Long, Boolean> followedMap = (dims.contains(InteractionDimension.FOLLOW) && !authorIdSet.isEmpty())
                ? followService.batchIsFollowing(userId, new ArrayList<>(authorIdSet))
                : Map.of();

        Map<Long, InteractionStatusVO> result = new LinkedHashMap<>();
        for (Map.Entry<Long, InteractionTarget> entry : byContentId.entrySet()) {
            InteractionStatusVO status = new InteractionStatusVO();
            // ★ T40：coin 在本契约里**刻意不查**（列表各面无展示位，InteractionDimension 也没有
            //   COIN 常量）⇒ 它保持 null。这不是漏填：契约就是"列表的 coin 恒 null"。
            // ★ 三态：选中 ⇒ 查得的布尔；未选中 ⇒ 保持 null（"本面不查该维"，不是 false）
            if (dims.contains(InteractionDimension.LIKE)) {
                status.setIsLiked(Boolean.TRUE.equals(likedMap.get(entry.getKey())));
            }
            if (dims.contains(InteractionDimension.FAVORITE)) {
                status.setIsFavorited(Boolean.TRUE.equals(favoritedMap.get(entry.getKey())));
            }
            if (dims.contains(InteractionDimension.FOLLOW)) {
                long authorId = entry.getValue().authorId();
                status.setIsFollowed(authorId > 0 && Boolean.TRUE.equals(followedMap.get(authorId)));
            }
            result.put(entry.getKey(), status);
        }
        return result;
    }
}
