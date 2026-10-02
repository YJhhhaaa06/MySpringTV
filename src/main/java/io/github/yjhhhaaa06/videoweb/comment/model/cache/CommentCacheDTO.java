package io.github.yjhhhaaa06.videoweb.comment.model.cache;

import lombok.Data;

import java.util.List;

/**
 * 评论缓存载荷 / 评论树节点（承接 TV {@code com.itheima.content.model.cache.CommentCacheDTO}）。
 *
 * <h2>★ 为什么它不在 content 域（TV 把它放在 content 下）</h2>
 * TV 的 {@code CommentCacheDTO} 位于 {@code com.itheima.content.model.cache}——因为
 * {@code CommentCache} 当时也归 content 域的包。S2 因此**没有**复用它，而是自持了一个扁平行类型
 * {@link io.github.yjhhhaaa06.videoweb.comment.model.entity.Comment}
 * （当时的理由见那份文件的 Javadoc："那是个内容域的缓存 DTO，属 S5 的领域，本切片不能依赖它"）。
 *
 * <p>S5 把 {@code CommentCache} 收进 **comment 域**（它本来就是评论的缓存），
 * 于是本 DTO 也归 comment 域——包结构因此比 TV 更贴合职责。
 *
 * <h2>一身三职（TV 原样）</h2>
 * <ol>
 *   <li><b>DB 行</b>：{@code CommentDao} 的六个读方法的结果类型（{@code resultMap} 显式映射）；</li>
 *   <li><b>Redis 值</b>：两键组的元素（roots LIST 里的一份 JSON / replies HASH 的 field 值）；</li>
 *   <li><b>VO 的父类</b>：{@link io.github.yjhhhaaa06.videoweb.comment.model.vo.CommentVO}
 *       在此基础上加 {@code isLiked}。</li>
 * </ol>
 *
 * <h2>★ 字段集是冻结契约（JSON 形状）</h2>
 * 对外的评论 JSON 键 = {@code username / commentId / contentId / userId / content / parentId /
 * replyToUserId / replyToUsername / likeCount / replyCount / children}（+ VO 的 {@code isLiked}）。
 * 旧 pytest 对 {@code commentId} / {@code content} / {@code children} / {@code replyCount}
 * 有逐字断言。
 *
 * <h2>{@code children} 的两种形态（T10-A/T10-B，必须分清）</h2>
 * <ul>
 *   <li><b>roots LIST 里的元素</b>：{@code children == null}（主楼列表**不带**楼中楼）。</li>
 *   <li><b>replies HASH 的 field 值</b>：该主楼的 children，且**只存前 K=2 条**（T10-B 预览），
 *       与响应里 {@code children} 的内容**同源**（同一份截断结果既写缓存也用于组装）。</li>
 *   <li><b>缺省全量路径</b>（{@code getFullTree}）：children 全量随行（不占两键组，直接上溯建树）。</li>
 * </ul>
 * 把"roots 不带 children"与"replies 带前 K 条"混起来会导致响应里出现重复或缺失的楼中楼——
 * 这正是旧 pytest {@code test_comment_paging} 第 7 条专门盯的地方。
 */
@Data
public class CommentCacheDTO {

    /** 评论作者名（{@code LEFT JOIN users u}）。 */
    private String username;

    /** 主键，列名 {@code comment_id}（不是 id）。 */
    private long commentId;

    private long contentId;
    private long userId;

    /** 评论正文（列名 {@code content}）。 */
    private String content;

    /** 主楼为 NULL；楼中楼一律指向**主楼** id（TV 建树上溯，见 CM-1）。 */
    private Long parentId;

    /** 楼中楼「回复 @xxx」的被 @ 者 id；NULL = 主楼，或回复主楼。 */
    private Long replyToUserId;

    /** 被回复者名（{@code LEFT JOIN users r}）。 */
    private String replyToUsername;

    private int likeCount;

    /** 主楼回复总数（= DB {@code reply_count} 列；楼中楼无意义）。T10-B 的信封 total 来源。 */
    private int replyCount;

    /** 楼中楼（仅 replies HASH 值 / 缺省全量路径非 null；roots 元素恒为 null）。 */
    private List<CommentCacheDTO> children;
}
