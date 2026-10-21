package io.github.yjhhhaaa06.videoweb.content;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.history.service.HistoryService;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T48 验收：**播放量读面**（第 5 个公共计数）—— {@code content:playCount:{id}} 的写侧消费
 * （{@code PlayCountChangedListener} 条件 {@code INCRBY}）、详情平铺、删除失效。
 *
 * <h2>期望值怎么来（独立 oracle）</h2>
 * <ul>
 *   <li>播放量一律**直查 DB** {@code content.play_count} 列复算（{@code playCountColumn}），
 *       不信接口回显；</li>
 *   <li>"一人一次"的口径来自 {@code history-需求.md} §四（重复 play 不 +、图文没有播放量），
 *       不是从实现里读出来的；</li>
 *   <li>缓存侧直接读 {@link org.springframework.data.redis.core.StringRedisTemplate} 的键值
 *       ——"冷 key 不建"只能用缓存 oracle 观测（DB 与接口都看不出"写没写半套"）。</li>
 * </ul>
 *
 * <h2>反向验证（注入 ⇒ 必红）</h2>
 * ① 去掉 {@code ContentCountAggregate.merged} 的 playCount 位 ⇒ 聚合用例
 *    {@code ContentDetailCountAggregateTests#五键聚合与播放量选择性回填} 红；
 * ② 去掉聚合层的 {@code backfillPlayCount} 调用 ⇒ 同上（键不回填 / 值错）；
 * ③ 监听器去掉"键存在才 INCR"的条件 ⇒ {@link #冷key播放不写半套缓存()} 红；
 * ④ 去掉 {@code ContentCache#removeContent} 的 {@code invalidatePlayCount} ⇒
 *    {@link #删除后播放量键消失且404不回填()} 红。
 */
class ContentPlayCountTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900012001";
    private static final String AUTHOR = "playcount-author";
    private static final String VIEWER_PHONE = "13900012002";
    private static final String VIEWER = "playcount-viewer";

    @Autowired
    private HistoryService historyService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String authorToken;
    private long authorId;
    private String viewerToken;
    private long viewerId;

    @BeforeEach
    void setUpUsers() {
        authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        authorId = userIdOf(AUTHOR_PHONE);
        viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);
        viewerId = userIdOf(VIEWER_PHONE);
        jdbcTemplate.update("DELETE FROM watch_history");
    }

    // ==================== 端到端 ====================

    @Test
    @DisplayName("★端到端：/play ⇒ 详情 playCount=1；重复 play 仍 1；/report 不改变它")
    void 播放量端到端() {
        long contentId = insertVideoContent(authorId, "播放量端到端");

        // 预热：详情一次，五个计数键存在（否则条件 INCR 无处可加，见下一条用例）
        assertThat(detail(contentId).path("playCount").asLong())
                .as("前置：没人播过 ⇒ 0").isZero();

        assertThat(post("/history/play?contentId=" + contentId, null, viewerToken).getStatusCode().value())
                .as("首播应 200").isEqualTo(200);

        assertThat(playCountColumn(contentId)).as("独立 oracle：DB 列 +1").isEqualTo(1);
        assertThat(detail(contentId).path("playCount").asLong())
                .as("★ 详情平铺第 5 个公共计数").isEqualTo(1L);
        assertThat(redis.opsForValue().get(CacheKeys.contentPlayCount(contentId)))
                .as("★ 预热过的键被条件 INCR 到 1").isEqualTo("1");

        // 同一人重复 play：幂等 200，播放量不变（一人一次口径）
        assertThat(post("/history/play?contentId=" + contentId, null, viewerToken).getStatusCode().value())
                .as("重复 play 也 200（幂等）").isEqualTo(200);
        assertThat(playCountColumn(contentId)).as("★ 一人一次：DB 仍 1").isEqualTo(1);
        assertThat(detail(contentId).path("playCount").asLong()).as("★ 详情仍 1").isEqualTo(1L);
        assertThat(redis.opsForValue().get(CacheKeys.contentPlayCount(contentId)))
                .as("★ 缓存也仍 1").isEqualTo("1");

        // /report 只写进度，不是计数入口
        assertThat(post("/history/report?contentId=" + contentId + "&progress=30", null, viewerToken)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(playCountColumn(contentId)).as("★ 独立 oracle：/report 没碰 play_count 列").isEqualTo(1);
        assertThat(detail(contentId).path("playCount").asLong())
                .as("★ /report 不改变播放量（唯一计数入口是 /play）").isEqualTo(1L);
    }

    @Test
    @DisplayName("★冷 key 不写半套缓存：不预热即 /play ⇒ 键仍不存在；随后详情回源列拿到真值")
    void 冷key播放不写半套缓存() {
        long contentId = insertVideoContent(authorId, "播放量冷键");
        assertThat(redis.hasKey(CacheKeys.contentPlayCount(contentId)))
                .as("前置：键从未加载").isFalse();

        assertThat(post("/history/play?contentId=" + contentId, null, viewerToken).getStatusCode().value())
                .isEqualTo(200);

        assertThat(playCountColumn(contentId)).as("DB oracle：列已 +1").isEqualTo(1);
        assertThat(redis.hasKey(CacheKeys.contentPlayCount(contentId)))
                .as("★ 条件 INCR：键不存在 ⇒ 什么都不写（写个 1 就是漏掉历史的残缺值）")
                .isFalse();

        // 详情：五键 miss ⇒ 一趟回源读列 ⇒ 回填 1（不是 0、也不是 2）
        assertThat(detail(contentId).path("playCount").asLong())
                .as("★ 回源读列拿到真值").isEqualTo(1L);
        assertThat(redis.opsForValue().get(CacheKeys.contentPlayCount(contentId)))
                .as("★ 回源后被回填为列值").isEqualTo("1");
    }

    @Test
    @DisplayName("图文帖：详情 playCount 恒 0（没有 /play 路径能通过类型校验）")
    void 图文详情播放量恒零() {
        long contentId = insertPostContent(authorId, "播放量图文");

        assertThat(detail(contentId).path("playCount").asLong())
                .as("★ 图文没有播放量").isZero();
        assertThat(playCountColumn(contentId)).as("DB 列也是 0").isZero();
    }

    // ==================== 事件时序（AFTER_COMMIT） ====================

    @Test
    @DisplayName("★/play 的事务回滚 ⇒ 缓存与 DB 都不动（AFTER_COMMIT 的存在证明：预热 5 仍是 5）")
    void 播放事务回滚不污染缓存() {
        long contentId = insertVideoContent(authorId, "播放量回滚");
        jdbcTemplate.update("UPDATE content SET play_count = 5 WHERE id = ?", contentId);
        // 预热：键 = 5（"值没被动过"才有判别力；冷键的"不存在"在两种实现下都成立）
        assertThat(detail(contentId).path("playCount").asLong()).isEqualTo(5L);
        assertThat(redis.opsForValue().get(CacheKeys.contentPlayCount(contentId))).isEqualTo("5");

        // 事务在**成功写完 watch_history 与 play_count 之后**被标记回滚：
        // 事件已发布、事务却回滚——这正是 @TransactionalEventListener(AFTER_COMMIT) 要挡的场景。
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.execute(status -> {
            historyService.play(viewerId, contentId);
            status.setRollbackOnly();
            return null;
        });

        assertThat(playCountColumn(contentId)).as("DB 回滚：列仍是 5").isEqualTo(5);
        assertThat(redis.opsForValue().get(CacheKeys.contentPlayCount(contentId)))
                .as("★ 回滚后缓存必须没动过（改成普通 @EventListener ⇒ 这里会变 6）").isEqualTo("5");
    }

    // ==================== 删除 ====================

    @Test
    @DisplayName("★内容删除：removeContent 失效播放量键；其后 404 请求不回填它")
    void 删除后播放量键消失且404不回填() {
        long contentId = insertVideoContent(authorId, "播放量删除");
        assertThat(post("/history/play?contentId=" + contentId, null, viewerToken).getStatusCode().value())
                .isEqualTo(200);
        // 预热（否则"删除后键不存在"零判别力：本来就不存在）
        assertThat(detail(contentId).path("playCount").asLong()).isEqualTo(1L);
        assertThat(redis.opsForValue().get(CacheKeys.contentPlayCount(contentId))).isEqualTo("1");

        assertThat(postQuery("/content/delete", query("contentId", String.valueOf(contentId)), authorToken)
                .getStatusCode().value()).as("前置：作者删除").isEqualTo(200);

        assertThat(redis.hasKey(CacheKeys.contentPlayCount(contentId)))
                .as("★ removeContent 一并失效播放量键（去掉那行 ⇒ 本断言红）").isFalse();

        // 404 短路在计数聚合之前：键刚被删除 ⇒ 若聚合先跑一步，它会 miss 并回填列值 "1"
        // ⇒ 下面的 hasKey 断言会红。⚠️ 不能先给键预置一个值（那样是命中，聚合本来就不回填
        //   ⇒ 两种实现都为真，零判别力 —— T42 假绿形态①）。
        assertThat(get("/search/IdSearch?contentId=" + contentId, authorToken).getStatusCode().value())
                .as("前置：详情 404").isEqualTo(404);
        assertThat(redis.hasKey(CacheKeys.contentPlayCount(contentId)))
                .as("★ 404 路径不回填（聚合若在 404 之前执行，会把列值回填进来 ⇒ 红）").isFalse();
    }

    // ==================== 列表零计数 ====================

    @Test
    @DisplayName("★列表（/start）：不带 playCount（T43 列表零计数口径，用 has()==false 判'没有'）")
    void 列表不带播放量() {
        long contentId = insertVideoContent(authorId, "播放量列表");
        assertThat(post("/history/play?contentId=" + contentId, null, viewerToken).getStatusCode().value())
                .isEqualTo(200);

        JsonNode startItem = itemOf(Envelope.data(get("/start", viewerToken)), contentId);
        // ★ 必须用 has(key)==false：asLong() 对缺失节点返回 0，分不出"没有这个键"与"值就是 0"
        assertThat(startItem.has("playCount"))
                .as("★ 列表卡片不带任何计数（列表零计数是契约，不是漏填）").isFalse();
        assertThat(startItem.has("likeCount")).as("对照：既有计数同样不在列表上").isFalse();
    }

    // ==================== 夹具 / oracle ====================

    /** 详情（匿名，避免互动态干扰）：返回 data 节点。 */
    private JsonNode detail(long contentId) {
        ResponseEntity<String> resp = get("/search/IdSearch?contentId=" + contentId, null);
        assertThat(resp.getStatusCode().value()).as("详情应 200：%s", resp.getBody()).isEqualTo(200);
        return Envelope.data(resp);
    }

    /** 独立 oracle：直查 {@code content.play_count} 列（不信接口回显）。 */
    private int playCountColumn(long contentId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT play_count FROM content WHERE id = ?", Integer.class, contentId);
        return value == null ? -1 : value;
    }

    /** 从列表节点里取指定 id 的条目（找不到直接失败——防"夹具没造上 / 被读面跳过"的假绿）。 */
    private static JsonNode itemOf(JsonNode list, long contentId) {
        for (JsonNode node : list) {
            if (node.path("id").asLong() == contentId) {
                return node;
            }
        }
        throw new AssertionError("列表里应包含内容 " + contentId + "，实际：" + list);
    }
}
