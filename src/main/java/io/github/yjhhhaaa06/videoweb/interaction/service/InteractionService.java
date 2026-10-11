package io.github.yjhhhaaa06.videoweb.interaction.service;

import io.github.yjhhhaaa06.videoweb.favorite.service.FavoriteService;
import io.github.yjhhhaaa06.videoweb.follow.service.FollowService;
import io.github.yjhhhaaa06.videoweb.interaction.model.InteractionTarget;
import io.github.yjhhhaaa06.videoweb.interaction.model.vo.InteractionStatusVO;
import io.github.yjhhhaaa06.videoweb.like.service.LikeService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 互动域：把"**当前用户对内容的互动态**"（点过赞吗 / 收藏了吗 / 关注作者了吗）聚合成一处
 * （R-10，详细设计见 {@code 收藏功能-分期与设计.md} §3.6）。
 *
 * <h2>它解决什么（依赖方向，不是边数）</h2>
 * 内容详情 / 列表要展示这三个"与我有关"的状态，它们分别属于 like / favorite / follow 三个域。
 * 本域只做**组装**：content 域依赖本域（**1 条边**，见 T10），不再直接 import 那三个域；
 * 三个域的数据归属不变（本域不是数据所有者）。
 * ⚠️ 总跨域边数其实**增加**了（3 → 1+3）——本方案的收益是"方向变单向 / 分层清晰"，
 * 别记成"依赖变少"（R-10 结论原话，见 {@code CURRENT_NEEDS.md} §三「R-10 结论」）。
 *
 * <h2>三条边界（写改动前先读）</h2>
 * <ol>
 *   <li>★ <b>本域不得依赖 content</b>：否则 {@code content → interaction → content} 成环、
 *       R-10 §3.6 的"无环"收益落空。"哪些维度适用"由调用方把 {@code type} 传入、
 *       作者由 {@link InteractionTarget} 带入，**不反查内容**。可复算判据：
 *       {@code grep -rn "import.*videoweb.content" src/main/java/.../interaction/} 必须零命中
 *       （T9 回写记录在 {@code CURRENT_TASKS.md}）。</li>
 *   <li>★ <b>刻意不拆 HTTP 接口</b>：本域**没有** {@code controller} —— "不拆接口"是 R-10 §3.6 的
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
 *   <li>{@link #getStatus} —— 单条（详情路径）；</li>
 *   <li>{@link #batchGetStatus} —— 批量（列表路径）。★ 它的存在理由：只给单条会让列表退回
 *       "逐条调用"（100 条推荐页 = 200 次往返，正是 {@code ContentStatusFiller} 当年要治的问题）；
 *       故批量必须**原样透传批量语义**，三维各一趟批量、**不得循环调单条**。</li>
 * </ul>
 *
 * <h2>匿名语义（userId == null）</h2>
 * **不查任何互动域，直接返回 {@code null}**（不是三个 {@code false}、不是空 Map）——
 * "没有登录态"与"登录但无互动"必须可区分（R-10 §3.6）。两个契约同款。
 *
 * <p>本类全是**纯读**（三趟缓存 / DB 读）⇒ 不加 {@code @Transactional}（事务边界篇 §四）。
 */
@Service
public class InteractionService {

    private final LikeService likeService;
    private final FavoriteService favoriteService;
    private final FollowService followService;

    public InteractionService(LikeService likeService, FavoriteService favoriteService, FollowService followService) {
        this.likeService = likeService;
        this.favoriteService = favoriteService;
        this.followService = followService;
    }

    /**
     * 单条互动状态（T10 之后详情路径改调本方法）。
     *
     * <p>三维各一次**单条**读：内容赞（{@code LikeService.isContentLiked}）、
     * 判收藏存在（{@code FavoriteService.isFavorited}，不 JOIN 的轻量点查）、
     * 关注作者（{@code FollowService.isFollowing}）。
     *
     * <p>⚠️ {@code authorId <= 0}（作者未知）⇒ 关注维度不查询、按 false 作答
     * （与既有列表填充跳过非正作者 id 的口径一致）；赞 / 藏维度不受影响。
     *
     * <p>⚠️ {@code type} 今天是"**预留的判据位置**"：视频 / 图文都适用三维，故暂不参与分支；
     * 将来按类型收窄维度（如投币）时在**本域内**用它判定，而不是去反查内容类型
     * （反查即依赖 content、即成环）。详见 {@link InteractionTarget} 与 F-13（{@code FURTHER_ISSUES.md} 登记）。
     *
     * @param userId    当前登录用户；{@code null} = 匿名 ⇒ 返回 {@code null}（不查任何互动域）
     * @param contentId 内容 id
     * @param authorId  内容作者 id（{@code <= 0} = 未知）
     * @param type      内容类型（1=视频、2=图文；"哪些维度适用"的判据位置，见上）
     * @return 三维状态；匿名 ⇒ {@code null}
     */
    public InteractionStatusVO getStatus(Long userId, long contentId, long authorId, int type) {
        if (userId == null) {
            return null;
        }
        InteractionStatusVO status = new InteractionStatusVO();
        status.setIsLiked(likeService.isContentLiked(userId, contentId));
        status.setIsFavorited(favoriteService.isFavorited(userId, contentId));
        status.setIsFollowed(authorId > 0 && followService.isFollowing(userId, authorId));
        return status;
    }

    /**
     * 批量互动状态（T10 之后列表路径改调本方法）—— 入参 N 个目标，返回 **N 条映射，不重不漏**。
     *
     * <h2>★ 批量语义：三维各一趟，绝不循环调单条</h2>
     * 不论入参多少条，本方法对三个互动域**各只调一次**批量契约：
     * {@code LikeService.batchIsContentLiked} / {@code FavoriteService.batchIsFavorited} /
     * {@code FollowService.batchIsFollowing}（关注维度按**去重后的作者集**查，多个内容同作者只查一次）。
     * ⚠️ 这是本域存在的核心约束（{@code ContentStatusFiller} 类注释的既有纪律）：
     * 改成"逐条调单条"会让 100 条推荐页变 200+ 次往返。判别力见
     * {@code InteractionStatusContractTests} 的调用次数断言。
     *
     * <h2>键集口径（不重不漏）</h2>
     * <ul>
     *   <li><b>不漏</b>：入参每个 contentId 都有一条 —— 底层批量返回"缺失 / 未知"的按
     *       {@code false} 处理（{@code FollowService.batchIsFollowing} 的缺失语义即此）；</li>
     *   <li><b>不重</b>：入参出现重复 contentId 时按**首次出现**去重（同一内容只查一次、
     *       只回一条）；同一内容携带不同作者 / 类型属调用方数据不自洽，不作校验。</li>
     * </ul>
     *
     * @param userId  当前登录用户；{@code null} = 匿名 ⇒ 返回 {@code null}（不查任何互动域）
     * @param targets 目标列表（可为空 ⇒ 空 Map；{@code null} 同空处理）
     * @return contentId → 三维状态（每个入参 id 恰好一条）；匿名 ⇒ {@code null}
     */
    public Map<Long, InteractionStatusVO> batchGetStatus(Long userId, List<InteractionTarget> targets) {
        if (userId == null) {
            return null;
        }
        if (targets == null || targets.isEmpty()) {
            return Map.of();
        }
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

        // ★ 三维各一趟批量（见方法注释"批量语义"）——不得改成循环调单条
        Map<Long, Boolean> likedMap = likeService.batchIsContentLiked(userId, contentIds);
        Map<Long, Boolean> favoritedMap = favoriteService.batchIsFavorited(userId, contentIds);
        Map<Long, Boolean> followedMap = authorIdSet.isEmpty()
                ? Map.of()
                : followService.batchIsFollowing(userId, new ArrayList<>(authorIdSet));

        Map<Long, InteractionStatusVO> result = new LinkedHashMap<>();
        for (Map.Entry<Long, InteractionTarget> entry : byContentId.entrySet()) {
            InteractionStatusVO status = new InteractionStatusVO();
            status.setIsLiked(Boolean.TRUE.equals(likedMap.get(entry.getKey())));
            status.setIsFavorited(Boolean.TRUE.equals(favoritedMap.get(entry.getKey())));
            long authorId = entry.getValue().authorId();
            status.setIsFollowed(authorId > 0 && Boolean.TRUE.equals(followedMap.get(authorId)));
            result.put(entry.getKey(), status);
        }
        return result;
    }
}
