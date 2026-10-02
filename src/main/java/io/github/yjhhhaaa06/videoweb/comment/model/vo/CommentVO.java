package io.github.yjhhhaaa06.videoweb.comment.model.vo;

import io.github.yjhhhaaa06.videoweb.comment.model.cache.CommentCacheDTO;

/**
 * 评论对外视图（承接 TV {@code com.itheima.content.model.vo.CommentVO}）。
 *
 * <p>与父类（{@link CommentCacheDTO}）的唯一差异是 {@code isLiked}（当前用户是否点过这条赞）。
 *
 * <h2>★ 为什么手写 getter（不要挂 Lombok {@code @Getter}）</h2>
 * 字段叫 {@code isLiked}，Lombok 会生成 {@code isLiked()} ⇒ Jackson 推导出的属性名是
 * <b>{@code liked}</b>；而 TV 的属性名是 <b>{@code isLiked}</b>（来自 {@code getIsLiked()}）。
 * 两者同时存在时 Jackson 甚至会输出两个字段。故显式写 {@code getIsLiked()} / {@code setIsLiked()}
 * 把命名钉死（同 {@code ContentVO} / {@code ContentDetailVO}）。
 *
 * <h2>构造器与 {@code convertToCommentVO} 的关系</h2>
 * TV 的 {@code convertToCommentVO} 用 TV 版构造器填 7 个字段再 setter 补其余。
 * 本实现保留同一个构造器（便于与 TV 逐行对照），但转换逻辑改为"复制全部字段 + 递归 children"
 * （见 {@code CommentService.convertToCommentVO}）——因为 {@code replyCount} / {@code children} /
 * {@code replyToUserId} 这些字段 TV 是**分两处**填的，漏一处就会静默丢字段。
 */
public class CommentVO extends CommentCacheDTO {

    private boolean isLiked;

    public CommentVO() {
        super();
    }

    /** 承接 TV 的 7 参构造器（字段名对应 {@code CommentCacheDTO}）。 */
    public CommentVO(String username, long commentId, long contentId, long userId,
                     String content, Long parentId, int likeCount) {
        setUsername(username);
        setCommentId(commentId);
        setContentId(contentId);
        setUserId(userId);
        setContent(content);
        setParentId(parentId);
        setLikeCount(likeCount);
    }

    public boolean getIsLiked() {
        return isLiked;
    }

    public void setIsLiked(boolean isLiked) {
        this.isLiked = isLiked;
    }
}
