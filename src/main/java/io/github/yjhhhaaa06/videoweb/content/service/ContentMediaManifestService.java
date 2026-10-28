package io.github.yjhhhaaa06.videoweb.content.service;

import io.github.yjhhhaaa06.videoweb.common.config.MediaProperties;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentMediaDao;
import io.github.yjhhhaaa06.videoweb.content.model.ContentType;
import io.github.yjhhhaaa06.videoweb.content.model.dto.MediaManifest;
import io.github.yjhhhaaa06.videoweb.content.model.entity.ContentMedia;
import io.github.yjhhhaaa06.videoweb.upload.model.UploadType;
import io.github.yjhhhaaa06.videoweb.upload.service.FileUploadService;
import io.github.yjhhhaaa06.videoweb.upload.service.Mp4DurationReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 媒体引用清单的校验（创建 / 编辑共享的唯一归一化入口）。
 *
 * <h2>职责</h2>
 * {@link #normalize} 把请求里的 {@link MediaManifest} 校验并归一化成
 * {@link NormalizedMedia}：类型约束（视频必须有视频 + 封面、图文不能带视频）、
 * URL 形状、文件真实存在、跨作品占用、清单内重复、视频时长。
 *
 * <h2>★ 事务边界</h2>
 * 本类**自身不开事务**：调用方（创建 / 编辑的事务）负责事务边界，
 * 让"内容行 + 媒体行"同进同退.
 *
 * <h2>★ 为什么 URL 是身份</h2>
 * upload-only 端点产出的文件名是 UUID，上传后不再复用 ⇒ URL 是不可变标识；
 * 编辑侧的差集必须按 URL 才能区分"换了一张图"与"重排顺序"，而 {@code sort} 是可变量，
 * 拿它当身份会让重试 / 并发改到别的行（{@code T63} 的同一结论）。
 *
 * <h2>已知边界（刻意不做）</h2>
 * <ul>
 *   <li><b>跨作品占用的竞态</b>：{@code countByUrlExcludingContent} 与插入之间不是原子的，
 *       两个请求同时首次引用同一 URL 的窗口仍在；{@code content_media} 没有 url 唯一索引。
 *       本仓无并发投稿口径，先记录、不加锁。</li>
 *   <li><b>审计列不冒充扫描器</b>：新插入的媒体行沿用 {@code addMedia} 的默认
 *       {@code file_exists=0}（"缺失/未扫描"），内容级聚合也不改写——{@code file_exists}
 *       的唯一批量修复入口仍是媒体扫描。此处只是"不谎报已扫描"。</li>
 *   <li><b>孤儿文件</b>：上传后未提交的文件不可能在事务里回收（它还没被引用过），
 *       由离线清理脚本按"磁盘存在、库内无引用"处理。</li>
 * </ul>
 */
@Slf4j
@Service
public class ContentMediaManifestService {

    private final ContentMediaDao contentMediaDao;
    private final FileUploadService fileUploadService;
    private final MediaProperties mediaProps;

    public ContentMediaManifestService(ContentMediaDao contentMediaDao,
                                       FileUploadService fileUploadService,
                                       MediaProperties mediaProps) {
        this.contentMediaDao = contentMediaDao;
        this.fileUploadService = fileUploadService;
        this.mediaProps = mediaProps;
    }

    /** 归一化后的目标清单（URL 均已脱前缀、类型已校验）。 */
    public record NormalizedMedia(String videoUrl,
                                  String coverUrl,
                                  List<String> imageUrls,
                                  Integer videoDurationSeconds) {
    }

    /**
     * 校验并归一化清单。
     *
     * @param type            内容类型（决定"必须有什么 / 不能有什么"）
     * @param manifest        请求里的清单；{@code null} 视为参数错误（创建时必填）
     * @param boundContentId  编辑时传内容 id（已绑定到**本内容**的 URL 跳过"文件存在 / 跨作品"检查，
     *                        以支持"文件已丢但仍可改标题"）；创建传 {@code null}
     * @throws ParamException 任一约束不通过（400）
     */
    public NormalizedMedia normalize(ContentType type, MediaManifest manifest, Long boundContentId) {
        Set<String> boundUrls = boundContentId == null
                ? Set.of()
                : urlsOf(contentMediaDao.findMediaByContentId(boundContentId));
        long excluded = boundContentId == null ? -1L : boundContentId;
        return normalize(type, manifest, boundUrls, excluded);
    }

    /**
     * 已持有当前行的调用点（{@link #reconcile}）直接传入已绑定 URL 集合，避免重复装载。
     *
     * @param excludedContentId 跨作品占用统计要排除的内容 id（创建传 {@code -1}）
     */
    private NormalizedMedia normalize(ContentType type, MediaManifest manifest,
                                      Set<String> boundUrls, long excludedContentId) {
        if (manifest == null) {
            throw new ParamException(ErrorCode.PARAM_INVALID, "媒体清单不能为空");
        }

        String video = clean(manifest.videoUrl());
        String cover = clean(manifest.coverUrl());
        List<String> images = new ArrayList<>(manifest.imageUrls().size());
        for (String raw : manifest.imageUrls()) {
            String url = clean(raw);
            if (url == null) {
                throw new ParamException(ErrorCode.PARAM_INVALID, "图片地址不能为空");
            }
            images.add(url);
        }

        if (type == ContentType.VIDEO) {
            if (video == null) {
                throw new ParamException(ErrorCode.PARAM_INVALID, "请选择视频文件");
            }
            if (cover == null) {
                throw new ParamException(ErrorCode.PARAM_INVALID, "请选择封面图片");
            }
            if (!images.isEmpty()) {
                throw new ParamException(ErrorCode.PARAM_INVALID, "视频不能带图片");
            }
        } else {
            if (video != null) {
                throw new ParamException(ErrorCode.PARAM_INVALID, "图文不能带视频");
            }
        }

        requireType(video, UploadType.VIDEO, "视频地址不合法");
        requireType(cover, UploadType.COVER, "封面地址不合法");
        for (String image : images) {
            requireType(image, UploadType.IMAGE, "图片地址不合法");
        }

        // 整份清单内不允许同一 URL 出现两次（含跨角色）：否则删旧文件时无法判断该删哪一份。
        LinkedHashSet<String> allUrls = new LinkedHashSet<>();
        addUnique(allUrls, video);
        addUnique(allUrls, cover);
        for (String image : images) {
            addUnique(allUrls, image);
        }

        for (String url : allUrls) {
            if (boundUrls.contains(url)) {
                // 已属于本内容：文件可能早已丢失，但"改标题 / 换封面"不应因此被卡住。
                continue;
            }
            if (!fileUploadService.exists(url)) {
                throw new ParamException(ErrorCode.PARAM_INVALID, "媒体文件不存在");
            }
            if (contentMediaDao.countByUrlExcludingContent(url, excludedContentId) > 0) {
                throw new ParamException(ErrorCode.PARAM_INVALID, "媒体资源已被其他内容引用");
            }
        }

        // 只有"新视频"才解析时长：未换源的编辑不必每次重读文件头（旧时长保持不变）。
        boolean videoIsNew = video != null && !boundUrls.contains(video);
        Integer duration = videoIsNew ? readDuration(video) : null;
        return new NormalizedMedia(video, cover, List.copyOf(images), duration);
    }

    // ========================================================================
    // 内部
    // ========================================================================

    private static void addUnique(Set<String> urls, String url) {
        if (url != null && !urls.add(url)) {
            throw new ParamException(ErrorCode.PARAM_INVALID, "媒体资源重复");
        }
    }

    private static Set<String> urlsOf(List<ContentMedia> rows) {
        Set<String> urls = new HashSet<>();
        for (ContentMedia row : rows) {
            urls.add(row.getUrl());
        }
        return urls;
    }

    private void requireType(String url, UploadType expected, String message) {
        if (url == null) {
            return;
        }
        if (fileUploadService.typeOfUrl(url) != expected) {
            throw new ParamException(ErrorCode.PARAM_INVALID, message);
        }
    }

    /** 去首尾空白 + 剥掉对外前缀；空白串与 {@code null} 同义（该角色为空）。 */
    private String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return mediaProps.stripBaseUrl(trimmed);
    }

    /**
     * 解析视频时长。任何失败都返回 {@code null}（未知 ≠ 0），绝不阻断提交
     * （时长只服务"进度百分比"，见 {@code architecture/domain/history/history.md} §六.2）。
     */
    private Integer readDuration(String url) {
        String absolute = fileUploadService.resolveAbsolutePath(url);
        if (absolute == null) {
            return null;
        }
        try {
            OptionalInt seconds = Mp4DurationReader.readSeconds(Path.of(absolute));
            return seconds.isPresent() ? seconds.getAsInt() : null;
        } catch (RuntimeException e) {
            log.warn("清单提交：视频时长解析入口异常，按未知处理: url={}", url, e);
            return null;
        }
    }

}
