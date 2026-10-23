package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
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
        ResponseEntity<String> resp = post("/api/v1/users",
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
        ResponseEntity<String> resp = post("/api/v1/users",
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
        ResponseEntity<String> resp = post("/api/v1/users",
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
        ResponseEntity<String> resp = get("/api/v1/contents?keyword=", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.code(resp)).isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        assertThat(Envelope.msg(resp)).isEqualTo("输入不能为空");
        assertThat(Envelope.hasErrorsField(resp))
                .as("业务拒绝不产 errors[]（否则前端会以为有字段级定位）：%s", resp.getBody())
                .isFalse();
    }

    // ==================== 框架族：缺参 / 类型 / 不可解析（同一形状） ====================

    @Test
    @DisplayName("缺必填参数 ⇒ 400 + errors[field=参数名]（不是只回一句「参数错误」）")
    void 缺必填参数带字段定位() {
        String token = registerAndGetToken("13800000201", "t57-missing-param", "123456");

        // /api/v1/contents/{id}/media 的 type 仍是必填 @RequestParam（`T58` 只把 id 收进路径）
        // ⇒ Spring 抛 MissingServletRequestParameterException（参数名由异常携带，不靠解析文案）。
        ResponseEntity<String> resp = put("/api/v1/contents/1/media", null, token);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.code(resp)).isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        JsonNode error = singleError(resp);
        assertThat(error.path("field").asString()).isEqualTo("type");
        assertThat(error.path("code").asString()).isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        assertThat(error.path("message").asString()).isEqualTo("缺少必填参数");
        assertThat(Envelope.msg(resp)).as("顶层摘要与 errors 同源").isEqualTo("type: 缺少必填参数");
    }

    @Test
    @DisplayName("参数类型不匹配 ⇒ 400 + errors[field=参数名]，且不回显非法值")
    void 类型不匹配带字段定位且不回显() {
        // 路径变量 id 声明为 Long（`T58`：id 进路径且统一叫 {id}），传 abc 由 Spring 转换失败
        ResponseEntity<String> resp = get("/api/v1/contents/abc/comments", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        JsonNode error = singleError(resp);
        assertThat(error.path("field").asString()).as("路径参数统一叫 id（§2.3）").isEqualTo("id");
        assertThat(error.path("message").asString()).isEqualTo("参数类型不正确");
        // ★ 异常 message 里带着 "Failed to convert 'contentId' with value: 'abc'" —— 那是回显，只许进日志
        assertThat(resp.getBody()).as("回显只进日志，不进 body").doesNotContain("abc");
    }

    @Test
    @DisplayName("请求体里的值不可解析 ⇒ errors[field] 指到具体字段（非法取值的定位能力）")
    void 请求体取值不可解析能定位字段() {
        String token = registerAndGetToken("13800000202", "t57-bad-value", "123456");

        // contentId 是 Long，JSON 里给 "abc" ⇒ Jackson 的 InvalidFormatException，
        // 引用链是 ["contentId"] ⇒ field 可以精确到字段（这正是"非法枚举值/非法取值"的定位路径）。
        ResponseEntity<String> resp = post("/api/v1/comments",
                Map.of("contentId", "abc", "message", "hi"), token);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        JsonNode error = singleError(resp);
        assertThat(error.path("field").asString()).isEqualTo("contentId");
        assertThat(error.path("message").asString()).isEqualTo("参数取值不合法");
        assertThat(resp.getBody()).doesNotContain("abc");
    }

    @Test
    @DisplayName("请求体整体不可解析 ⇒ 400 + 一条无 field 的 errors（不伪造字段名）")
    void 请求体整体不可解析时不给假字段() {
        // 需要 @RequestBody 的端点不带请求体 ⇒ HttpMessageNotReadableException，且无可定位字段。
        ResponseEntity<String> resp = post("/api/v1/sessions", null, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        JsonNode error = singleError(resp);
        assertThat(error.has("field")).as("定位不到字段时**省略** field，而不是编一个名字：%s", error).isFalse();
        assertThat(error.size()).as("只有 code / message 两个键").isEqualTo(2);
        assertThat(error.path("message").asString()).isEqualTo("请求体缺失或格式错误");
        assertThat(Envelope.msg(resp)).isEqualTo("请求体缺失或格式错误");
    }

    @Test
    @DisplayName("请求体约束真的生效：空用户名 → 400 + errors[field=userName]")
    void 请求参数上的约束生效() {
        // ⚠️ `T59` 载荷面契约变更：改用户名的 `@NotBlank` 从 `@RequestParam` 挪到请求体
        // （{@code ChangeUserNameRequest}）⇒ 本用例现在走 {@code MethodArgumentNotValidException}
        // 分支，不再是 {@code HandlerMethodValidationException}。
        // ★ 覆盖缺口（如实登记）：`T59-2` 之后 `src/main` 已没有"标在控制器方法参数上的约束"，
        // 方法校验分支暂无产码点（登记见 `CURRENT_ISSUES.md` I-28）。本用例仍钉住
        // "约束注解的 message 进 errors[].message、field 逐字是请求字段名"这一对外契约。
        String token = registerAndGetToken("13800000203", "t57-param-constraint", "123456");

        ResponseEntity<String> resp = put("/api/v1/users/me/username", Map.of("userName", ""), token);

        assertThat(resp.getStatusCode().value())
                .as("约束生效（不是 500，也不是静默成功）：%s", resp.getBody()).isEqualTo(400);
        assertThat(Envelope.code(resp)).isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        JsonNode error = singleError(resp);
        assertThat(error.path("field").asString()).isEqualTo("userName");
        assertThat(error.path("code").asString()).isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        // 文案取自源码里的注解 message：约束缺 message 时是 Hibernate 的**本地化默认值**，
        // 随 Locale 变 ⇒ 对外 body 里不许出现（这是"文案必须写死"的判别力所在）。
        assertThat(error.path("message").asString()).isEqualTo("用户名不能为空");
    }

    @Test
    @DisplayName("同一字段违反两条约束 ⇒ 两条 errors（不合并：合并要选「哪条算第一条」）")
    void 同字段多条约束不合并() {
        // password="" 同时违反 @NotBlank 与 @Pattern（空串也匹配不上 ^[a-zA-Z0-9]{6,16}$）
        // ⇒ 契约 §2.5.1 明确"不合并"，否则"还违反了另一条"的信息会被悄悄丢掉。
        ResponseEntity<String> resp = post("/api/v1/users",
                Map.of("username", "u", "password", "", "phone", "13800000000"), null);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        JsonNode errors = Envelope.errors(resp);
        assertThat(errors.size()).as("两条约束各一条：%s", resp.getBody()).isEqualTo(2);
        assertThat(fieldNames(errors)).containsExactly("password", "password");
        // 两条文案不同（不是同一条被复制两次）——这才是"每条违反一条"的证据。
        assertThat(messageOfIndex(errors, 0)).isNotEqualTo(messageOfIndex(errors, 1));
    }

    @Test
    @DisplayName("报文残缺（不是值错）⇒ 文案是「请求体缺失或格式错误」，不误报成类型错误")
    void 报文残缺不误报为类型错误() {
        // ★ 判别点：解析层失败（StreamRead）与数据绑定层失败（Databind）是两类成因。
        // 无论能否从引用链回收字段名，**文案**都必须说"报文坏了"，而不是"某字段类型不对"。
        ResponseEntity<String> resp = postRaw("/api/v1/sessions", "{\"phone\":\"13800000000\",");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        JsonNode error = singleError(resp);
        assertThat(error.path("message").asString()).isEqualTo("请求体缺失或格式错误");
    }

    // ==================== 辅助（只读 JSON 树，不反序列化成 ApiResponse） ====================

    /** 发送**原始文本**请求体（用于构造残缺 JSON；正常路径一律走基类的对象序列化）。 */
    private ResponseEntity<String> postRaw(String path, String rawBody) {
        return send(spec -> spec.uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(rawBody.getBytes(StandardCharsets.UTF_8)));
    }

    private static String messageOfIndex(JsonNode errors, int index) {
        return errors.get(index).path("message").asString();
    }

    private static JsonNode singleError(ResponseEntity<String> resp) {
        JsonNode errors = Envelope.errors(resp);
        assertThat(errors.isArray()).as("errors 必须是数组：%s", resp.getBody()).isTrue();
        assertThat(errors.size()).as("这一类错误只定位到一个字段：%s", resp.getBody()).isEqualTo(1);
        return errors.get(0);
    }

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
