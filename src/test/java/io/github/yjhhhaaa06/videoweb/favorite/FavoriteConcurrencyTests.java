package io.github.yjhhhaaa06.videoweb.favorite;

import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收藏写路径的**并发语义**（`T65`）：同一 {@code (user, folder, content)} 并发收藏 ⇒ 两个 200、一行、
 * 计数 1。
 *
 * <h2>为什么必须有这一条（它判的是什么）</h2>
 * `T65` 把 {@code POST /api/v1/favorites} 的重复收藏从 409 改成 200（终态已达成），
 * 而"怎么判重复"才是真正的实现选择：
 * <ul>
 *   <li>✅ 判据 = {@code INSERT IGNORE} 的**受影响行数**（0 行 ⇒ 别人先插进去了）；</li>
 *   <li>❌ 判据 = "先 SELECT 再 INSERT"—— 两个事务同时查到"没有"，随后一个必然撞
 *       {@code uk_folder_content} ⇒ 409 / 500。**单线程用例抓不到这个窗口**
 *       （顺序执行时先查必命中），所以这条并发用例是唯一能把"只靠先查后插"判红的断言。</li>
 * </ul>
 * 夹具照 {@code CouponConcurrencyTests} 的 {@code CountDownLatch} 双闸：全部线程先卡在
 * {@code startGate}，主线程放行后一起冲 —— "提交后各跑各的"造不出同一行的锁竞争。
 *
 * <h2>断言（全部是可观察终态，不断言内部调用）</h2>
 * <ol>
 *   <li>两个请求**都是 200**（幂等成功，不是"一个成功一个 409"）；</li>
 *   <li>{@code favorite_item} 里该 {@code (folder, content)} **恰 1 行**；</li>
 *   <li>★ 独立 oracle 直查 {@code content.favorite_count}，并用第二套算法
 *       {@code COUNT(DISTINCT user_id)} 复算 —— 两者一致（不读接口回显、不读缓存）；</li>
 *   <li>★ <b>对照夹具</b>：另一用户对同一内容的收藏、以及同一用户的另一夹 / 另一内容
 *       都不受影响（去掉 {@code WHERE} 项或写错维度 ⇒ 这几条必红）。</li>
 * </ol>
 *
 * <h2>反向验证（主会话提交后实测）</h2>
 * 把 {@code FavoriteService#insertIdempotent} 的 {@code insertItemIgnore} 换回裸 {@code INSERT}
 * ⇒ 本类红（输家撞唯一键 ⇒ 409/500，"两个 200"的断言先倒）。
 */
class FavoriteConcurrencyTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    /** 任务口径就是"两线程"：两个并发请求已足以覆盖"同一行插入"的竞争窗口。 */
    private static final int THREADS = 2;

    @BeforeEach
    void 清空本域相关表() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content");
    }

    @Test
    @DisplayName("★同一 (user, folder, content) 两线程并发收藏：两个 200、favorite_item 恰 1 行、favorite_count = 1")
    void 并发重复收藏两个都成功且只留一行() throws Exception {
        TestUser me = register("13800008001", "并发收藏人");
        TestUser other = register("13800008002", "另一个人");

        long folder = insertFolderRow(me.id, "并发夹");
        long content = insertContent(me.id);
        // 对照①：同一用户的**另一夹**里收**另一内容**（走真实端点 ⇒ favorite_count 也由写路径维护）
        long otherFolder = insertFolderRow(me.id, "另一夹");
        long otherContent = insertContent(me.id);
        assertThat(addItem(me.token, otherContent, otherFolder).getStatusCode().value()).isEqualTo(200);
        // 对照②：**另一用户**把**同一内容**收进他自己的夹 —— 并发重复不得动他那一行，
        //         收藏数也必须是"按人去重"的 2（本人才算 1 份）
        long strangersFolder = insertFolderRow(other.id, "别人的夹");
        assertThat(addItem(other.token, content, strangersFolder).getStatusCode().value()).isEqualTo(200);
        assertThat(favoriteCountOf(content)).as("前提：另一用户已收 ⇒ 计数 1").isEqualTo(1L);

        List<Integer> statuses = runConcurrentAdds(me.token, folder, content);

        assertThat(statuses)
                .as("★ 两个并发重复收藏都必须 200 —— 出现 409/500 说明判重只靠'先查后插'（并发下必然漏）")
                .containsExactly(200, 200);

        // 独立 oracle：行数与计数（不读接口回显、不读缓存）
        assertThat(itemRowCount(folder, content)).as("该 (folder, content) 恰 1 行").isOne();
        assertThat(oracleDistinctUserCount(content)).as("按人去重 = 2（本人 + 另一用户）").isEqualTo(2L);
        assertThat(favoriteCountOf(content))
                .as("★ favorite_count 直查 = 2：并发重复不得多算，也不得漏算另一用户")
                .isEqualTo(2L);
        assertThat(oracleTotalItemCount(me.id)).as("本人全部夹里的记录数 = 2（并发夹 1 + 另一夹 1）")
                .isEqualTo(2L);

        // 对照夹具：另一用户 / 另一夹 / 另一内容都不受影响（去掉 WHERE 谓词必红）
        assertThat(itemRowCount(strangersFolder, content)).as("另一用户那一行必须原样保留").isOne();
        assertThat(itemRowCount(otherFolder, otherContent)).as("本人的另一夹不受影响").isOne();
        assertThat(favoriteCountOf(otherContent)).as("另一内容的收藏数仍为 1").isEqualTo(1L);
    }

    // ==================== 并发执行器 ====================

    /** 一次收藏尝试的**HTTP 状态码**（失败也返回码：状态码本身就是被测契约）。 */
    private List<Integer> runConcurrentAdds(String token, long folderId, long contentId) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);
        List<Future<Integer>> futures = new ArrayList<>(THREADS);
        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    try {
                        return addItem(token, contentId, folderId).getStatusCode().value();
                    } finally {
                        finished.countDown();
                    }
                }));
            }

            startGate.countDown();

            assertThat(finished.await(60, TimeUnit.SECONDS))
                    .as("并发请求在 60 秒内未全部结束——疑似死锁或锁等待超时（InnoDB 默认锁等待上限 50s）")
                    .isTrue();

            List<Integer> statuses = new ArrayList<>(THREADS);
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 夹具与独立 oracle（直连 DB，不经接口） ====================

    private record TestUser(long id, String token) {
    }

    private TestUser register(String phone, String username) {
        String token = registerAndGetToken(phone, username, PASSWORD);
        return new TestUser(userIdOf(phone), token);
    }

    private ResponseEntity<String> addItem(String token, long contentId, long folderId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contentId", contentId);
        body.put("folderId", folderId);
        return post("/api/v1/favorites", body, token);
    }

    private long insertContent(long authorId) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description, is_deleted) VALUES (?, 1, ?, ?, 0)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setString(2, "T65 并发测试内容 " + authorId);
            ps.setString(3, "T65 并发测试描述");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    private long insertFolderRow(long userId, String name) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO favorite_folder (user_id, name, is_default) VALUES (?, ?, 0)",
                    new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, name);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("favorite_folder 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    /** (folder_id, content_id) 精确对查的行数（比"数总数"更能定位写错行）。 */
    private long itemRowCount(long folderId, long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_item WHERE folder_id = ? AND content_id = ?",
                Long.class, folderId, contentId);
        return n == null ? 0L : n;
    }

    /** 某用户全部夹里的记录总数（跨夹 oracle）。 */
    private long oracleTotalItemCount(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_item WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }

    /** ★ 独立复算：{@code COUNT(DISTINCT user_id)}（与列的维护实现无关的第二套算法）。 */
    private long oracleDistinctUserCount(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM favorite_item WHERE content_id = ?",
                Long.class, contentId);
        return n == null ? 0L : n;
    }

    /** ★ 独立 oracle：直查冗余列 {@code content.favorite_count}（不读接口回显、不读缓存）。 */
    private long favoriteCountOf(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT favorite_count FROM content WHERE id = ?", Long.class, contentId);
        assertThat(n).as("内容行应存在（contentId=%s）", contentId).isNotNull();
        return n;
    }
}
