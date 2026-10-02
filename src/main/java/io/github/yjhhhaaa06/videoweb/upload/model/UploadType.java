package io.github.yjhhhaaa06.videoweb.upload.model;

import java.util.List;

/**
 * 上传类型（承接 TV {@code com.itheima.upload.controller.UploadType}）。
 *
 * <p>三类：视频 / 图片 / 封面。每类带三件套：**磁盘子目录**（{@code dir}）、
 * **合法扩展名**（{@code suffixes}）、**{@code content_media.type} 取值**（{@code mediaType}）。
 *
 * <table>
 *   <caption>枚举口径（TV 原样，逐字保留）</caption>
 *   <tr><th>枚举</th><th>dir</th><th>suffixes</th><th>mediaType</th></tr>
 *   <tr><td>{@code VIDEO}</td><td>{@code video}</td><td>.mp4</td><td>1</td></tr>
 *   <tr><td>{@code IMAGE}</td><td>{@code image}</td><td>.jpg / .png / .jpeg</td><td>2</td></tr>
 *   <tr><td>{@code COVER}</td><td>{@code cover}</td><td>.jpg / .png</td><td>3</td></tr>
 * </table>
 * ⚠️ 注意 {@code COVER} 的 {@code dir} 是 {@code cover}（不是 image）——URL 形如
 * {@code /upload/cover/xxx.png}，与 {@code FileUploadService.UPLOAD_URL_PATTERN} 的
 * {@code (video|image|cover)} 三选一对应。**别把 dir 与 contentTypePrefix 混为一谈**
 * （旧实现里 COVER 的 contentTypePrefix 是 {@code image/}，那是给"按 contentType 猜类型"用的）。
 *
 * <h2>★ S7 只搬"有调用方"的成员（"不搬无主代码"纪律）</h2>
 * 旧枚举另有 {@code contentTypePrefix} 字段 + {@code fromContentType()} / {@code fromFileName()} /
 * {@code getContentTypePrefix()} / {@code getSuffixes()} 四个成员——实测**全部无调用方**
 * （旧 {@code FileUploadService.validate} 只用 {@code isSuffixValid}；上传类型一律由端点显式指定，
 * 从不"按 contentType 猜"）。故不搬，避免把一段从未运行的代码带进新仓。
 */
public enum UploadType {

    /** 视频：{@code .mp4}，媒体类型 1。 */
    VIDEO("video", List.of(".mp4"), 1),

    /** 图文正文图片：{@code .jpg/.png/.jpeg}，媒体类型 2。 */
    IMAGE("image", List.of(".jpg", ".png", ".jpeg"), 2),

    /** 封面：{@code .jpg/.png}，媒体类型 3。 */
    COVER("cover", List.of(".jpg", ".png"), 3);

    private final String dir;
    private final List<String> suffixes;
    private final int mediaType;

    UploadType(String dir, List<String> suffixes, int mediaType) {
        this.dir = dir;
        this.suffixes = suffixes;
        this.mediaType = mediaType;
    }

    /**
     * 文件名扩展名是否合法（**不区分大小写**）。
     *
     * <p>TV 逻辑逐字保留：先防"没有 {@code .} 或 {@code .} 在末尾"，再取**最后一个**点的子串小写比较。
     *
     * @param fileName 原始文件名（{@code MultipartFile.getOriginalFilename()}）；
     *                 {@code null} 直接为 false（配合 {@code contentType == null} 的判断）
     */
    public boolean isSuffixValid(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex == -1 || dotIndex == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(dotIndex).toLowerCase();
        return suffixes.contains(ext);
    }

    /**
     * 按 {@code content_media.type}（1 视频 / 2 图片 / 3 封面）映射回上传类型，用于作者换源。
     * 无匹配返回 {@code null}（调用方抛 {@code ParamException("type 不合法，应为 1/2/3")}）。
     */
    public static UploadType fromMediaType(int mediaType) {
        for (UploadType type : values()) {
            if (type.mediaType == mediaType) {
                return type;
            }
        }
        return null;
    }

    /** 磁盘子目录名（拼落盘路径与 URL 都用它）。 */
    public String getDir() {
        return dir;
    }

    /** {@code content_media.type} 取值。 */
    public int getMediaType() {
        return mediaType;
    }
}