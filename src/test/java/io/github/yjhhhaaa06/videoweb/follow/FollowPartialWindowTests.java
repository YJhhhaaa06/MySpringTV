package io.github.yjhhhaaa06.videoweb.follow;

import io.github.yjhhhaaa06.videoweb.follow.cache.FollowCache;
import io.github.yjhhhaaa06.videoweb.follow.cache.FollowRedisOps;
import io.github.yjhhhaaa06.videoweb.follow.dao.FollowDao;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 第三批 T4 / 账 B7：{@code FollowCache} 的 **T11-C 前缀窗口装载**口径。
 *
 * <h2>为什么必须单列一个类</h2>
 * {@link FollowFlowTests} 钉"接口返回什么"，{@link FollowCacheTests} 钉"缓存时序对不对"，
 * 两者**都无法**证明"大列表 miss 时只装了窗口而不是全量"——那是一次**装载量**断言，
 * 只有数 DAO 被调了哪条、带的什么参数才能观察到。这正是本批"验收靠注入→观察"的落点。
 *
 * <h2>四个被观察的量</h2>
 * <ol>
 *   <li><b>装载量</b>：window miss 走 {@code findFollowedUserIdsWindow(0, offset+count)}，
 *       **绝不**走全量 {@code findAllFollowedUserIds}（后者是"百万行装载"的来源）；</li>
 *   <li><b>集合不变量</b>：DB 取满 want ⇒ 可能还有 ⇒ {@code partial:} 标记存在；取不满 ⇒ 到底 ⇒ 清除；</li>
 *   <li><b>判定</b>：部分态下 {@code ZSCORE} 未命中要回落 DB（前缀里查不到 ≠ 不是成员）；</li>
 *   <li><b>补齐</b>：全量读遇部分态先补齐；页越界先补齐到该页；补齐到底才转完整态。</li>
 * </ol>
 *
 * <h2>为什么用 {@code followCache} 而不是 HTTP</h2>
 * 本类要断言的是**缓存内部决策**，走接口只会经过 {@code FollowService} 把观察点挡住；
 * 且夹具用**假 id**（{@code follow} 表无外键），省掉几十次注册。
 * 端到端行为仍由 {@link FollowFlowTests} / {@link FollowCacheTests} 兜着。
 */
class FollowPartialWindowTests extends AbstractFollowIntegrationTest {

    private static final String PHONE_B = "13800004001";
    private static final String PHONE_EXTRA = "13800004002";

    /** 假的被关注者 id（`follow` 无外键 ⇒ 不必真的存在用户）。**升序**用于断言窗口顺序。 */
    private static final List<Long> TARGETS = List.of(11L, 12L, 13L, 14L, 15L);

    private static final String PARTIAL_PREFIX = "partial:";

    @Autowired
    private FollowCache followCache;

    @MockitoSpyBean
    private FollowDao followDao;

    @MockitoSpyBean
    private FollowRedisOps followRedisOps;

    @BeforeEach
    void resetStubs() {
        Mockito.reset(followDao, followRedisOps);
    }

    // ==================== ① 装载量：只装窗口，不全量 ====================

    @Test
    @DisplayName("★前缀装载：miss 只取 [0, offset+count)，不做全量装载（账 B7 的存在证明）")
    void 窗口miss只前缀装载() {
        TestUser b = register(PHONE_B, "partial-b");
        followAll(b);                                   // DB 共 5 条关注

        FollowCache.Window w = followCache.getFollowingWindow(b.id(), 0, 2);

        assertThat(w.ids()).as("第 1 页只应含升序前 2 个")
                .containsExactly(TARGETS.get(0), TARGETS.get(1));
        verify(followDao, times(1)).findFollowedUserIdsWindow(b.id(), 0L, 2);
        verify(followDao, never())
                .findAllFollowedUserIds(anyLong());     // ★ 这一行就是"没有百万行装载"的证明
        assertThat(zCard(FOLLOWING_KEY + b.id())).as("只装了 2 个成员").isEqualTo(2L);
        assertThat(hasKey(PARTIAL_PREFIX + FOLLOWING_KEY + b.id()))
                .as("DB 取满 want(=2) ⇒ 可能还有后续 ⇒ 必须打 partial 标记").isTrue();
        assertThat(w.total())
                .as("部分态 total 走**计数口径**（DB 列真值 5），不是 ZCARD(2)——ZCARD 会低估")
                .isEqualTo(5L);
    }

