package io.github.yjhhhaaa06.videoweb.support;

import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 信封断言辅助。
 *
 * <p>刻意用 JSON 树（而非把响应反序列化成 {@code ApiResponse<LoginVO>}）来断言：
 * 泛型反序列化在 Jackson 3 下的类型传递更绕，而树的读取对"字段名是否真的是 msg""data 是否真的缺失"
 * 这类**契约形状**断言更直接——而契约形状正是本切片要锁定的东西。
 */
public final class Envelope {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Envelope() {
    }

    public static JsonNode parse(ResponseEntity<String> response) {
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            throw new AssertionError("响应体为空，无法解析信封。status=" + response.getStatusCode());
        }
        return MAPPER.readTree(body);
    }

    /** 取信封 data 下的字段（字符串形态）。 */
    public static String str(ResponseEntity<String> response, String field) {
        JsonNode node = parse(response).path("data").path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asString();
    }

    /**
     * 取整个 {@code data} 节点。
     *
     * <p>用于 {@code data} 本身不是对象的端点——例如 {@code /coupon/grab} 的 {@code data}
     * 是一个**纯字符串**券码，{@code /coupon/list} 的 {@code data} 是**数组**。
     * {@link #str} 走的是 {@code data.<field>} 路径，对这两种形状取不到值，故需要本方法。
     */
    public static JsonNode data(ResponseEntity<String> response) {
        return parse(response).path("data");
    }

    public static long num(ResponseEntity<String> response, String field) {
        return parse(response).path("data").path(field).asLong();
    }

    public static int code(ResponseEntity<String> response) {
        return parse(response).path("code").asInt();
    }

    public static String msg(ResponseEntity<String> response) {
        JsonNode node = parse(response).path("msg");
        return node.isMissingNode() ? null : node.asString();
    }

    public static boolean hasDataField(ResponseEntity<String> response) {
        return parse(response).has("data");
    }
}
