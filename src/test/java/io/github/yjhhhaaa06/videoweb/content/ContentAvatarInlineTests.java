package io.github.yjhhhaaa06.videoweb.content;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容面内联头像的契约测试（`R-37` / `T122`）。
 *
 * <h2>钉住什么</h2>
 * <ul>
 *   <li><b>详情 / 空间</b>两个有展示位的面：{@code avatarUrl} 必须非空，且等于
 *       "该内容作者"的生效 URL（不是整页一个常量）；动态卡在 `feed/FeedAvatarInlineTests`；</li>
 *   <li><b>按作者区分</b>：两个作者各用不同默认头像，同一响应里不能串；</li>
 *   <li><b>换头像后下一次读立即生效</b>：证明写后失效 + 读时装饰这条链真的通了；</li>
 *   <li><b>搜索面不填</b>：搜索结果卡不显示作者头像 ⇒ {@code avatarUrl} 保持 {@code null}
 *       （与"按面填"的计数口径一致，不是漏填）。</li>
 * </ul>
 *
 * <h2>独立 oracle</h2>
 * 期望 URL 由用例按 {@code userId % 6 + 1} 的默认头像规则**自己写死**，不调用生产
 * {@code AvatarRefs}；作者 id 直查 {@code users} 表。
 *
 * <h2>反向验证（注入 ⇒ 必红）</h2>
 * ① 去掉 {@code ContentAvatarFiller.fillAvatarBatch} ⇒ 空间断言红；
 * ② 去掉 {@code fillAvatar} ⇒ 详情断言红；
 * ③ 去掉 {@code AvatarService} 的 {@code invalidate} ⇒ "换头像后生效"红。
 */
class ContentAvatarInlineTests extends AbstractContentIntegrationTest {

    private static final String PHONE_AUTHOR = "13900009101";
    private static final String PHONE_OTHER = "13900009102";

    @Test
    @DisplayName("详情：作者头像内联，且按作者区分（默认 3 vs 默认 5）")
    void 详情_作者头像内联且按作者区分() {
        String authorToken = registerAndGetToken(PHONE_AUTHOR, "avatar-content-a");
        long authorId = userIdOf(PHONE_AUTHOR);
        chooseDefault(authorToken, 3);
        long c1 = insertVideoContent(authorId, "详情头像一");

        JsonNode d1 = Envelope.data(get("/api/v1/contents/" + c1, null));
        assertThat(d1.path("authorId").asLong()).isEqualTo(authorId);
        assertThat(d1.path("avatarUrl").asString())
                .as("详情必须内联作者生效头像，不能是 null / 空串")
                .isEqualTo("/avatars/default-3.svg");

        String otherToken = registerAndGetToken(PHONE_OTHER, "avatar-content-b");
        long otherId = userIdOf(PHONE_OTHER);
        chooseDefault(otherToken, 5);
        long c2 = insertVideoContent(otherId, "详情头像二");

        assertThat(Envelope.data(get("/api/v1/contents/" + c2, null)).path("avatarUrl").asString())
                .as("另一作者必须是另一张默认头像（防整页常量）")
                .isEqualTo("/avatars/default-5.svg");
    }

    @Test
    @DisplayName("空间 contentPage：投稿卡作者头像内联，且按作者区分")
    void 空间contentPage_作者头像内联() {
        String authorToken = registerAndGetToken(PHONE_AUTHOR, "avatar-space-a");
        long authorId = userIdOf(PHONE_AUTHOR);
        chooseDefault(authorToken, 3);
        long c1 = insertVideoContent(authorId, "空间头像一");

        JsonNode list = Envelope.data(get("/api/v1/users/" + authorId, null))
                .path("contentPage").path("list");
        assertThat(itemOf(list, c1).path("avatarUrl").asString())
                .isEqualTo("/avatars/default-3.svg");

        String otherToken = registerAndGetToken(PHONE_OTHER, "avatar-space-b");
        long otherId = userIdOf(PHONE_OTHER);
        chooseDefault(otherToken, 5);
        long c2 = insertVideoContent(otherId, "空间头像二");

        JsonNode otherList = Envelope.data(get("/api/v1/users/" + otherId, null))
                .path("contentPage").path("list");
        assertThat(itemOf(otherList, c2).path("avatarUrl").asString())
                .as("别人的空间不能串成前一个作者的头像")
                .isEqualTo("/avatars/default-5.svg");
    }

    @Test
    @DisplayName("★换头像后：下一次详情读立即拿到新 URL（写后失效 + 读时装饰）")
    void 换头像后下一次读立即生效() {
        String token = registerAndGetToken(PHONE_AUTHOR, "avatar-change");
        long authorId = userIdOf(PHONE_AUTHOR);
        chooseDefault(token, 3);
        long contentId = insertVideoContent(authorId, "换头像内容");

        assertThat(Envelope.data(get("/api/v1/contents/" + contentId, null)).path("avatarUrl").asString())
                .as("第一次读会预热 JVM 缓存")
                .isEqualTo("/avatars/default-3.svg");

        chooseDefault(token, 5);

        assertThat(Envelope.data(get("/api/v1/contents/" + contentId, null)).path("avatarUrl").asString())
                .as("没有写后失效的话，这里会继续读预热值（最长 5min）")
                .isEqualTo("/avatars/default-5.svg");
    }

    @Test
    @DisplayName("搜索面不填头像：avatarUrl 保持 null（搜索结果卡没有头像展示位）")
    void 搜索面不填头像() {
        String authorToken = registerAndGetToken(PHONE_AUTHOR, "avatar-search");
        long authorId = userIdOf(PHONE_AUTHOR);
        chooseDefault(authorToken, 3);
        long contentId = insertVideoContent(authorId, "头像搜索词");

        JsonNode list = Envelope.data(get("/api/v1/contents?keyword=头像搜索词", null)).path("list");
        assertThat(itemOf(list, contentId).path("avatarUrl").isNull())
                .as("本面不填 = null；与 count 的'按面可空'同一口径")
                .isTrue();
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
        throw new AssertionError("列表中找不到 contentId=" + contentId + "，实际=" + list);
    }
}
