package io.github.yjhhhaaa06.videoweb.feed;

import io.github.yjhhhaaa06.videoweb.content.AbstractContentIntegrationTest;
import io.github.yjhhhaaa06.videoweb.feed.mq.FeedTopology;
import io.github.yjhhhaaa06.videoweb.feed.service.FeedInboxWriter;
import io.github.yjhhhaaa06.videoweb.feed.service.FeedRebuildService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * feed 切片（S9）测试的共用夹具：在 {@link AbstractContentIntegrationTest} 之上补 feed 侧的造数据与 oracle。
 *
 * <h2>为什么继承 content 的夹具</h2>
 * feed 的窗口/读路径消费的就是"库里已有的内容 + 媒体"——与 S5 的读路径测试同一批数据形态。
 * 直接继承 {@code AbstractContentIntegrationTest}（{@code insertVideoContent} 等）省掉一份重复，
 * 且它自带的"每测清 Redis"纪律照旧生效。
 *
 * <h2>★ 本类额外要清的：feed 表 + MQ 队列</h2>
 * <ul>
 *   <li><b>feed 表</b>：{@code feed_inbox} / {@code feed_inbox_sync} / {@code auto_bigv} / {@code follow}
 *       ——与 content 侧无外键，删除顺序不影响正确性；</li>
 *   <li><b>MQ 队列</b>：测试 RabbitMQ 也是**进程级单例**，且两个消费者（{@code FeedPushConsumer} /
 *       {@code FeedRebuildConsumer}）在测试上下文里**是活的**——不清队列会让上一条用例的消息
 *       被下一条用例的断言"看见"（极难定位的耦合）。故每个用例前清空三个队列。</li>
 * </ul>
 *
 * <h2>两套独立 oracle（同 S3/S4/S5 纪律）</h2>
 * ① MySQL：直查 {@code feed_inbox} / {@code feed_inbox_sync} 行与列（不信任接口回显）；
 * ② Redis：直读 {@code feed:inbox:*} / {@code feed:outbox:*} 键与成员。
 */
public abstract class AbstractFeedIntegrationTest extends AbstractContentIntegrationTest {

    /** 与 {@code CacheKeys} 一致（刻意写死字面量——key 形状本身是契约）。 */
    protected static final String FEED_INBOX_PREFIX = "feed:inbox:";
    protected static final String FEED_OUTBOX_PREFIX = "feed:outbox:";
    protected static final String FEED_REBUILD_LOCK_PREFIX = "feed:rebuild:lock:";

    /** 域级分页常量（TV T19：feed 上限 100、信封 100）。 */
    protected static final int FEED_PAGE_SIZE_MAX = 100;
    protected static final int FEED_PAGE_SIZE_DEFAULT = 100;

    @Autowired
    protected AmqpAdmin amqpAdmin;

    @Autowired
    protected FeedInboxWriter feedInboxWriter;

    @Autowired
    protected FeedRebuildService feedRebuildService;

    @BeforeEach
    void resetFeedState() {
        // 在父类（content）的 resetState 之后执行：先删有"内容侧从属"关系的表，再删 feed 侧。
        jdbcTemplate.update("DELETE FROM feed_inbox");
        jdbcTemplate.update("DELETE FROM feed_inbox_sync");
        jdbcTemplate.update("DELETE FROM auto_bigv");
        jdbcTemplate.update("DELETE FROM follow");
        purgeQueues();
    }

    /** 清空三个 feed 队列（消费者是活的 ⇒ 必须清，见类注释）。队列缺失时静默（RabbitAdmin 尚未声明）。 */
    protected void purgeQueues() {
        for (String queue : List.of(FeedTopology.QUEUE_PUSH, FeedTopology.QUEUE_REBUILD, FeedTopology.QUEUE_DLQ)) {
            try {
                amqpAdmin.purgeQueue(queue);
            } catch (RuntimeException ignored) {
                // 队列尚未声明 / 连接未就绪：忽略（下一次用例会再试）
            }
        }
    }

    // ==================== 造数据（JDBC 直插） ====================

    /**
     * 直接插 {@code follow} 行并**同步维护两侧计数列**（SOP 坑 13：夹具的冗余计数必须自洽）。
     */
    protected void insertFollowRow(long userId, long followedUserId) {
        jdbcTemplate.update("INSERT INTO follow (user_id, followed_user_id) VALUES (?, ?)",
                userId, followedUserId);
        jdbcTemplate.update("UPDATE users SET follow_count = follow_count + 1 WHERE id = ?", userId);
        jdbcTemplate.update("UPDATE users SET follower_count = follower_count + 1 WHERE id = ?", followedUserId);
    }

    /** 直接把粉丝数钉到某值（用于制造"阈值命中 / 滞回带内"的作者，不经过关注 API）。 */
    protected void setFollowerCount(long userId, int count) {
        jdbcTemplate.update("UPDATE users SET follower_count = ? WHERE id = ?", count, userId);
    }

    /** 直接造自动大V状态行（"存在即自动大V"）。 */
    protected void insertAutoBigV(long userId) {
        jdbcTemplate.update("INSERT IGNORE INTO auto_bigv (user_id) VALUES (?)", userId);
    }

    /** 直接造收件箱窗口行（构造"已同步且有内容"的初态）。 */
    protected void insertFeedInboxRow(long userId, long contentId) {
        jdbcTemplate.update("INSERT IGNORE INTO feed_inbox (user_id, content_id) VALUES (?, ?)",
                userId, contentId);
    }

    /** 写同步状态行（"存在即已同步" ⇒ 读侧走窗口路径而非纯拉）。 */
    protected void markSynced(long userId) {
        jdbcTemplate.update("INSERT INTO feed_inbox_sync (user_id) VALUES (?) "
                + "ON DUPLICATE KEY UPDATE sync_time = CURRENT_TIMESTAMP", userId);
    }

    // ==================== DB 侧 oracle ====================

    protected long countInboxRows(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM feed_inbox WHERE user_id = ?", Long.class, userId);
        return n == null ? 0 : n;
    }

    protected boolean hasInboxRow(long userId, long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM feed_inbox WHERE user_id = ? AND content_id = ?",
                Long.class, userId, contentId);
        return n != null && n > 0;
    }

    protected boolean isSynced(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM feed_inbox_sync WHERE user_id = ?", Long.class, userId);
        return n != null && n > 0;
    }

    protected boolean isAutoBigV(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auto_bigv WHERE user_id = ?", Long.class, userId);
        return n != null && n > 0;
    }

    // ==================== Redis 侧 oracle ====================

    protected boolean hasCacheKey(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    /** 收件箱 ZSet 成员（升序）。 */
    protected java.util.Set<String> inboxMembers(long userId) {
        return redis.opsForZSet().range(FEED_INBOX_PREFIX + userId, 0, -1);
    }

    // ==================== HTTP ====================

    /** {@code GET /feed}（无分页参数 ⇒ 走缺省 1 / 100）。 */
    protected ResponseEntity<String> getFeed(String token) {
        return get("/feed", token);
    }

    protected ResponseEntity<String> getFeed(String token, int page, int pageSize) {
        return get("/feed?page=" + page + "&pageSize=" + pageSize, token);
    }
}
