package io.github.yjhhhaaa06.videoweb.feed;

import io.github.yjhhhaaa06.videoweb.feed.service.FeedBigVRouter;
import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

/**
 * 大V**读侧判定**的测试（S9）——验证 {@link FeedBigVRouter} 的**三路并集**
 * 〚名单 ∪ 自动大V状态表 ∪ 粉丝数 ≥ 阈值〛，尤其是**第②路（滞回产物）**与**第③路（冷启动兜底）**。
 *
 * <h2>⚠️ 为什么用独立上下文（低阈值 3 / 名单 987654）</h2>
 * 默认阈值 10000 ⇒ 测试里造不出"阈值命中"的作者。故用 {@code @SpringBootTest(properties=...)}
 * 起一个低阈值上下文（同 {@code FollowBigVTests} 的手法）。
 * 名单项取一个**不可能与自增 id 冲突**的固定值 987654（本类不需要该用户真实存在——
 * 名单命中路径**不发任何 SQL**，这正是它作为"显式事实"的语义）。
 *
 * <h2>三路各一条用例（缺一不可）</h2>
 * 只测第③路会漏掉滞回（掉粉后仍在带内的作者会被误判成普通）；
 * 只测第②路会漏掉冷启动（历史已达标但未入状态表的作者会被误判成普通）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"video.bigv.threshold=3", "video.bigv.downgrade-ratio=0.6",
                "video.bigv.user-ids=987654"})
class FeedBigVTests extends AbstractFeedIntegrationTest {

    private static final long LISTED_ID = 987_654L;
    private static final String PHONE_X = "13900006001";

    @Autowired
    private FeedBigVRouter bigVRouter;

    @MockitoSpyBean
    private UserDao userDao;

    @Test
    @DisplayName("第①路 名单命中 ⇒ 大V（不经阈值、也不发 SQL，零粉丝也是大V）")
    void 名单命中即大V() {
        assertThat(bigVRouter.isBigV(LISTED_ID)).as("名单项 = 显式事实").isTrue();
    }

    @Test
    @DisplayName("第②路 状态表命中（滞回产物）⇒ 大V，即便粉丝数远低于阈值")
    void 状态表命中即大V() {
        long userId = registerUser();
        setFollowerCount(userId, 1);           // 低于阈值 3
        insertAutoBigV(userId);                 // "曾达线升为大V、掉粉后仍在带内未降级"

        assertThat(bigVRouter.isBigV(userId))
                .as("滞回：状态表命中即大V（否则掉粉后会被突然降级，内容从发件箱腿消失）").isTrue();
    }

    @Test
    @DisplayName("第③路 粉丝数 ≥ 阈值（DB 真值）⇒ 大V（冷启动 / 未入状态表时的兜底）")
    void 粉丝数达线即大V() {
        long userId = registerUser();
        setFollowerCount(userId, 3);           // 恰好达线
        assertThat(isAutoBigV(userId)).as("本用例刻意不造状态行，只考第③路").isFalse();

        assertThat(bigVRouter.isBigV(userId))
                .as("已达标但无状态行 ⇒ 第③路兜底，不得因'没有状态行'突然降级").isTrue();
    }

    @Test
    @DisplayName("三路皆不命中 ⇒ 普通作者；批量入口按名单先行、其余走 SQL")
    void 三路皆不命中为普通作者() {
        long userId = registerUser();
        setFollowerCount(userId, 0);

        assertThat(bigVRouter.isBigV(userId)).isFalse();
        Set<Long> batch = bigVRouter.isBigVBatch(List.of(LISTED_ID, userId));
        assertThat(batch).as("并集：名单项 + 无 SQL 命中的普通作者").containsExactly(LISTED_ID);
    }

    @Test
    @DisplayName("★状态表/粉丝数查询失败 ⇒ fail-open：整批只保留名单项（不 500、不误判）")
    void 判定失败failOpen保留名单() {
        Mockito.reset(userDao);
        long userId = registerUser();
        setFollowerCount(userId, 3);           // 本该命中第③路
        doThrow(new DataAccessException("模拟状态表/粉丝数查询失败") {
        }).when(userDao).findUserIdsByMinFollowerCount(anyList(), anyInt());

        Set<Long> batch = bigVRouter.isBigVBatch(List.of(LISTED_ID, userId));

        assertThat(batch).as("SQL 失败 ⇒ fail-open，只返回名单项（其余按普通作者）").containsExactly(LISTED_ID);
    }

    private long registerUser() {
        registerAndGetToken(PHONE_X, "bigv-x", PASSWORD);
        return userIdOf(PHONE_X);
    }
}
