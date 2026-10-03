package io.github.yjhhhaaa06.videoweb.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收件箱**窗口重建**的测试（S9）——覆盖 TV {@code FeedRebuildService} 的核心契约：
 * <b>整窗替换 + 每关注作者最近 K + 排除大V + 写同步状态（空窗也写）</b>。
 *
 * <h2>oracle</h2>
 * 直查 {@code feed_inbox}（该用户的窗口行）与 {@code feed_inbox_sync}（同步态）——
 * 不信任任何接口回显（重建本身无对外端点）。
 */
class FeedRebuildTests extends AbstractFeedIntegrationTest {

    private static final String PHONE_VIEWER = "13900007001";
    private static final String PHONE_A = "13900007002";
    private static final String PHONE_B = "13900007003";

    @Test
    @DisplayName("★重建：窗口 = 各关注作者最近 K 条的归并（去重降序），并写同步状态")
    void 重建窗口并写同步态() {
        long viewer = registerUser(PHONE_VIEWER, "viewer");
        long a = registerUser(PHONE_A, "author-a");
        long b = registerUser(PHONE_B, "author-b");
        insertFollowRow(viewer, a);
        insertFollowRow(viewer, b);
        long a1 = insertVideoContent(a, "a-1");
        long a2 = insertVideoContent(a, "a-2");
        long b1 = insertVideoContent(b, "b-1");
        assertThat(isSynced(viewer)).isFalse();

        feedRebuildService.rebuildInbox(viewer);

        assertThat(countInboxRows(viewer)).as("窗口 = 三条内容（a1,a2,b1）").isEqualTo(3);
        assertThat(hasInboxRow(viewer, a1)).isTrue();
        assertThat(hasInboxRow(viewer, a2)).isTrue();
        assertThat(hasInboxRow(viewer, b1)).isTrue();
        assertThat(isSynced(viewer)).as("'存在即已同步' ⇒ 必须有同步态行").isTrue();
    }

    @Test
    @DisplayName("★重建排除大V作者：大V内容不进窗口（由发件箱腿负责），普通作者照常收录")
    void 重建排除大V() {
        long viewer = registerUser(PHONE_VIEWER, "viewer");
        long normal = registerUser(PHONE_A, "normal");
        long bigv = registerUser(PHONE_B, "bigv");
        insertFollowRow(viewer, normal);
        insertFollowRow(viewer, bigv);
        setFollowerCount(bigv, 20_000);        // 默认阈值 10000 ⇒ 大V
        long n1 = insertVideoContent(normal, "普通作者内容");
        long bigv1 = insertVideoContent(bigv, "大V内容");

        feedRebuildService.rebuildInbox(viewer);

        assertThat(hasInboxRow(viewer, n1)).as("普通作者内容应收录").isTrue();
        assertThat(hasInboxRow(viewer, bigv1)).as("大V内容必须排除（避免与发件箱腿重复）").isFalse();
        assertThat(countInboxRows(viewer)).isEqualTo(1);
    }

    @Test
    @DisplayName("★空关注 ⇒ 空但已同步（写同步态、窗口零行）")
    void 空关注也写同步态() {
        long viewer = registerUser(PHONE_VIEWER, "viewer");

        feedRebuildService.rebuildInbox(viewer);

        assertThat(countInboxRows(viewer)).isZero();
        assertThat(isSynced(viewer)).as("'空但已同步' ⇒ 同步态必须写（否则读侧会一直回退纯拉）").isTrue();
    }

    @Test
    @DisplayName("★整窗替换：上一次重建留下的陈旧行（作者已取关）必须被清掉")
    void 整窗替换清陈旧行() {
        long viewer = registerUser(PHONE_VIEWER, "viewer");
        long a = registerUser(PHONE_A, "author-a");
        long staleAuthor = registerUser(PHONE_B, "stale-author");
        long kept = insertVideoContent(a, "仍关注的");
        long stale = insertVideoContent(staleAuthor, "已取关的");
        // 预置一条"陈旧"窗口行：来自一个**不在当前关注集**里的作者
        insertFeedInboxRow(viewer, stale);
        insertFollowRow(viewer, a);
        markSynced(viewer);

        feedRebuildService.rebuildInbox(viewer);

        assertThat(hasInboxRow(viewer, stale)).as("重建先清后建 ⇒ 陈旧行必须消失").isFalse();
        assertThat(hasInboxRow(viewer, kept)).isTrue();
        assertThat(countInboxRows(viewer)).isEqualTo(1);
    }

    private long registerUser(String phone, String username) {
        registerAndGetToken(phone, username, PASSWORD);
        return userIdOf(phone);
    }
}
