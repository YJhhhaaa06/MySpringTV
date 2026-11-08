package io.github.yjhhhaaa06.videoweb.user.service;

import io.github.yjhhhaaa06.videoweb.common.exception.BusinessException;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.exception.UserNotFoundException;
import io.github.yjhhhaaa06.videoweb.upload.model.UploadResult;
import io.github.yjhhhaaa06.videoweb.upload.model.UploadType;
import io.github.yjhhhaaa06.videoweb.upload.service.FileUploadService;
import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import io.github.yjhhhaaa06.videoweb.user.model.AvatarRefs;
import io.github.yjhhhaaa06.videoweb.user.model.vo.UserAvatarVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 头像业务（`R-27`）：自定义上传 / 默认切换。
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li>只写 {@code users.avatar_ref} 一个列 —— 头像不是内容媒体，**不碰** {@code content_media}；</li>
 *   <li>文件字节交给 {@link FileUploadService}（`/upload/avatar/`），本类负责"先落盘、后写库、失败补偿"；</li>
 *   <li>★ `R-37` 起**旧文件不再立即删**：延迟 ≥5min（读缓存窗口），实际由
 *       {@code tools/devclean/media_clean.py} 按 {@code users.avatar_ref} 回收。
 *       立即删会让"已经发出去的旧 URL"在缓存窗口内 404（裂图），而不是显示旧头像。</li>
 * </ul>
 *
 * <h2>★ 为什么不加 @Transactional</h2>
 * 写库是一条 UPDATE；文件系统与 DB 没有共同事务。加事务只会让"文件已写、事务回滚"的补偿
 * 更难讲清。口径与 upload 域一致：**DB 失败就删本次新文件**；DB 成功后**不删旧文件**
 * （`R-37` 延迟回收，见上）。
 *
 * <h2>★ 并发口径：LWW + 允许孤儿</h2>
 * 两个并发上传各自写一个文件，最后写库的是后到者；先到者写入的文件可能成为孤儿
 * （`R-37` 起不再删旧文件，孤儿统一由 {@code media_clean.py} 回收）。
 * 头像是低频设置，不为它引入 `SELECT ... FOR UPDATE`。
 * 进程在"文件已写、DB 未提交"之间崩溃同样会留孤儿；回收口径同上。
 */
@Slf4j
@Service
public class AvatarService {

    /** 服务端硬上限：前端裁剪产物通常 <100KB；2MB 是防"直接调 API 传大图"的边界。 */
    private static final long MAX_AVATAR_BYTES = 2L * 1024 * 1024;

    private final UserDao userDao;
    private final FileUploadService fileUploadService;
    private final AvatarUrlResolver avatarUrlResolver;

    public AvatarService(UserDao userDao, FileUploadService fileUploadService,
                         AvatarUrlResolver avatarUrlResolver) {
        this.userDao = userDao;
        this.fileUploadService = fileUploadService;
        this.avatarUrlResolver = avatarUrlResolver;
    }

    /**
     * 自定义头像上传（{@code PUT /api/v1/users/me/avatar}）。
     *
     * <p>校验顺序：空文件 → 大小 → 用户存在 → 落盘 → 写库。大小在**落盘之前**检查，
     * 失败路径不留文件。
     *
     * @param userId 当前登录用户
     * @param file   裁剪后的图片（前端 180×180 JPEG；服务端只校验类型 + ≤2MB，不重编码）
     * @return 生效 `avatarUrl`（= 刚写入的自定义 URL）
     */
    public UserAvatarVO upload(long userId, MultipartFile file) {
        if (file == null || file.getSize() <= 0) {
            throw new ParamException(ErrorCode.PARAM_INVALID, "请选择要上传的文件");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new ParamException(ErrorCode.PARAM_INVALID, "头像图片不能超过2M");
        }

        if (userDao.findByIdForProfile(userId) == null) {
            throw new UserNotFoundException();
        }

        UploadResult saved = null;
        try {
            saved = fileUploadService.saveFile(file, UploadType.AVATAR);
            if (userDao.updateAvatarRef(userId, saved.url()) == 0) {
                throw new UserNotFoundException();
            }
            // R-37：写库成功后立刻失效 JVM 缓存；顺序不能反（先失效再写库，读线程可能回填旧值）。
            avatarUrlResolver.invalidate(userId);
            // R-37：**不删旧文件**——旧 URL 可能还在别的实例 / 在途响应的缓存窗口里。
            return new UserAvatarVO(userId, saved.url());
        } catch (BusinessException e) {
            deleteNewQuietly(saved);
            throw e;
        } catch (Exception e) {
            deleteNewQuietly(saved);
            log.warn("头像上传失败，已删除本次落盘文件, userId={}", userId, e);
            throw new AvatarWriteException(e);
        }
    }

    /**
     * 切换到某张默认头像（{@code PUT /api/v1/users/me/avatar/default}）。
     *
     * <p>只改 {@code avatar_ref}；旧的自定义文件**保留**（`R-37`：延迟回收，避免旧 URL 404）。
     * 编号非法 ⇒ 400，用户不存在 ⇒ 401（与 `GET /users/me` 同口径）。
     */
    public UserAvatarVO chooseDefault(long userId, Integer avatarId) {
        int id = AvatarRefs.validateDefaultId(avatarId);

        if (userDao.findByIdForProfile(userId) == null) {
            throw new UserNotFoundException();
        }

        if (userDao.updateAvatarRef(userId, AvatarRefs.defaultRef(id)) == 0) {
            throw new UserNotFoundException();
        }
        // R-37：同 upload —— 写库成功后失效，保证下一次读拿到新 ref。
        avatarUrlResolver.invalidate(userId);
        return new UserAvatarVO(userId, AvatarRefs.defaultUrl(id));
    }

    private void deleteNewQuietly(UploadResult saved) {
        if (saved != null) {
            fileUploadService.deleteFileQuietly(saved.absolutePath());
        }
    }

    /**
     * 非业务异常（IO / DB 故障）包装：交全局出口 → 500。
     *
     * <p>与 `UploadController.UploadFailureException` 同款；不跨包复用那个 package-private 类。
     */
    static class AvatarWriteException extends RuntimeException {
        AvatarWriteException(Throwable cause) {
            super(cause);
        }
    }
}
