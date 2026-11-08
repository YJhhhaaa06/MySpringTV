package io.github.yjhhhaaa06.videoweb.follow;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关注 / 粉丝列表内联头像的契约测试（`R-37` / `T124`）。
 *
 * <h2>钉住什么</h2>
 * <ul>
 *   <li>following / followers 两个方向都带 {@code avatarUrl}；</li>
 *   <li>值等于**条目本人**的生效 URL —— 已设置的用户是自定义默认，未设置的用户按
 *       {@code userId % 6 + 1} 回落（两条不同的来源，防"整页一个常量"）；</li>
 *   <li>顺手多读的 {@code avatar_ref} 不改列表顺序 / 分页语义（ORDER BY id 不动）。</li>
 * </ul>
 *
 * <h2>独立 oracle</h2>
 * 未设置者的期望 URL 由用例按 {@code userId % 6 + 1} 自己算；已设置者写死 {@code default:5}。
 *
 * <h2>反向验证（注入 ⇒ 必红）</h2>
 * 去掉 {@code findUsersByIds} 的 {@code avatar_ref} 列 ⇒ 已设置者仍回落、断言红。
 */
class FollowAvatarInlineTests extends AbstractFollowIntegrationTest {

    private static final String PHONE_A = "13900009301";
    private static final String PHONE_B = "13900009302";

    @Test
    @DisplayName("following / followers：各带条目本人的头像（已设置 vs 未设置回落）")
    void 关注与粉丝列表各带各的头像() {
        TestUser a = register(PHONE_A, "follow-avatar-a");
        TestUser b = register(PHONE_B, "follow-avatar-b");

        // ★ 不能写死 default:5：A 的回落值 = a.id()%6+1，随自增 id 变。
        //   写死 5 会 1/6 误红（A 回落正好是 5），也会 1/6 假绿（去掉 avatar_ref 列后
        //   B 的回落正好也是 5）。用 (r+2)%6+1 保证 pick 与 A 的回落、B 自己的回落都不同。
        int r = (int) (a.id() % 6);
        int aFallback = r + 1;
        int bPick = (r + 2) % 6 + 1;
        assertThat(bPick).as("夹具自检：B 的选择不能撞 A 的回落")
                .isNotEqualTo(aFallback);
        assertThat(bPick).as("夹具自检：B 的选择不能撞 B 自己的回落（否则去列假绿）")
                .isNotEqualTo((int) ((a.id() + 1) % 6) + 1);

        ResponseEntity<String> setAvatar = put("/api/v1/users/me/avatar/default",
                Map.of("avatarId", bPick), b.token());
        assertThat(setAvatar.getStatusCode().value()).as("%s", setAvatar.getBody()).isEqualTo(200);

        assertThat(follow(b.id(), a.token()).getStatusCode().value()).isEqualTo(200);

        JsonNode following = Envelope.data(getFollowing(a.id(), a.token())).path("list");
        JsonNode bItem = itemOf(following, b.id());
        assertThat(bItem.path("userId").asLong()).isEqualTo(b.id());
        assertThat(bItem.path("avatarUrl").asString())
                .as("following 里 B 是已设置的 default:%s", bPick)
                .isEqualTo("/avatars/default-" + bPick + ".svg");

        JsonNode followers = Envelope.data(getFollowers(b.id(), b.token())).path("list");
        JsonNode aItem = itemOf(followers, a.id());
        assertThat(aItem.path("userId").asLong()).isEqualTo(a.id());
        assertThat(aItem.path("avatarUrl").asString())
                .as("followers 里 A 没设置过 ⇒ 按 userId%6+1 回落（独立复算）")
                .isEqualTo("/avatars/default-" + aFallback + ".svg");
        assertThat(aItem.path("avatarUrl").asString())
                .as("不能把 B 的头像串给 A")
                .isNotEqualTo("/avatars/default-" + bPick + ".svg");
    }

    private static JsonNode itemOf(JsonNode list, long userId) {
        for (JsonNode item : list) {
            if (item.path("userId").asLong() == userId) {
                return item;
            }
        }
        throw new AssertionError("列表中找不到 userId=" + userId + "，实际=" + list);
    }
}
