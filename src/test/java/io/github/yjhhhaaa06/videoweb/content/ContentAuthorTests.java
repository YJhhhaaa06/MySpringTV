package io.github.yjhhhaaa06.videoweb.content;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5：content **作者写路径**（{@code /content/delete}、{@code /content/mediaDelete}）的契约测试。
 *
 * <h2>★ 本类最重要的是"级联的四条独立 oracle"</h2>
 * 删作品要同时改**四张表**：{@code content}（软删）、{@code comment}（级联软删）、
 * {@code content_like}（物理删）、{@code content_media}（物理删）。
 * 旧 pytest 的写法是"删完调接口看返回 404"——那只证明了内容读不到，
 * **证明不了**评论/点赞/媒体有没有跟着清。故这里对每一张表各用一条 SQL 独立复算。
 *
 * <h2>另外三组</h2>
 * <ul>
 *   <li><b>删单图的 {@code sort} 重排</b>：删中间一张后剩余图片必须是 {@code 1..n} 连续
 *       （前端靠 {@code index+1} 定位）。</li>
 *   <li><b>提交后三处缓存都被失效</b>：内容 key、评论两键组、类型分区索引。
 *       只证明其中一处被失效不足以证明链路完整——而这正是"一次业务事实、三处缓存"的全部。</li>
 *   <li><b>权限与边界</b>：非作者 403（且不得产生任何写入）、不存在 404、未登录 401。</li>
 * </ul>
 *
 * <h2>★ `T63` 契约跟随（G8）：删图的选择参数 {@code sort} → {@code url}</h2>
 * 本类原有的 4 处 {@code query("type","2","sort",…)} 一并改成 {@code query("type","2","url",…)}。
 * <b>依据</b>：《接口契约.md》§2.4①（"重复删除 = 404，已删亦视为不存在"）+ §2.5（DELETE 的选择参数走 query）
 * —— {@code sort} 是**可变位置**（删完一张 {@code compactImageSort} 就把后面的前移），
 * 于是"重试删第 2 张"会删掉**原来的第 3 张**且仍回 204（全站唯一"重试破坏另一个对象"的面）。
 * 这不是"把期望值改成实际值"：期望值仍从契约推导（§2.4① 已删视为不存在 ⇒ 重试 404），
 * 变的只是**定位载体**。反向验证：把定位改回 {@code sort} ⇒
 * {@link #删单图_重试同一url只可能404且不误删下一张()} 必红（终态只剩第 1 张）。
 */
class ContentAuthorTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900006001";
    private static final String AUTHOR = "author-path";
    private static final String OTHER_PHONE = "13900006002";
    private static final String OTHER = "author-other";

    // ========================================================================
    // /content/delete
    // ========================================================================

    @Test
    @DisplayName("★删作品：四张表的终态各用一条独立 oracle 复算（content/comment/content_like/content_media）")
    void 删作品_级联四条独立oracle() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        String otherToken = registerAndGetToken(OTHER_PHONE, OTHER);
        long authorId = userIdOf(AUTHOR_PHONE);
        long otherId = userIdOf(OTHER_PHONE);
        long contentId = insertPostContent(authorId, "待删除作品");

        long main = insertMainComment(contentId, otherId, "主楼评论");
        insertComment(contentId, otherId, "回复 1", main, null);
        insertComment(contentId, otherId, "回复 2", main, null);
        insertContentLike(otherId, contentId);
        insertContentLike(authorId, contentId);

        // 预热三处缓存：内容 key / 评论两键组 / 类型分区索引
        assertThat(get("/api/v1/contents/" + contentId, token).getStatusCode().value()).isEqualTo(200);
        get("/api/v1/contents/" + contentId + "/comments?page=1&pageSize=10", token);
        get("/api/v1/contents/recommended", null);
        assertThat(cachedContentJson(contentId)).as("前置：内容 key 已缓存").isNotNull();
        assertThat(redis.hasKey(rootsKey(contentId))).as("前置：评论 roots 已装载").isTrue();
        assertThat(indexContains(contentId)).as("前置：内容在推荐索引里").isTrue();

        assertThat(deleteQuery("/api/v1/contents/" + String.valueOf(contentId), query(), token)
                .getStatusCode().value()).as("`T59`：删除 = 204").isEqualTo(204);

        // 四条独立 oracle
        assertThat(contentIsDeletedColumn(contentId)).as("① content.is_deleted = 1").isEqualTo(1);
        assertThat(countSoftDeletedComments(contentId)).as("② 三条评论全部 is_deleted=1")
                .isEqualTo(3);
        assertThat(countLikeRows(contentId)).as("③ content_like 物理删空").isZero();
        assertThat(countMediaRows(contentId)).as("④ content_media 物理删空").isZero();

        // 三处缓存都被失效（只查一处不足以证明链路完整）
        assertThat(cachedContentJson(contentId)).as("内容 key 被剔除").isNull();
        assertThat(redis.hasKey(rootsKey(contentId))).as("评论 roots 被整组失效").isFalse();
        assertThat(redis.hasKey(repliesKey(contentId))).as("评论 replies 也被整组失效").isFalse();
        assertThat(indexContains(contentId)).as("从推荐索引里剔除").isFalse();

        // 反向对照：别人的内容不受影响（排除"删除清空全库"的假绿）
        long otherContentId = insertVideoContent(otherId, "别人的内容");
        assertThat(countMediaRows(otherContentId)).isPositive();
        assertThat(deleteQuery("/api/v1/contents/" + String.valueOf(otherContentId), query(), otherToken)
                .getStatusCode().value()).as("`T59`：删除 = 204").isEqualTo(204);
        assertThat(countMediaRows(contentId)).as("自己的那些行没有被「复活」").isZero();
    }

    @Test
    @DisplayName("★删作品：共享 URL 仍被另一作品引用时**不得删物理文件**；最后一个引用者删除才清")
    void 删作品_共享URL不误删文件() throws IOException {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long first = insertContent(authorId, CONTENT_TYPE_IMAGE, "共享A", "简介", 2);
        long second = insertContent(authorId, CONTENT_TYPE_IMAGE, "共享B", "简介", 2);
        String shared = "/upload/image/shared-delete.png";
        insertMedia(first, shared, MEDIA_TYPE_IMAGE, 1);
        insertMedia(second, shared, MEDIA_TYPE_IMAGE, 1);

        Path file = Path.of(testMediaRoot()).resolve("image").resolve("shared-delete.png");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        assertThat(Files.exists(file)).isTrue();

        assertThat(deleteQuery("/api/v1/contents/" + first, query(), token)
                .getStatusCode().value()).isEqualTo(204);
        assertThat(Files.exists(file))
                .as("第二个作品仍引用该 URL ⇒ 文件必须保留（否则删 A 会打穿 B）").isTrue();

        assertThat(deleteQuery("/api/v1/contents/" + second, query(), token)
                .getStatusCode().value()).isEqualTo(204);
        assertThat(Files.exists(file)).as("最后一个引用者删除后才清理物理文件").isFalse();
    }

    @Test
    @DisplayName("删作品：非作者 403 且**不产生任何写入**；不存在/已删 404；未登录 401")
    void 删作品_权限与边界() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        String otherToken = registerAndGetToken(OTHER_PHONE, OTHER);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertPostContent(authorId, "受保护作品");
        long main = insertMainComment(contentId, authorId, "评论");
        insertContentLike(authorId, contentId);

        ResponseEntity<String> forbidden = deleteQuery("/api/v1/contents/" + String.valueOf(contentId),query(), otherToken);
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(Envelope.msg(forbidden)).isEqualTo("只能操作自己的作品");
        assertThat(contentIsDeletedColumn(contentId)).as("403 时内容不得被软删").isZero();
        assertThat(countMediaRows(contentId)).as("403 时媒体行不得被删").isEqualTo(3);
        assertThat(countLikeRows(contentId)).as("403 时点赞行不得被删").isEqualTo(1);
        assertThat(countSoftDeletedComments(contentId)).as("403 时评论不得被软删").isZero();

        assertThat(deleteQuery("/api/v1/contents/" + "987654321", query(), authorToken)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(deleteQuery("/api/v1/contents/" + String.valueOf(contentId), query(), null)
                .getStatusCode().value()).as("未登录 ⇒ 401").isEqualTo(401);
        assertThat(main).isPositive();
    }

    // ========================================================================
    // /content/mediaDelete
    // ========================================================================

    @Test
    @DisplayName("★删单图：行数减一，且剩余图片 sort 重排为 1..n 连续（前端 index+1 定位的前提）")
    void 删单图_sort重排() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertContent(authorId, CONTENT_TYPE_IMAGE, "三图内容", "简介", 2);
        insertMedia(contentId, "/upload/image/a.jpg", MEDIA_TYPE_IMAGE, 1);
        insertMedia(contentId, "/upload/image/b.jpg", MEDIA_TYPE_IMAGE, 2);
        insertMedia(contentId, "/upload/image/c.jpg", MEDIA_TYPE_IMAGE, 3);
        insertMedia(contentId, "/upload/cover/x.png", MEDIA_TYPE_COVER, 1);

        assertThat(mediaTypeSorts(contentId, MEDIA_TYPE_IMAGE)).containsExactly("2:1", "2:2", "2:3");

        // 删中间那张（`T63`：按稳定标识 url 定位，不再用可变位置 sort）
        assertThat(deleteQuery("/api/v1/contents/" + String.valueOf(contentId) + "/media",
                query("type", "2", "url", "/upload/image/b.jpg"), token)
                .getStatusCode().value()).as("`T59`：删除 = 204").isEqualTo(204);

        assertThat(mediaTypeSorts(contentId, MEDIA_TYPE_IMAGE))
                .as("删中间后必须仍是 1..n 连续（sort=3 的那张被重排为 2）").containsExactly("2:1", "2:2");
        assertThat(jdbcTemplate.queryForList(
                "SELECT url FROM content_media WHERE content_id = ? ORDER BY type, sort", String.class, contentId))
                .as("b.jpg 的记录确实被删了（不是只改了 sort）")
                .containsExactly("/upload/image/a.jpg", "/upload/image/c.jpg", "/upload/cover/x.png");

        // 详情回读：imageUrls 只剩两张（提交后 REFRESH 生效）
        assertThat(Envelope.data(get("/api/v1/contents/" + contentId, token))
                .path("imageUrls").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("删单图：type != 2 → 400『仅支持删除图片』（视频/封面是结构性资源）；媒体不存在 → 404")
    void 删单图_只允许图片() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "视频内容");

        ResponseEntity<String> video = deleteQuery("/api/v1/contents/" + String.valueOf(contentId) + "/media",
                query("type", "1", "url", "/upload/video/" + contentId + ".mp4"), token);
        assertThat(video.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(video)).isEqualTo("仅支持删除图片");
        assertThat(countMediaRows(contentId)).as("400 时不得删除任何行").isEqualTo(2);

        ResponseEntity<String> missing = deleteQuery("/api/v1/contents/" + String.valueOf(contentId) + "/media",
                query("type", "2", "url", "/upload/image/never-uploaded.jpg"), token);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(Envelope.msg(missing)).isEqualTo("媒体资源不存在");

        assertThat(deleteQuery("/api/v1/contents/" + String.valueOf(contentId) + "/media",
                query("type", "2", "url", "/upload/video/" + contentId + ".mp4"), null)
                .getStatusCode().value()).as("未登录 ⇒ 401").isEqualTo(401);
    }

    @Test
    @DisplayName("★删单图重试：同一 url 再删一次 ⇒ 404，且不会删到下一张（`T63` 的存在理由）")
    void 删单图_重试同一url只可能404且不误删下一张() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertContent(authorId, CONTENT_TYPE_IMAGE, "三图重试", "简介", 2);
        insertMedia(contentId, "/upload/image/a.jpg", MEDIA_TYPE_IMAGE, 1);
        insertMedia(contentId, "/upload/image/b.jpg", MEDIA_TYPE_IMAGE, 2);
        insertMedia(contentId, "/upload/image/c.jpg", MEDIA_TYPE_IMAGE, 3);

        // 与客户端完全相同的请求（模拟"丢了 2xx 后自动重试"）
        MultiValueMap<String, String> deleteMiddle =
                query("type", "2", "url", "/upload/image/b.jpg");
        String path = "/api/v1/contents/" + contentId + "/media";

        assertThat(deleteQuery(path, deleteMiddle, token).getStatusCode().value())
                .as("首次删除 = 204").isEqualTo(204);

        ResponseEntity<String> retry = deleteQuery(path, deleteMiddle, token);
        assertThat(retry.getStatusCode().value())
                .as("重试必须是 404（§2.4①：已删视为不存在）——若回 204，说明它删掉了**另一张**")
                .isEqualTo(404);
        assertThat(Envelope.msg(retry)).isEqualTo("媒体资源不存在");

        // 独立 oracle：终态 url 集合 = 原第 1、3 张（第 3 张必须还在）
        assertThat(imageUrlsOf(contentId))
                .as("重试不得命中下一张：c.jpg 必须仍在")
                .containsExactly("/upload/image/a.jpg", "/upload/image/c.jpg");
        assertThat(mediaTypeSorts(contentId, MEDIA_TYPE_IMAGE))
                .as("两次请求只允许压缩一次 sort").containsExactly("2:1", "2:2");
    }

    @Test
    @DisplayName("★并发删同一 url：恰好一个 204、一个 404，且剩余 sort 仍 1..n（打红『不看 affected rows / 再压一次』）")
    void 并发删同一url_恰好一个成功一个404() throws Exception {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertContent(authorId, CONTENT_TYPE_IMAGE, "三图并发", "简介", 2);
        insertMedia(contentId, "/upload/image/a.jpg", MEDIA_TYPE_IMAGE, 1);
        insertMedia(contentId, "/upload/image/b.jpg", MEDIA_TYPE_IMAGE, 2);
        insertMedia(contentId, "/upload/image/c.jpg", MEDIA_TYPE_IMAGE, 3);

        String path = "/api/v1/contents/" + contentId + "/media";
        MultiValueMap<String, String> deleteMiddle =
                query("type", "2", "url", "/upload/image/b.jpg");

        // CountDownLatch 真同时起跑（不是"提交后各跑各的"）：两个线程都阻塞在 gate 上再一起冲
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>(2);
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return deleteQuery(path, deleteMiddle, token).getStatusCode().value();
                }));
            }
            startGate.countDown();
            List<Integer> statuses = new ArrayList<>(2);
            for (Future<Integer> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
            assertThat(statuses)
                    .as("并发双删同一行：恰好一个 204、一个 404（0 行 affected ⇒ 404）")
                    .containsExactlyInAnyOrder(204, 404);
        } finally {
            pool.shutdownNow();
        }

        // 独立 oracle：只剩 a、c，且 sort 仍 1..2（输家不得再压一次 sort）
        assertThat(imageUrlsOf(contentId))
                .as("只删掉 b.jpg").containsExactly("/upload/image/a.jpg", "/upload/image/c.jpg");
        assertThat(mediaTypeSorts(contentId, MEDIA_TYPE_IMAGE))
                .as("只有一次删除成功 ⇒ 只压缩一次：c 从 3 变 2；若两个请求都压缩，会变成 1、1")
                .containsExactly("2:1", "2:2");
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    /** 该内容全部媒体 url（按 {@code type, sort} 排序；独立 oracle，不读接口回显）。 */
    private List<String> imageUrlsOf(long contentId) {
        return jdbcTemplate.queryForList(
                "SELECT url FROM content_media WHERE content_id = ? AND type = ? ORDER BY sort",
                String.class, contentId, MEDIA_TYPE_IMAGE);
    }

    private int countSoftDeletedComments(long contentId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE content_id = ? AND is_deleted = 1",
                Integer.class, contentId);
        return n == null ? 0 : n;
    }
}
