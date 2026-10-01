package io.github.yjhhhaaa06.videoweb.user.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * <p>TV 把校验手写在 {@code LoginController} 里（踩坑清单 §8【隐患】：校验散落、无声明式约束）。
 * 这里改为声明式约束，由 {@code @Valid} + {@link io.github.yjhhhaaa06.videoweb.common.web.GlobalExceptionHandler}
 * 统一收口。
 *
 * <p>约束口径从 TV 的 {@code PasswordUtil.isPasswordLegal} 承接：
 * 密码 {@code ^[a-zA-Z0-9]{6,16}$}（注意 TV 注释里还留着旧版 {1,16} 的痕迹，实际生效的是 {6,16}）。
 */
public record RegisterRequest(

        @NotBlank(message = "用户名不能为空")
        @Size(min = 1, max = 50, message = "用户名长度需在 1-50 之间")
        String username,

        @NotBlank(message = "密码不能为空")
        @Pattern(regexp = "^[a-zA-Z0-9]{6,16}$", message = "密码须为 6-16 位字母或数字")
        String password,

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式错误")
        String phone
) {
}
