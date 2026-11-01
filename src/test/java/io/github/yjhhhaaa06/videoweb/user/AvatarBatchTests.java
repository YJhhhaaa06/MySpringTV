package io.github.yjhhhaaa06.videoweb.user;

import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批量头像端点（{@code GET /api/v1/users/avatars?userIds=}）的契约测试（`R-27` / `T80`）。
 *
 * <h2>为什么必须独立存在</h2>
 * 评论 / 动态 / 关注列表 / 图文作者只看得到 userId，看不到 `UserInfoVO` / `ProfileVO`。
 * 本端点把"作者快照面怎么拿头像"从"往缓存里塞 avatarUrl"改成一次公开批量读：
 * 换头像后下一次进页即生效，旧 URL 不会留在评论缓存里。
 *
 * <h2>钉住什么</h2>
 * <ul>
 *   <li>公开可达（匿名评论 / 图文必须能显示头像）；</li>
 *   <li>三种来源：自定义 URL / 选中的 {@code default:N} / 未选过按 userId 回落；</li>
 *   <li>不存在的 id **不返回条目**（不是返回 null）；</li>
 *   <li>逗号分隔、去重、≤100；缺参 / 非法 / 超限 400。</li>
 * </ul>
 *
 * <h2>反向验证（注入 ⇒ 必红）</h2>
 * ① 给端点加 @RequiresLogin ⇒ 匿名用例 401；② 去掉 default 解析 / 回落 ⇒ 对应 URL 断言红；
 * ③ 去掉去重 ⇒ "重复 id 只返回一条"红；④ 去掉上限 ⇒ "101 个 400"红。
 */
class AvatarBatchTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "Password123";

    @Test
    @DisplayName("★公开批量：自定义 / 选中默认 / 未选回落三种来源各就各位，缺 id 不返回")
    void 公开批量返回生效头像() {
        String tokenA = registerAndGetToken("13800004201", "batch-a", PASSWORD);
        String customA = Envelope.str(uploadAvatar(jpegBytes(), "a.jpg", "image/jpeg", tokenA), "avatarUrl");
        long idA = userIdOf("13800004201");

        String tokenB = registerAndGetToken("13800004202", "batch-b", PASSWORD);
        long idB = userIdOf("13800004202");
        assertThat(put("/api/v1/users/me/avatar/default", Map.of("avatarId", 4), tokenB)
                .getStatusCode().value()).isEqualTo(200);

        registerAndGetToken("13800004203", "batch-c", PASSWORD);
        long idC = userIdOf("13800004203");

        ResponseEntity<String> resp = get("/api/v1/users/avatars?userIds="
                + idA + "," + idB + "," + idC + ",999999", null);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);

        JsonNode data = Envelope.data(resp);
        assertThat(data.isArray()).as("必须返回数组，不是把 userId 当路径参数的 ProfileVO").isTrue();
        assertThat(data.size()).as("不存在的 999999 不返回条目").isEqualTo(3);

        assertThat(urlOf(data, idA)).as("A 是自定义").isEqualTo(customA);
        assertThat(urlOf(data, idB)).as("B 选了默认 4").isEqualTo("/avatars/default-4.svg");
        assertThat(urlOf(data, idC)).as("C 没选过 ⇒ 按 userId%6+1 回落")
                .isEqualTo("/avatars/default-" + (idC % 6 + 1) + ".svg");
    }

    @Test
    @DisplayName("重复 userId 去重：同一作者多条评论/动态只返回一条")
    void 重复id去重() {
        String token = registerAndGetToken("13800004204", "batch-dup", PASSWORD);
        long userId = userIdOf("13800004204");

        ResponseEntity<String> resp = get("/api/v1/users/avatars?userIds="
                + userId + "," + userId + "," + userId, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode data = Envelope.data(resp);
        assertThat(data.size()).isEqualTo(1);
        assertThat(urlOf(data, userId)).isEqualTo("/avatars/default-" + (userId % 6 + 1) + ".svg");
    }

    @Test
    @DisplayName("缺参 / 空值 / 非法 / 101 个 ⇒ 400（上限按去重后计算）")
    void 参数非法400() {
        assertThat(get("/api/v1/users/avatars", null).getStatusCode().value()).isEqualTo(400);
        assertThat(get("/api/v1/users/avatars?userIds=", null).getStatusCode().value()).isEqualTo(400);
        assertThat(get("/api/v1/users/avatars?userIds=abc", null).getStatusCode().value()).isEqualTo(400);
        assertThat(get("/api/v1/users/avatars?userIds=1,,2", null).getStatusCode().value()).isEqualTo(400);

        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 101; i++) {
            if (i > 1) {
                many.append(',');
            }
            many.append(i);
        }
        assertThat(get("/api/v1/users/avatars?userIds=" + many, null).getStatusCode().value())
                .as("100 是上限；101 必须 400，不能把库打成一次超大 IN").isEqualTo(400);
    }

    // ==================== 夹具 ====================

    private ResponseEntity<String> uploadAvatar(byte[] content, String fileName, String contentType, String token) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(contentType));
        form.add("file", new HttpEntity<>(resource(content, fileName), headers));
        return putMultipart("/api/v1/users/me/avatar", form, token);
    }

    private static String urlOf(JsonNode data, long userId) {
        for (JsonNode item : data) {
            if (item.path("userId").asLong() == userId) {
                return item.path("avatarUrl").asString();
            }
        }
        throw new AssertionError("批量响应里缺少 userId=" + userId + "：" + data);
    }

    private static Resource resource(byte[] content, String fileName) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private static byte[] jpegBytes() {
        return new byte[]{0x01, 0x02, 0x03};
    }
}
