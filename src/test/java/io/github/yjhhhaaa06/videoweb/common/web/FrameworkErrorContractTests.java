package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import io.github.yjhhhaaa06.videoweb.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **框架级错误的归位契约**（`T56`）：未映射路径 / 方法不支持 / 媒体类型不支持，
 * 三条改造前都被 {@code @ExceptionHandler(Exception.class)} 吃成 **500**。
 *
 * <h2>三个判据各自从哪来（不抄实现）</h2>
 * <ul>
 *   <li><b>未映射 ⇒ 404 + 信封</b>：{@code architecture/tech/api/接口契约.md} §2.1 的 404 行
 *       与 §三 #6（"未映射也必须是 404"）。</li>
 *   <li><b>静态/媒体 ⇒ 404 但**不产信封**</b>：任务红线"不能把媒体 404 变成信封"——
 *       消费方是 {@code <img>} / {@code <video>}，不看 body；因此访问日志的 {@code code}
 *       必须退回 {@code 0}（"从未产生业务信封"哨兵）。</li>
 *   <li><b>405 / 415 有码可查</b>：§2.1 的 405/415 行 + §2.2 粒度判据③（运维按码统计）。</li>
 * </ul>
 *
 * <h2>★ 反向验证指向</h2>
 * 去掉 {@code GlobalExceptionHandler#handleNoResource} ⇒ 未映射与静态两条用例回到 500；
 * 去掉该方法里"静态豁免"分支（一律产信封）⇒ {@link #静态资源与媒体缺文件_404但不产信封()} 变红；
 * 405 处理器里不复制 {@code Allow} ⇒ {@link #方法不支持_405带Allow头()} 变红。
 */
class FrameworkErrorContractTests extends AbstractHttpIntegrationTest {

    /** 访问输出端 sink（用完必须 close；与 RateLimitFilterContractTests 同款自挂）。 */
    private LogCapture access;

    @BeforeEach
    void attachAccessCapture() {
        access = LogCapture.access();
        access.clear();
    }

    @AfterEach
    void detachAccessCapture() {
        if (access != null) {
            access.close();
        }
    }

    @Test
    @DisplayName("未映射路径 = 404 信封（不再是 catch-all 的 500），且访问行能按码统计")
    void 未映射路径_404信封() {
        ResponseEntity<String> resp = get("/no-such-page-t56-probe", null);

        assertThat(resp.getStatusCode().value())
                .as("§2.1：未映射路径必须是 404（改造前是 500，属已知漂移）")
                .isEqualTo(404);
        assertThat(Envelope.code(resp)).isEqualTo(ErrorCode.SYSTEM_ENDPOINT_NOT_FOUND.getCode());
        assertThat(Envelope.msg(resp)).isEqualTo(ErrorCode.SYSTEM_ENDPOINT_NOT_FOUND.getMessage());
        assertThat(Envelope.hasDataField(resp)).as("失败体不带 data").isFalse();
        assertThat(resp.getHeaders().getFirst("X-Request-Id")).isNotNull();

        String line = awaitAccessLine("/no-such-page-t56-probe");
        assertThat(line)
                .as("未映射路径是业务信封 ⇒ 访问行 code 必须是码本身（可统计哪些不存在的接口被打）")
                .contains("status=404")
                .contains("code=" + ErrorCode.SYSTEM_ENDPOINT_NOT_FOUND.getCode() + " ");
    }

    @Test
    @DisplayName("静态资源与媒体缺文件：404 但**不产信封**（消费方不看 body），访问行 code 退回哨兵 0")
    void 静态资源与媒体缺文件_404但不产信封() {
        // 三条覆盖两张清单：精确匹配（/favicon.ico）+ 两个前缀（/static/、/upload/）。
        // 用不存在的文件制造 404 —— 存在就变 200，断言会红，属预期。
        for (String path : List.of("/favicon.ico", "/static/css/t56-missing.css", "/upload/t56-missing.mp4")) {
            ResponseEntity<String> resp = get(path, null);

            assertThat(resp.getStatusCode().value()).as("%s 必须是 404", path).isEqualTo(404);
            String body = resp.getBody();
            assertThat(body == null || body.isBlank())
                    .as("%s 是静态/媒体请求，响应体必须为空（给 <img>/<video> 一段 JSON 没有意义），实际=%s",
                            path, body)
                    .isTrue();
            assertThat(resp.getHeaders().getFirst("X-Request-Id"))
                    .as("即便不产信封，请求标识也必须可读").isNotNull();
        }

        String line = awaitAccessLine("/upload/t56-missing.mp4");
        assertThat(line)
                .as("没有业务信封 ⇒ 访问行 code=0（不是某个错误码：该哨兵仍须存在）")
                .contains("status=404")
                .contains("code=0 ");
    }

    @Test
    @DisplayName("方法不支持：405 + 码 + **保住 Allow 头**（客户端才知道该用哪个方法）")
    void 方法不支持_405带Allow头() {
        // /api/v1/users/me/password 只接受 PUT（`T58` 起）；用 GET 打它 ⇒ 路径命中、方法不匹配。
        ResponseEntity<String> resp = get("/api/v1/users/me/password", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(405);
        assertThat(Envelope.code(resp)).isEqualTo(ErrorCode.PARAM_METHOD_NOT_ALLOWED.getCode());
        assertThat(resp.getHeaders().getFirst("Allow"))
                .as("Allow 由异常携带，丢弃它等于把 405 退化成'不知道为什么不行'")
                .isNotNull()
                .contains("PUT");
        assertThat(resp.getHeaders().getFirst("X-Request-Id")).isNotNull();
    }

    @Test
    @DisplayName("Content-Type 不支持：415 + 码 + Accept 头，且**不回显**客户端发来的类型")
    void 媒体类型不支持_415() {
        // 该收 JSON 的登录端点收到 text/plain ⇒ 415。
        // 故意在 body 与 Content-Type 里塞标记串：它们只许进日志，绝不许出现在响应体。
        String marker = "t56-echo-probe";
        ResponseEntity<String> resp = send(spec -> spec.uri("/api/v1/sessions")
                .contentType(MediaType.valueOf("text/" + marker))
                .body("plain-" + marker));

        assertThat(resp.getStatusCode().value()).isEqualTo(415);
        assertThat(Envelope.code(resp)).isEqualTo(ErrorCode.PARAM_MEDIA_TYPE_UNSUPPORTED.getCode());
        assertThat(resp.getBody())
                .as("异常 message 里的客户端可控字符串不许进 body（脱敏只在日志出口）")
                .doesNotContain(marker);
        assertThat(resp.getHeaders().getAccept())
                .as("Accept 由异常携带：告诉客户端该发什么")
                .isNotNull()
                .isNotEmpty();
        assertThat(resp.getHeaders().getFirst("X-Request-Id")).isNotNull();
    }

    @Test
    @DisplayName("★ 受保护端点收到错误 Content-Type 仍先 401：415 **不是**匿名可见（与 405 不同）")
    void 受保护端点的415仍先401() {
        // 判别力：若把"方法/类型不匹配"也当成"未映射"放行到路由层（405 就是这样），
        // 这里会变成 415 —— 那会把"这条受保护路径接受什么类型"泄露给匿名者。
        // 415 的时机在处理器解析**之后**（RequestMappingLookup 已返回 HandlerMethod ⇒ 默认拒绝生效）。
        ResponseEntity<String> resp = execute(client.put().uri("/api/v1/users/me/password")
                .contentType(MediaType.TEXT_PLAIN)
                .body("x"));

        assertThat(resp.getStatusCode().value())
                .as("受保护端点：鉴权先于 Content-Type 判定").isEqualTo(401);
        assertThat(Envelope.code(resp)).isEqualTo("AUTH_UNAUTHORIZED");
    }

    /** 轮询访问行（同 RateLimitFilterContractTests 手法：文件写入与响应之间有毫秒级竞态）。 */
    private String awaitAccessLine(String path) {
        return access.awaitLine(
                line -> line.contains(" path=" + path + " "),
                "access 行 path=" + path,
                Duration.ofSeconds(3));
    }
}
