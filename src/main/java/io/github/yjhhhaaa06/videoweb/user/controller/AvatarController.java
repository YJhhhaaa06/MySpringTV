package io.github.yjhhhaaa06.videoweb.user.controller;

import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.log.AuditLog;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.Public;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.user.model.dto.UpdateDefaultAvatarRequest;
import io.github.yjhhhaaa06.videoweb.user.model.vo.UserAvatarVO;
import io.github.yjhhhaaa06.videoweb.user.service.AvatarService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 用户头像接口（`R-27`）。
 *
 * <table>
 *   <caption>三个端点</caption>
 *   <tr><th>方法 / 路径</th><th>鉴权</th><th>职责</th></tr>
 *   <tr><td>{@code PUT /api/v1/users/me/avatar}</td><td>{@link RequiresLogin}</td>
 *       <td>multipart 自定义上传；落 `/upload/avatar/` + 写 {@code avatar_ref}</td></tr>
 *   <tr><td>{@code PUT /api/v1/users/me/avatar/default}</td><td>{@link RequiresLogin}</td>
 *       <td>JSON {@code {"avatarId":1..6}}；写 {@code default:N}</td></tr>
 *   <tr><td>{@code GET /api/v1/users/avatars?userIds=}</td><td>{@link Public}</td>
 *       <td>作者快照面的批量读；≤100 个 id，匿名可达（评论 / 图文本身公开）</td></tr>
 * </table>
 *
 * <h2>★ 为什么不复用 {@code /api/v1/uploads/{type}}</h2>
 * 那是"只落盘、不写库"的通用口，必须后接内容清单；头像则需要"落盘 + 写 user 行 + 删旧文件"
 * 的单一事务意图。复用会把两段流程拆开，留下"上传了但没绑定"的孤儿窗口，且
 * {@code type=avatar} 会污染内容媒体的类型空间（{@code UploadType#fromDir} 刻意不认它）。
 *
 * <h2>★ 批量端点为什么公开</h2>
 * 评论 / 图文详情是匿名可达的；头像本身已经能从 {@code GET /users/{id}} 读到。
 * 若要求登录，匿名用户的评论区会退回 userId 默认头像 —— 同一份内容两套头像，
 * 那是假降级。批量端点只返回 avatar URL，不返回用户名 / 手机号，枚举面与公开主页同级。
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

    /**
     * 批量读作者头像（公开）。
     *
     * @param userIds 逗号分隔的 userId，例 {@code ?userIds=1,2,3}；≤100 个（去重后）
     */
    @Public
    @GetMapping("/api/v1/users/avatars")
    public ApiResponse<List<UserAvatarVO>> batch(@RequestParam(required = false) String userIds) {
        return ApiResponse.success(avatarService.listAvatars(parseUserIds(userIds)));
    }

    /**
     * 解析逗号分隔的 userId 列表。
     *
     * <p>空 / 非法 / 超过 100 个都 400。**去重后**再判上限：前端按页收集 id，
     * 同一作者的多条评论 / 动态会重复，去重是服务端义务，不是让客户端去重。
     */
    private static List<Long> parseUserIds(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ParamException(ErrorCode.PARAM_INVALID, "userIds 不能为空");
        }
        String[] parts = raw.split(",", -1);
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String part : parts) {
            String value = part.trim();
            if (value.isEmpty()) {
                throw new ParamException(ErrorCode.PARAM_INVALID, "userIds 格式错误");
            }
            long id;
            try {
                id = Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new ParamException(ErrorCode.PARAM_INVALID, "userIds 格式错误");
            }
            if (id <= 0) {
                throw new ParamException(ErrorCode.PARAM_INVALID, "userIds 格式错误");
            }
            ids.add(id);
        }
        if (ids.isEmpty()) {
            throw new ParamException(ErrorCode.PARAM_INVALID, "userIds 不能为空");
        }
        if (ids.size() > AvatarService.MAX_BATCH_SIZE) {
            throw new ParamException(ErrorCode.PARAM_INVALID,
                    "userIds 最多 " + AvatarService.MAX_BATCH_SIZE + " 个");
        }
        return new ArrayList<>(ids);
    }
}
