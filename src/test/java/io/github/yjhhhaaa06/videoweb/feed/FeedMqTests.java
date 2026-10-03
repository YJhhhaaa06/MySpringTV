package io.github.yjhhhaaa06.videoweb.feed;

import io.github.yjhhhaaa06.videoweb.feed.mq.FeedPushNotifier;
import io.github.yjhhhaaa06.videoweb.feed.mq.FeedTopology;
import io.github.yjhhhaaa06.videoweb.feed.mq.InboxRebuildNotifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MQ **端到端**测试（S9）：拓扑声明 + 投递 → 消费 → 落库，以及**有限重试 → 死信**。
 *
 * <h2>为什么这是 S9 的"新基建存在证明"</h2>
 * S9 首次启用 Spring AMQP。前面几类测试直接调 service，验证了业务行为；
 * <b>本类才证明"MQ 这条路真的通"</b>：拓扑被声明、消息经 broker 到达消费者、消费者真的执行了动作。
 * 缺了它，"MQ 没接上但业务测试全绿"这种失败会完全隐形。
 *
 * <h2>异步性 ⇒ 轮询等待</h2>
 * 消费是异步的（prefetch=1、单消费者），故用 {@link #awaitUntil} 轮询断言目标状态，
 * 而非固定 sleep（避免 flaky）。
 */
class FeedMqTests extends AbstractFeedIntegrationTest {

    private static final long TIMEOUT_MILLIS = 20_000L;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private FeedPushNotifier feedPushNotifier;

    @Autowired
    private InboxRebuildNotifier inboxRebuildNotifier;

    @Test
    @DisplayName("★拓扑：3 队列被声明（RabbitAdmin 在连接建立时幂等声明）")
    void 拓扑已声明() {
        for (String queue : new String[]{FeedTopology.QUEUE_PUSH, FeedTopology.QUEUE_REBUILD,
                FeedTopology.QUEUE_DLQ}) {
            QueueInformation info = amqpAdmin.getQueueInfo(queue);
            assertThat(info).as("队列 " + queue + " 必须存在（Declarables + RabbitAdmin）").isNotNull();
        }
    }

    @Test
    @DisplayName("★端到端：内容发布投递 → 消费者写扩散 → 粉丝收件箱出现该内容")
    void 端到端写扩散() {
        long fanId = registerUser("13900005001", "mq-fan");
        long authorId = registerUser("13900005002", "mq-author");
        insertFollowRow(fanId, authorId);
        long contentId = insertVideoContent(authorId, "MQ 内容");

        feedPushNotifier.publishContentPublished(contentId, authorId);

        awaitUntil(() -> hasInboxRow(fanId, contentId));
        assertThat(hasInboxRow(fanId, contentId)).isTrue();
    }

    @Test
    @DisplayName("★端到端：关注触发重建投递 → 去抖 → 消费者重建 → 同步态落库")
    void 端到端重建() {
        long viewer = registerUser("13900005003", "mq-viewer");
        long authorId = registerUser("13900005004", "mq-author2");
        insertFollowRow(viewer, authorId);
        long contentId = insertVideoContent(authorId, "重建内容");

        inboxRebuildNotifier.publishInboxRebuild(viewer);

        awaitUntil(() -> isSynced(viewer));
        assertThat(isSynced(viewer)).as("重建必须写同步态").isTrue();
        assertThat(hasInboxRow(viewer, contentId)).as("重建窗口应收录该内容").isTrue();
    }

    @Test
    @DisplayName("★失败重试耗尽 → 转死信（DLQ 计数增加；不重入队、无热循环）")
    void 失败转死信() {
        long dlqBefore = dlqMessageCount();

        // 空载荷 ⇒ 消费者 decode 抛 IllegalArgumentException（"消息不可用"）⇒ 有限重试后转死信
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        rabbitTemplate.send(FeedTopology.EXCHANGE_PUSH, FeedTopology.RK_PUSH_CONTENT,
                new Message(new byte[0], props));

        awaitUntil(() -> dlqMessageCount() > dlqBefore);
        assertThat(dlqMessageCount()).as("非法消息必须进 DLQ（保留证据，不静默丢弃）").isGreaterThan(dlqBefore);
    }

    // ==================== helpers ====================

    private long dlqMessageCount() {
        QueueInformation info = amqpAdmin.getQueueInfo(FeedTopology.QUEUE_DLQ);
        return info == null ? 0L : info.getMessageCount();
    }

    private void awaitUntil(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待被中断", e);
            }
        }
    }

    private long registerUser(String phone, String username) {
        registerAndGetToken(phone, username, PASSWORD);
        return userIdOf(phone);
    }
}
