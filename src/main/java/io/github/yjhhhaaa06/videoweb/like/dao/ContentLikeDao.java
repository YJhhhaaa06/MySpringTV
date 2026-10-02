package io.github.yjhhhaaa06.videoweb.like.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

/**
 * 内容点赞数据访问。
 *
 * <p>迁移自 TV {@code com.itheima.like.dao.ContentLikeDao}——《迁移参照系》§2.1 的机械改动：
 * 删 {@code Connection conn} 首参、删 {@code throws SQLException}、{@code ?} → {@code #{name}}、
 * {@code StringBuilder} 拼 {@code IN (?,?,...)} → {@code <foreach>}、{@code @Component} → {@code @Mapper}。
 * <b>SQL 文本原样保留</b>。
 *
 * <h2>搬 6 个、不搬 1 个</h2>
 * TV 共 7 个方法。**不搬** {@code deleteByContentId}（"删除内容时级联物理删点赞记录"）——
 * 它的调用方是 {@code ContentService} 的内容删除路径，属 **S5**；本切片无调用方，
 * 搬进来就是无主代码。补回位置：S5 搬内容删除时一并补。
 *
 * <p>★ 一个值得点名的收益（《迁移参照系》§2.1）：TV 用 {@code StringBuilder} 循环拼
 * {@code IN (?,?,...)} 并逐个 {@code setLong}，在 MyBatis 里由 {@code <foreach>} 一行替代。
 * 这是**白赚**，不是重写成本。
 */
@Mapper
public interface ContentLikeDao {

    /** TV: {@code INSERT INTO content_like (user_id, content_id) VALUES (?, ?)}。撞唯一键 {@code uk_user_content} 由异常出口映射 409。 */
    int addLike(@Param("userId") long userId, @Param("contentId") long contentId);

    /** TV: {@code DELETE FROM content_like WHERE user_id = ? AND content_id = ?}。 */
    int deleteLike(@Param("userId") long userId, @Param("contentId") long contentId);

    /**
     * 是否已点赞。TV: {@code SELECT COUNT(*) FROM content_like WHERE user_id = ? AND content_id = ?}
     * ——写法翻译为 {@code EXISTS}（行集等价，同 {@code UserDao.isPhoneUsed} / {@code ContentDao.isContentExist}）。
     */
    boolean isLiked(@Param("userId") long userId, @Param("contentId") long contentId);

    /**
     * 批量判定：用户在给定内容子集中**已点赞**的那些 id。
     * TV 用 {@code StringBuilder} 拼 IN；此处 {@code <foreach>}。
     * 服务**降级路径**（Redis 不可用时按请求 id 作答，不为少量 id 拉用户全量点赞史）。
     */
    Set<Long> findLikedContentIds(@Param("userId") long userId, @Param("contentIds") List<Long> contentIds);

    /**
     * 该用户点赞过的**全部**内容 id（用户维度装载）。
     * TV: {@code SELECT content_id FROM content_like WHERE user_id = ?}
     * ——T4 反转后它既是缓存 miss 的回填 loader，也是批量查询 miss 时的答案来源。
     */
    Set<Long> findLikedContentIdsByUser(@Param("userId") long userId);

    /** 内容点赞总数（计数/成员分离：只 COUNT，不加载成员）。TV: {@code countByContentId}。 */
    int countByContentId(@Param("contentId") long contentId);
}
