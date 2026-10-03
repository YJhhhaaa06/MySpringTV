package io.github.yjhhhaaa06.videoweb.admin;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S8：admin **内容运维**（{@code /api/admin/content/*}）的行为测试。
 *
 * <h2>语义翻译自哪些旧用例</h2>
 * 旧 pytest {@code test_hide_content.py}（一致性 + 边界两节）是本类的规格来源——
 * 意图保留、形式随架构重写（《测试策略》§四 纪律 3）：
 * <ul>
 *   <li>一致性：下架 → 详情 404 / 主页不列出 / 管理端 {@code hidden=true}；
 *       恢复 → 详情 200 且**媒体 URL 与计数逐字不变** / 主页重新列出 / {@code hidden=false}；</li>
 *   <li>边界：404（不存在）/ 409（重复下架、未下架恢复、已删除）/ 400（缺 contentId）/ 鉴权三角色。</li>
 * </ul>
 *
 * <h2>★ 为什么"下架后前台不可见"必须同时断三个面</h2>
 * 只断详情 404 是**不够**的：详情读的是内容缓存（{@code content:{id}}），而"主页不列出"
 * 走的是 DB 窗口查询（{@code WHERE is_deleted = 0}）——两者是**不同的**可见性路径，
 * 一个失效另一个不失效时，只测一边就会漏（旧 pytest 的原注释也点明了这一点：
 * 首页/搜索因随机排序与分词不稳定而不作断言，改用"详情 404 + 主页不列出"这类确定性项）。
 * 再加上管理端清单的 {@code hidden} 标志（读的是 {@code is_deleted IN (0,2)} 的原值），
 * 三个面合起来才能证明"下架"这个事实被三处一致地看见了。
 */
class AdminContentTests extends AbstractAdminIntegrationTest {

    private static final String ADMIN_PHONE = "13900002001";
    private static final String ADMIN = "admin-content";
    private static final String AUTHOR_PHONE = "13900002002";
    private static final String AUTHOR = "admin-content-author";

    // ========================================================================
    // 管理端清单
    // ========================================================================

    @Test
    @DisplayName("清单：包含正常(0)与下架(2)、排除已删除(1)，条目五键齐全且 hidden 口径正确")
    void 管理端清单_形状与过滤() {
        String adminToken = registerAdmin(ADMIN_PHONE, ADMIN);
        registerNormal(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);

        long normal = insertContent(authorId, CONTENT_TYPE_VIDEO, "清单_正常", "d", 1);
        long hidden = insertContent(authorId, CONTENT_TYPE_VIDEO, "清单_已下架", "d", 1);
        long deleted = insertContent(authorId, CONTENT_TYPE_VIDEO, "清单_已删除", "d", 1);
        jdbcTemplate.update("UPDATE content SET is_deleted = 2 WHERE id = ?", hidden);
        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", deleted);

        ResponseEntity<String> resp = get("/api/admin/content/list", adminToken);

        assertThat(resp.getStatusCode().value()).as("管理端清单应 200: %s", resp.getBody()).isEqualTo(200);
        JsonNode data = Envelope.data(resp);
        assertThat(data.isArray()).as("data 必须是数组（旧 pytest 的 shape 断言）").isTrue();

        List<Long> ids = new ArrayList<>();
        for (JsonNode item : data) {
            ids.add(item.path("id").asLong());
        }
        assertThat(ids).as("含正常与已下架").contains(normal, hidden);
        assertThat(ids).as("不含已删除(1) 的内容").doesNotContain(deleted);

        JsonNode hiddenItem = itemById(data, hidden);
        JsonNode normalItem = itemById(data, normal);
        assertThat(hiddenItem.path("hidden").asBoolean()).as("下架内容 hidden=true").isTrue();
        assertThat(normalItem.path("hidden").asBoolean()).as("正常内容 hidden=false").isFalse();

        // 五键齐全（旧 pytest：id / title / type / authorName / hidden）
        assertThat(normalItem.size()).as("条目应恰好五键（多/少都算回归）").isEqualTo(5);
        for (String key : List.of("id", "title", "type", "authorName", "hidden")) {
            assertThat(normalItem.has(key)).as("条目缺少键 " + key).isTrue();
        }
        assertThat(normalItem.path("title").asString()).isEqualTo("清单_正常");
        assertThat(normalItem.path("type").asInt()).isEqualTo(CONTENT_TYPE_VIDEO);
        assertThat(normalItem.path("authorName").asString()).isEqualTo(AUTHOR);
    }

    // ========================================================================
    // 下架 → 前台不可见 → 恢复 → 数据完好（一致性）
    // ========================================================================

    @Test
    @DisplayName("★一致性：下架后详情 404 + 主页不列出；恢复后详情 200 且媒体 URL 与计数逐字不变")
    void 下架恢复往返_数据完好() {
        String adminToken = registerAdmin(ADMIN_PHONE, ADMIN);
        registerNormal(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);

        long contentId = insertVideoContent(authorId, "往返标题");
        // 造评论与点赞（下架不得动它们——"隐藏≠删除"）
        insertMainComment(contentId, authorId, "往返评论");
        insertContentLike(authorId, contentId);
        jdbcTemplate.update("UPDATE content SET comment_count = 1, like_count = 1 WHERE id = ?", contentId);

        JsonNode before = Envelope.data(get("/search/IdSearch?contentId=" + contentId, null));
        String videoUrlBefore = before.path("videoUrl").asString();
        String coverUrlBefore = before.path("coverUrl").asString();
        int commentCountBefore = before.path("commentCount").asInt();
        int likeCountBefore = before.path("likeCount").asInt();

        // ---------- 下架 ----------
        ResponseEntity<String> hide = post("/api/admin/content/hide?contentId=" + contentId, null, adminToken);
        assertThat(hide.getStatusCode().value()).as("下架应 200: %s", hide.getBody()).isEqualTo(200);
        assertThat(Envelope.data(hide).asString()).isEqualTo("下架成功");

        assertThat(get("/search/IdSearch?contentId=" + contentId, null).getStatusCode().value())
                .as("下架后详情应 404").isEqualTo(404);
        assertThat(profileContentIds(authorId)).as("下架后作者主页不应再列出").doesNotContain(contentId);
        assertThat(itemById(Envelope.data(get("/api/admin/content/list", adminToken)), contentId)
                .path("hidden").asBoolean()).as("管理端清单 hidden=true").isTrue();

        // 独立 oracle：状态确实落库为 2，且评论/点赞行数未变
        assertThat(contentIsDeletedColumn(contentId)).isEqualTo(2);
        assertThat(commentRows(contentId)).as("下架不动评论").isEqualTo(1);
        assertThat(countLikeRows(contentId)).as("下架不动点赞记录").isEqualTo(1);

        // ---------- 恢复 ----------
        ResponseEntity<String> unhide = post("/api/admin/content/unhide?contentId=" + contentId, null, adminToken);
        assertThat(unhide.getStatusCode().value()).as("恢复应 200: %s", unhide.getBody()).isEqualTo(200);
        assertThat(Envelope.data(unhide).asString()).isEqualTo("恢复成功");

        JsonNode after = Envelope.data(get("/search/IdSearch?contentId=" + contentId, null));
        assertThat(after.path("videoUrl").asString()).as("视频 URL 应保持不变").isEqualTo(videoUrlBefore);
        assertThat(after.path("coverUrl").asString()).as("封面 URL 应保持不变").isEqualTo(coverUrlBefore);
        assertThat(after.path("commentCount").asInt()).isEqualTo(commentCountBefore);
        assertThat(after.path("likeCount").asInt()).isEqualTo(likeCountBefore);

        assertThat(profileContentIds(authorId)).as("恢复后作者主页应重新列出").contains(contentId);
        assertThat(itemById(Envelope.data(get("/api/admin/content/list", adminToken)), contentId)
                .path("hidden").asBoolean()).as("管理端清单 hidden=false").isFalse();
        assertThat(contentIsDeletedColumn(contentId)).isEqualTo(0);
    }

    // ========================================================================
    // 边界：404 / 409 / 400（异常类型与文案逐字保留）
    // ========================================================================

    @Test
    @DisplayName("下架边界：不存在 404；重复下架 409；已删除 409；缺 contentId 400")
    void 下架_边界() {
        String adminToken = registerAdmin(ADMIN_PHONE, ADMIN);
        registerNormal(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);

        // 不存在 → 404
        ResponseEntity<String> missing = post("/api/admin/content/hide?contentId=999999999", null, adminToken);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(Envelope.msg(missing)).as("TV 文案逐字保留").isEqualTo("内容不存在");

        // 重复下架 → 409
        long contentId = insertContent(authorId, CONTENT_TYPE_VIDEO, "下架边界", "d", 1);
        assertThat(post("/api/admin/content/hide?contentId=" + contentId, null, adminToken)
                .getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> again = post("/api/admin/content/hide?contentId=" + contentId, null, adminToken);
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(Envelope.msg(again)).isEqualTo("内容已下架");

        // 已删除(1) → 409
        long deleted = insertContent(authorId, CONTENT_TYPE_VIDEO, "下架边界_已删", "d", 1);
        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", deleted);
        ResponseEntity<String> onDeleted = post("/api/admin/content/hide?contentId=" + deleted, null, adminToken);
        assertThat(onDeleted.getStatusCode().value()).isEqualTo(409);
        assertThat(Envelope.msg(onDeleted)).isEqualTo("内容已删除，无法下架");

        // 缺 contentId → 400（旧版手写文案，新版走全局出口——契约差异见《决策留痕表》D-12）
        assertThat(post("/api/admin/content/hide", null, adminToken).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("恢复边界：不存在 404；未下架 409；已删除 409；缺 contentId 400")
    void 恢复_边界() {
        String adminToken = registerAdmin(ADMIN_PHONE, ADMIN);
        registerNormal(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);

        ResponseEntity<String> missing = post("/api/admin/content/unhide?contentId=999999999", null, adminToken);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(Envelope.msg(missing)).isEqualTo("内容不存在");

        // 正常态（未下架）→ 409
        long normal = insertContent(authorId, CONTENT_TYPE_VIDEO, "恢复边界_正常", "d", 1);
        ResponseEntity<String> onNormal = post("/api/admin/content/unhide?contentId=" + normal, null, adminToken);
        assertThat(onNormal.getStatusCode().value()).isEqualTo(409);
        assertThat(Envelope.msg(onNormal)).isEqualTo("内容未下架");

        // 已删除(1) → 409
        long deleted = insertContent(authorId, CONTENT_TYPE_VIDEO, "恢复边界_已删", "d", 1);
        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", deleted);
        ResponseEntity<String> onDeleted = post("/api/admin/content/unhide?contentId=" + deleted, null, adminToken);
        assertThat(onDeleted.getStatusCode().value()).isEqualTo(409);
        assertThat(Envelope.msg(onDeleted)).isEqualTo("内容已删除，无法恢复");

        assertThat(post("/api/admin/content/unhide", null, adminToken).getStatusCode().value()).isEqualTo(400);
    }

    // ========================================================================
    // ★ 声明式机制证明：AFTER_COMMIT（下架移除缓存 / 恢复写回缓存）
    // ========================================================================

    @Test
    @DisplayName("★机制证明：下架/恢复的缓存变更发生在提交后——全程不读详情也生效（含索引剔除）")
    void 下架恢复_缓存变更在提交后生效() {
        String adminToken = registerAdmin(ADMIN_PHONE, ADMIN);
        registerNormal(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "缓存机制标题");

        // 预热：读详情填 content key；读 /start 触发索引懒重建（建出 content:index:* 四个键）
        assertThat(get("/search/IdSearch?contentId=" + contentId, null).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/start", null).getStatusCode().value()).isEqualTo(200);
        assertThat(cachedContentJson(contentId)).as("前置：内容 key 应已被读路径回填").isNotNull();
        assertThat(indexContains(contentId)).as("前置：/start 之后内容应已进索引").isTrue();

        // 下架：此后**不再读任何接口**，直接查 Redis
        assertThat(post("/api/admin/content/hide?contentId=" + contentId, null, adminToken)
                .getStatusCode().value()).isEqualTo(200);

        assertThat(cachedContentJson(contentId))
                .as("下架提交后 content key 必须被移除（REMOVE 分支，非本方法内直改缓存）")
                .isNull();
        assertThat(indexContains(contentId))
                .as("下架必须把 id 从**全部**索引 key 里剔除，否则首页会反复探到一个永远 null 的 id")
                .isFalse();

        // 恢复：同样不读接口 ⇒ content key 只能来自 AFTER_COMMIT 的 REFRESH
        assertThat(post("/api/admin/content/unhide?contentId=" + contentId, null, adminToken)
                .getStatusCode().value()).isEqualTo(200);

        String cached = cachedContentJson(contentId);
        assertThat(cached)
                .as("恢复提交后内容 key 必须已被 REFRESH 写回；若为空则说明提交后副作用未触发"
                        + "（或副作用被写在了事务内、随回滚丢失）")
                .isNotNull()
                .contains("缓存机制标题");
    }

    // ==================== 夹具 ====================

    /** 管理端清单里 {@code id == contentId} 的条目；不存在则断言失败。 */
    private static JsonNode itemById(JsonNode list, long contentId) {
        for (JsonNode item : list) {
            if (item.path("id").asLong() == contentId) {
                return item;
            }
        }
        throw new AssertionError("管理端清单应包含 contentId=" + contentId + "，实际: " + list);
    }

    /** {@code GET /profile?userId=X} 第 1 页的内容 id 列表。 */
    private List<Long> profileContentIds(long authorId) {
        JsonNode page = Envelope.data(get("/profile?userId=" + authorId, null)).path("contentPage").path("list");
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : page) {
            ids.add(item.path("id").asLong());
        }
        return ids;
    }
}
