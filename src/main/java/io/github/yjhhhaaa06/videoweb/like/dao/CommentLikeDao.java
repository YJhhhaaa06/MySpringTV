package io.github.yjhhhaaa06.videoweb.like.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

/**
 * 评论点赞数据访问。
 *
 * <p>迁移自 TV {@code com.itheima.like.dao.CommentLikeDao}——机械改动同 {@link ContentLikeDao}。
 * <b>SQL 文本原样保留</b>。
 *
 * <p>TV 共 6 个方法，**全部搬**（无 S5 归属的死代码）：
 * 增 / 删 / 单查 / 批量判定 / 用户维度全量 / 计数。
 *
 * <p>注意方法名与内容侧略有差异（TV 原文如此）：内容侧删法是 {@code deleteLike}，
 * 评论侧是 {@code removeLike}。**保留各自原名**，不顺手统一——
 * 统一方法名会让"这行是从 TV 哪来"的对照成本上升，而收益只是审美。
 */
@Mapper
public interface CommentLikeDao {

    /** TV: {@code INSERT INTO comment_like (user_id, comment_id) VALUES (?, ?)}。撞唯一键 {@code uk_user_comment} 由异常出口映射 409。 */
    int addLike(@Param("userId") long userId, @Param("commentId") long commentId);

    /** TV: {@code DELETE FROM comment_like WHERE user_id = ? AND comment_id = ?}。 */
    int removeLike(@Param("userId") long userId, @Param("commentId") long commentId);

    /** 是否已点赞（{@code COUNT(*) > 0} → {@code EXISTS}）。TV: {@code isLiked}。 */
    boolean isLiked(@Param("userId") long userId, @Param("commentId") long commentId);

    /** 批量判定：用户在给定评论子集中已点赞的 id（降级路径用）。TV 的 IN 拼接 → {@code <foreach>}。 */
    Set<Long> findLikedCommentIds(@Param("userId") long userId, @Param("commentIds") List<Long> commentIds);

    /** 该用户点赞过的全部评论 id（缓存回填 loader）。TV: {@code findLikedCommentIdsByUser}。 */
    Set<Long> findLikedCommentIdsByUser(@Param("userId") long userId);

    /** 评论点赞总数。TV: {@code countByCommentId}。 */
    int countByCommentId(@Param("commentId") long commentId);
}
