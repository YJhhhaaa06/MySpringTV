package io.github.yjhhhaaa06.videoweb.content.service;

import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentDetailVO;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.follow.service.FollowService;
import io.github.yjhhhaaa06.videoweb.like.service.LikeService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容列表 / 详情的**状态填充器**（承接 TV {@code ContentStatusFiller}，S5）。
 *
 * <h2>它解决什么</h2>
 * 内容的 VO 里有两个**与请求者有关**的字段：{@code isLiked}（我点过赞吗）与
 * {@code isFollowed}（我关注了作者吗）。它们不在内容表里，也不该进内容缓存
 * （缓存是**请求无关**的，否则每个用户一份缓存）。故在组装响应时按需填充。
 *
 * <h2>★ 一次列表请求只打一次缓存/DB（批量而非逐条）</h2>
 * {@link #fillLikeAndFollowBatch} 把 N 条内容收敛成 **2 次批量查询**：
 * ① {@code LikeService.batchIsContentLiked}（一趟 pipeline SISMEMBER 或一次 IN 查询）；
 * ② {@code FollowService.batchIsFollowing}（一趟 pipeline ZSCORE 或一次 IN 查询）。
 * 若逐条调用，一个 100 条的推荐页就是 200 次往返——这正是 TV 的 T8 引批量读要保住的**性质**，
 * 也是本类存在的唯一理由（否则各 Service 内联几行就够了）。
 *
 * <p>⚠️ <b>S6-B2a</b>：② 原先直连 {@code follow.cache.FollowCache}，现改为经
 * {@code FollowService} 的公开查询方法。批量语义是原样透传，**性能性质不变**；
 * 变的是"关注域缓存实现不再是对外 API"。
 *
 * <h2>四类填充点与调用方</h2>
 * <table>
 *   <caption>填充方法 → 调用方</caption>
 *   <tr><th>方法</th><th>调用方</th></tr>
 *   <tr><td>{@link #fillContentLikeStatus}</td><td>{@code /search/IdSearch}（详情，单条点赞态）</td></tr>
 *   <tr><td>{@link #fillFollowStatus(ContentDetailVO, Long)}</td><td>{@code /search/IdSearch}（详情，单条关注态）</td></tr>
 *   <tr><td>{@link #fillLikeAndFollowBatch}</td><td>{@code /start}、{@code /search/keywordSearch}</td></tr>
 *   <tr><td>{@link #fillFollowStatus(List, Long)}</td><td>{@link #fillLikeAndFollowBatch} 内部；
 *       以及**将来的 feed 切片**（它只有关注态、无点赞态）</td></tr>
 * </table>
 * TV 的 {@code ProfileService} **不用**本类（它自己内联了点赞批量查询 + 用
 * {@code FollowCache} 只判单条 {@code isFollowed}）——本实现保持该差异，不为"统一"而改动行为。
 *
 * <p>所有方法都对 {@code userId == null}（匿名）短路：匿名请求不填任何个性化字段，
 * 于是 {@code isLiked}/{@code isFollowed} 保持 Java 默认值 {@code false}
 * （旧 pytest 对匿名场景的断言即此）。
 */
@Component
public class ContentStatusFiller {

    private final LikeService likeService;
    private final FollowService followService;

    public ContentStatusFiller(LikeService likeService, FollowService followService) {
        this.likeService = likeService;
        this.followService = followService;
    }

    // ========================================================================
    // 点赞状态
    // ========================================================================

    /** 详情页单条内容的点赞态。 */
    public void fillContentLikeStatus(ContentDetailVO vo, long contentId, long userId) {
        Map<Long, Boolean> likedMap = likeService.batchIsContentLiked(userId, List.of(contentId));
        Boolean liked = likedMap.get(contentId);
        vo.setIsLiked(liked != null && liked);
    }

    /** 列表页批量点赞态 + 批量关注态（一趟内容批量 + 一趟关注批量）。 */
    public void fillLikeAndFollowBatch(List<ContentVO> list, Long userId) {
        if (userId == null || list.isEmpty()) {
            return;
        }
        List<Long> contentIds = new ArrayList<>(list.size());
        for (ContentVO vo : list) {
            contentIds.add(vo.getId());
        }
        Map<Long, Boolean> likedMap = likeService.batchIsContentLiked(userId, contentIds);
        if (likedMap == null) {
            likedMap = new HashMap<>();
        }
        for (ContentVO vo : list) {
            Boolean liked = likedMap.get(vo.getId());
            vo.setIsLiked(liked != null && liked);
        }
        fillFollowStatus(list, userId);
    }

    // ========================================================================
    // 关注状态
    // ========================================================================

    /**
     * 列表页批量关注态。
     *
     * <p>作者 id 取 {@code > 0} 的（跳过作者未知的条目）；全为 0 时**不发查询**——
     * 避免空 {@code IN ()} 查询（TV 原样）。
     */
    public void fillFollowStatus(List<ContentVO> list, Long userId) {
        if (userId == null || list == null || list.isEmpty()) {
            return;
        }
        List<Long> authorIds = new ArrayList<>();
        for (ContentVO vo : list) {
            if (vo.getAuthorId() > 0) {
                authorIds.add(vo.getAuthorId());
            }
        }
        if (authorIds.isEmpty()) {
            return;
        }
        Map<Long, Boolean> followedMap = followService.batchIsFollowing(userId, authorIds);
        for (ContentVO vo : list) {
            Boolean followed = followedMap.get(vo.getAuthorId());
            vo.setIsFollowed(followed != null && followed);
        }
    }

    /** 详情页单条内容的关注态。 */
    public void fillFollowStatus(ContentDetailVO vo, Long userId) {
        if (userId == null || vo == null) {
            return;
        }
        vo.setIsFollowed(followService.isFollowing(userId, vo.getAuthorId()));
    }
}
