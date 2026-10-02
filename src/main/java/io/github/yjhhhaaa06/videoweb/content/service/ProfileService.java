package io.github.yjhhhaaa06.videoweb.content.service;

import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.content.cache.ContentCache;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.model.cache.ContentCacheDTO;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ProfileVO;
import io.github.yjhhhaaa06.videoweb.follow.cache.FollowCache;
import io.github.yjhhhaaa06.videoweb.like.service.LikeService;
import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import io.github.yjhhhaaa06.videoweb.user.model.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 用户主页（承接 TV {@code com.itheima.content.service.ProfileService}，S5）。
 *
 * <h2>它为什么在 content 域</h2>
 * TV 就是把它放在 {@code com.itheima.content.service}——因为它返回的主体是**内容列表**
 * （按作者分页），外加用户维度信息。保持原位置（硬搬到 user 域会让 user 域依赖 content 的内容缓存）。
 *
 * <h2>★ 决策表 G-5：⚠️ 有意改进——去掉事务</h2>
 * TV 的事务回调**只**承载三条 DB 查询（用户行 + 内容总数 + 页内 id 窗口），查完即提交归还连接，
 * 再在事务外做缓存批量读（TV 的 T3 专门这么改的，注释写明"消除外层事务持连接 + miss 装载
 * 再取新连接"）。三条纯读无原子性需求；去掉后**对外行为完全一致**。
 *
 * <h2>★ 本切片在此兑现 S4 的 F-7 承诺（G-8）</h2>
 * {@code followerCount} / {@code followCount} 走 {@link FollowCache} 的**计数缓存**
 * （S4 去掉、F-7 写明"S5 迁 ProfileService 时补回"）。三处缓存的读都在**事务外**：
 * 关注态、两个计数、以及内容批量。
 *
 * <h2>顺序与"哪些字段可以缺席"</h2>
 * <pre>
 * ① isFollowed（单条，FollowCache）  —— 只看别人时才有；看自己 / 匿名 ⇒ null
 * ② 两个计数（FollowCache）          —— 恒有
 * ③ 用户行 + 总数 + 窗口 id（DB）    —— 用户不存在 ⇒ 404
 * ④ 页内内容批量（ContentCache）      —— 取不到的条目**跳过**（已删/媒体损坏）
 * ⑤ 页内点赞态（LikeService 批量）    —— 仅登录且列表非空
 * </pre>
 * ①② 放在 ③ 之前是 TV 的原样顺序（缓存读比 DB 读便宜，且 404 时白读一次也无害）。
 */
@Slf4j
@Service
public class ProfileService {

    private final UserDao userDao;
    private final ContentDao contentDao;
    private final FollowCache followCache;
    private final ContentCache contentCache;
    private final LikeService likeService;

    public ProfileService(UserDao userDao,
                          ContentDao contentDao,
                          FollowCache followCache,
                          ContentCache contentCache,
                          LikeService likeService) {
        this.userDao = userDao;
        this.contentDao = contentDao;
        this.followCache = followCache;
        this.contentCache = contentCache;
        this.likeService = likeService;
    }

    /**
     * 用户主页。
     *
     * @param profileUserId 被查看的用户
     * @param currentUserId 当前登录用户；{@code null} = 匿名
     * @param page          页码（≥1，由 Controller 归一）
     * @param pageSize      页大小（1..100，由 Controller 归一）
     * @throws NotFoundException 用户不存在（404「用户不存在」）
     */
    public ProfileVO getProfile(long profileUserId, Long currentUserId, int page, int pageSize) {
        // ① 是否关注（走 FollowCache 三态读 + miss 回填 + Redis 挂降级 DB），事务外
        Boolean isFollowed = (currentUserId != null && currentUserId != profileUserId)
                ? followCache.isFollowing(currentUserId, profileUserId)
                : null;
        // ② 关注数 / 粉丝数（G-8 的计数缓存，事务外）
        int followerCount = followCache.getFollowerCount(profileUserId);
        int followCount = followCache.getFollowCount(profileUserId);

        // ③ DB 查询：用户行 + 内容总数 + 页内 id 窗口（feed2-25 T25：窗口 SQL 取代"全量 id + 内存切片"）
        User user = userDao.findByIdForProfile(profileUserId);
        if (user == null) {
            throw new NotFoundException("用户不存在");
        }
        int total = contentDao.countContentByUser(profileUserId);
        int offset = (page - 1) * pageSize;
        List<Long> pageIds = offset < total
                ? contentDao.findContentIdsByUserWindow(profileUserId, offset, pageSize)
                : Collections.emptyList();

        // ④ 事务外：页内批量读（一趟 pipeline + 批量装载），按原序跳过 null
        List<ContentVO> contentList = new ArrayList<>();
        Map<Long, ContentCacheDTO> byId = contentCache.getContentsBatch(pageIds);
        for (Long contentId : pageIds) {
            ContentCacheDTO cached = byId.get(contentId);
            if (cached == null) {
                continue;       // 已删 / 媒体损坏 ⇒ 跳过（total 不变——TV 原样语义）
            }
            contentList.add(contentCache.toContentVO(cached));
        }

        // ⑤ 页内点赞态（只对该页批量查询）
        if (currentUserId != null && !contentList.isEmpty()) {
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

        return new ProfileVO(profileUserId, user.getUsername(),
                followerCount, followCount, isFollowed,
                new PageResult<>(contentList, total, page, pageSize));
    }
}
