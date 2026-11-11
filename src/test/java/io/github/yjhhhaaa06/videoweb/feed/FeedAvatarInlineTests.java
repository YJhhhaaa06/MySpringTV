package io.github.yjhhhaaa06.videoweb.feed;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态卡内联头像的契约测试（`R-37` / `T122`）。
 *
 * <p>★ 单独放在 {@code feed} 包：`ArchitectureTests` 规则 5 禁止 **content 依赖 feed**，
 * 本用例要用 {@link AbstractFeedIntegrationTest} 的收件箱 / 关注夹具，故必须归 feed 侧
 * （feed → content 是允许的方向）。
 *
 * <h2>钉住什么</h2>
 * 同一 `/feed` 响应里，两位作者的卡片各带各的 {@code avatarUrl}（防整页常量 / 串人）。
 *
 * <h2>反向验证</h2>
 * 去掉 {@code FeedService#renderPage} 里的 {@code fillAvatarBatch} ⇒ 两条断言变 null。
 */
class FeedAvatarInlineTests extends AbstractFeedIntegrationTest {

    private static final String PHONE_VIEWER = "13900009111";
    private static final String PHONE_A = "13900009112";
    private static final String PHONE_B = "13900009113";

    @Test
    @DisplayName("动态卡：两位作者的卡片各带各的头像（同一响应内不串）")
    void 动态卡_作者头像内联且按作者区分() {
        String viewerToken = registerAndGetToken(PHONE_VIEWER, "avatar-viewer");
        long viewerId = userIdOf(PHONE_VIEWER);

        String aToken = registerAndGetToken(PHONE_A, "avatar-feed-a");
        long aId = userIdOf(PHONE_A);
        chooseDefault(aToken, 3);
        long c1 = insertVideoContent(aId, "动态头像一");

        String bToken = registerAndGetToken(PHONE_B, "avatar-feed-b");
        long bId = userIdOf(PHONE_B);
        chooseDefault(bToken, 5);
        long c2 = insertVideoContent(bId, "动态头像二");

        insertFollowRow(viewerId, aId);
        insertFollowRow(viewerId, bId);

        JsonNode list = Envelope.data(get("/api/v1/feed", viewerToken)).path("list");
        assertThat(itemOf(list, c1).path("avatarUrl").asString())
                .as("A 的卡片带 default:3")
                .isEqualTo("/avatars/default-3.svg");
        assertThat(itemOf(list, c2).path("avatarUrl").asString())
                .as("两张卡不能共用第一个作者的头像")
                .isEqualTo("/avatars/default-5.svg");
    }

    private void chooseDefault(String token, int avatarId) {
        ResponseEntity<String> resp = put("/api/v1/users/me/avatar/default",
                Map.of("avatarId", avatarId), token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
    }

    private static JsonNode itemOf(JsonNode list, long contentId) {
        for (JsonNode item : list) {
            if (item.path("id").asLong() == contentId) {
                return item;
            }
        }
        throw new AssertionError("动态流中找不到 contentId=" + contentId + "，实际=" + list);
    }
}
