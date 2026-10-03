package io.github.yjhhhaaa06.videoweb.feed.event;

import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.event.ContentPublishedEvent;
import io.github.yjhhhaaa06.videoweb.feed.AbstractFeedIntegrationTest;
import io.github.yjhhhaaa06.videoweb.feed.mq.FeedPushNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 声明式机制的**存在证明**（S9 / DoD #7）——测"机制真的在拦"，而不是"端到端看起来对"。
 *
 * <h2>为什么本类在 {@code feed.event} 包</h2>
 * 它要构造 {@link ContentPublishedEvent}（content 域的事件）来验证订阅方的 AFTER_COMMIT 时序。
 * 按 ArchUnit 规则 3「跨域事件只能由自己的 {@code event} 包消费」，**任何**引用别域事件的类
 * （包括测试）都必须落在 {@code *.event} 包——否则构建失败（实测：本类原先在 {@code feed}
 * 包时被该规则拦下）。这正说明该规则在起作用。
 *
 * <h2>两个手法</h2>
 * <ul>
 *   <li><b>{@code @Transactional}（{@code FeedWindowWriter.replaceWindow}）</b>：
 *       让"先清后建"事务里的**第二步查询**失败 ⇒ 断言第一步的 DELETE **被回滚**（陈旧行仍在）、
 *       同步态行**未落**。若事务没生效（例如注解挪到自调用方法上，坑 12），DELETE 会真实生效 ⇒
 *       本用例立刻失败。</li>
 *   <li><b>{@code @TransactionalEventListener(AFTER_COMMIT)}（{@link ContentPublishedFeedListener}）</b>：
 *       事务内发事件 + 事务回滚 ⇒ 投递**不得发生**；事务提交 ⇒ 投递**必须发生**。
 *       成对写（"提交后生效" + "回滚后未污染"）是该机制的判据（SOP §三 #7 参考实现同款）。</li>
 * </ul>
 */
class FeedMechanismTests extends AbstractFeedIntegrationTest {

    private static final String PHONE_VIEWER = "13900004001";
    private static final String PHONE_FOLLOWED = "13900004002";
    private static final String PHONE_STALE = "13900004003";

    @MockitoSpyBean
    private ContentDao contentDao;

    @MockitoSpyBean
    private FeedPushNotifier feedPushNotifier;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    void resetStubs() {
        Mockito.reset(contentDao, feedPushNotifier);
        // 隔离 MQ：本类只验证"事件 → 监听器是否被调用"，不真的投递（避免污染队列 / 异步噪声）
        doNothing().when(feedPushNotifier).publishContentPublished(anyLong(), anyLong());
    }

    // ==================== @Transactional ====================

    @Test
    @DisplayName("★@Transactional 生效：窗口重算失败 ⇒ 先清后建**整体回滚**（陈旧行仍在、同步态未落）")
    void 窗口重算失败整体回滚() {
        long viewer = registerUser(PHONE_VIEWER, "viewer");
        long followed = registerUser(PHONE_FOLLOWED, "followed");
        long staleAuthor = registerUser(PHONE_STALE, "stale-author");
        insertFollowRow(viewer, followed);
        long staleContent = insertVideoContent(staleAuthor, "陈旧行（来自未关注作者）");
        insertFeedInboxRow(viewer, staleContent);      // 预置：来自**不在关注集**的作者

        // 让"替换窗口"事务内的**第二步**（窗口重查）失败 ⇒ 第一步的 DELETE 必须被回滚
        doThrow(new DataAccessException("模拟窗口重算失败") {
        }).when(contentDao).findRecentContentIdsByUsers(anyList(), anyInt());

        feedRebuildService.rebuildInbox(viewer);       // 内部降级吞掉，不抛

        assertThat(hasInboxRow(viewer, staleContent))
                .as("事务回滚 ⇒ DELETE 被撤销，陈旧行必须仍在（事务失效时它会被真的删掉）").isTrue();
        assertThat(isSynced(viewer))
                .as("同步态与窗口替换**同一事务** ⇒ 回滚后不得留下同步行").isFalse();
    }

    // ==================== @TransactionalEventListener(AFTER_COMMIT) ====================

    @Test
    @DisplayName("★提交后副作用：事务提交 ⇒ 内容发布事件触达 feed 投递")
    void 提交后投递发生() {
        new TransactionTemplate(txManager).execute(status -> {
            events.publishEvent(new ContentPublishedEvent(42L, 7L));
            return null;
        });
        verify(feedPushNotifier).publishContentPublished(42L, 7L);
    }

    @Test
    @DisplayName("★提交后副作用：事务回滚 ⇒ 投递**不发生**（AFTER_COMMIT 的判据）")
    void 回滚后不投递() {
        assertThatThrownBy(() -> new TransactionTemplate(txManager).execute(status -> {
            events.publishEvent(new ContentPublishedEvent(43L, 8L));
            throw new IllegalStateException("模拟事务回滚");
        })).isInstanceOf(IllegalStateException.class);

        verify(feedPushNotifier, never()).publishContentPublished(43L, 8L);
    }

    private long registerUser(String phone, String username) {
        registerAndGetToken(phone, username, PASSWORD);
        return userIdOf(phone);
    }
}
