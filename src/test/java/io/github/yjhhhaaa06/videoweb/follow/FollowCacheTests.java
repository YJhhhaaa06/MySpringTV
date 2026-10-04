package io.github.yjhhhaaa06.videoweb.follow;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.follow.cache.FollowRedisOps;
import io.github.yjhhhaaa06.videoweb.follow.dao.FollowDao;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * S4 的**缓存语义与时序**测试 —— 本切片最有价值的测试（决策表 F-4 / F-5 / F-7 的固化）。
 *
 * <h2>为什么必须单列一个类</h2>
 * {@link FollowFlowTests} 钉的是"接口返回什么"，它**完全无法**证明缓存时序是对的——
 * 那种层次的测试在"缓存写在事务内"和"缓存写在提交后"两种实现下**都会通过**。
 * 本类专门构造只有正确实现才能通过的场面。
 *
 * <h2>★ 核心手法：先预热缓存，再制造回滚（或提交）</h2>
 * 两个用例的**唯一差别**是事务成功还是回滚，其它前置完全相同：
 *
 * <pre>
 * {@link #事务回滚后缓存未被污染()}  预热 → 计数更新抛异常 → 回滚 → 断言缓存**保持预热原值**
 * {@link #提交成功后缓存被更新()}    预热 → 正常提交        → 断言缓存**被增量更新**
 * </pre>
 *
 * 若有人把缓存更新从 {@code AFTER_COMMIT} 改回"写在事务方法体末尾"或"事务内更新"，
 * 第一个用例立刻失败。**这就是该机制的存在证明。**
 *
 * <h3>预热为什么要"两侧都加载"</h3>
 * 条件双写的准入门槛是"<b>两侧都算已加载</b>"（数据 key 存在，或空标记存在）。
 * 只预热一侧的话，写路径会走"整体失效"分支（而非增量写），
 * 于是"回滚 vs 提交"的差别表现为"DEL 有没有发生"，判别力变弱。故预热要覆盖
 * ① 关注者侧的关注集（非空，这样错误写入会多出一个成员）
 * ② 被关注者侧的粉丝集（**空**，即空标记——这恰好也覆盖了"空标记参与写路径判定"）。
 *
 * <h2>其余不变式</h2>
 * <ul>
 *   <li>{@link #冷key关注不创建半套缓存()} —— 条件写（防残缺缓存）+ 读自愈</li>
 *   <li>{@link #空标记让无关注的用户不再回源DB()} —— 空标记（防穿透）</li>
 *   <li>{@link #命中缓存时不回源DB()} —— 三态读的 hit 分支</li>
 *   <li>{@link #缓存miss时回源DB并回填()} —— 三态读的 miss 分支</li>
 *   <li>{@link #取关后成员从缓存移除()} —— 写路径的 ZREM 分支</li>
 *   <li>{@link #Redis不可用时降级DB且不抛()} —— Redis 失败 ⇒ 降级，接口不 500</li>
 *   <li>{@link #DB失败不被降级吞掉()} —— ★ DB 失败 ⇒ **上抛**（两种失败的区分）</li>
 * </ul>
 */
class FollowCacheTests extends AbstractFollowIntegrationTest {

    private static final String PHONE_A = "13800003001";   // 被关注者（本次关注的目标）
    private static final String PHONE_B = "13800003002";   // 操作者
    private static final String PHONE_C = "13800003003";   // b 已有的关注（预热用）

    @MockitoSpyBean
    private UserDao userDao;

    @MockitoSpyBean
    private FollowDao followDao;

    @MockitoSpyBean
    private FollowRedisOps followRedisOps;

    @BeforeEach
    void resetStubs() {
        Mockito.reset(userDao, followDao, followRedisOps);
    }

    // ==================== ★ 事务时序：AFTER_COMMIT 的存在证明（F-4） ====================

    /**
     * ★★ 决策表 F-4 的核心测试 ★★
     *
     * <p>场面：缓存**两侧都已预热** ⇒ 此时若缓存被更新，一定会留下痕迹
     * （关注集多一个成员、空标记被解除）。然后让**事务内的**计数更新抛异常 ⇒ 整笔回滚。
     *
     * <p>断言"缓存保持预热原值"。因为缓存更新挂在 {@code AFTER_COMMIT}，事务没提交 ⇒ 监听器不触发。
     * 一旧写法（把 {@code followCache.cacheFollow(...)} 写在事务之后）在这里其实也能过，
     * 但一旦有人把那一行**挪进**事务体，这个用例就会失败——这正是它要防的误改。
     */
    @Test
    @DisplayName("★事务回滚后缓存未被污染（AFTER_COMMIT 的存在证明：不提交就绝不更新缓存）")
    void 事务回滚后缓存未被污染() {
        TestUser a = register(PHONE_A, "cache-a");
        TestUser b = register(PHONE_B, "cache-b");
        TestUser c = register(PHONE_C, "cache-c");
        prewarmBothSides(b, a, c);

        // 事务内的第二步计数更新失败 ⇒ 整笔回滚（此时 addFollow + updateFollowCount 已执行）
        doThrow(new DataAccessException("模拟计数更新失败") {
        }).when(userDao).updateFollowerCount(anyLong(), anyInt());

        assertThatThrownBy(() -> followService.follow(b.id(), a.id()))
                .isInstanceOf(DataAccessException.class);

        // ① DB 侧回滚了
        assertThat(countFollowRows(b.id(), a.id())).as("关系行必须回滚").isZero();
        assertThat(followCountColumn(b.id())).as("b 的 follow_count 回到预热值（1）").isEqualTo(1);
        assertThat(followerCountColumn(a.id())).as("a 的 follower_count 回到原值（0）").isZero();

        // ② ★缓存侧也必须"没动过"★
        assertThat(followingSetContains(b.id(), a.id()))
                .as("回滚不得把 a 写进 b 的关注集（若写进去，说明缓存更新脱离了 AFTER_COMMIT）")
                .isFalse();
        assertThat(zCard(FOLLOWING_KEY + b.id())).as("b 的关注集仍只有预热的那 1 个").isEqualTo(1L);
        assertThat(hasKey(FOLLOWER_KEY + a.id())).as("回滚不得建立 a 的粉丝集").isFalse();
        assertThat(hasKey(EMPTY_PREFIX + FOLLOWER_KEY + a.id()))
                .as("回滚不得解除 a 粉丝集的空标记")
                .isTrue();
    }

    /**
     * {@link #事务回滚后缓存未被污染()} 的**反向对照**——同一套预热，只把事务放行。
     *
     * <p>没有这条，前一个用例可能是"缓存永不更新"而**误过**（假绿）。两条合起来才排除这种可能：
     * 提交则更新、回滚则不更新，"唯一变量是提交与否"。
     */
    @Test
    @DisplayName("★反向对照：同样预热、事务正常提交 ⇒ 缓存被正确增量更新（排除\"缓存永不更新\"的假绿）")
    void 提交成功后缓存被更新() {
        TestUser a = register(PHONE_A, "cache-a");
        TestUser b = register(PHONE_B, "cache-b");
        TestUser c = register(PHONE_C, "cache-c");
        prewarmBothSides(b, a, c);

        followService.follow(b.id(), a.id());

        assertThat(followingSetContains(b.id(), a.id())).as("b 的关注集应新增 a").isTrue();
        assertThat(zCard(FOLLOWING_KEY + b.id())).as("1(预热 c) + 1(本次 a)").isEqualTo(2L);
        assertThat(followerSetContains(a.id(), b.id())).as("a 的粉丝集应新增 b").isTrue();
        assertThat(zCard(FOLLOWER_KEY + a.id())).isEqualTo(1L);
        assertThat(hasKey(EMPTY_PREFIX + FOLLOWER_KEY + a.id())).as("空标记应被解除（已有真实成员）").isFalse();

        assertThat(countFollowRows(b.id(), a.id())).isEqualTo(1L);
        assertThat(followCountColumn(b.id())).isEqualTo(2);
        assertThat(followerCountColumn(a.id())).isEqualTo(1);
    }

    // ==================== 条件写：防"残缺缓存" ====================

    /**
     * 冷 key（Redis 里四个 key 都不存在）关注 ⇒ **不得创建任何半套 key**。
     *
     * <p>理由：创建了就是"半套数据"——例如只有关注集没有粉丝集，读路径会把这个残缺状态
     * 当成可信数据命中，从而给出错的关注列表 / isFollowed 答案。设计是"交给读回填 DB 真理"。
     *
     * <p>同时验证**读自愈**：随后的读 miss → 回源 DB → 回填，缓存才被正确建立。
     */
    @Test
    @DisplayName("冷 key 关注不创建半套缓存（条件写）；随后的读 miss 回源并回填")
    void 冷key关注不创建半套缓存() {
        TestUser a = register(PHONE_A, "cache-a");
        TestUser b = register(PHONE_B, "cache-b");

        followService.follow(b.id(), a.id());

        assertThat(keysWithPrefix(FOLLOWING_KEY))
                .as("条件写：两侧都未加载 ⇒ 不得创建关注集（否则会造出会被命中的残缺缓存）")
                .isEmpty();
        assertThat(keysWithPrefix(FOLLOWER_KEY)).as("同上：粉丝集也不得创建").isEmpty();

        // DB 是真理源，读回来必须正确
        assertThat(Envelope.code(getFollowing(b.id(), b.token()))).isEqualTo(200);
        assertThat(followingSetContains(b.id(), a.id())).as("读 miss 后回填成员").isTrue();
        assertThat(ttlSecondsOf(FOLLOWING_KEY + b.id())).as("回填必须带 TTL（-1 = 永不过期，是缺陷）")
                .isPositive();
    }

    // ==================== 三态读 / 空标记 / 回填 ====================

    /**
     * 无关注的用户：第一次读 miss → 回源 DB → 写**空标记**（而不是建一个空 ZSet——
     * Redis 里空集合不存在）；第二次读命中空标记 ⇒ **不得再回源 DB**。
     */
    @Test
    @DisplayName("空标记：无关注的用户不再每次回源 DB；且不会建出空数据 key")
    void 空标记让无关注的用户不再回源DB() {
        TestUser a = register(PHONE_A, "cache-a");

        assertThat(Envelope.code(getFollowing(a.id(), a.token()))).isEqualTo(200);

        assertThat(hasKey(EMPTY_PREFIX + FOLLOWING_KEY + a.id())).as("应写下空标记").isTrue();
        assertThat(hasKey(FOLLOWING_KEY + a.id())).as("空集不建数据 key").isFalse();
        assertThat(ttlSecondsOf(EMPTY_PREFIX + FOLLOWING_KEY + a.id()))
                .as("空标记是**短 TTL**（TV 口径 60s）").isBetween(1L, 60L);

        Mockito.clearInvocations(followDao);
        assertThat(Envelope.code(getFollowing(a.id(), a.token()))).isEqualTo(200);
        verify(followDao, never()).findAllFollowedUserIds(anyLong());
        // 窗口读的 loader 也必须没被调用（T4 起 miss 走的是这条）
        verify(followDao, never()).findFollowedUserIdsWindow(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("缓存 miss 时回源 DB 并回填；答案与 DB 一致（独立 oracle）")
    void 缓存miss时回源DB并回填() {
        TestUser a = register(PHONE_A, "cache-a");
        TestUser b = register(PHONE_B, "cache-b");
        insertFollowRow(b.id(), a.id());

        var resp = getFollowing(b.id(), b.token());

        assertThat(Envelope.data(resp).path("total").asInt()).isEqualTo(1);
        assertThat(Envelope.data(resp).path("list").get(0).path("userId").asLong()).isEqualTo(a.id());
        assertThat(followingSetContains(b.id(), a.id())).as("miss 回填").isTrue();
    }

    @Test
    @DisplayName("命中缓存时不再回源 DB（窗口读也不走 DB 窗口）")
    void 命中缓存时不回源DB() {
        TestUser a = register(PHONE_A, "cache-a");
        TestUser b = register(PHONE_B, "cache-b");
        TestUser c = register(PHONE_C, "cache-c");
        insertFollowRow(b.id(), a.id());
        insertFollowRow(b.id(), c.id());

        assertThat(Envelope.code(getFollowing(b.id(), b.token()))).isEqualTo(200);   // 第一次：miss → 回填
        Mockito.clearInvocations(followDao);

        assertThat(Envelope.code(getFollowing(b.id(), b.token()))).isEqualTo(200);   // 第二次：命中
        verify(followDao, never()).findAllFollowedUserIds(anyLong());
        verify(followDao, never()).findFollowedUserIdsWindow(anyLong(), anyLong(), anyInt());
    }

    // ==================== 写路径：取关（ZREM 分支） ====================

    @Test
    @DisplayName("取关后成员从两侧缓存移除（ZREM 分支，而非整体失效）")
    void 取关后成员从缓存移除() {
        TestUser a = register(PHONE_A, "cache-a");
        TestUser b = register(PHONE_B, "cache-b");

        followService.follow(b.id(), a.id());
        // 先读热两侧（冷 key 时写路径会整体失效；读热后才走 ZREM 增量写）
        getFollowing(b.id(), b.token());
        getFollowers(a.id(), b.token());
        assertThat(followingSetContains(b.id(), a.id())).isTrue();
        assertThat(followerSetContains(a.id(), b.id())).isTrue();

        followService.unfollow(b.id(), a.id());

        assertThat(followingSetContains(b.id(), a.id())).as("取关后成员应从关注集移除").isFalse();
        assertThat(followerSetContains(a.id(), b.id())).as("取关后成员应从粉丝集移除").isFalse();
        assertThat(countFollowRows()).isZero();
    }

    // ==================== 两种失败必须区分（F-5） ====================

    /**
     * Redis 不可用：读**降级直查 DB** 且不抛 —— 缓存挂掉不能让关注列表 500。
     *
     * <p>降级路径用的是 **DB 窗口查询 + DB 计数**（不是全量装载）——TV 的口径，
     * 避免"为了看一页把百万粉丝全拉进内存"。
     */
    @Test
    @DisplayName("★Redis 不可用：读降级 DB 且不抛（缓存挂掉不能让关注接口 500）")
    void Redis不可用时降级DB且不抛() {
        TestUser a = register(PHONE_A, "cache-a");
        TestUser b = register(PHONE_B, "cache-b");
        insertFollowRow(b.id(), a.id());

        doThrow(new CacheUnavailableException("模拟 Redis 故障")).when(followRedisOps).existingOf(anyString(), anyString(), anyString());

        var resp = getFollowing(b.id(), b.token());

        assertThat(Envelope.code(resp)).isEqualTo(200);
        assertThat(Envelope.data(resp).path("total").asInt()).as("降级时 total 取计数列").isEqualTo(1);
        assertThat(Envelope.data(resp).path("list").get(0).path("userId").asLong()).isEqualTo(a.id());
        assertThat(Envelope.data(resp).path("list").get(0).path("isFollowed").asBoolean())
                .as("批量判关注态也应降级作答").isTrue();
    }

    /**
     * ★ **DB 失败必须上抛**——不得被降级分支吞成"空列表"。
     *
     * <p>场面：先让 Redis 入口全失败（强制走降级分支），再让降级用的 DB loader 抛异常。
     * 若异常被吞，用户会看到"关注列表为空"——把"读不到"伪装成"没有"，是更坏的语义。
     */
    @Test
    @DisplayName("★DB 失败不被降级吞掉：必须上抛（降级成空列表会把\"读不到\"伪装成\"没有\"）")
    void DB失败不被降级吞掉() {
        TestUser a = register(PHONE_A, "cache-a");
        TestUser b = register(PHONE_B, "cache-b");

        doThrow(new CacheUnavailableException("模拟 Redis 故障")).when(followRedisOps).existingOf(anyString(), anyString(), anyString());
        doThrow(new DataAccessException("模拟 DB 故障") {
        }).when(followDao).findFollowedUserIdsWindow(anyLong(), anyLong(), anyInt());

        assertThatThrownBy(() -> followService.getFollowingList(b.id(), b.id(), 1, 100))
                .as("DB 故障必须冒泡（→ 出口 500），不得被降级成空信封")
                .isInstanceOf(DataAccessException.class);
    }

    // ==================== 夹具：预热 ====================

    /**
     * 预热写路径两侧：
     * ① b 的关注集（**非空**，含 c）——错误写入会多出一个成员，留下可判别的痕迹；
     * ② a 的粉丝集（**空**，即空标记）——条件写需要"两侧都已加载"才走增量写。
     */
    private void prewarmBothSides(TestUser b, TestUser a, TestUser c) {
        insertFollowRow(b.id(), c.id());
        assertThat(Envelope.code(getFollowing(b.id(), b.token()))).isEqualTo(200);
        assertThat(Envelope.code(getFollowers(a.id(), b.token()))).isEqualTo(200);

        assertThat(followingSetContains(b.id(), c.id())).as("预热①：b 的关注集含 c").isTrue();
        assertThat(zCard(FOLLOWING_KEY + b.id())).isEqualTo(1L);
        assertThat(hasKey(EMPTY_PREFIX + FOLLOWER_KEY + a.id())).as("预热②：a 的粉丝集为空标记").isTrue();
    }
}
