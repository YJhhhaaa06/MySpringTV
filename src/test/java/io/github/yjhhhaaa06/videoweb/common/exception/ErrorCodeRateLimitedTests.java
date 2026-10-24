package io.github.yjhhhaaa06.videoweb.common.exception;

import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流对外契约：{@link ErrorCode#RATE_LIMITED} 的**码 / 文案 / HTTP 类别 / 信封形状**。
 *
 * <h2>为什么这一条必须有</h2>
 * 429 是限流专项唯一新增的对外契约面（见 {@code architecture/tech/rate-limit/限流.md} §三）—— 客户端会按
 * {@code Retry-After} 与码决定重试节奏，落地后**改不动**。它同时要满足两条既有规则，而这两条都
 * **不会**因为"多了一个枚举值"自动报错：
 * <ol>
 *   <li><b>码带正确状态类别</b>：本表的状态同源于枚举（`T55` 前是 {@code HttpStatus.resolve(code)}）。
 *       写成 400 也能编译、能启动，只在前端把 429 退避逻辑走成普通错误时才被发现；</li>
 *   <li><b>不动既有项语义</b>：新增一段时最容易顺手"整理"上面几段。</li>
 * </ol>
 *
 * <p>纯单测（不起上下文、不连依赖）。码表的**结构性**约束（形态 / 白名单 / 无死码）
 * 在 {@link ErrorCodeContractTests}。
 */
class ErrorCodeRateLimitedTests {

    @Test
    @DisplayName("RATE_LIMITED 的标识与 HTTP 类别 = 429（写错不会报错，只在前端退避逻辑失效时才暴露）")
    void 限流码与HTTP类别() {
        assertThat(ErrorCode.RATE_LIMITED.getCode()).isEqualTo("RATE_LIMITED");
        assertThat(ErrorCode.RATE_LIMITED.getStatus())
                .as("429 是本仓第一个「非错误输入」语义的 4xx（见 architecture/tech/rate-limit/限流.md §三）")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("RATE_LIMITED 的中文提示是对外文案（进响应体 msg），改它等于改客户端可见行为")
    void 限流提示文案() {
        assertThat(ErrorCode.RATE_LIMITED.getMessage())
                .as("首版定的文案（见 architecture/tech/rate-limit/限流.md §三）")
                .isEqualTo("请求过于频繁");
    }

    @Test
    @DisplayName("信封形状不变：code 原样透传、data 为 null（NON_NULL ⇒ JSON 里不出现 data 键）")
    void 信封形状不变() {
        ApiResponse<Void> body = ApiResponse.error(ErrorCode.RATE_LIMITED);

        assertThat(body.code()).isEqualTo("RATE_LIMITED");
        assertThat(body.msg()).isEqualTo("请求过于频繁");
        assertThat(body.data()).as("失败响应不带 data（NON_NULL：JSON 里不出现该键）").isNull();
    }

    @Test
    @DisplayName("★ 红线：认证三态的 HTTP 类别与字符串码未被顺手'整理'成同一个码")
    void 认证码语义未被改动() {
        assertThat(ErrorCode.AUTH_UNAUTHORIZED.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.AUTH_TOKEN_EXPIRED.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.AUTH_USER_NOT_FOUND.getStatus())
                .as("★ 刻意设计：账号不存在对外是 401（认证语义）而**不是** 404")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.AUTH_BAD_CREDENTIALS.getMessage())
                .as("★ 登录失败只有这一句：拆开就是手机号探测口")
                .isEqualTo("账号或密码错误");
        assertThat(ErrorCode.AUTH_FORBIDDEN.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ErrorCode.PARAM_INVALID.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.SYSTEM_INTERNAL.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
