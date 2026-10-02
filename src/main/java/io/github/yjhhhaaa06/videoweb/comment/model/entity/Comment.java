package io.github.yjhhhaaa06.videoweb.comment.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 评论行类型。
 *
 * <p>TV 侧没有同名实体——`CommentDao.findCommentById` / `getComments` 等直接装配
 * `com.itheima.content.model.cache.CommentCacheDTO`。那是个**内容域的缓存 DTO**
 * （带 {@code children} 楼中楼树、{@code replyCount} 预览等读路径字段），
 * 属 S5 的领域，本切片不能依赖它。故本切片自持一个**扁平行类型**。
 *
 * <h2>字段与 TV 的对应</h2>
 * 属性名 ↔ TV {@code CommentCacheDTO} 的同名字段（{@code commentId / contentId / userId /
 * content / parentId / replyToUserId / replyToUsername / likeCount / replyCount}），
 * 另补 TV 未装配但查询已选出的 {@code createTime / updateTime / isDeleted}。
 *
 * <p><b>两个 JOIN 列保留不删</b>：{@code username}（评论作者名）与 {@code replyToUsername}
 * （被 @ 者名）SQL 里已选出，但**写路径不消费**（写路径只用 contentId / userId / parentId）。
 * 保留的理由：① SQL 逐字原样，便于与 TV 对照复核；② S5 读路径直接用，避免重新发明同一语句。
 *
 * <p>本类同时承担两个角色：① {@code insert} 的入参（{@code useGeneratedKeys} 回填
 * {@code commentId}）；② {@code findById} 的结果类型（{@code resultMap} 显式映射）。
 * 与 {@code user/model/entity/User} 的角色安排一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Comment {

    /** 主键，列名是 {@code comment_id}（不是 id）。插入时由 {@code useGeneratedKeys} 回填。 */
    private Long commentId;

    private Long contentId;
    private Long userId;

    /** 评论正文。列名是 {@code content}，TV 的 DTO/Command 里叫 {@code message}。 */
    private String content;

    /** 主楼为 NULL；楼中楼一律指向**主楼** id（TV 建树上溯，见 CM-1）。 */
    private Long parentId;

    /** 楼中楼「回复 @xxx」的被 @ 者 id；NULL = 主楼，或回复主楼。 */
    private Long replyToUserId;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer likeCount;

    /** 0=正常 1=已软删。查询不过滤该列（软删判定由各语句的 WHERE 自行表达）。 */
    private Integer isDeleted;

    /** 主楼楼中楼回复数（{@code is_deleted=0} 口径）。 */
    private Integer replyCount;

    // ---- 以下两列来自 SELECT 的 JOIN，写路径不消费，S5 读路径使用 ----

    /** 评论作者名（{@code LEFT JOIN users u}）。 */
    private String username;

    /** 被回复者名（{@code LEFT JOIN users r}）。 */
    private String replyToUsername;
}
