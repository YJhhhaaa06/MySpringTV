package io.github.yjhhhaaa06.videoweb.user.controller;

import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.user.model.dto.ChangePasswordRequest;
import io.github.yjhhhaaa06.videoweb.user.model.dto.LoginRequest;
import io.github.yjhhhaaa06.videoweb.user.model.dto.RegisterRequest;
import io.github.yjhhhaaa06.videoweb.user.model.vo.LoginVO;
import io.github.yjhhhaaa06.videoweb.user.model.vo.UserInfoVO;
import io.github.yjhhhaaa06.videoweb.user.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
 * <p><b>路径与契约沿袭 TV</b>（决策⑧「路径沿袭控制迁移变量」）：
 * {@code /user/login}、{@code /user/register}、{@code /user/changePassword}、
 * {@code /user/changeUserName}，响应体形状不变。
 *
 * <p>{@code /user/me} 是新增端点（TV 没有），用于切片 0「持 token 访问受保护接口」的验收闭环。
 */
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** 注册并自动登录。token 可能为 null（注册成功但自动登录失败），见《事务边界决策表》U-1。 */
    @PostMapping("/register")
    public ApiResponse<LoginVO> register(@RequestBody @Valid RegisterRequest request) {
        return ApiResponse.success(userService.registerAndLogin(request));
    }

    /** 登录。 */
    @PostMapping("/login")
    public ApiResponse<LoginVO> login(@RequestBody @Valid LoginRequest request) {
        return ApiResponse.success(userService.login(request.phone(), request.password()));
    }

    /** 修改密码。TV 中该路径在受保护精确清单内。 */
    @RequiresLogin
    @PostMapping("/changePassword")
    public ApiResponse<Void> changePassword(@CurrentUserId long userId,
                                           @RequestBody @Valid ChangePasswordRequest request) {
        userService.changePassword(userId, request);
        return ApiResponse.success();
    }

    /** 修改用户名。TV 中该路径在受保护精确清单内。 */
    @RequiresLogin
    @PostMapping("/changeUserName")
    public ApiResponse<Void> changeUserName(@CurrentUserId long userId,
                                           @RequestParam @NotBlank String userName) {
        userService.changeUserName(userId, userName);
        return ApiResponse.success();
    }

    /** 当前用户信息（新增端点）。 */
    @RequiresLogin
    @GetMapping("/me")
    public ApiResponse<UserInfoVO> me(@CurrentUserId long userId) {
        return ApiResponse.success(userService.getProfile(userId));
    }
}
