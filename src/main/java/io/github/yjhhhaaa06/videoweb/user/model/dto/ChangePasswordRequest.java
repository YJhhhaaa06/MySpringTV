package io.github.yjhhhaaa06.videoweb.user.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 修改密码请求。承接 TV {@code com.itheima.user.model.dto.ChangePasswordDTO}。 */
public record ChangePasswordRequest(

        @NotBlank(message = "原密码不能为空")
        String oldPassword,

        @NotBlank(message = "新密码不能为空")
        @Pattern(regexp = "^[a-zA-Z0-9]{6,16}$", message = "密码须为 6-16 位字母或数字")
        String newPassword
) {
}
