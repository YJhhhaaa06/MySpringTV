package io.github.yjhhhaaa06.videoweb.content.model;

/**
 * 内容类型（承接 TV {@code com.itheima.content.model.command.ContentType}）。
 *
 * <p>只有两个取值，写进 {@code content.type}：{@code 1 = 视频}、{@code 2 = 图文}。
 * 发布端点据此区分（{@code /api/upload/video} 与 {@code /api/upload/post}），
 * SQL 不参与判定。
 */
public enum ContentType {

    /** 视频作品。 */
    VIDEO(1),

    /** 图文（帖子）。 */
    POST(2);

    private final int typeNumber;

    ContentType(int typeNumber) {
        this.typeNumber = typeNumber;
    }

    /** {@code content.type} 取值。 */
    public int getTypeNumber() {
        return typeNumber;
    }
}