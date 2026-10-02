package io.github.yjhhhaaa06.videoweb.content.model.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.yjhhhaaa06.videoweb.content.model.cache.ContentCacheDTO;

import java.util.List;

/**
 * 内容**列表条目**（承接 TV {@code ContentVO}）。
 *
 * <h2>相对父类的两个差异（TV 原样，逐条保留）</h2>
 * <ol>
 *   <li>加 {@code isLiked} / {@code isFollowed}——列表页的"我点过赞吗 / 我关注了作者吗"；</li>
 *   <li>用 {@code @JsonIgnore} 屏蔽 {@code videoUrl} / {@code imageUrls}——
 *       **列表不带媒体地址**（TV 的设计：列表只需要封面，媒体地址属详情）</li>
 * </ol>
 * 于是对外 JSON 的键集 = 父类键集 − {videoUrl, imageUrls} + {isLiked, isFollowed}，
 * 即 {@code {id, authorId, type, title, description, categoryId, commentCount, likeCount,
 * commentEnabled, authorName, coverUrl, createTime, isLiked, isFollowed}}。
 * **这是冻结契约**（旧 pytest 对 {@code authorName} 有逐字断言；缺/多字段都算回归）。
 *
 * <h2>⚠️ 为什么手写 getter 而不挂 Lombok {@code @Getter}</h2>
 * 字段叫 {@code isLiked}，Lombok 会生成 {@code isLiked()} ⇒ Jackson 推导出属性名
 * <b>{@code liked}</b>；而 TV 的属性名是 <b>{@code isLiked}</b>（来自 {@code getIsLiked()}）。
 * 两者**同时存在**时 Jackson 会输出**两个**字段（{@code liked} 与 {@code isLiked}）。
 * 故这里显式写 {@code getIsLiked()} / {@code setIsLiked()}，把命名钉死。
 */
public class ContentVO extends ContentCacheDTO {

    private boolean isLiked;
    private boolean isFollowed;

    public ContentVO() {
    }

    public boolean getIsLiked() {
        return isLiked;
    }

    public void setIsLiked(boolean isLiked) {
        this.isLiked = isLiked;
    }

    public boolean getIsFollowed() {
        return isFollowed;
    }

    public void setIsFollowed(boolean isFollowed) {
        this.isFollowed = isFollowed;
    }

    /** 列表不返回视频地址（TV {@code @JsonIgnore} 原样）。 */
    @Override
    @JsonIgnore
    public String getVideoUrl() {
        return super.getVideoUrl();
    }

    /** 列表不返回图片地址组（TV {@code @JsonIgnore} 原样）。 */
    @Override
    @JsonIgnore
    public List<String> getImageUrls() {
        return super.getImageUrls();
    }
}
