package io.github.yjhhhaaa06.videoweb.comment.model.cache;

import lombok.Data;

import java.time.LocalDateTime;
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
 * <h2>★ 字段集是对外契约（JSON 形状）</h2>
 * 对外的评论 JSON 键 = {@code username / commentId / contentId / userId / content / parentId /
 * replyToUserId / replyToUsername / createTime / likeCount / replyCount / children}
 * （+ VO 的 {@code isLiked}）。
 * 旧 pytest 对 {@code commentId} / {@code content} / {@code children} / {@code replyCount}
 * 有逐字断言。
 *
 * <p>⚠️ <b>加字段要三处同步</b>（`GAP-12`，2026-10-31 补 {@code createTime}）：本类字段
 * + {@code CommentMapper.xml} 的 {@code commentCacheMap} 映射
 * + {@code CommentService#convertToCommentVO} 的**手写逐字段拷贝**。
 * 漏最后一处时，缓存里有值、响应里恒 {@code null} —— DB 与缓存都"看起来正常"，
 * 只表现成"前端不显示时间"。判别力由 {@code CommentReadTests} 的反向验证钉住。
 *
 * <h2>{@code children} 的两种形态（T10-A/T10-B，必须分清）</h2>
 * <ul>
 *   <li><b>roots LIST 里的元素</b>：{@code children == null}（主楼列表**不带**楼中楼）。</li>
 *   <li><b>replies HASH 的 field 值</b>：该主楼的 children，且**只存前 K=2 条**（T10-B 预览），
 *       与响应里 {@code children} 的内容**同源**（同一份截断结果既写缓存也用于组装）。</li>
 * </ul>
 * （`T59` 前还有第三种"缺省全量整树"形态，已随端点单一形状退役。）
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

    /**
     * 发布时间（列 {@code create_time}，{@code datetime}，秒级）。
     *
     * <p>与其它对外 VO 的时间字段同口径：序列化为 {@code yyyy-MM-ddTHH:mm:ss}、
     * 语义时区 Asia/Shanghai（《接口契约》§2.7）。前端按用户本地时区呈现。
     *
     * <p>★ 本项目历史数据里该列可空（{@code DEFAULT CURRENT_TIMESTAMP} 但未声明 NOT NULL），
     * 且**存量 Redis 元素没有这个键**（缓存 key 无版本前缀，10 分钟 TTL 内读到的是老 JSON）
     * ⇒ 前端必须容忍 {@code null}（不显示时间），不能当成"必有值"。
     */
    private LocalDateTime createTime;

    private int likeCount;

    /** 主楼回复总数（= DB {@code reply_count} 列；楼中楼无意义）。T10-B 的信封 total 来源。 */
    private int replyCount;

    /** 楼中楼（仅 replies HASH 值 / 缺省全量路径非 null；roots 元素恒为 null）。 */
    private List<CommentCacheDTO> children;
}
