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

    /**
     * {@code content.type} 列值 → 枚举；未知值返回 {@code null}。
     *
     * <p>从 DB 行/缓存载荷反向映射时用（如编辑根据"已存在的内容类型"选简介上限）。
     * 调用方拿到 {@code null} 说明数据越过了媒体装配的校验，应按"不可用"处理，不要静默当 POST。
     */
    public static ContentType fromTypeNumber(int typeNumber) {
        for (ContentType type : values()) {
            if (type.typeNumber == typeNumber) {
                return type;
            }
        }
        return null;
    }
}