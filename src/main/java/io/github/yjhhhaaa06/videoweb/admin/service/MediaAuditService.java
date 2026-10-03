package io.github.yjhhhaaa06.videoweb.admin.service;

import io.github.yjhhhaaa06.videoweb.admin.model.audit.MediaAuditItem;
import io.github.yjhhhaaa06.videoweb.admin.model.audit.MediaAuditResult;
import io.github.yjhhhaaa06.videoweb.admin.model.audit.RestoreResult;
import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentMediaDao;
import io.github.yjhhhaaa06.videoweb.content.model.cache.ContentCacheDTO;
import io.github.yjhhhaaa06.videoweb.content.model.entity.ContentMedia;
import io.github.yjhhhaaa06.videoweb.upload.service.FileUploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 媒体资源运维（承接 TV {@code com.itheima.admin.service.MediaAuditService}，263 行 / 2 处事务）。
 *
 * <h2>职责</h2>
 * <ol>
 *   <li>{@link #scanAll()} —— 全量扫描 {@code content_media} 引用的文件是否存在，
 *       回写 {@code file_exists} / {@code last_verify_time}（媒体行 + 内容级聚合）；</li>
 *   <li>{@link #restoreMedia(long, MultipartFile)} —— 按**数据库原文件名**把上传的文件写回磁盘。</li>
 * </ol>
 *
 * <h2>TV → 新实现</h2>
 * <table>
 *   <caption>对照片段</caption>
 *   <tr><th>TV</th><th>新实现</th></tr>
 *   <tr><td>{@code transactionTemplate.execute(conn -> …)}</td><td>{@code @Transactional}（决策表 §四·S8：两处均 ✅ 保持单事务）</td></tr>
 *   <tr><td>{@code conn} 穿透 DAO</td><td>删——DAO 方法签名不再有 {@code Connection}</td></tr>
 *   <tr><td>{@code catch (SQLException) → ServerException}</td><td>删——{@code DataAccessException} 交 {@code GlobalExceptionHandler}（→500 记堆栈）</td></tr>
 *   <tr><td>{@code AppConfig.getUploadPath()}</td><td>{@code FileUploadService.resolveAbsolutePath(url)}（见 {@link #resolveExpectedPath}）</td></tr>
 *   <tr><td>{@code Part}</td><td>{@code MultipartFile}（{@code transferTo} 只用于"生成文件名"的落盘；这里是"写回固定路径"，故用 {@code Files.copy}）</td></tr>
 *   <tr><td>{@code MediaAuditService} 在 admin 域</td><td>同（本类）</td></tr>
 * </table>
 *
 * <h2>★ 它为什么排在 S8（依赖 S7）</h2>
 * {@code restoreMedia} 写回的**目标路径**必须与 {@code /upload/**} 的静态挂载根一致。
 * 那个根由 S7 引入（{@code video.upload.root} + {@code WebMvcConfig}）；没有它，
 * "恢复"写出的文件对外恒 404。故 S7 必须先于 S8（《切片计划》§〇 的顺序理由 ①）。
 */
@Slf4j
@Service
public class MediaAuditService {

    private final ContentDao contentDao;
    private final ContentMediaDao contentMediaDao;
    private final FileUploadService fileUploadService;

    public MediaAuditService(ContentDao contentDao,
                             ContentMediaDao contentMediaDao,
                             FileUploadService fileUploadService) {
        this.contentDao = contentDao;
        this.contentMediaDao = contentMediaDao;
        this.fileUploadService = fileUploadService;
    }

    // ========================================================================
    // 扫描（决策表 §四·S8：✅ 保持单事务）
    // ========================================================================

    /**
     * 全量扫描并回写 {@code file_exists} / {@code last_verify_time}。
     *
     * <h2>★ 决策表 §四·S8：✅ 保持单事务</h2>
     * 一次扫描要对**每一条**媒体行与**每一条**内容行各发一条 UPDATE。这些 UPDATE 是一次
     * "世界快照"的写入，必须同进同退：若中途失败（DB 抖动 / 进程被杀）而前一半已提交，
     * 库里就会留下**半轮扫描**的印记——部分行的 {@code last_verify_time} 是本次，
     * 部分还是上次，而 {@code file_exists} 的口径已经不自洽（无法判断哪一半可信）。
     * 回滚则回到扫描前的自洽状态（全部是上次的快照）。故用 {@code @Transactional}。
     *
     * <h2>逐条口径（TV 原样保留）</h2>
     * <ul>
     *   <li><b>URL 非法</b>（不匹配 {@code /upload/{dir}/{file}} 或含 {@code ..}）⇒
     *       {@code status=INVALID_URL}、{@code exists=false}（计入 {@code invalid}）；</li>
     *   <li><b>文件在盘上</b> ⇒ {@code EXISTS}；否则 {@code MISSING}；</li>
     *   <li><b>孤儿媒体</b>（{@code content_id} 不在 {@code content} 表里）⇒ 收进
     *       {@code orphanMediaIds}；它**不参与**内容级聚合（没有内容行可回写）；</li>
     *   <li><b>内容级聚合</b>：该内容的**全部**媒体行都存在 ⇒ {@code ok=true}；
     *       <b>没有任何媒体行</b> ⇒ 视为完整（{@code ok=true}，纯文字内容）并收进
     *       {@code contentsWithoutMedia}。即"内容完整"⟺"它引用的文件都在"。</li>
     *   <li>{@code notScanned} 恒为 0（TV 的"分批未扫数"字段；本实现一次扫完，字段保留在契约里）。</li>
     * </ul>
     *
     * @return 汇总结果（含 8 个契约键与每条媒体明细）
     */
    @Transactional
    public MediaAuditResult scanAll() {
        LocalDateTime now = LocalDateTime.now();
        Timestamp ts = Timestamp.valueOf(now);

        List<ContentMedia> mediaList = contentMediaDao.findAllMedia();
        List<ContentCacheDTO> contents = contentDao.findAllContent();
        Map<Long, String> titleMap = new HashMap<>();
        Set<Long> contentIdSet = new HashSet<>();
        for (ContentCacheDTO content : contents) {
            titleMap.put(content.getId(), content.getTitle());
            contentIdSet.add(content.getId());
        }

        List<MediaAuditItem> items = new ArrayList<>();
        Map<Long, List<Boolean>> mediaFlagsByContent = new HashMap<>();
        List<Long> orphanMediaIds = new ArrayList<>();
        int existing = 0;
        int missing = 0;
        int invalid = 0;

        for (ContentMedia media : mediaList) {
            String expectedPath = resolveExpectedPath(media.getUrl());
            boolean exists = expectedPath != null && new File(expectedPath).isFile();
            String status;
            if (expectedPath == null) {
                status = "INVALID_URL";
                invalid++;
            } else if (exists) {
                status = "EXISTS";
                existing++;
            } else {
                status = "MISSING";
                missing++;
            }

            contentMediaDao.updateFileExists(media.getMediaId(), exists, ts);

            MediaAuditItem item = new MediaAuditItem();
            item.setMediaId(media.getMediaId());
            item.setContentId(media.getContentId());
            item.setContentTitle(titleMap.get(media.getContentId()));
            item.setType(media.getType());
            item.setUrl(media.getUrl());
            item.setFileExists(exists);
            item.setLastVerifyTime(now);
            item.setExpectedPath(expectedPath);
            item.setStatus(status);
            items.add(item);

            mediaFlagsByContent.computeIfAbsent(media.getContentId(), key -> new ArrayList<>()).add(exists);
            if (!contentIdSet.contains(media.getContentId())) {
                orphanMediaIds.add(media.getMediaId());
            }
        }

        List<Long> contentsWithoutMedia = new ArrayList<>();
        for (Long contentId : contentIdSet) {
            List<Boolean> flags = mediaFlagsByContent.get(contentId);
            boolean ok;
            if (flags == null || flags.isEmpty()) {
                contentsWithoutMedia.add(contentId);
                ok = true; // 纯文字内容视为完整
            } else {
                ok = true;
                for (Boolean flag : flags) {
                    if (!flag) {
                        ok = false;
                        break;
                    }
                }
            }
            contentDao.updateFileExists(contentId, ok, ts);
        }

        MediaAuditResult result = new MediaAuditResult();
        result.setScanTime(now);
        result.setTotal(mediaList.size());
        result.setExisting(existing);
        result.setMissing(missing);
        result.setInvalid(invalid);
        result.setNotScanned(0);
        result.setOrphanMediaIds(orphanMediaIds);
        result.setContentsWithoutMedia(contentsWithoutMedia);
        result.setItems(items);
        return result;
    }

    // ========================================================================
    // 恢复（决策表 §四·S8：✅ 保持单事务）
    // ========================================================================

    /**
     * 按数据库原文件名重新上传写回（{@code POST /api/admin/media/restore}）。
     *
     * <h2>校验顺序（逐条保留——决定 400/404/400）</h2>
     * <pre>
     * ① part 为空或 0 字节        → 400「请选择要恢复的文件」（**在事务之外**，与 TV 同行序）
     * ② mediaId 查不到            → 404「媒体资源不存在: mediaId=…」
     * ③ url 反解不出磁盘路径      → 400「媒体 URL 不合法，无法定位文件: …」
     * ④ 扩展名与目标不一致        → 400「文件扩展名不匹配，目标需要 .…」
     * </pre>
     *
     * <h2>★ 决策表 §四·S8：✅ 保持单事务，且**写文件在事务内**（有意保持旧口径）</h2>
     * 事务内做四件事：写文件 → 回写媒体行 {@code file_exists=1} → 重算内容级聚合 →（返回）。
     * ⚠️ <b>文件系统与 DB 没有共同事务</b>：DB 回滚时**已写入的文件会残留**——
     * 这与 S7 的"落盘与提交无法原子"是同一族问题，TV 亦然，故**保持不改进**
     * （引入补偿删除会把"管理员手工恢复"变成有副作用的复杂流程，收益不足）。
     * 顺序（先写文件、后写 DB）也是刻意的：反过来会出现"DB 说文件存在、盘上却没有"的更坏状态。
     *
     * <h2>⚠️ 一处必须注意的实现细节：为什么不是 {@code catch (IOException) → throws IOException}</h2>
     * Spring 的默认回滚规则**只覆盖 {@code RuntimeException} 与 {@code Error}**——
     * 让受检的 {@code IOException} 逃出去，事务会**照常提交**（写了一半的 DB 改动被固化）。
     * 故这里把 IO 故障包成 {@link UncheckedIOException}（运行时异常）再抛：
     * 既触发回滚，又交 {@code GlobalExceptionHandler} 的兜底分支出 500。
     * 这是"声明式机制静默失效"的另一处变体（SOP §2.5），已在 {@code AdminTransactionTests} 钉住。
     *
     * @param mediaId {@code content_media.id}
     * @param part    上传的文件（文件名决定扩展名，扩展名必须与目标一致）
     * @return 写回结果（含磁盘绝对路径与字节数）
     */
    @Transactional
    public RestoreResult restoreMedia(long mediaId, MultipartFile part) {
        if (part == null || part.getSize() <= 0) {
            throw new ParamException("请选择要恢复的文件");
        }

        ContentMedia media = contentMediaDao.findMediaById(mediaId);
        if (media == null) {
            throw new NotFoundException("媒体资源不存在: mediaId=" + mediaId);
        }

        String expectedPath = resolveExpectedPath(media.getUrl());
        if (expectedPath == null) {
            throw new ParamException("媒体 URL 不合法，无法定位文件: " + media.getUrl());
        }

        String targetExt = extensionOf(new File(expectedPath).getName());
        String submittedExt = extensionOf(part.getOriginalFilename());
        if (submittedExt == null || !submittedExt.equalsIgnoreCase(targetExt)) {
            throw new ParamException("文件扩展名不匹配，目标需要 ." + targetExt);
        }

        File target = new File(expectedPath);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try {
            Files.copy(part.getInputStream(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("媒体恢复写入失败, mediaId={}", mediaId, e);
            throw new UncheckedIOException("文件写入失败", e);
        }

        LocalDateTime now = LocalDateTime.now();
        Timestamp ts = Timestamp.valueOf(now);
        contentMediaDao.updateFileExists(media.getMediaId(), true, ts);
        refreshContentAggregate(media.getContentId(), ts);

        RestoreResult result = new RestoreResult();
        result.setMediaId(media.getMediaId());
        result.setUrl(media.getUrl());
        result.setTargetPath(target.getAbsolutePath().replace('\\', '/'));
        result.setSize(target.length());
        result.setFileExists(true);
        return result;
    }

    /**
     * 恢复某条媒体后，重算它所属内容的**文件完整性聚合**。
     *
     * <p>TV {@code refreshContentAggregate} 逐条保留：
     * <ul>
     *   <li><b>孤儿媒体直接返回</b>——没有内容行可回写（{@code isContentExist} 判 {@code is_deleted = 0}，
     *       已软删的内容同样跳过）；</li>
     *   <li>该内容**没有任何媒体行** ⇒ 视为完整（{@code !hasMedia || ok}，与 {@link #scanAll} 同口径）；</li>
     *   <li>逐条解析 url → 路径并判存在性，**只要有一条不存在**就 {@code ok=false}。</li>
     * </ul>
     */
    private void refreshContentAggregate(long contentId, Timestamp ts) {
        if (!contentDao.isContentExist(contentId)) {
            return; // 孤儿媒体，不更新 content
        }
        List<ContentMedia> rows = contentMediaDao.findMediaByContentId(contentId);
        boolean hasMedia = false;
        boolean ok = true;
        for (ContentMedia media : rows) {
            hasMedia = true;
            String path = resolveExpectedPath(media.getUrl());
            if (path == null || !new File(path).isFile()) {
                ok = false;
                break;
            }
        }
        contentDao.updateFileExists(contentId, !hasMedia || ok, ts);
    }

    /**
     * URL → 期望的磁盘绝对路径（{@code null} = URL 非法）。
     *
     * <h2>★ 为什么委托给 {@link FileUploadService}</h2>
     * TV 在这里自带一份 {@code MEDIA_URL_PATTERN} 正则（与 {@code FileUploadService} 里那份**重复**）。
     * {@code /upload/{dir}/{file}} 的形状是**上传域的所有物**——{@code saveFile} 造它、
     * {@code deleteFileByUrl} 解它、{@code WebMvcConfig} 挂它。两处各写一份正则必然漂移，
     * 且 {@code ".."} 逃逸守卫也只该有一处。故本方法只做**转发**
     * （《决策留痕表》B-19）；文件层尚未迁移时 TV 只能重复实现，本仓不必。
     */
    private String resolveExpectedPath(String url) {
        return fileUploadService.resolveAbsolutePath(url);
    }

    /** 取**最后一个点起**的扩展名（小写）；无点 / 以点结尾 / null ⇒ {@code null}（TV 逐字）。 */
    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase();
    }
}
