package io.github.yjhhhaaa06.videoweb.favorite.service;

import io.github.yjhhhaaa06.videoweb.common.exception.BusinessException;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.exception.ForbiddenException;
import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.config.MediaProperties;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import io.github.yjhhhaaa06.videoweb.favorite.dao.FavoriteFolderDao;
import io.github.yjhhhaaa06.videoweb.favorite.dao.FavoriteItemDao;
import io.github.yjhhhaaa06.videoweb.favorite.model.FolderLatestContent;
import io.github.yjhhhaaa06.videoweb.favorite.model.entity.FavoriteFolder;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteFolderVO;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.PublicFavoriteFolderVO;
import io.github.yjhhhaaa06.videoweb.upload.model.UploadResult;
import io.github.yjhhhaaa06.videoweb.upload.model.UploadType;
import io.github.yjhhhaaa06.videoweb.upload.service.FileUploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 收藏夹封面（{@code R-33}）：读取时解析 + 自定义封面的上传 / 恢复 / 旧文件清理。
 *
 * <h2>三态互斥（本类的唯一语义）</h2>
 * <pre>
 *   cover_ref != NULL                      ⇒ 自定义：coverUrl = jointUrl(cover_ref)
 *   cover_ref == NULL 且最新一条有封面      ⇒ 自动：coverUrl = 该内容的 coverUrl
 *   cover_ref == NULL 且最新一条取不到/无封面 ⇒ 占位：coverUrl = NULL（前端灰底 TV）
 * </pre>
 * ★ <b>自动封面 = 夹内最新一条收藏</b>（视频或图文都算），由
 * {@link FavoriteItemDao#findLatestContentIdByFolderIds} 取 id、再经
 * {@link ContentService#loadContentVOs} 取 URL —— 收藏域 SQL **不 JOIN content 表**（I-07 ③），
 * "能不能装出来"由 content 装载契约统一回答（已删 / 下架 / 媒体损坏 ⇒ 取不到 ⇒ 占位）。
 *
 * <h2>★ 不缓存、不回退</h2>
 * folders 本来就不缓存（见 {@code favorite.md} §七）；封面在每次列表读取时现算。
 * 最新一条失效时**直接占位**，不向前找上一条可用收藏 —— 那是 R-33 的显式取舍
 * （对齐 B 站"最新视频被下架 ⇒ 视频不见了封面"）。
 *
 * <h2>★ 文件与 DB 无法原子（照 {@code AvatarService} 的口径）</h2>
 * 上传 = 先落盘 `/upload/favorite-cover/` → 再写 `cover_ref` → 最后删旧文件。
 * DB 失败就删本次新文件；DB 成功后删旧文件（尽力而为）。并发上传按 LWW 处理，
 * 先到者写入的新文件可能成为孤儿；进程在"已落盘、未提交"之间崩溃同样留孤儿
 * （R-33 已登记代价，回收不在本窗）。
 */
@Slf4j
@Service
public class FavoriteFolderCoverService {

    /** 服务端硬上限：前端裁剪产物通常 <300KB；2MB 是防"直接调 API 传大图"的边界。 */
    private static final long MAX_COVER_BYTES = 2L * 1024 * 1024;

    /** 只有这个前缀下的文件才是本业务拥有的旧封面；其它 URL 永不删。 */
    private static final String CUSTOM_COVER_PREFIX = "/upload/favorite-cover/";

    private final FavoriteFolderDao folderDao;
    private final FavoriteItemDao itemDao;
    private final ContentService contentService;
    private final FileUploadService fileUploadService;
    /** 对外媒体 URL 前缀：与 content 的 `coverUrl` 同一套（`video.media.base-url`，默认空串）。 */
    private final MediaProperties mediaProperties;

    public FavoriteFolderCoverService(FavoriteFolderDao folderDao,
                                      FavoriteItemDao itemDao,
                                      ContentService contentService,
                                      FileUploadService fileUploadService,
                                      MediaProperties mediaProperties) {
        this.folderDao = folderDao;
        this.itemDao = itemDao;
        this.contentService = contentService;
        this.fileUploadService = fileUploadService;
        this.mediaProperties = mediaProperties;
    }

    // ========================================================================
    // 读路径：把生效 coverUrl / hasCustomCover 填进行
    // ========================================================================

    /**
     * 填「我的收藏夹」列表（含私密）：自定义优先，否则自动，取不到即 {@code null}。
     *
     * <p>就地修改入参（VO 一身两职：Mapper 行 + API 形状，与 {@code itemCount} 同款）；
     * 调用方是 {@code FavoriteService#listMyFolders}。空列表 / 全自定义 ⇒ 不查库。
     */
    public void fillMyCovers(List<FavoriteFolderVO> folders) {
        if (folders == null || folders.isEmpty()) {
            return;
        }
        Set<Long> needAuto = new LinkedHashSet<>();
        for (FavoriteFolderVO folder : folders) {
            if (!hasCustom(folder.getCoverRef())) {
                needAuto.add(folder.getId());
            }
        }
        Map<Long, String> autoCovers = resolveAutoCovers(needAuto);
        for (FavoriteFolderVO folder : folders) {
            String custom = folder.getCoverRef();
            if (hasCustom(custom)) {
                folder.setCoverUrl(jointUrl(custom));
                folder.setHasCustomCover(Boolean.TRUE);
            } else {
                folder.setCoverUrl(autoCovers.get(folder.getId()));
                folder.setHasCustomCover(Boolean.FALSE);
            }
        }
    }

    /**
     * 填「他人公开收藏夹」列表：只回带生效 {@code coverUrl}（公开面不需要
     * {@code hasCustomCover} / {@code coverRef}）。调用方是 {@code FavoriteService#listPublicFolders}。
     */
    public void fillPublicCovers(List<PublicFavoriteFolderVO> folders) {
        if (folders == null || folders.isEmpty()) {
            return;
        }
        Set<Long> needAuto = new LinkedHashSet<>();
        for (PublicFavoriteFolderVO folder : folders) {
            if (!hasCustom(folder.getCoverRef())) {
                needAuto.add(folder.getId());
            }
        }
        Map<Long, String> autoCovers = resolveAutoCovers(needAuto);
        for (PublicFavoriteFolderVO folder : folders) {
            String custom = folder.getCoverRef();
            folder.setCoverUrl(hasCustom(custom) ? jointUrl(custom) : autoCovers.get(folder.getId()));
        }
    }

    /**
     * 自动封面解析：一组夹各自**最新一条收藏**的封面。
     *
     * <p>两步：① 一条窗口查询取 {@code folderId → contentId}（只碰本域表）；
     * ② 一趟 {@code ContentService.loadContentVOs} 批量取 coverUrl。取不到的 contentId
     * 不在结果里 ⇒ 该夹 {@code null} 占位。
     *
     * @param folderIds 需要自动封面的夹（**可为空** ⇒ 直接返回空 Map，不发 SQL）
     */
    private Map<Long, String> resolveAutoCovers(Collection<Long> folderIds) {
        if (folderIds == null || folderIds.isEmpty()) {
            return Map.of();
        }
        List<FolderLatestContent> latest = itemDao.findLatestContentIdByFolderIds(new ArrayList<>(folderIds));
        if (latest.isEmpty()) {
            return Map.of();
        }
        List<Long> contentIds = latest.stream()
                .map(FolderLatestContent::getContentId)
                .distinct()
                .toList();
        List<ContentVO> contents = contentService.loadContentVOs(contentIds);
        Map<Long, String> coverByContentId = new HashMap<>(contents.size());
        for (ContentVO content : contents) {
            String coverUrl = content.getCoverUrl();
            if (coverUrl != null && !coverUrl.isBlank()) {
                coverByContentId.put(content.getId(), coverUrl);
            }
        }
        Map<Long, String> coverByFolderId = new HashMap<>(latest.size());
        for (FolderLatestContent row : latest) {
            String coverUrl = coverByContentId.get(row.getContentId());
            if (coverUrl != null) {
                coverByFolderId.put(row.getFolderId(), coverUrl);
            }
        }
        return coverByFolderId;
    }

    // ========================================================================
    // 写路径：上传 / 恢复自动 / 删夹清理
    // ========================================================================

    /**
     * 自定义封面上传（{@code PUT /api/v1/favorite-folders/{id}/cover}）。
     *
     * <p>校验顺序：空文件 → 大小 → 夹存在且归属我 → 落盘 → 写库 → 删旧文件。
     * 大小在**落盘之前**检查，失败路径不留文件；类型/内容校验在
     * {@link FileUploadService#saveFile} 内（{@link UploadType#FOLDER_COVER} 只收图片）。
     *
     * @param userId   当前登录用户
     * @param folderId 目标夹；不存在 → 404、不是我的 → 403
     * @param file     前端 16:9 裁剪产物（服务端只校验类型 + ≤2MB，不重编码）
     */
    public void upload(long userId, long folderId, MultipartFile file) {
        if (file == null || file.getSize() <= 0) {
            throw new ParamException(ErrorCode.PARAM_INVALID, "请选择要上传的文件");
        }
        if (file.getSize() > MAX_COVER_BYTES) {
            throw new ParamException(ErrorCode.PARAM_INVALID, "封面图片不能超过2M");
        }

        FavoriteFolder folder = requireOwnedFolder(userId, folderId);
        UploadResult saved = null;
        try {
            saved = fileUploadService.saveFile(file, UploadType.FOLDER_COVER);
            if (folderDao.updateCoverRef(folderId, saved.url()) == 0) {
                // 归属校验之后被并发删夹 ⇒ 本次落盘文件作废（catch 里统一删）。
                throw new NotFoundException(ErrorCode.FAVORITE_FOLDER_NOT_FOUND, "收藏夹不存在");
            }
            deleteCustomCoverQuietly(folder.getCoverRef());
        } catch (BusinessException e) {
            deleteNewQuietly(saved);
            throw e;
        } catch (Exception e) {
            deleteNewQuietly(saved);
            log.warn("收藏夹封面上传失败，已删除本次落盘文件, userId={}, folderId={}", userId, folderId, e);
            throw new FolderCoverWriteException(e);
        }
    }

    /**
     * 恢复自动封面（{@code DELETE /api/v1/favorite-folders/{id}/cover}）。
     *
     * <p>写 {@code cover_ref = NULL} 后删旧自定义文件；本来就没有自定义封面时同样是成功
     * （幂等 204，不报 404/409）。归属校验口径与上传相同。
     */
    public void clear(long userId, long folderId) {
        FavoriteFolder folder = requireOwnedFolder(userId, folderId);
        folderDao.updateCoverRef(folderId, null);
        deleteCustomCoverQuietly(folder.getCoverRef());
    }

    /**
     * 尽力而为地删掉一个**自定义封面**文件（删夹 / 替换 / 恢复共用）。
     *
     * <p>只有 {@value #CUSTOM_COVER_PREFIX} 前缀下的 URL 才删：DB 里的值若被写坏成
     * 别的前缀（例如内容封面），绝不越权删除别的域的文件。空值 / 默认占位都不会到这里。
     */
    public void deleteCustomCoverQuietly(String coverRef) {
        if (!ownsCustomFile(coverRef)) {
            return;
        }
        fileUploadService.deleteFileByUrl(coverRef);
    }

    // ========================================================================
    // 内部
    // ========================================================================

    /** 取回夹并校验归属（404 / 403 的分界线；与 {@code FavoriteService.requireOwnedFolder} 同口径）。 */
    private FavoriteFolder requireOwnedFolder(long userId, long folderId) {
        FavoriteFolder folder = folderDao.findById(folderId);
        if (folder == null) {
            throw new NotFoundException(ErrorCode.FAVORITE_FOLDER_NOT_FOUND, "收藏夹不存在");
        }
        if (folder.getUserId() == null || folder.getUserId() != userId) {
            throw new ForbiddenException("只能操作自己的收藏夹");
        }
        return folder;
    }

    /** 是否“设过自定义封面”：非空白即算（脏值也优先，不静默降级成自动）。 */
    private static boolean hasCustom(String coverRef) {
        return coverRef != null && !coverRef.isBlank();
    }

    /**
     * 是否为本业务**拥有**的自定义文件（前缀 + 非空白）：只有它才允许物理删除。
     *
     * <p>与 {@link #hasCustom} 分开：`cover_ref` 若被写坏成别的域 URL，
     * 读路径仍按“自定义”展示（不静默改成自动），但删除路径**绝不越权**。
     */
    private static boolean ownsCustomFile(String coverRef) {
        return hasCustom(coverRef) && coverRef.startsWith(CUSTOM_COVER_PREFIX);
    }

    /** 对外 URL：与 content 的 `coverUrl` 共用 `video.media.base-url`（默认空串 ⇒ 原样）。 */
    private String jointUrl(String url) {
        String base = mediaProperties.baseUrl();
        return base == null || base.isEmpty() ? url : base + url;
    }

    private void deleteNewQuietly(UploadResult saved) {
        if (saved != null) {
            fileUploadService.deleteFileQuietly(saved.absolutePath());
        }
    }

    /**
     * 非业务异常（IO / DB 故障）包装：交全局出口 → 500。
     *
     * <p>与 {@code AvatarService.AvatarWriteException} 同款；不跨包复用那个 package-private 类。
     */
    static class FolderCoverWriteException extends RuntimeException {
        FolderCoverWriteException(Throwable cause) {
            super(cause);
        }
    }
}
