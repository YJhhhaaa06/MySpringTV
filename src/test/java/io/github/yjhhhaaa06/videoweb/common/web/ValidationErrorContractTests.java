package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **校验失败的字段级契约**（`T57`，契约见 {@code architecture/tech/api/接口契约.md} §2.5）：
 * {@code 400} 的校验族必须给出 {@code errors:[{field, code, message}]}，让前端把文案挂到**具体输入框**上。
 *
 * <h2>为什么需要它（不是"多一个字段"的审美问题）</h2>
 * `T57` 之前只有一条拼接串 {@code msg}：注册表单三个字段同时错时，前端能拿到的是一句话，
 * 无法判断哪句属于哪个输入框 —— 只能靠解析中文，文案一改就崩。字段级定位必须是**结构化的**。
 *
 * <h2>期望值从哪来（不抄实现）</h2>
 * <ul>
 *   <li>{@code field} = **请求里的字段名** —— 由本类提交的 JSON 键推出（{@code password} / {@code phone}），
 *       不是从实现里读的；实现若改用 Java 属性名/参数名，本类不会跟着变。</li>
 *   <li>{@code code} = {@link ErrorCode#PARAM_INVALID} —— §2.2 粒度判据：字段级定位已由 {@code field} 承担，
 *       同族约束共用一码（"同一 toast 换文案 ⇒ 同码"）。</li>
 *   <li>{@code message} = 约束注解上的**稳定文案**（源码里的契约文案，不是异常 message）。</li>
 * </ul>
 *
 * <h2>为什么带"对照"用例</h2>
 * 只验证"校验失败有 errors"是不够的 —— 那允许实现"所有 400 都塞 errors"。
 * {@link #业务拒绝不带errors()} 钉住**边界**：Service 内守卫（手写校验）产出的 400 仍然只有 {@code msg}，
 * 这是 §2.5 明确登记的**已知缺口**（不是漏改，迁移时机归载荷面任务）。
 */
class ValidationErrorContractTests extends AbstractHttpIntegrationTest {

    @Test
    @DisplayName("两字段同时错 ⇒ 两条 errors，field 精确、code 走码表、message 取注解文案")
    void 两字段同时错给出两条字段错误() {
        // username "x" 合法（@NotBlank 通过、长度 1 在 1-50 内）⇒ 只有 password / phone 两条。
        ResponseEntity<String> resp = post("/user/register",
                Map.of("username", "x", "password", "ab", "phone", "1"), null);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.code(resp)).isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        assertThat(Envelope.hasDataField(resp)).as("失败体不得出现 data").isFalse();

        JsonNode errors = Envelope.errors(resp);
        assertThat(errors.isArray()).as("errors 必须是数组：%s", resp.getBody()).isTrue();
        assertThat(errors.size()).as("两个字段各违反一条 ⇒ 恰好两条：%s", resp.getBody()).isEqualTo(2);

        assertThat(fieldNames(errors)).containsExactlyInAnyOrder("password", "phone");

        for (JsonNode error : errors) {
            assertThat(error.size()).as("每条只有 field / code / message 三个键：%s", error).isEqualTo(3);
            // ★ code 的序列化形态是契约的一部分：必须是枚举名（不是 400、不是序数、不是 toString）
            assertThat(error.path("code").asString()).isEqualTo(ErrorCode.PARAM_INVALID.getCode());
            assertThat(error.path("message").asString()).isNotBlank();
        }
        assertThat(messageOf(errors, "password")).isEqualTo("密码须为 6-16 位字母或数字");
        assertThat(messageOf(errors, "phone")).isEqualTo("手机号格式错误");
    }

    @Test
    @DisplayName("顶层 msg 与 errors[] 同源：摘要里能读到每个字段的定位与文案")
    void 顶层msg是字段错误的摘要() {
        ResponseEntity<String> resp = post("/user/register",
                Map.of("username", "x", "password", "ab", "phone", "1"), null);

        // 摘要格式沿用 T57 之前的 "field: message"（旧前端哪怕在做字符串解析也不失配），
        // 且必须与 errors[] 逐条对应 —— 两条错误 ⇒ 摘要里两个 "field: " 前缀。
        String msg = Envelope.msg(resp);
        assertThat(msg).as("摘要含字段定位（旧前端兜底路径）").contains("password: ").contains("phone: ");
        assertThat(msg.split("; ")).as("摘要与 errors[] 同源（两条错误 ⇒ 两段）").hasSize(2);
    }

    @Test
    @DisplayName("参数回显不进响应体：非法值只出现在日志侧")
    void 非法值不回显() {
        // 密码刻意用"一眼能认出的长串"：违反 @Pattern，但**不许**出现在响应体里。
        // 反向验证：把 errors[].message 改成 ex.getMessage()（含 "Validation failed for argument..."），
        // 或把入参拼进文案，本条即红。
        String secret = "verysecret-zzzzzzzzzzzzzzzz";
        ResponseEntity<String> resp = post("/user/register",
                Map.of("username", "u", "password", secret, "phone", "13800000000"), null);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(fieldNames(Envelope.errors(resp))).containsExactly("password");
        assertThat(resp.getBody()).as("入参回显只进日志，绝不进 body").doesNotContain("verysecret");
        assertThat(Envelope.msg(resp)).isEqualTo("password: 密码须为 6-16 位字母或数字");
    }

    @Test
    @DisplayName("对照：业务拒绝（手写校验的 400）不带 errors —— errors 只属于校验族")
    void 业务拒绝不带errors() {
        // /search/keywordSearch 的空白关键词由 SearchController 的守卫拒绝（文案 "输入不能为空"），
        // 属"业务拒绝"而不是"声明式校验"，故**不产** errors[]（§2.5 已知缺口，迁移归载荷面）。
        ResponseEntity<String> resp = get("/search/keywordSearch?keyword=", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.code(resp)).isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        assertThat(Envelope.msg(resp)).isEqualTo("输入不能为空");
        assertThat(Envelope.hasErrorsField(resp))
                .as("业务拒绝不产 errors[]（否则前端会以为有字段级定位）：%s", resp.getBody())
                .isFalse();
    }

    // ==================== 辅助（只读 JSON 树，不反序列化成 ApiResponse） ====================

    private static List<String> fieldNames(JsonNode errors) {
        List<String> names = new ArrayList<>();
        errors.forEach(error -> names.add(error.path("field").asString()));
        return names;
    }

    private static String messageOf(JsonNode errors, String field) {
        for (JsonNode error : errors) {
            if (field.equals(error.path("field").asString())) {
                return error.path("message").asString();
            }
        }
        throw new AssertionError("errors 里没有字段 " + field + "：" + errors);
    }
}