    // ==================== ② 判定：部分态未命中回落 DB ====================

    @Test
    @DisplayName("★部分态单条判定：命中前缀不回源；未命中必须回落 DB（前缀里查不到 ≠ 不是成员）")
    void 部分态下单条判定未命中回落DB() {
        TestUser b = register(PHONE_B, "partial-b");
        followAll(b);
        followCache.getFollowingWindow(b.id(), 0, 2);    // 制造部分态：只装了 11/12
        assertThat(hasKey(PARTIAL_PREFIX + FOLLOWING_KEY + b.id())).isTrue();

        assertThat(followCache.isFollowing(b.id(), TARGETS.get(0)))
                .as("在前缀里 ⇒ ZSCORE 命中即可信").isTrue();
        assertThat(followCache.isFollowing(b.id(), TARGETS.get(4)))
                .as("不在前缀里但确实已关注 ⇒ 必须回落 DB 才答得对（真错答案 = false）").isTrue();

        verify(followDao, times(1)).isFollowing(b.id(), TARGETS.get(4));
        verify(followDao, times(1)).isFollowing(anyLong(), anyLong());   // 命中那次不得回源
    }

    @Test
    @DisplayName("★部分态批量判定：命中不回源；未命中并入 DB 批量兜底（且不为部分态回填全量）")
    void 部分态下批量判定未命中回落DB() {
        TestUser b = register(PHONE_B, "partial-b");
        followAll(b);
        followCache.getFollowingWindow(b.id(), 0, 2);

        Map<Long, Boolean> result = followCache.batchIsFollowing(
                b.id(), List.of(TARGETS.get(0), TARGETS.get(4)));

        assertThat(result).containsEntry(TARGETS.get(0), true).containsEntry(TARGETS.get(4), true);
        verify(followDao, times(1)).findFollowedIdsIn(eq(b.id()), eq(List.of(TARGETS.get(4))));
        verify(followDao, never()).findAllFollowedUserIds(anyLong());    // 部分态不回填全量
        assertThat(zCard(FOLLOWING_KEY + b.id())).as("仍只装着前缀的 2 个").isEqualTo(2L);
    }

    // ==================== ③ 补齐：全量读 / 页越界 ====================

    @Test
    @DisplayName("★部分态全量读：必须先补齐再返回（返回前缀会让 feed 静默漏人）")
    void 部分态全量读先补齐() {
        TestUser b = register(PHONE_B, "partial-b");
        followAll(b);
        followCache.getFollowingWindow(b.id(), 0, 2);    // 部分态 W=2
        assertThat(hasKey(PARTIAL_PREFIX + FOLLOWING_KEY + b.id())).isTrue();

        List<Long> all = followCache.getFollowingIds(b.id());

        assertThat(all).as("必须返回全部 5 个，而不是前缀里的 2 个").containsExactlyElementsOf(TARGETS);
        assertThat(hasKey(PARTIAL_PREFIX + FOLLOWING_KEY + b.id()))
                .as("补齐后必须清除 partial 转完整态").isFalse();
        assertThat(zCard(FOLLOWING_KEY + b.id())).isEqualTo(5L);
    }

    @Test
    @DisplayName("★部分态页越界：先补齐到该页（仍非全量）；补齐到底才转完整态")
    void 部分态页越界先补齐() {
        TestUser b = register(PHONE_B, "partial-b");
        followAll(b);                                     // 共 5 条
        followCache.getFollowingWindow(b.id(), 0, 2);     // 部分态 W=2

        FollowCache.Window page2 = followCache.getFollowingWindow(b.id(), 2, 2);

        assertThat(page2.ids()).containsExactly(TARGETS.get(2), TARGETS.get(3));
        verify(followDao, times(1)).findFollowedUserIdsWindow(b.id(), 2L, 2);   // 只补 [W=2, 4)
        verify(followDao, never()).findAllFollowedUserIds(anyLong());
        assertThat(hasKey(PARTIAL_PREFIX + FOLLOWING_KEY + b.id()))
                .as("want=2 取满 ⇒ 后面可能还有 ⇒ 仍是部分态").isTrue();
        assertThat(page2.total()).as("部分态 total 走计数口径").isEqualTo(5L);

        // 再往后再补一次 ⇒ DB 只回 1 行（< want）⇒ 到底 ⇒ 转完整态
        FollowCache.Window page3 = followCache.getFollowingWindow(b.id(), 4, 2);

        assertThat(page3.ids()).containsExactly(TARGETS.get(4));
        assertThat(hasKey(PARTIAL_PREFIX + FOLLOWING_KEY + b.id()))
                .as("补齐到底 ⇒ 清 partial（本断言只钉'标记被清'；total 恰好两口径同值，分不出口径切换）")
                .isFalse();
        assertThat(page3.total()).isEqualTo(5L);
    }

