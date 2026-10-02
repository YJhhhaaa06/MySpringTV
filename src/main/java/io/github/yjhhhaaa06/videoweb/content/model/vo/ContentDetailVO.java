package io.github.yjhhhaaa06.videoweb.content.model.vo;

import io.github.yjhhhaaa06.videoweb.content.model.cache.ContentCacheDTO;

/**
 * 内容**详情**（承接 TV {@code ContentDetailVO}）。
 *
 * <p>与父类（{@link ContentCacheDTO}）的差异只有两个字段：{@code isLiked} / {@code isFollowed}。
 * **媒体字段（videoUrl / imageUrls）在详情里是要返回的**——这正是它与
 * {@link ContentVO}（列表，屏蔽媒体）的区别。
 *
 * <p>对外 JSON 的键集 = 父类全部键 + {@code isLiked} / {@code isFollowed}。
 * 旧 pytest {@code S-06}/{@code S-08} 断言 {@code title} 存在、且
 * {@code videoUrl} 或 {@code imageUrls} 至少有一个存在（按 type 决定）——已固化为测试。
 *
 * <h2>⚠️ 手写 getter 的理由同 {@link ContentVO}</h2>
 * Lombok 对 {@code boolean isLiked} 生成 {@code isLiked()} ⇒ 属性名会变成 {@code liked}，
 * 与 TV 的 {@code isLiked} 不符。
 */
public class ContentDetailVO extends ContentCacheDTO {

    private boolean isLiked;
    private boolean isFollowed;

    public ContentDetailVO() {
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
}
