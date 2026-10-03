package io.github.yjhhhaaa06.videoweb.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写扩散（fanout）与降级补推（backfill）的**落库 + 失效**测试（S9）。
 *
 * <h2>为什么直接调 service 而不发 HTTP</h2>
 * fanout / backfill 的**触发**是 MQ 消费（无对外端点）。它的**行为**（写哪些收件箱行、失效哪些缓存）
 * 才是本切片要锁定的契约。直接调 {@link io.github.yjhhhaaa06.videoweb.feed.service.FeedInboxWriter}
 * 让断言聚焦在行为上、不受 MQ 时序影响；MQ 的**投递/消费链**另有 {@code FeedMqTests} 端到端覆盖。
 *
 * <h2>独立 oracle</h2>
 * ① MySQL：直查 {@code feed_inbox} 行；② Redis：直查 {@code feed:inbox:*} 是否被 DEL。
 */
class FeedFanoutTests extends AbstractFeedIntegrationTest {

    private static final String PHONE_FAN = "13900008001";
    private static final String PHONE_AUTHOR = "13900008002";

    @Test
    @DisplayName("fanout：一条内容落进每个粉丝的收件箱 + 失效其收件箱缓存")
    void fanout落库并失效缓存() {
        long fanId = registerUser(PHONE_FAN, "fan1");
        long authorId = registerUser(PHONE_AUTHOR, "author1");
        insertFollowRow(fanId, authorId);
        long contentId = insertVideoContent(authorId, "一条内容");
        // 预热缓存：让 feed:inbox:{fanId} 存在，从而能断言 fanout 把它 DEL 了
        redis.opsForZSet().add(FEED_INBOX_PREFIX + fanId, String.valueOf(contentId), contentId);
        assertThat(hasCacheKey(FEED_INBOX_PREFIX + fanId)).isTrue();

        feedInboxWriter.fanout(contentId, authorId);

        assertThat(hasInboxRow(fanId, contentId)).as("DB 真相必须写入").isTrue();
        assertThat(countInboxRows(fanId)).isEqualTo(1);
        assertThat(hasCacheKey(FEED_INBOX_PREFIX + fanId)).as("写后失效：缓存 key 必须被 DEL").isFalse();
    }

    @Test
    @DisplayName("★fanout 幂等：重复投递 / 重试重放不产生重复行（INSERT IGNORE）")
    void fanout幂等() {
        long fanId = registerUser(PHONE_FAN, "fan1");
        long authorId = registerUser(PHONE_AUTHOR, "author1");
        insertFollowRow(fanId, authorId);
        long contentId = insertVideoContent(authorId, "一条内容");

        feedInboxWriter.fanout(contentId, authorId);
        feedInboxWriter.fanout(contentId, authorId);   // 重放
        feedInboxWriter.fanout(contentId, authorId);

        assertThat(countInboxRows(fanId)).as("唯一键 uk_user_content 去重 ⇒ 恒 1 行").isEqualTo(1);
    }

    @Test
    @DisplayName("★fanout 跳过大V作者：大V内容不进粉丝收件箱（由发件箱腿读时拉），但仍失效其 outbox 缓存")
    void fanout跳过大V() {
        long fanId = registerUser(PHONE_FAN, "fan1");
        long bigV = registerUser(PHONE_AUTHOR, "bigv");
        insertFollowRow(fanId, bigV);
        setFollowerCount(bigV, 20_000);        // 默认阈值 10000 ⇒ 是大V（第③路：粉丝数 ≥ 阈值）
        long contentId = insertVideoContent(bigV, "大V内容");
        redis.opsForZSet().add(FEED_OUTBOX_PREFIX + bigV, String.valueOf(contentId), contentId);

        feedInboxWriter.fanout(contentId, bigV);

        assertThat(hasInboxRow(fanId, contentId)).as("大V内容不得进粉丝收件箱").isFalse();
        assertThat(hasCacheKey(FEED_OUTBOX_PREFIX + bigV))
                .as("大V发件箱写后失效**无条件**执行（先于大V早退）").isFalse();
    }

    @Test
    @DisplayName("降级补推：作者最近 K 条内容补写进现任粉丝收件箱")
    void 降级补推落库() {
        long fanId = registerUser(PHONE_FAN, "fan1");
        long authorId = registerUser(PHONE_AUTHOR, "author1");
        insertFollowRow(fanId, authorId);
        long c1 = insertVideoContent(authorId, "存量一");
        long c2 = insertVideoContent(authorId, "存量二");

        feedInboxWriter.backfillAuthor(authorId);

        assertThat(hasInboxRow(fanId, c1)).isTrue();
        assertThat(hasInboxRow(fanId, c2)).isTrue();
        assertThat(countInboxRows(fanId)).isEqualTo(2);
    }

    @Test
    @DisplayName("降级补推消费时复查：作者已是大V ⇒ 跳过（可见性由发件箱腿保证，不造残影行）")
    void 降级补推复查大V后跳过() {
        long fanId = registerUser(PHONE_FAN, "fan1");
        long authorId = registerUser(PHONE_AUTHOR, "author1");
        insertFollowRow(fanId, authorId);
        long c1 = insertVideoContent(authorId, "存量一");
        setFollowerCount(authorId, 20_000);    // 消费时刻已是大V

        feedInboxWriter.backfillAuthor(authorId);

        assertThat(hasInboxRow(fanId, c1)).as("大V不补推（发件箱腿负责可见性）").isFalse();
        assertThat(countInboxRows(fanId)).isZero();
    }

    private long registerUser(String phone, String username) {
        registerAndGetToken(phone, username, PASSWORD);
        return userIdOf(phone);
    }
}
