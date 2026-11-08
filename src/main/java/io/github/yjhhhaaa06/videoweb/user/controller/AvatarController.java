package io.github.yjhhhaaa06.videoweb.user.controller;

import io.github.yjhhhaaa06.videoweb.common.log.AuditLog;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.user.model.dto.UpdateDefaultAvatarRequest;
import io.github.yjhhhaaa06.videoweb.user.model.vo.UserAvatarVO;
import io.github.yjhhhaaa06.videoweb.user.service.AvatarService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户头像接口（`R-27`；`R-37` 起批量读端点退役）。
 *
 * <table>
 *   <caption>两个端点</caption>
 *   <tr><th>方法 / 路径</th><th>鉴权</th><th>职责</th></tr>
 *   <tr><td>{@code PUT /api/v1/users/me/avatar}</td><td>{@link RequiresLogin}</td>
 *       <td>multipart 自定义上传；落 `/upload/avatar/` + 写 {@code avatar_ref}</td></tr>
 *   <tr><td>{@code PUT /api/v1/users/me/avatar/default}</td><td>{@link RequiresLogin}</td>
 *       <td>JSON {@code {"avatarId":1..6}}；写 {@code default:N}</td></tr>
 * </table>
 *
 * <h2>★ 为什么没有"批量读"端点了（`R-37`）</h2>
 * 作者快照面（评论 / 动态 / 图文 / 关注列表）改为**响应内联 `avatarUrl`**，由各域在装配
 * 响应时经 {@code AvatarUrlResolver} 读时装饰。前端不再有"先收 id、再补头像"的第二次请求，
 * 这个公开端点因此退役（端点 76→75）。
 *
 * <h2>★ 为什么不复用 {@code /api/v1/uploads/{type}}</h2>
 * 那是"只落盘、不写库"的通用口，必须后接内容清单；头像则需要"落盘 + 写 user 行"的单一意图。
 * 复用会把两段流程拆开，留下"上传了但没绑定"的孤儿窗口，且
 * {@code type=avatar} 会污染内容媒体的类型空间（{@code UploadType#fromDir} 刻意不认它）。
 */
@RestController
public class AvatarController {

    private final AvatarService avatarService;

    public AvatarController(AvatarService avatarService) {
        this.avatarService = avatarService;
    }

    /**
     * 自定义头像上传。
     *
     * <p>服务端只校验类型（jpg/png/jpeg）与 ≤2MB，**不重编码、不强制 180×180** ——
     * 裁剪是客户端体验，不是安全边界（`R-27` 已知代价）。
     * 旧的自定义文件在写库成功后删除；失败路径删除本次新文件。
     */
    @RequiresLogin
    @PutMapping("/api/v1/users/me/avatar")
    public ApiResponse<UserAvatarVO> upload(@CurrentUserId long userId,
                                            @RequestPart("file") MultipartFile file) {
        UserAvatarVO result = avatarService.upload(userId, file);
        AuditLog.success("user.changeAvatar", userId, "userId:" + userId);
        return ApiResponse.success(result);
    }

    /**
     * 切换到默认头像。
     *
     * <p>{@code avatarId} 缺失 ⇒ 400（`@Valid` + {@code @NotNull}）；越界 ⇒ 400
     * （服务层 {@code AvatarRefs.validateDefaultId}，文案「头像编号不合法，应为 1..6」）。
     */
    @RequiresLogin
    @PutMapping("/api/v1/users/me/avatar/default")
    public ApiResponse<UserAvatarVO> chooseDefault(@CurrentUserId long userId,
                                                   @RequestBody @Valid UpdateDefaultAvatarRequest request) {
        UserAvatarVO result = avatarService.chooseDefault(userId, request.avatarId());
        AuditLog.success("user.changeAvatar", userId, "userId:" + userId);
        return ApiResponse.success(result);
    }
}
