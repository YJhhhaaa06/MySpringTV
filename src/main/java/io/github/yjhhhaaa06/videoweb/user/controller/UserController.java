package io.github.yjhhhaaa06.videoweb.user.controller;

import io.github.yjhhhaaa06.videoweb.common.log.AuditLog;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.Public;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.user.model.dto.ChangePasswordRequest;
import io.github.yjhhhaaa06.videoweb.user.model.dto.ChangeUserNameRequest;
import io.github.yjhhhaaa06.videoweb.user.model.dto.LoginRequest;
import io.github.yjhhhaaa06.videoweb.user.model.dto.RegisterRequest;
import io.github.yjhhhaaa06.videoweb.user.model.dto.UpdateHistoryRecordingRequest;
import io.github.yjhhhaaa06.videoweb.user.model.vo.HistoryRecordingVO;
import io.github.yjhhhaaa06.videoweb.user.model.vo.LoginVO;
import io.github.yjhhhaaa06.videoweb.user.model.vo.UserInfoVO;
import io.github.yjhhhaaa06.videoweb.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口。
 *
 * <p>迁移自 TV {@code com.itheima.user.controller.LoginController}——对照片段：

 * <table>
 *   <caption>TV → 新实现</caption>
 *   <tr><th>TV</th><th>新实现</th></tr>
 *   <tr><td>{@code @WebServlet("/user/*")} + doPost 里 {@code switch(pathInfo)}</td>
 *       <td>{@code @RestController} + {@code @PostMapping}（方法即端点）</td></tr>
 *   <tr><td>{@code RequestParser.parse(req, XxxDTO.class)}</td>
 *       <td>{@code @RequestBody @Valid}（消息转换器 + 声明式校验）</td></tr>
 *   <tr><td>{@code (Long) req.getAttribute("userId")} + null 检查</td>
 *       <td>{@code @CurrentUserId} + {@code @RequiresLogin}</td></tr>
 *   <tr><td>{@code CommandConverter.xxxToCommand(dto)}</td>
 *       <td>删除（DTO/Command 双层的唯一作用是给手写解析器做适配，参数绑定后无必要）</td></tr>
 * </table>
 *
 * <p><b>路径（`T58` 资源化）</b>：响应体形状不变，路径按
 * {@code 接口契约.md} §2.3 改为「资源 + 方法」——
 * 注册 {@code POST /api/v1/users}（创建用户）、登录 {@code POST /api/v1/sessions}（创建会话）、
 * 改密 {@code PUT /api/v1/users/me/password}、改名 {@code PUT /api/v1/users/me/username}、
 * 当前用户 {@code GET /api/v1/users/me}、记录开关 {@code PUT /api/v1/users/me/history-recording}（{@code R-25}）。
 * TV 的 {@code /user/{login,register,...}} 形态退役
 * （硬切，见 §1.2）。
 *
 * <p>★ <b>登录为什么叫 sessions</b>：登录的产物是**会话凭据**（token），不是"用户"这个资源；
 * 动词 {@code login} 不许进路径（§2.3），故落在"创建会话"上。
 *
 * <p>{@code /users/me} 是新增端点（TV 没有），用于切片 0「持 token 访问受保护接口」的验收闭环。
 */
@RestController
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** 注册并自动登录。token 可能为 null（注册成功但自动登录失败），见《事务边界决策表》U-1。 */
    @Public
    @PostMapping("/api/v1/users")
    public ApiResponse<LoginVO> register(@RequestBody @Valid RegisterRequest request) {
        return ApiResponse.success(userService.registerAndLogin(request));
    }

    /** 登录（创建会话：`T58` 起 {@code POST /api/v1/sessions}）。 */
    @Public
    @PostMapping("/api/v1/sessions")
    public ApiResponse<LoginVO> login(@RequestBody @Valid LoginRequest request) {
        return ApiResponse.success(userService.login(request.phone(), request.password()));
    }

    /**
     * 修改密码。TV 中该路径在受保护精确清单内。
     *
     * <p><b>T2 审计（B12）</b>：审计行在服务方法返回（= 事务已提交）之后写。
     * 放在控制器而不是服务方法体内是有意的——服务方法标了 {@code @Transactional}，
     * 在方法体内写审计会在**提交之前**留下"改过密码"的记录；提交失败就变成假留痕。
     * 控制器在事务之外，服务调用返回即已提交，故此处是"事实之后"。
     * {@code target} 只记对象标识 {@code userId:N}——旧密码 / 新密码 / 手机号一律不落盘。
     */
    @RequiresLogin
    @PutMapping("/api/v1/users/me/password")
    public ApiResponse<Void> changePassword(@CurrentUserId long userId,
                                           @RequestBody @Valid ChangePasswordRequest request) {
        userService.changePassword(userId, request);
        AuditLog.success("user.changePassword", userId, "userId:" + userId);
        return ApiResponse.success();
    }

    /**
     * 修改用户名。TV 中该路径在受保护精确清单内。
     *
     * <p>审计口径同 {@link #changePassword}（{@code UserService.changeUserName} 亦为
     * {@code @Transactional}）。{@code target} 只记 {@code userId:N}，**不记新用户名**。
     *
     * <p>★ `T59` 载荷面：入参改为 JSON body {@code {"userName": "…"}}（约束在
     * {@link ChangeUserNameRequest} 上，失败体仍由 `T57` 的出口产出
     * {@code errors[field=userName]}）。
     */
    @RequiresLogin
    @PutMapping("/api/v1/users/me/username")
    public ApiResponse<Void> changeUserName(@CurrentUserId long userId,
                                           @RequestBody @Valid ChangeUserNameRequest request) {
        userService.changeUserName(userId, request.userName());
        AuditLog.success("user.changeUserName", userId, "userId:" + userId);
        return ApiResponse.success();
    }

    /** 当前用户信息（新增端点）。 */
    @RequiresLogin
    @GetMapping("/api/v1/users/me")
    public ApiResponse<UserInfoVO> me(@CurrentUserId long userId) {
        return ApiResponse.success(userService.getProfile(userId));
    }

    /**
     * 停用 / 恢复「记录浏览历史」（{@code R-25}，2026-10-31）。
     *
     * <p>为什么是 {@code PUT} 而不是 {@code POST .../pause}：它是**对子资源的替换**
     * （{@code history-recording} 这个开关的取值），同值重写结果不变 ⇒ 天然幂等；
     * 而 {@code pause} 是动词，违反《接口契约.md》§2.3「路径里禁止动词」。
     *
     * <p>⚠️ 服务端**不**拿这个值去校验 history 的写端点：本端点只落一个偏好，
     * "停用后不再记录"由客户端**不发**那三个写请求保证。这是刻意取舍（B站 实测是服务端强制），
     * 代价与推翻条件见 {@code architecture/domain/history/history-已知代价.md}。
     *
     * <p>不写 {@code AuditLog}：它不是身份 / 凭据变更，且开关状态本身在 {@code users} 行里可查
     * （改密 / 改名要审计，是因为那两个动作会改变"对外可见的身份"）。
     */
    @RequiresLogin
    @PutMapping("/api/v1/users/me/history-recording")
    public ApiResponse<HistoryRecordingVO> updateHistoryRecording(
            @CurrentUserId long userId,
            @RequestBody @Valid UpdateHistoryRecordingRequest request) {
        return ApiResponse.success(userService.updateHistoryRecording(userId, request.historyRecording()));
    }
}
