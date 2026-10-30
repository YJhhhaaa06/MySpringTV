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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 媒体引用清单的校验与落库（创建 / 编辑共享的唯一媒体写入原语）。
 *
 * <h2>两层职责</h2>
 * <ol>
 *   <li>{@link #normalize}：把请求里的 {@link MediaManifest} 校验并归一化成
 *       {@link NormalizedMedia}（类型约束 / URL 形状 / 文件存在 / 跨作品占用 / 视频时长）；</li>
 *   <li>{@link #reconcile}：把"当前 {@code content_media} 行"与"目标清单"做差集，
 *       删缺席、插新增、按数组顺序重写 {@code sort}，并返回**需要清理物理文件**的 URL。</li>
 * </ol>
 *
 * <h2>★ 事务边界</h2>
 * 本类**自身不开事务**：{@code reconcile} 必须在调用方（创建 / 编辑的事务）里执行，
 * 让"内容行 + 媒体行"同进同退；返回的待删 URL 由 Controller 在**提交后**删除，
 * 避免"DB 回滚了、文件却没了"。
 *
 * <h2>★ 为什么 URL 是身份</h2>
 * upload-only 端点产出的文件名是 UUID，上传后不再复用 ⇒ URL 是不可变标识；
 * 清单按 URL 做差集才能区分"换了一张图"与"重排顺序"，而 {@code sort} 是可变量，
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
 *   <li><b>未绑定上传无属主</b>：upload-only 端点只落盘、不记 uploader；未绑定的 URL
 *       只靠 UUID 不可猜 + **需登录**（登录不阻止跨用户认领）。若 URL 泄露，他人可在自己的
 *       清单里认领它；认领后 URL 归其内容，随后删作品会删掉该文件。本仓不做 upload 登记表
 *       （要新增持久化、认领与清理状态）；接对象存储时由存储层的签名 / 生命周期承接这一职责。</li>
 *   <li><b>占用计数没有 url 索引</b>：{@code content_media.url} 无普通索引 ⇒ 每个新 URL
 *       一次全表计数。清单条目为个位数，先接受；有量时应先加 url 索引，而不是批量化。</li>
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

    /**
     * 按整份清单重写某内容的媒体行。
     *
     * <p>差集口径：按 {@code (type, url)} 保留命中行、删除缺席行（含"同 key 的重复历史行"）、
     * 对保留但位置变了的行写目标 {@code sort}、对缺失的目标行插入。
     * 返回的是**旧 URL 中不再被本清单引用**的部分（去重）——调用方提交事务后再删物理文件；
     * 被"换个角色继续使用"的 URL 不会出现在返回值里。
     *
     * @return 需要清理的物理文件 URL（可能为空；顺序稳定但不承诺业务含义）
     */
    public List<String> reconcile(long contentId, ContentType type, MediaManifest manifest) {
        List<ContentMedia> current = contentMediaDao.findMediaByContentId(contentId);
        NormalizedMedia desired = normalize(type, manifest, urlsOf(current), contentId);
        List<DesiredRow> desiredRows = desiredRows(type, desired);
        Set<RowKey> desiredKeys = desiredKeys(desiredRows);
        Set<String> desiredUrls = new LinkedHashSet<>();
        for (DesiredRow row : desiredRows) {
            desiredUrls.add(row.url());
        }

        Map<RowKey, ContentMedia> kept = new LinkedHashMap<>();
        LinkedHashSet<String> removedUrls = new LinkedHashSet<>();
        Set<RowKey> seen = new HashSet<>();

        for (ContentMedia row : current) {
            RowKey key = new RowKey(row.getType(), row.getUrl());
            if (!desiredKeys.contains(key) || !seen.add(key)) {
                // 缺席 ⇒ 删；同 (type,url) 的重复历史行 ⇒ 只留第一行、其余删掉。
                contentMediaDao.deleteMediaById(row.getMediaId());
                // ★ 删物理文件前再查一次"还有没有别人引用"：新清单路径已拒绝跨作品共享，
                //   但历史数据 / 并发窗口仍可能让同一 URL 落在两个内容上；无条件删会打穿对方。
                if (!desiredUrls.contains(row.getUrl())
                        && contentMediaDao.countByUrlExcludingContent(row.getUrl(), contentId) == 0) {
                    removedUrls.add(row.getUrl());
                }
            } else {
                kept.put(key, row);
            }
        }

        for (DesiredRow desiredRow : desiredRows) {
            ContentMedia existing = kept.get(new RowKey(desiredRow.type(), desiredRow.url()));
            if (existing == null) {
                contentMediaDao.addMedia(contentId, desiredRow.url(), desiredRow.type(), desiredRow.sort(),
                        desiredRow.type() == UploadType.VIDEO.getMediaType()
                                ? desired.videoDurationSeconds() : null);
            } else if (!Integer.valueOf(desiredRow.sort()).equals(existing.getSort())) {
                contentMediaDao.updateMediaSort(existing.getMediaId(), desiredRow.sort());
            }
        }

        return List.copyOf(removedUrls);
    }

    // ========================================================================
    // 内部
    // ========================================================================

    /** 内容类型 → 目标行（视频/封面恒 sort=1；图片按清单顺序 1..n）。 */
    private static List<DesiredRow> desiredRows(ContentType type, NormalizedMedia media) {
        List<DesiredRow> rows = new ArrayList<>();
        if (type == ContentType.VIDEO) {
            rows.add(new DesiredRow(UploadType.VIDEO.getMediaType(), media.videoUrl(), 1));
            rows.add(new DesiredRow(UploadType.COVER.getMediaType(), media.coverUrl(), 1));
            return rows;
        }
        if (media.coverUrl() != null) {
            rows.add(new DesiredRow(UploadType.COVER.getMediaType(), media.coverUrl(), 1));
        }
        int sort = 1;
        for (String image : media.imageUrls()) {
            rows.add(new DesiredRow(UploadType.IMAGE.getMediaType(), image, sort++));
        }
        return rows;
    }

    private static Set<RowKey> desiredKeys(List<DesiredRow> rows) {
        Set<RowKey> keys = new HashSet<>();
        for (DesiredRow row : rows) {
            keys.add(new RowKey(row.type(), row.url()));
        }
        return keys;
    }

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

    private record DesiredRow(int type, String url, int sort) {
    }

    private record RowKey(int type, String url) {
    }
}
