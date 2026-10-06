package io.github.yjhhhaaa06.videoweb.user.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 修改密码请求。承接 TV {@code com.itheima.user.model.dto.ChangePasswordDTO}。
 *
 * <p>⭐ **`phone` 刻意只加 {@code @NotBlank}、不加 {@code @Pattern}**——这是还原 TV 行为的关键取舍，
 * 不是漏写了约束。TV 在这一处的口径是**两级且用的是宽口径**：
 * <ol>
 *   <li>{@code CommandConverter.changePasswordToCommand}：先 blank 检查 ⇒ {@code ParamException("输入不能为空")} → 400，
 *       再用**宽** {@code StringUtil.phoneCheck}（11 位全数字 + 首位 1）判格式 ⇒ 400；</li>
 *   <li>{@code UserService.doChangePassword}：再拿它跟库里的本人手机号**比对** ⇒
 *       {@code ParamException("手机号不匹配")} → 400（比对在 UserService 里，见 {@code UserService.changePassword}）。</li>
 * </ol>
 *
 * <p><b>为什么不能按注册口径加 {@code @Pattern("^1[3-9]\\d{9}$")}</b>：TV 这里放行的宽口径号
 * （如 {@code 100…}）在库里是**存在的历史数据**（《遗留台账》E-6）。一旦收紧，这些账号**连自己的密码都改不了**
 * ——把一条无害的历史差异变成真实故障。
 *
 * <p>反过来说，**只加 {@code @NotBlank} 已能完整还原对外可观察行为**：任何不等于本人号的输入都走
 * "手机号不匹配" → 400（格式合不合法与之观测不可分），而空值走 {@code MethodArgumentNotValidException} → 400。
 */
public record ChangePasswordRequest(

        @NotBlank(message = "手机号不能为空")
        String phone,

        @NotBlank(message = "原密码不能为空")
        String oldPassword,

        @NotBlank(message = "新密码不能为空")
        @Pattern(regexp = "^[a-zA-Z0-9]{6,16}$", message = "密码须为 6-16 位字母或数字")
        String newPassword
) {
}
