package io.github.yjhhhaaa06.videoweb.common.exception;

import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流对外契约（限流专项 T23）：{@link ErrorCode#RATE_LIMITED} 的**值 / 消息 / HTTP 对齐**。
 *
 * <h2>为什么这一条必须有</h2>
 * 429 是本专项**唯一新增的对外契约面**（见 {@code architecture/tech/限流.md} §三）—— 客户端会按
 * {@code code} 与
 * {@code Retry-After} 决定重试节奏，落地后**改不动**（同"默认公开"那条的先例）。
 * 它同时要满足本仓两条既有规则，而这两条都**不会**因为"多了一个枚举值"自动报错：
 * <ol>
 *   <li><b>code 与 HTTP 对齐</b>（决策⑧）：写成 1001 之类也能编译、能跑，
 *       只在 {@code GlobalExceptionHandler} 里被静默回落成 400 —— 故在这里把
 *       "429 必须是一个合法 HTTP 状态且就是 429"钉住；</li>
 *   <li><b>不动既有项语义</b>（T23 红线）：新增一段时最容易顺手"整理"上面几段
 *       —— 尤其 {@code USER_NOT_FOUND} / {@code WRONG_PASSWORD} / {@code TOKEN_EXPIRED}
 *       的 401 是**刻意的认证语义**（勿"修正"成 404）。故把既有码一起钉住。</li>
 * </ol>
 *
 * <p>纯单测（不起上下文、不连依赖）—— 它断的是编译期就能看出的常量契约。
 */
class ErrorCodeRateLimitedTests {

    @Test
    @DisplayName("RATE_LIMITED 的业务码 = 429，且与 HTTP 状态对齐（写错会编译得过，只在运行期静默回落 400）")
    void 限流业务码与HTTP对齐() {
        assertThat(ErrorCode.RATE_LIMITED.getCode())
                .as("429 是本仓第一个「非错误输入」语义的 4xx（见 architecture/tech/限流.md §三）")
                .isEqualTo(429);
        assertThat(HttpStatus.resolve(ErrorCode.RATE_LIMITED.getCode()))
                .as("★ code 必须能解析成合法 HTTP 状态（决策⑧「HTTP 状态码正确化」）——"
                        + "写成 1001 之类能编译、能启动，只在 GlobalExceptionHandler 里被静默回落成 400")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("RATE_LIMITED 的中文提示是对外文案（进响应体 msg），改它等于改客户端可见行为")
    void 限流提示文案() {
        assertThat(ErrorCode.RATE_LIMITED.getMessage())
                .as("首版定的文案（见 architecture/tech/限流.md §三）；英文/空串会让前端只能按 code 分类展示")
                .isEqualTo("请求过于频繁");
    }

    @Test
    @DisplayName("信封形状不变：code 原样透传、data 为 null（NON_NULL ⇒ JSON 里不出现 data 键）")
    void 信封形状不变() {
        ApiResponse<Void> body = ApiResponse.error(ErrorCode.RATE_LIMITED.getCode(),
                ErrorCode.RATE_LIMITED.getMessage());

        assertThat(body.code()).isEqualTo(429);
        assertThat(body.msg()).isEqualTo("请求过于频繁");
        assertThat(body.data()).as("失败响应不带 data（NON_NULL：JSON 里不出现该键）").isNull();
    }

    @Test
    @DisplayName("★ 红线：既有错误码语义未被改动（含 USER_NOT_FOUND / WRONG_PASSWORD / TOKEN_EXPIRED 刻意的 401）")
    void 既有错误码语义未被改动() {
        assertThat(ErrorCode.PARAM_ERROR.getCode()).isEqualTo(400);
        assertThat(ErrorCode.UNAUTHORIZED.getCode()).isEqualTo(401);
        assertThat(ErrorCode.USER_NOT_FOUND.getCode())
                .as("★ 刻意设计：用户不存在对外是 401（认证语义）而**不是** 404，勿\"修正\"")
                .isEqualTo(401);
        assertThat(ErrorCode.WRONG_PASSWORD.getCode()).isEqualTo(401);
        assertThat(ErrorCode.TOKEN_EXPIRED.getCode()).isEqualTo(401);
        assertThat(ErrorCode.FORBIDDEN.getCode()).isEqualTo(403);
        assertThat(ErrorCode.NOT_FOUND.getCode()).isEqualTo(404);
        assertThat(ErrorCode.CONFLICT.getCode()).isEqualTo(409);
        assertThat(ErrorCode.SERVER_ERROR.getCode()).isEqualTo(500);
    }
}
