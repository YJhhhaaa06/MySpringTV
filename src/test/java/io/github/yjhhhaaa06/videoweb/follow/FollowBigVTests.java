package io.github.yjhhhaaa06.videoweb.follow;

import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * 自动大V**滞回判定**的测试（决策表 F-6）——验证"关注/取关的计数变化确实同步维护了
 * {@code auto_bigv}"，以及该判定与关注事务的 **fail-atomic** 关系。
 *
 * <h2>⚠️ 为什么本类用**独立的 Spring 上下文**</h2>
 * TV 的阈值默认是 {@code feed.bigv.threshold=10000}——测试里关注几个用户**永远触发不了状态迁移**。
 * 要测滞回必须覆盖阈值/系数，于是这里用 {@code @SpringBootTest(properties = ...)} 起一个
 * **低阈值**上下文（3 / 0.6）。这是本切片唯一需要单独上下文的用例（换来约十秒的额外启动时间），
 * 代价换的是"滞回逻辑真的被测到"，而不是变成一段永远绿的死代码。
 *
 * <p>顺带验证了配置绑定：{@code video.bigv.*} → {@code BigVProperties}（连字符 → 驼峰）。
 *
 * <h2>阈值口径（threshold=3 / ratio=0.6 ⇒ 降级线 = 1.8）</h2>
 * <table>
 *   <caption>粉丝数与动作</caption>
 *   <tr><th>粉丝数</th><th>区间</th><th>动作</th></tr>
 *   <tr><td>3+</td><td>{@code >= 3}</td><td>{@code INSERT IGNORE}（UPGRADED，入表）</td></tr>
 *   <tr><td>2</td><td>带内 {@code [1.8, 3)}</td><td><b>不动</b>（滞回：已升者不因掉到带内而翻转）</td></tr>
 *   <tr><td>0～1</td><td>{@code < 1.8}</td><td>{@code DELETE}（DOWNGRADED，删行）</td></tr>
 * </table>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"video.bigv.threshold=3", "video.bigv.downgrade-ratio=0.6"})
class FollowBigVTests extends AbstractFollowIntegrationTest {

    private static final String PHONE_A = "13800004001";   // 被关注者（粉丝数变化者）
    private static final String PHONE_B = "13800004002";
    private static final String PHONE_C = "13800004003";
    private static final String PHONE_D = "13800004004";

    @MockitoSpyBean
    private UserDao userDao;

    @BeforeEach
    void resetStubs() {
        Mockito.reset(userDao);
    }

    @Test
    @DisplayName("★滞回升降级：达线入表 / 掉进带内不翻转 / 跌破降级线才删行")
    void 滞回升降级() {
        TestUser a = register(PHONE_A, "bigv-a");
        TestUser b = register(PHONE_B, "bigv-b");
        TestUser c = register(PHONE_C, "bigv-c");
        TestUser d = register(PHONE_D, "bigv-d");

        // 1 个粉丝：1 < 1.8 ⇒ 删除（本就不在表中 ⇒ 无 edge）
        followService.follow(b.id(), a.id());
        assertThat(followerCountColumn(a.id())).isEqualTo(1);
        assertThat(isAutoBigV(a.id())).as("未达升级线，不得入表").isFalse();

        // 2 个粉丝：带内 ⇒ 不翻转
        followService.follow(c.id(), a.id());
        assertThat(followerCountColumn(a.id())).isEqualTo(2);
        assertThat(isAutoBigV(a.id())).as("带内（未达升级线）不得入表").isFalse();

        // 3 个粉丝：>= 3 ⇒ UPGRADED
        followService.follow(d.id(), a.id());
        assertThat(followerCountColumn(a.id())).isEqualTo(3);
        assertThat(isAutoBigV(a.id())).as("达升级线 ⇒ 入表").isTrue();

        // 回到 2 个粉丝：带内 ⇒ ★滞回：状态行保留（这是"滞回"与"无状态阈值"的唯一区别）
        followService.unfollow(d.id(), a.id());
        assertThat(followerCountColumn(a.id())).isEqualTo(2);
        assertThat(isAutoBigV(a.id())).as("带内不翻转（滞回生效）").isTrue();

        // 回到 1 个粉丝：< 1.8 ⇒ DOWNGRADED（删行）
        followService.unfollow(c.id(), a.id());
        assertThat(followerCountColumn(a.id())).isEqualTo(1);
        assertThat(isAutoBigV(a.id())).as("跌破降级线 ⇒ 删行").isFalse();

        // 幂等：再取关一个（0 粉丝）⇒ 本就不在表中 ⇒ 无 edge、无异常
        followService.unfollow(b.id(), a.id());
        assertThat(followerCount(a.id())).isZero();
        assertThat(isAutoBigV(a.id())).isFalse();
    }

    @Test
    @DisplayName("★大V判定失败 ⇒ **整个关注事务回滚**（fail-atomic：计数不许变）")
    void 大V判定失败则整个关注事务回滚() {
        TestUser a = register(PHONE_A, "bigv-a");
        TestUser b = register(PHONE_B, "bigv-b");

        // evaluate() 的第一条语句就是读粉丝数 —— 让它失败
        doThrow(new DataAccessException("模拟大V判定失败") {
        }).when(userDao).getFollowerCountById(anyLong());

        assertThatThrownBy(() -> followService.follow(b.id(), a.id()))
                .isInstanceOf(DataAccessException.class);

        assertThat(countFollowRows()).as("关系行必须回滚").isZero();
        assertThat(followCountColumn(b.id())).as("计数必须回滚").isZero();
        assertThat(followerCountColumn(a.id())).as("计数必须回滚").isZero();
        assertThat(isAutoBigV(a.id())).isFalse();
    }

    /** 便捷读取：{@code followerCountColumn} 的语义化别名（本类关注点就是粉丝数）。 */
    private int followerCount(long userId) {
        return followerCountColumn(userId);
    }
}