    // ==================== ⑤ 最后一跳：装载/部分态之下 Redis 再抖 ====================

    @Test
    @DisplayName("★部分态下 Redis 再抖：内部最后一跳仍降级 DB 窗口（不 500、total 不丢）")
    void 部分态下最后一跳失败仍降级() {
        TestUser b = register(PHONE_B, "partial-b");
        followAll(b);
        followCache.getFollowingWindow(b.id(), 0, 2);     // 部分态
        assertThat(hasKey(PARTIAL_PREFIX + FOLLOWING_KEY + b.id())).isTrue();

        // 探测入口（existingOf）保持正常 ⇒ 真的进到 partialWindow；让 zCard 抛 ⇒ 打中**内部** catch。
        // 这条覆盖的是"前缀装载那一跳之后 Redis 又抖"的场面——只 stub 探测入口的用例测不到它。
        doThrow(new CacheUnavailableException("模拟 Redis 故障")).when(followRedisOps).zCard(anyString());

        FollowCache.Window w = followCache.getFollowingWindow(b.id(), 0, 2);

        assertThat(w.ids()).as("降级仍要给出该页").containsExactly(TARGETS.get(0), TARGETS.get(1));
        assertThat(w.total()).as("降级时 total 取计数口径（DB 列真值 5）").isEqualTo(5L);
    }

    // ==================== ④ 写路径：部分态一律整体失效 ====================

    @Test
    @DisplayName("★部分态写路径：关注一律整体失效（往前缀里插非前缀成员会破坏 ZRANGE 偏移语义）")
    void 部分态下写路径整体失效() {
        TestUser b = register(PHONE_B, "partial-b");
        followAll(b);
        followCache.getFollowingWindow(b.id(), 0, 2);     // 关注侧：部分态（数据 key + partial 都在）
        TestUser extra = register(PHONE_EXTRA, "partial-extra");
        // ⚠️ 被关注侧的粉丝集**必须也是"已加载"**（此处是空标记）——否则两条条件写的准入判定
        //    会在"冷 key"那一关就短路去整体失效，于是本用例就**测不到** partial 那一条判定
        //    （第一版就是这么假绿的：RV-D 去掉 partial 判定竟未变红）。
        followCache.getFollowerWindow(extra.id(), 0, 2);
        assertThat(hasKey(FOLLOWING_KEY + b.id())).as("前置：关注集已装载").isTrue();
        assertThat(hasKey(PARTIAL_PREFIX + FOLLOWING_KEY + b.id())).as("前置：处于部分态").isTrue();
        assertThat(hasKey(EMPTY_PREFIX + FOLLOWER_KEY + extra.id()))
                .as("前置：粉丝集以空标记形式'已加载'").isTrue();

        followService.follow(b.id(), extra.id());

        assertThat(hasKey(FOLLOWING_KEY + b.id()))
                .as("部分态下不得增量写（会造出'前缀 + 非前缀成员'的畸形集合）").isFalse();
        assertThat(hasKey(PARTIAL_PREFIX + FOLLOWING_KEY + b.id()))
                .as("★ partial 必须与数据 key 一起失效——残留会让下次装载的整体集合被误读为前缀").isFalse();
        assertThat(hasKey(EMPTY_PREFIX + FOLLOWING_KEY + b.id())).isFalse();
        // DB 是真理源：随后读回来仍是正确答案（5 个旧的 + 1 个新的）
        assertThat(followCache.getFollowingIds(b.id())).hasSize(TARGETS.size() + 1);
    }

    // ==================== 夹具 ====================

    /** 让 {@code b} 关注一排**假 id**（`follow` 无外键）；{@link #insertFollowRow} 会同步计数列。 */
    private void followAll(TestUser b) {
        for (Long target : TARGETS) {
            insertFollowRow(b.id(), target);
        }
    }
}
