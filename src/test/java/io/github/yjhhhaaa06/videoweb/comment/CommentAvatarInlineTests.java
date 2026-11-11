package io.github.yjhhhaaa06.videoweb.comment;

import io.github.yjhhhaaa06.videoweb.content.AbstractContentIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评论面内联头像的契约测试（`R-37` / `T123`）。
 *
 * <h2>钉住什么</h2>
 * <ul>
 *   <li>主楼 + 楼中楼都带 {@code avatarUrl}，且各自等于**该条作者**的生效 URL；</li>
 *   <li>同一棵树里两个作者不串（A 默认 3 / B 默认 5）；</li>
 *   <li>第一次读会走缓存装配，第二次读命中评论缓存——两次都必须带当前头像；</li>
 *   <li>换头像后下一次读立即生效（写后失效 + 读时装饰）；另一作者不受影响（对照）；</li>
 *   <li>匿名可读（头像不是登录态数据）。</li>
 * </ul>
 *
 * <h2>独立 oracle</h2>
 * 期望 URL 按 {@code userId % 6 + 1} 自己写死；作者 id 直查 {@code users}。
 *
 * <h2>反向验证（注入 ⇒ 必红）</h2>
 * ① 去掉 {@code collectUserIds} 对 children 的递归 ⇒ 楼中楼断言红；
 * ② 去掉 {@code vo.setAvatarUrl(...)} ⇒ 主楼断言红；
 * ③ 去掉 {@code AvatarService} 的 {@code invalidate} ⇒ "换头像后生效"红。
 */
class CommentAvatarInlineTests extends AbstractContentIntegrationTest {

    private static final String PHONE_ROOT = "13900009201";
    private static final String PHONE_REPLY = "13900009202";

    @Test
    @DisplayName("主楼 + 楼中楼：各带各的作者头像（同一棵树不串）")
    void 评论树_主楼与楼中楼各带各的头像() {
        String rootToken = registerAndGetToken(PHONE_ROOT, "comment-avatar-root");
        long rootUserId = userIdOf(PHONE_ROOT);
        chooseDefault(rootToken, 3);

        String replyToken = registerAndGetToken(PHONE_REPLY, "comment-avatar-reply");
        long replyUserId = userIdOf(PHONE_REPLY);
        chooseDefault(replyToken, 5);

        long contentId = insertVideoContent(rootUserId, "评论头像内容");
        long rootId = insertComment(contentId, rootUserId, "主楼", null, null);
        long childId = insertComment(contentId, replyUserId, "楼中楼", rootId, rootUserId);
        setReplyCount(rootId, 1);

        JsonNode data = Envelope.data(get("/api/v1/contents/" + contentId + "/comments", null));
        JsonNode root = itemOf(data.path("list"), rootId);
        assertThat(root.path("userId").asLong()).isEqualTo(rootUserId);
        assertThat(root.path("avatarUrl").asString())
                .as("主楼作者 A 的头像")
                .isEqualTo("/avatars/default-3.svg");

        JsonNode child = itemOf(root.path("children"), childId);
        assertThat(child.path("userId").asLong()).isEqualTo(replyUserId);
        assertThat(child.path("avatarUrl").asString())
                .as("楼中楼作者 B 的头像，不能串成 A")
                .isEqualTo("/avatars/default-5.svg");
    }

    @Test
    @DisplayName("★缓存命中路径也带头像；换头像后下一次读立即生效，另一作者不受影响")
    void 缓存命中与换头像() {
        String rootToken = registerAndGetToken(PHONE_ROOT, "comment-avatar-change-root");
        long rootUserId = userIdOf(PHONE_ROOT);
        chooseDefault(rootToken, 3);

        String replyToken = registerAndGetToken(PHONE_REPLY, "comment-avatar-change-reply");
        long replyUserId = userIdOf(PHONE_REPLY);
        chooseDefault(replyToken, 5);

        long contentId = insertVideoContent(rootUserId, "评论换头像内容");
        long rootId = insertComment(contentId, rootUserId, "主楼", null, null);
        long childId = insertComment(contentId, replyUserId, "楼中楼", rootId, rootUserId);
        setReplyCount(rootId, 1);

        // 第一次：评论缓存 miss，走 DB 装配 + 头像解析。
        JsonNode first = Envelope.data(get("/api/v1/contents/" + contentId + "/comments", null));
        assertThat(itemOf(first.path("list"), rootId).path("avatarUrl").asString())
                .isEqualTo("/avatars/default-3.svg");
        assertThat(redisKeys())
                .as("第一次读必须把评论树写进缓存；否则'第二次命中'只是口头声明")
                .anyMatch(key -> key.startsWith("content:comments:" + contentId));

        // 第二次：评论缓存 hit；头像仍必须由读时装饰给出（不在缓存载荷里）。
        JsonNode cached = Envelope.data(get("/api/v1/contents/" + contentId + "/comments", null));
        assertThat(itemOf(cached.path("list"), rootId).path("avatarUrl").asString())
                .as("缓存命中不能丢头像字段")
                .isEqualTo("/avatars/default-3.svg");

        // 换 A 的头像：下一次读必须拿到新 URL；B 的楼中楼不受影响（对照）。
        chooseDefault(rootToken, 1);

        JsonNode changed = Envelope.data(get("/api/v1/contents/" + contentId + "/comments", null));
        JsonNode root = itemOf(changed.path("list"), rootId);
        assertThat(root.path("avatarUrl").asString())
                .as("没有写后失效的话，这里会继续读预热值")
                .isEqualTo("/avatars/default-1.svg");
        assertThat(itemOf(root.path("children"), childId).path("avatarUrl").asString())
                .as("另一作者的头像不能被一起改掉")
                .isEqualTo("/avatars/default-5.svg");
    }

    private void chooseDefault(String token, int avatarId) {
        ResponseEntity<String> resp = put("/api/v1/users/me/avatar/default",
                Map.of("avatarId", avatarId), token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
    }

    private static JsonNode itemOf(JsonNode list, long id) {
        for (JsonNode item : list) {
            if (item.path("commentId").asLong() == id) {
                return item;
            }
        }
        throw new AssertionError("评论树中找不到 commentId=" + id + "，实际=" + list);
    }
}
