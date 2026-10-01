package io.github.yjhhhaaa06.videoweb.user.model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 *
 * <p>TV 的 {@code LoginCommand} 支持「按 id」与「按手机号」两种登录方式（{@code LoginType} 枚举）。
 * 切片 0 只承接**按手机号**登录——按 id 登录在对外 HTTP 接口上没有独立入口
 * （{@code registerAndLogin} 内部按 id 调用，属内部路径），待确认产品语义后再补。
 * 见《事务边界决策表》U-3/U-4 的说明。
 */
public record LoginRequest(

        @NotBlank(message = "手机号不能为空")
        String phone,

        @NotBlank(message = "密码不能为空")
        String password
) {
}
