package io.github.yjhhhaaa06.videoweb.like;

import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.like.cache.LikeRedisOps;
import io.github.yjhhhaaa06.videoweb.like.dao.ContentLikeDao;
import io.github.yjhhhaaa06.videoweb.like.service.LikeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 切片 S3 的**缓存语义与时序**测试 —— 本切片最有价值的测试（决策表 L-6 / L-7 的固化）。
 *
 * <h2>为什么必须单列一个类</h2>
 * {@link LikeFlowTests} 钉的是"接口返回什么"，它**完全无法**证明缓存时序是对的——
 * 那种层次的测试在"缓存写在事务内"和"缓存写在提交后"两种实现下**都会通过**。
 * 本类专门构造只有正确实现才能通过的场面。
 *
 * <h2>★ 核心手法：先预热缓存，再制造回滚（或提交）</h2>
 * 两个用例的**唯一差别**是事务成功还是回滚，其它前置完全相同：
 *
 * <pre>
 * {@link #事务回滚后缓存未被污染()}  预热 → 计数更新抛异常 → 回滚 → 断言缓存**保持原值**（"0"、set 里无该 id）
 * {@link #提交成功后缓存被更新()}    预热 → 正常提交        → 断言缓存**被 +1**（"1"、set 里有该 id）
 * </pre>
 *
 * 若有人把缓存更新从 {@code AFTER_COMMIT} 改回"写在事务方法体末尾"或"事务内更新"，
 * 第一个用例立刻失败（缓存会变成 "1"）。**这就是该机制的存在证明。**
 *
 * <h2>其余不变式</h2>
 * <ul>
 *   <li>{@link #冷key点赞不创建半套缓存()} —— 条件写（防残缺缓存）</li>
 *   <li>{@link #缓存miss时回源DB并回填()} —— 三态读的 miss 分支</li>
 *   <li>{@link #计数为0是合法值而空标记承载无点赞()} / {@link #点赞必须清掉空标记()} /
 *       {@link #写失败时空标记一并失效()} / {@link #非空回填清掉空标记()} —— ★账 B2：空标记（T4 补回）</li>
 *   <li>{@link #Redis不可用时降级DB且不抛()} —— Redis 失败 ⇒ 降级，接口不 500</li>
 *   <li>{@link #DB失败不被降级吞掉()} —— ★ DB 失败 ⇒ **上抛**（两种失败的区分）</li>
 *   <li>{@link #批量查询冷miss回源并回填()} —— 批量路径（S5 的 /comment/replies 依赖它）</li>
 * </ul>
 */
class LikeCacheTests extends AbstractLikeIntegrationTest {

    private static final String AUTHOR_PHONE = "13800006001";
    private static final String AUTHOR = "cache-author";

    @Autowired
    private LikeService likeService;

    @MockitoSpyBean
    private ContentDao contentDao;

    @MockitoSpyBean
    private ContentLikeDao contentLikeDao;

    @MockitoSpyBean
    private LikeRedisOps likeRedisOps;

    @BeforeEach
    void resetStubs() {
        Mockito.reset(contentDao, contentLikeDao, likeRedisOps);
    }

    // ==================== ★ 事务时序：AFTER_COMMIT 的存在证明 ====================

    /**
     * ★★ 决策表 L-6 的核心测试 ★★
     *
     * <p>场面：缓存**已预热**（两个 key 都存在，计数为 "0"）⇒ 此时若缓存被更新，
     * 一定会留下痕迹。然后让**事务内的**计数更新抛异常 ⇒ 整笔回滚。
     *
     * <p>断言"缓存保持预热原值"。因为缓存更新挂在 {@code AFTER_COMMIT}，事务没提交 ⇒ 监听器不触发。
     * 旧 TV 写法（把 {@code cache.likeContent(...)} 写在事务 lambda 之后）在这里其实也能过，
     * 但一旦有人把那一行**挪进** lambda，这个用例就会失败——这正是它要防的误改。
     */
    @Test
    @DisplayName("★事务回滚后缓存未被污染（AFTER_COMMIT 的存在证明：不提交就绝不更新缓存）")
    void 事务回滚后缓存未被污染() {
        long userId = 9001L;
        long contentId = insertContent(9001L);
        prewarmCache(userId, contentId);
        assertThat(contentLikeCountValue(contentId)).as("前置：缓存已预热为 \"0\"").isEqualTo("0");

        doThrow(new DataAccessException("模拟计数更新失败") {
        }).when(contentDao).updateLikeCount(anyLong(), anyInt());

        assertThatThrownBy(() -> likeService.likeContent(userId, contentId))
                .isInstanceOf(DataAccessException.class);

        // ① DB 侧回滚了
        assertThat(countContentLikeRows(contentId, userId)).isZero();
        assertThat(contentLikeCountColumn(contentId)).isZero();

        // ② ★缓存侧也必须"没动过★
        assertThat(contentLikeCountValue(contentId))
                .as("回滚不得让缓存计数变成 1（若变成 1，说明缓存更新脱离了 AFTER_COMMIT）")
                .isEqualTo("0");
        assertThat(userLikeSetContains(userId, contentId))
                .as("回滚不得把 contentId 写进集合")
                .isFalse();
    }

    /**
     * {@link #事务回滚后缓存未被污染()} 的**反向对照**——同一套预热，只把事务放行。
     *
     * <p>没有这条，前一个用例可能是"缓存永不更新"而**误过**（假绿）。两条合起来才排除这种可能：
     * 提交则更新、回滚则不更新，"唯一变量是提交与否"。
     */
    @Test
    @DisplayName("★反向对照：同样预热、事务正常提交 ⇒ 缓存被正确更新（排除\"缓存永不更新\"的假绿）")
    void 提交成功后缓存被更新() {
        long userId = 9002L;
        long contentId = insertContent(9002L);
        prewarmCache(userId, contentId);

        likeService.likeContent(userId, contentId);

        assertThat(contentLikeCountValue(contentId)).as("提交后缓存计数 +1").isEqualTo("1");
        assertThat(userLikeSetContains(userId, contentId)).as("提交后成员进入集合").isTrue();
        assertThat(countContentLikeRows(contentId, userId)).isEqualTo(1L);
        assertThat(contentLikeCountColumn(contentId)).isEqualTo(1);

        // 取消点赞同样要在提交后同步：成员移除、计数递减
        likeService.removeLikeContent(userId, contentId);
        assertThat(userLikeSetContains(userId, contentId)).isFalse();
        assertThat(contentLikeCountValue(contentId)).isEqualTo("0");
        assertThat(contentLikeCountColumn(contentId)).isZero();
    }

    // ==================== 条件写：防"残缺缓存" ====================

    /**
     * 冷 key（Redis 里两个 key 都不存在）点赞 ⇒ **不得创建任何 key**。
     *
     * <p>理由：创建了就是"半套数据"——例如只有计数没有成员，读路径会把这个残缺 key 当成
     * 可信数据命中，从而给出错的 {@code isLiked} 答案。设计是"交给读回填 DB 真理"。
     *
     * <p>同时验证了**读自愈**：随后的读 miss → 回源 DB → 回填，缓存才被正确建立。
     */
    @Test
    @DisplayName("冷 key 点赞不创建半套缓存（条件写）；随后的读 miss 回源并回填")
    void 冷key点赞不创建半套缓存() {
        long userId = 9003L;
        long contentId = insertContent(9003L);

        likeService.likeContent(userId, contentId);

        assertThat(redisKeys())
                .as("条件写：key 原本不存在 ⇒ 不得创建（否则会造出会被命中的残缺缓存）")
                .isEmpty();
        // DB 是真理源，读回来必须正确
        assertThat(likeService.isContentLiked(userId, contentId)).isTrue();
        // 读操作顺带完成回填（成员 set 由"读状态"回填，计数 key 由"读计数"回填）
        assertThat(userLikeSetContains(userId, contentId)).as("读 miss 后回填成员").isTrue();
        assertThat(likeService.getContentLikeCount(contentId)).isEqualTo(1);
        assertThat(contentLikeCountValue(contentId)).as("读 miss 后回填计数").isEqualTo("1");
    }

    // ==================== 三态读 / 回填 ====================

    @Test
    @DisplayName("缓存 miss（过期/被清）时回源 DB 并回填；答案仍正确")
    void 缓存miss时回源DB并回填() {
        long userId = 9004L;
        long contentId = insertContent(9004L);
        likeService.likeContent(userId, contentId);
        likeService.isContentLiked(userId, contentId);   // 建立缓存
        assertThat(redisKeys()).isNotEmpty();

        clearRedis();                                    // 模拟 TTL 到期 / 被清空
        assertThat(redisKeys()).isEmpty();

        assertThat(likeService.isContentLiked(userId, contentId))
                .as("miss 时回源 DB，答案仍正确")
                .isTrue();
        assertThat(userLikeSetContains(userId, contentId)).as("并已回填").isTrue();
    }

    /**
     * ★ <b>账 B2（第三批 T4）：空标记</b>——四域口径统一后的 like 侧边界。
     *
     * <p>{@code 0} 是**合法计数**，必须与"key 不存在"区分开（靠 {@code GET} 返回 null 而非 {@code "0"}）；
     * 而"该用户一个赞都没有"这一事实由**空标记**承载（Redis 里空集合不存在）。
     *
     * <p>⚠️ 本用例的前身（S3）断言的是"只应有计数 key，没有成员 set"——那是 L-7"有意不做空标记"
     * 的产物：**从未点赞的用户每次读状态都回源 DB**。T4 按"四域统一"把 TV 口径搬回来后，
     * 断言随之改为"空标记必须落盘，且第二次读不再回源"（这就是负缓存的存在证明）。
     */
    @Test
    @DisplayName("★空标记：从未点赞的用户不再每次回源 DB；且不建空成员 set（账 B2）")
    void 计数为0是合法值而空标记承载无点赞() {
        long userId = 9005L;
        long contentId = insertContent(9005L);

        assertThat(likeService.getContentLikeCount(contentId)).isZero();
        assertThat(contentLikeCountValue(contentId))
                .as("0 应被回填为字符串 \"0\"，而不是当成 miss 不写")
                .isEqualTo("0");

        // 该用户没有点赞 ⇒ 读状态返回 false，且成员 set 不会被建立（空集合无法表达）⇒ 改由空标记承载
        assertThat(likeService.isContentLiked(userId, contentId)).isFalse();
        String emptyKey = EMPTY_PREFIX + USER_LIKE_SET_KEY + userId;
        assertThat(redisKeys())
                .as("空集不建数据 key，改写空标记（T4 / 账 B2 补回）")
                .containsExactlyInAnyOrder(CONTENT_LIKE_COUNT_KEY + contentId, emptyKey);
        assertThat(ttlSecondsOf(emptyKey))
                .as("空标记是**短 TTL**（口径 60s，与成员 key 的 15m 不同）").isBetween(1L, 60L);

        // ★ 命中空标记 ⇒ 不再回源 DB（单条与批量两条读路径都要覆盖）
        Mockito.clearInvocations(contentLikeDao);
        assertThat(likeService.isContentLiked(userId, contentId)).isFalse();
        assertThat(likeService.batchIsContentLiked(userId, List.of(contentId)))
                .containsEntry(contentId, false);
        verify(contentLikeDao, never()).findLikedContentIdsByUser(anyLong());
    }

    /**
     * ★ <b>账 B2：点赞条件写必须清掉空标记</b>。
     *
     * <p>场面：先读一次"没点赞"把空标记立住，再点赞。若不 {@code DEL empty:}，读路径**先看空标记**
     * ⇒ 刚点的赞在 60 秒内会被答案"未点赞"挡回去（**假否定**，比回源 DB 更坏）。
     * 这条用例就是那个 `DEL KEYS[3]` 的存在证明。
     */
    @Test
    @DisplayName("★点赞条件写清空标记：'从未点赞'的结论不得把刚点的赞挡成 60s 假否定（账 B2）")
    void 点赞必须清掉空标记() {
        long userId = 9010L;
        long contentId = insertContent(9010L);

        assertThat(likeService.isContentLiked(userId, contentId)).isFalse();   // 立起空标记
        String emptyKey = EMPTY_PREFIX + USER_LIKE_SET_KEY + userId;
        assertThat(hasKey(emptyKey)).as("前置：空标记已建立").isTrue();

        likeService.likeContent(userId, contentId);

        assertThat(hasKey(emptyKey))
                .as("条件写必须清空标记，否则 60s 内读会给出假的 false").isFalse();
        assertThat(likeService.isContentLiked(userId, contentId)).isTrue();
    }

    /**
     * ★ <b>账 B2：写失败的自愈必须把空标记一起失效</b>。
     *
     * <p>Redis 写失败时按纪律"失效 key 让读自愈"；若只失效数据 key 与计数 key 而留下空标记，
     * 读路径会**先命中空标记**给出 false 且**不回源 DB** ⇒ 刚提交的赞在 60s 内不可见。
     * 这条用例是 {@code LikeCache.conditionalWrite} 的 catch 块里那个
     * {@code CacheKeys.empty(setKey)} 的存在证明。
     */
    @Test
    @DisplayName("★写失败自愈必须连空标记一起失效（否则刚写的赞被 60s 假否定挡住，账 B2）")
    void 写失败时空标记一并失效() {
        long userId = 9012L;
        long contentId = insertContent(9012L);

        assertThat(likeService.isContentLiked(userId, contentId)).isFalse();   // 立起空标记
        String emptyKey = EMPTY_PREFIX + USER_LIKE_SET_KEY + userId;
        assertThat(hasKey(emptyKey)).as("前置：空标记已建立").isTrue();

        doThrow(new CacheUnavailableException("模拟 Redis 故障"))
                .when(likeRedisOps).applyLikeConditionalWrite(anyString(), anyString(), anyString(), anyLong());

        likeService.likeContent(userId, contentId);        // 业务提交成功，缓存写失败 → 走自愈

        assertThat(hasKey(emptyKey))
                .as("写失败后空标记必须被失效，否则读路径 60s 内无法自愈").isFalse();
        assertThat(likeService.isContentLiked(userId, contentId))
                .as("失效后读 miss → 回源 DB → 得到真实答案").isTrue();
    }

    /**
     * ★ <b>账 B2：非空回填必须清掉空标记</b>（ops 级用例）。
     *
     * <p>为什么必须是 ops 级：上面两条端到端用例都构造不出"**非空回填与空标记共存**"的场面
     * （点赞那条约路径已先由 {@code LIKE_LUA} 删掉标记；空集那条约根本不走非空回填）。
     * 而这一行是必需的：读路径**先看空标记**，若回填真集合时不清它，真数据会被挡在 60s 内 ⇒ 假否定。
     * ⇒ 本用例是 {@code BACKFILL_SET_LUA} 里 {@code DEL KEYS[2]} 的存在证明（去掉它则变红）。
     */
    @Test
    @DisplayName("★非空回填清空标记（BACKFILL_SET_LUA 的 DEL KEYS[2] 的存在证明，账 B2）")
    void 非空回填清掉空标记() {
        long userId = 9011L;
        String setKey = USER_LIKE_SET_KEY + userId;
        String emptyKey = EMPTY_PREFIX + setKey;

        redis.opsForValue().set(emptyKey, "1", Duration.ofSeconds(60));   // 先立空标记
        assertThat(hasKey(emptyKey)).as("前置：空标记已建立").isTrue();

        likeRedisOps.backfillSet(setKey, emptyKey, Set.of(123L), Duration.ofMinutes(15));

        assertThat(hasKey(setKey)).as("回填写入成员").isTrue();
        assertThat(hasKey(emptyKey))
                .as("非空回填必须清空标记，否则读路径会命中它给出 60s 假否定").isFalse();
    }

    // ==================== ★ 两种失败的区分 ====================

    /**
     * ★ <b>Redis 失败 ⇒ 降级直查 DB，不影响结果，也不抛。</b>
     *
     * <p>TV 明写的硬约束（NEEDS 4.2 / H5）：缓存是加速器，挂掉不能让点赞接口 500。
     * 读路径与写路径都要降级——写路径降级为"失效 key 让读自愈"。
     */
    @Test
    @DisplayName("★Redis 不可用：读降级 DB 且不抛；写也降级（缓存挂掉不能让点赞接口 500）")
    void Redis不可用时降级DB且不抛() {
        long userId = 9006L;
        long contentId = insertContent(9006L);
        likeService.likeContent(userId, contentId);      // Redis 正常时先写好 DB

        // 让所有 Redis 入口都失败（三态读的探测入口 = existingOf；计数读 = getString）
        doThrow(new CacheUnavailableException("模拟 Redis 故障")).when(likeRedisOps).existingOf(anyString(), anyString());
        doThrow(new CacheUnavailableException("模拟 Redis 故障")).when(likeRedisOps).getString(anyString());
        doThrow(new CacheUnavailableException("模拟 Redis 故障"))
                .when(likeRedisOps).applyUnlikeConditionalWrite(anyString(), anyString(), anyLong());

        // 读：降级直查 DB，答案仍正确
        assertThat(likeService.isContentLiked(userId, contentId)).isTrue();
        assertThat(likeService.getContentLikeCount(contentId)).isEqualTo(1);
        assertThat(likeService.batchIsContentLiked(userId, List.of(contentId))).containsEntry(contentId, true);

        // 写：缓存更新失败，但业务事务照常提交
        likeService.removeLikeContent(userId, contentId);
        assertThat(countContentLikeRows(contentId, userId)).as("业务写入不受缓存故障影响").isZero();
        assertThat(contentLikeCountColumn(contentId)).isZero();
    }

    /**
     * ★ <b>DB 失败 ⇒ 必须上抛，不能被降级吞掉。</b>
     *
     * <p>这是"两种失败"区分的另一半，也是更容易写错的一半：如果实现里 catch 得宽一点
     * （比如统一 catch {@code RuntimeException}），DB 异常会被当成"缓存不可用"而降级成
     * **空值/false**——把"读不到"伪装成"没点赞"，是比 500 更坏的语义。
     *
     * <p>场面：Redis 正常但 key 不存在（走 miss 回源路径），DB loader 抛异常。
     */
    @Test
    @DisplayName("★DB 失败不被降级吞掉：必须上抛（降级成 false 会把\"读不到\"伪装成\"没点赞\"）")
    void DB失败不被降级吞掉() {
        long userId = 9007L;
        long contentId = insertContent(9007L);
        // Redis 里没有该用户的 set ⇒ 读会走 miss → 回源 DB
        doThrow(new DataAccessException("模拟 DB 故障") {
        }).when(contentLikeDao).findLikedContentIdsByUser(anyLong());

        assertThatThrownBy(() -> likeService.isContentLiked(userId, contentId))
                .as("DB 故障必须冒泡（→ 出口 500），不得被降级成 false")
                .isInstanceOf(DataAccessException.class);
    }

    // ==================== 批量 ====================

    /**
     * 批量查询：冷 miss 回源并回填；随后走 warm 路径（pipeline）；空入参返回空映射。
     *
     * <p>这是 **S5 的 {@code /comment/replies} 与内容列表**要用的能力（给每条评论标注 isLiked），
     * 本切片无端点，故在 service 层覆盖。
     */
    @Test
    @DisplayName("批量查询：冷 miss 回源并回填 → warm 命中结果一致；空入参返回空映射")
    void 批量查询冷miss回源并回填() {
        long userId = 9008L;
        long likedContent = insertContent(9008L);
        long untouchedContent = insertContent(9008L);
        likeService.likeContent(userId, likedContent);
        clearRedis();

        // 冷 miss
        Map<Long, Boolean> cold = likeService.batchIsContentLiked(
                userId, List.of(likedContent, untouchedContent));
        assertThat(cold).containsEntry(likedContent, true).containsEntry(untouchedContent, false);
        assertThat(userLikeSetContains(userId, likedContent)).as("冷 miss 后回填").isTrue();

        // warm（pipeline 路径）——结果必须与冷路径一致
        Map<Long, Boolean> warm = likeService.batchIsContentLiked(
                userId, List.of(likedContent, untouchedContent));
        assertThat(warm).as("warm 与 cold 结果必须一致").isEqualTo(cold);

        assertThat(likeService.batchIsContentLiked(userId, List.of())).isEmpty();
        assertThat(likeService.batchIsCommentLiked(userId, List.of())).isEmpty();
    }

    @Test
    @DisplayName("评论侧缓存键形状正确（与内容侧 key 不同：user:commentLikeSet / comment:likeCount）")
    void 评论侧缓存键形状() {
        long userId = 9009L;
        long authorId = 9009L;
        long contentId = insertContent(authorId);
        long commentId = insertComment(contentId, authorId, "评论", null, null);

        likeService.likeComment(userId, commentId);

        // 两族 key 各自**懒回填**：读状态只回填成员 set，读计数才回填 count key（缓存按需建立，不预写无关 key）
        assertThat(likeService.isCommentLiked(userId, commentId)).isTrue();
        assertThat(userCommentLikeSetContains(userId, commentId)).isTrue();

        assertThat(likeService.getCommentLikeCount(commentId)).isEqualTo(1);
        assertThat(commentLikeCountValue(commentId)).isEqualTo("1");

        assertThat(userLikeSetContains(userId, commentId))
                .as("评论点赞不得写进内容的 set（两套 key 必须隔离）")
                .isFalse();
    }

    // ==================== helpers ====================

    /**
     * 预热两个缓存 key（模拟"此前有人读过，缓存已有数据"）。
     *
     * <p>这样"缓存是否被更新"才有可观察的痕迹——冷 key 场景下无论实现对错都不会有 key，
     * 断言就没有区分力。
     */
    private void prewarmCache(long userId, long contentId) {
        redis.opsForValue().set(CONTENT_LIKE_COUNT_KEY + contentId, "0", Duration.ofMinutes(15));
        // 集合必须非空才存在，故放一个哨兵成员（一个绝不可能被点赞的 id）
        redis.opsForSet().add(USER_LIKE_SET_KEY + userId, "999999999");
        redis.expire(USER_LIKE_SET_KEY + userId, Duration.ofMinutes(15));
    }
}
