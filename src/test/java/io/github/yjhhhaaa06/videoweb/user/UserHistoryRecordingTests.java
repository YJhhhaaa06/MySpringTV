package io.github.yjhhhaaa06.videoweb.user;

import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「记录浏览历史」开关的契约测试（`R-25` / `T73`；口径的唯一事实源 =
 * {@code architecture/domain/history/history-需求.md} §2.1、代价见
 * {@code architecture/domain/history/history-已知代价.md}）。
 *
 * <h2>钉住什么（以及为什么每条都有判别力）</h2>
 * <ul>
 *   <li>{@link #默认开_且字段显式存在()}：新用户必须是 {@code true}，且**键真的在**
 *       （缺键在 JSON 侧读出来也是 false ⇒ 光断 {@code asBoolean()} 会把"没这个字段"当成"关着"）。</li>
 *   <li>★ {@link #关掉只影响自己_另一个用户仍是开()}：**独立 oracle** 直查 {@code users} 两行。
 *       没有"另一人"这条对照时，把 SQL 写成不带 {@code where id = ?} 的
 *       {@code update users set history_recording = 0} 也会全绿 —— 那正是最该拦住的错。</li>
 *   <li>{@link #再打开()}：双向可写（只测"关"会漏掉 {@code where recording = 0} 之类的单向实现）。</li>
 *   <li>{@link #缺字段必须400()}：{@code Boolean} 包装 + {@code @NotNull} 的意义 ——
 *       原生 {@code boolean} 会把"没传"静默绑成 {@code false}（隐私开关不能静默取值）。</li>
 *   <li>★ {@link #关闭后写端点仍放行_服务端刻意不强制()}：**这条断的是"没有做"**。
 *       它把 `R-25` 的刻意偏离变成一条会红的用例 —— 谁"顺手"给 {@code /progress} 加上开关校验，
 *       这里立刻从 404 变成 403/409。⚠️ 不要把它读成"忘了校验"。</li>
 * </ul>
 */
class UserHistoryRecordingTests extends AbstractHttpIntegrationTest {

    private static final String PHONE_A = "13800003101";
    private static final String PHONE_B = "13800003102";

    private int recordedFlagOf(long userId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT history_recording FROM users WHERE id = ?", Integer.class, userId);
        assertThat(value).as("users 行必须存在（否则下面的 oracle 读的是空气）").isNotNull();
        return value;
    }

    @Test
    @DisplayName("新用户默认开，且 historyRecording 键显式存在（不是缺键）")
    void 默认开_且字段显式存在() {
        String token = registerAndGetToken(PHONE_A, "r25default", "Password123");

        var data = Envelope.data(get("/api/v1/users/me", token));
        assertThat(data.path("historyRecording").isBoolean())
                .as("必须是显式 true/false；缺键时 JsonNode.asBoolean() 也返回 false，会把'没做'读成'关着'")
                .isTrue();
        assertThat(data.path("historyRecording").asBoolean()).isTrue();
        assertThat(recordedFlagOf(userIdOf(PHONE_A))).as("DB 默认就是 1（V9）").isEqualTo(1);
    }

    @Test
    @DisplayName("★关掉只影响自己：另一个用户仍是开（独立 oracle 直查两行）")
    void 关掉只影响自己_另一个用户仍是开() {
        String tokenA = registerAndGetToken(PHONE_A, "r25a", "Password123");
        String tokenB = registerAndGetToken(PHONE_B, "r25b", "Password123");

        ResponseEntity<String> resp = put("/api/v1/users/me/history-recording",
                Map.of("historyRecording", false), tokenA);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.data(resp).path("historyRecording").asBoolean()).as("回执 = 已落库的值").isFalse();

        // 独立 oracle：不读接口回显，直接查 DB 两行（对照行 B 是这条用例的判别力所在）
        assertThat(recordedFlagOf(userIdOf(PHONE_A))).as("A 被关掉").isEqualTo(0);
        assertThat(recordedFlagOf(userIdOf(PHONE_B))).as("B 不受影响（去掉 where id=? 时这里先红）").isEqualTo(1);

        assertThat(Envelope.data(get("/api/v1/users/me", tokenA)).path("historyRecording").asBoolean()).isFalse();
        assertThat(Envelope.data(get("/api/v1/users/me", tokenB)).path("historyRecording").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("再打开：双向可写（只测'关'会漏掉单向实现）")
    void 再打开() {
        String token = registerAndGetToken(PHONE_A, "r25again", "Password123");

        assertThat(put("/api/v1/users/me/history-recording", Map.of("historyRecording", false), token)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(recordedFlagOf(userIdOf(PHONE_A))).isEqualTo(0);

        assertThat(put("/api/v1/users/me/history-recording", Map.of("historyRecording", true), token)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(recordedFlagOf(userIdOf(PHONE_A))).isEqualTo(1);
    }

    @Test
    @DisplayName("未登录 ⇒ 401（且库里那一行没被动过）")
    void 未登录必须401() {
        // 先造出一行（匿名请求没有任何可改的对象，得有一个真实用户的库值当 oracle）
        registerAndGetToken(PHONE_A, "r25anon", "Password123");

        ResponseEntity<String> resp = put("/api/v1/users/me/history-recording",
                Map.of("historyRecording", false), null);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(recordedFlagOf(userIdOf(PHONE_A))).as("401 的请求一行都不该改").isEqualTo(1);
    }

    @Test
    @DisplayName("缺字段 ⇒ 400 且 errors[field=historyRecording]（Boolean 包装 + @NotNull 的意义）")
    void 缺字段必须400() {
        String token = registerAndGetToken(PHONE_A, "r25miss", "Password123");

        ResponseEntity<String> resp = put("/api/v1/users/me/history-recording", Map.of(), token);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        var errors = Envelope.errors(resp);
        assertThat(errors.isArray()).as("校验族必须带 errors[]").isTrue();
        assertThat(errors.get(0).path("field").asString()).as("field = 请求里的字段名").isEqualTo("historyRecording");
        assertThat(recordedFlagOf(userIdOf(PHONE_A))).as("被拒请求零副作用").isEqualTo(1);
    }

    @Test
    @DisplayName("非布尔值 ⇒ 400（解析层失败，不是业务校验）")
    void 非布尔值必须400() {
        String token = registerAndGetToken(PHONE_A, "r25bad", "Password123");

        ResponseEntity<String> resp = put("/api/v1/users/me/history-recording",
                Map.of("historyRecording", "maybe"), token);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(recordedFlagOf(userIdOf(PHONE_A))).as("被拒请求零副作用").isEqualTo(1);
    }

    @Test
    @DisplayName("★关闭后写端点仍放行 —— 服务端刻意不强制（谁给 /progress 加校验，这条必红）")
    void 关闭后写端点仍放行_服务端刻意不强制() {
        String token = registerAndGetToken(PHONE_A, "r25noenforce", "Password123");
        assertThat(put("/api/v1/users/me/history-recording", Map.of("historyRecording", false), token)
                .getStatusCode().value()).isEqualTo(200);

        // 关掉之后照样能上报：这里用**不存在的 contentId**（987654）⇒ 期望 404「内容不存在」。
        // 关键不在 404 本身，而在于"请求走到了内容存在性校验"——说明没有任何一层去读开关。
        // ⚠️ 若有人给写路径加开关守卫（403 / 409 / 静默 200），这条会红：那是**改变决策**，必须走 R-##。
        ResponseEntity<String> resp = put("/api/v1/contents/987654/progress", Map.of("progress", 10), token);
        assertThat(resp.getStatusCode().value())
                .as("关掉开关不影响写端点的行为（R-25：服务端不强制；见 history-已知代价.md）")
                .isEqualTo(404);
        assertThat(Envelope.code(resp)).isEqualTo("CONTENT_NOT_FOUND");
    }
}
