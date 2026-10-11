package io.github.yjhhhaaa06.videoweb.interaction;

import io.github.yjhhhaaa06.videoweb.favorite.service.FavoriteService;
import io.github.yjhhhaaa06.videoweb.follow.service.FollowService;
import io.github.yjhhhaaa06.videoweb.interaction.model.InteractionTarget;
import io.github.yjhhhaaa06.videoweb.interaction.model.vo.InteractionStatusVO;
import io.github.yjhhhaaa06.videoweb.interaction.service.InteractionService;
import io.github.yjhhhaaa06.videoweb.like.service.LikeService;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * T9（收藏二期-1，R-10）：**互动域**两种契约（单条 / 批量）的契约测试。
 *
 * <h2>为什么没有 HTTP（本域刻意不拆接口）</h2>
 * R-10 的核心约束是"不拆 HTTP 接口"⇒ 本域没有 Controller，可观察面就是
 * {@link InteractionService} 两个方法的**返回值 + 返回值所反映的 DB 终态**，
 * 故直接用真容器起 Spring、经**真实写入口**（{@code likeService.likeContent} 等）
 * 造状态后调服务 —— 是本仓"集成测试 = 新的端到端"的一个服务级切片。
 *
 * <h2>覆盖什么（期望值全部出自业务口径，盖住实现照样写得出来）</h2>
 * <ul>
 *   <li><b>单条三维</b>：已赞 / 已藏 / 已关注作者 ⇒ 各自 true；同作者的另一条内容
 *       ⇒ 只有 {@code isFollowed=true}（★ 关注判的是**作者**不是内容）；</li>
 *   <li>★ <b>域边界排除评论点赞</b>：只赞了某内容的**评论** ⇒ 该内容的 {@code isLiked} 必须 false
 *       （like 域同时含内容赞与评论赞，互动域只用**内容赞**契约）；</li>
 *   <li>★ <b>匿名（userId == null）</b>：返回 {@code null}（**不是**三个 false）且
 *       **不查任何互动域**（spy 零交互）—— "没登录"与"登录但没互动"必须可区分；</li>
 *   <li>★ <b>批量不重不漏</b>：给 N 个（含重复）id 返回 N 条唯一映射；三维在一次批量里各自正确；</li>
 *   <li>★ <b>批量原样透传批量语义</b>：三维各**恰好一趟**批量调用、单条方法**零次**
 *       （逐条会让 100 条列表变 200+ 次往返 —— {@code ContentStatusFiller} 的既有纪律）；</li>
 *   <li><b>作者未知（authorId &lt;= 0）</b>：该条目不进关注查询、按 false 作答
 *       （与既有列表填充口径一致）；全部作者未知 ⇒ 关注维度整趟不发查询。</li>
 * </ul>
 *
 * <h2>★ 期望值怎么来的（规避"迎合型伪测试"）</h2>
 * "三维归属"出自 R-10（{@code 收藏功能-分期与设计.md} §3.6：isLiked / isFavorited / isFollowed）；
 * "匿名 ⇒ null 且不查"出自同节（"不是三个 false"是它相对平铺的表达力增益）；
 * "批量不得循环调单条"出自 T9 红线（{@code CURRENT_TASKS.md}）与 {@code ContentStatusFiller}
 * 类注释的性能性质；"评论赞不算内容赞"出自 T9 红线"域边界排除评论点赞"；
 * "authorId &lt;= 0 跳过"是既有填充行为（{@code ContentStatusFiller.fillFollowStatus}）的保留。
 * 盖住实现，这些断言照样写得出来。
 *
 * <h2>★ 反向验证（注入 → 确认变红 → 还原；实测记录见 T9 回写）</h2>
 * <ul>
 *   <li>去掉两条方法的 {@code userId == null} 短路 ⇒ 两条"匿名"用例变红（NPE / 有交互）；</li>
 *   <li>把 {@code batchGetStatus} 改成循环调 {@code getStatus}（或循环调三个单条方法）
 *       ⇒ {@code 批量一趟透传不循环调单条} 变红（单条方法被调 N 次）；</li>
 *   <li>把关注维度的作者换成内容 id（或漏传 authorId）⇒ {@code 单条三维各自正确}
 *       的"同作者另一条"断言变红。</li>
 * </ul>
 *
 * <h2>独立性</h2>
 * 夹具全部走**真实写服务**（like / favorite / follow），关键前置行再直查 DB 复算
 * （content_like / favorite_item / follow 三张表）—— 不信接口 / 服务回显。
 * 三个被依赖服务的读写路径各有自己的测试类兜底，本类只钉**组装**语义。
 */
class InteractionStatusContractTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    @Autowired
    private InteractionService interactionService;

    @Autowired
    private StringRedisTemplate redis;

    /** 三个被组装的服务换成 spy（真实执行 + 可验证调用形态）；夹具与断言共用同一实例。 */
    @MockitoSpyBean
    private LikeService likeService;

    @MockitoSpyBean
    private FavoriteService favoriteService;

    @MockitoSpyBean
    private FollowService followService;

    @BeforeEach
    void 清空本域相关表并重置spy() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        // 本域没有自己的表（聚合器）；以下是被聚合三域的存储 + 夹具内容表。
        jdbcTemplate.update("DELETE FROM follow");
        jdbcTemplate.update("DELETE FROM auto_bigv");
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content_like");
        jdbcTemplate.update("DELETE FROM comment_like");
        jdbcTemplate.update("DELETE FROM comment");
        jdbcTemplate.update("DELETE FROM content");
        clearRedis();
        // spy 是单例 bean，调用记录会跨用例累积 ⇒ 每测清零（真实行为不受影响）
        Mockito.reset(likeService, favoriteService, followService);
    }

    // ========================================================================
    // 单条契约
    // ========================================================================

    @Test
    @DisplayName("★单条：三维各自正确（同作者另一条只亮关注）、评论赞不算内容赞、按用户隔离")
    void 单条三维各自正确且按用户隔离() {
        TestUser viewer = register("13900009001", "t9-viewer");
        TestUser author = register("13900009002", "t9-author");
        TestUser other = register("13900009003", "t9-other");
        long likedAndFavorited = insertContent(author.id(), 1);
        long sameAuthorOnly = insertContent(author.id(), 1);
        long commentLikedOnly = insertContent(author.id(), 1);
        long commentId = insertComment(commentLikedOnly, author.id());

        likeService.likeContent(viewer.id(), likedAndFavorited);
        favoriteService.addItem(viewer.id(), null, likedAndFavorited);
        followService.follow(viewer.id(), author.id());
        likeService.likeComment(viewer.id(), commentId);        // ★ 评论赞：不得影响内容 isLiked
        followService.follow(other.id(), author.id());          // 对照：另一人只关注、不赞不藏

        // 独立 oracle：三份前置状态确实落地（防"夹具没造上"的假绿）
        assertThat(countRows("SELECT COUNT(*) FROM content_like WHERE user_id = ? AND content_id = ?",
                viewer.id(), likedAndFavorited)).as("点赞行").isEqualTo(1L);
        assertThat(countRows("SELECT COUNT(*) FROM favorite_item WHERE user_id = ? AND content_id = ?",
                viewer.id(), likedAndFavorited)).as("收藏行").isEqualTo(1L);
        assertThat(countRows("SELECT COUNT(*) FROM follow WHERE user_id = ? AND followed_user_id = ?",
                viewer.id(), author.id())).as("关注行").isEqualTo(1L);

        // ① 已赞 + 已藏 + 已关注
        InteractionStatusVO first = interactionService.getStatus(viewer.id(), likedAndFavorited, author.id(), 1);
        assertThat(first.getIsLiked()).isTrue();
        assertThat(first.getIsFavorited()).isTrue();
        assertThat(first.getIsFollowed()).isTrue();

        // ② 同作者的另一条内容：没赞没藏，但关注维度必须亮
        //    （★ 若实现把 contentId 当作者 id 传下去，这里会变 false）
        InteractionStatusVO second = interactionService.getStatus(viewer.id(), sameAuthorOnly, author.id(), 1);
        assertThat(second.getIsLiked()).as("没赞过这条").isFalse();
        assertThat(second.getIsFavorited()).as("没藏过这条").isFalse();
        assertThat(second.getIsFollowed()).as("★ 关注判的是**作者**：同作者的另一条内容也是 true").isTrue();

        // ③ 域边界：评论赞不进内容赞（like 域含评论赞，互动域只用内容赞契约）
        InteractionStatusVO third = interactionService.getStatus(viewer.id(), commentLikedOnly, author.id(), 1);
        assertThat(third.getIsLiked())
                .as("★ 只赞了这条内容的**评论**，没赞内容本身 ⇒ false（评论点赞不在互动域边界内）")
                .isFalse();

        // ④ 隔离：另一人只关注 ⇒ 三维里只有关注亮；漏 userId 过滤会把 viewer 的赞 / 藏混进来
        InteractionStatusVO otherView = interactionService.getStatus(other.id(), likedAndFavorited, author.id(), 1);
        assertThat(otherView.getIsLiked()).as("另一人没赞过").isFalse();
        assertThat(otherView.getIsFavorited()).as("另一人没藏过").isFalse();
        assertThat(otherView.getIsFollowed()).as("另一人关注了作者 ⇒ true").isTrue();
    }

    @Test
    @DisplayName("★单条：登录但零互动 ⇒ 三维全 false 且**非 null**（与匿名可区分）")
    void 单条登录零互动返回非null且三维false() {
        TestUser spectator = register("13900009017", "t9-spectator");
        TestUser author = register("13900009018", "t9-spectator-author");
        long content = insertContent(author.id(), 1);

        InteractionStatusVO status = interactionService.getStatus(spectator.id(), content, author.id(), 1);

        assertThat(status)
                .as("★ 登录且零互动 ⇒ 对象在场（不是 null）—— 与匿名用例成对，表达力增益的两半")
                .isNotNull();
        assertThat(status.getIsLiked()).isFalse();
        assertThat(status.getIsFavorited()).isFalse();
        assertThat(status.getIsFollowed()).isFalse();
    }

    @Test
    @DisplayName("★单条匿名（userId == null）：返回 null 且不查任何互动域（不是三个 false）")
    void 单条匿名返回null且不查互动域() {
        TestUser viewer = register("13900009004", "t9-anon-single");
        TestUser author = register("13900009005", "t9-anon-author");
        long content = insertContent(author.id(), 1);
        // 有互动数据在场也不查（"短路"而非"恰好查不到"）
        likeService.likeContent(viewer.id(), content);
        favoriteService.addItem(viewer.id(), null, content);
        followService.follow(viewer.id(), author.id());

        Mockito.reset(likeService, favoriteService, followService);
        InteractionStatusVO status = interactionService.getStatus(null, content, author.id(), 1);

        assertThat(status)
                .as("★ 匿名 ⇒ null（不是三个 false）—— '没登录'与'登录但没互动'必须可区分（R-10 §3.6）")
                .isNull();
        verifyNoInteractions(likeService, favoriteService, followService);
    }

    // ========================================================================
    // 批量契约
    // ========================================================================

    @Test
    @DisplayName("★批量：给 N 个 id 返回 N 条唯一映射（不重不漏），三维各自正确、干扰行不串")
    void 批量不重不漏且三维各自正确() {
        TestUser viewer = register("13900009006", "t9-batch");
        TestUser other = register("13900009007", "t9-batch-other");
        TestUser author = register("13900009008", "t9-batch-author");
        TestUser otherAuthor = register("13900009009", "t9-batch-author2");
        long liked = insertContent(author.id(), 1);
        long favorited = insertContent(author.id(), 2);         // 图文（type=2）走同一契约
        long untouched = insertContent(otherAuthor.id(), 1);

        likeService.likeContent(viewer.id(), liked);
        favoriteService.addItem(viewer.id(), null, favorited);
        followService.follow(viewer.id(), author.id());
        // ★ 干扰行（T8 同款教训：没有对照，"去掉过滤"也可能仍绿）——
        //   另一人：赞了"未互动"那条、又收藏了它与"已收藏"那条
        likeService.likeContent(other.id(), untouched);
        favoriteService.addItem(other.id(), null, favorited);
        favoriteService.addItem(other.id(), null, untouched);

        List<InteractionTarget> targets = List.of(
                new InteractionTarget(liked, author.id(), 1),
                new InteractionTarget(favorited, author.id(), 2),
                new InteractionTarget(untouched, otherAuthor.id(), 1),
                new InteractionTarget(liked, author.id(), 1));      // ★ 重复 id：收敛为一条

        Map<Long, InteractionStatusVO> result = interactionService.batchGetStatus(viewer.id(), targets);

        assertThat(result.keySet())
                .as("★ 不重不漏：入参 4 条（含重复）⇒ 恰好 3 个唯一 key")
                .containsExactlyInAnyOrder(liked, favorited, untouched);

        assertThat(result.get(liked).getIsLiked()).isTrue();
        assertThat(result.get(liked).getIsFavorited()).isFalse();
        assertThat(result.get(liked).getIsFollowed()).as("viewer 关注了作者 ⇒ true").isTrue();

        assertThat(result.get(favorited).getIsLiked()).isFalse();
        assertThat(result.get(favorited).getIsFavorited()).isTrue();
        assertThat(result.get(favorited).getIsFollowed()).as("同作者 ⇒ 关注为 true").isTrue();

        InteractionStatusVO untouchedView = result.get(untouched);
        assertThat(untouchedView.getIsLiked())
                .as("★ 另一人赞了这条 —— 若批量漏 userId，viewer 会看到 true").isFalse();
        assertThat(untouchedView.getIsFavorited())
                .as("★ 另一人收藏了这条 —— 若批量漏 userId，viewer 会看到 true").isFalse();
        assertThat(untouchedView.getIsFollowed()).as("viewer 没关注 otherAuthor").isFalse();
    }

    @Test
    @DisplayName("★批量契约原样透传批量语义：三维各一趟批量、单条方法零次（不得循环调单条）")
    void 批量一趟透传不循环调单条() {
        TestUser viewer = register("13900009010", "t9-pass");
        TestUser author = register("13900009011", "t9-pass-author");
        TestUser otherAuthor = register("13900009012", "t9-pass-author2");
        long c1 = insertContent(author.id(), 1);
        long c2 = insertContent(author.id(), 1);
        long c3 = insertContent(otherAuthor.id(), 1);
        likeService.likeContent(viewer.id(), c1);
        favoriteService.addItem(viewer.id(), null, c2);
        followService.follow(viewer.id(), author.id());

        interactionService.batchGetStatus(viewer.id(), List.of(
                new InteractionTarget(c1, author.id(), 1),
                new InteractionTarget(c2, author.id(), 1),
                new InteractionTarget(c3, otherAuthor.id(), 1),
                new InteractionTarget(c1, author.id(), 1)));        // 重复 id

        // ★ 三维各**恰好一趟**批量：内容 id 去重后 3 个、作者去重后 2 个（入参 4 条含重复）
        //   ——逐条调用会让这里的 times(1) 变 times(N)、never() 变 N 次（判别力就在这两个断言）。
        //   入参断言用 argThat + 集合相等（不看顺序）：判别力（一趟 + 去重）不减，且不把测试
        //   绑死在实现选的去重容器（LinkedHashMap 的首次出现序）上。
        verify(likeService, times(1)).batchIsContentLiked(eq(viewer.id()),
                argThat(ids -> ids != null && ids.size() == 3 && ids.containsAll(List.of(c1, c2, c3))));
        verify(likeService, never()).isContentLiked(anyLong(), anyLong());
        verify(favoriteService, times(1)).batchIsFavorited(eq(viewer.id()),
                argThat(ids -> ids != null && ids.size() == 3 && ids.containsAll(List.of(c1, c2, c3))));
        verify(favoriteService, never()).isFavorited(anyLong(), anyLong());
        verify(followService, times(1)).batchIsFollowing(eq(viewer.id()),
                argThat(ids -> ids != null && ids.size() == 2 && ids.containsAll(List.of(author.id(), otherAuthor.id()))));
        verify(followService, never()).isFollowing(anyLong(), anyLong());
    }

    @Test
    @DisplayName("★批量匿名（userId == null）：返回 null 且不查任何互动域")
    void 批量匿名返回null且不查互动域() {
        TestUser viewer = register("13900009013", "t9-anon-batch");
        TestUser author = register("13900009014", "t9-anon-batch-author");
        long content = insertContent(author.id(), 1);
        likeService.likeContent(viewer.id(), content);              // 有数据在场也不查

        Mockito.reset(likeService, favoriteService, followService);
        Map<Long, InteractionStatusVO> result = interactionService.batchGetStatus(null, List.of(
                new InteractionTarget(content, author.id(), 1)));

        assertThat(result)
                .as("★ 匿名 ⇒ null（不是空 Map、更不是全 false 映射）—— 两个契约同款语义")
                .isNull();
        verifyNoInteractions(likeService, favoriteService, followService);
    }

    @Test
    @DisplayName("批量：authorId <= 0 的条目不进关注查询（按 false）；全未知 ⇒ 关注维度整趟不发")
    void 批量跳过作者未知的条目() {
        TestUser viewer = register("13900009015", "t9-noauthor");
        TestUser author = register("13900009016", "t9-noauthor-author");
        long known = insertContent(author.id(), 1);
        long unknown = insertContent(author.id(), 1);
        followService.follow(viewer.id(), author.id());

        Map<Long, InteractionStatusVO> result = interactionService.batchGetStatus(viewer.id(), List.of(
                new InteractionTarget(known, author.id(), 1),
                new InteractionTarget(unknown, 0L, 1)));            // 作者未知

        assertThat(result.get(known).getIsFollowed()).isTrue();
        assertThat(result.get(unknown).getIsFollowed())
                .as("作者未知 ⇒ 不查也不猜，false（与既有列表填充口径一致）").isFalse();
        verify(followService, times(1)).batchIsFollowing(eq(viewer.id()), eq(List.of(author.id())));

        // 全部作者未知 ⇒ 关注维度**整趟不发**（不为空作者集发 IN () 查询）
        Mockito.reset(followService);
        interactionService.batchGetStatus(viewer.id(), List.of(new InteractionTarget(known, 0L, 1)));
        verify(followService, never()).batchIsFollowing(anyLong(), anyList());
    }

    // ========================================================================
    // 夹具与独立 oracle
    // ========================================================================

    private void clearRedis() {
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private TestUser register(String phone, String username) {
        registerAndGetToken(phone, username, PASSWORD);
        return new TestUser(userIdOf(phone));
    }

    private record TestUser(long id) {
    }

    private long countRows(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    private long insertContent(long authorId, int type) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description, is_deleted) VALUES (?, ?, ?, ?, 0)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setInt(2, type);
            ps.setString(3, "T9 测试内容 " + authorId);
            ps.setString(4, "T9 测试描述");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    private long insertComment(long contentId, long userId) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO comment (content_id, user_id, content) VALUES (?, ?, ?)",
                    new String[]{"comment_id"});
            ps.setLong(1, contentId);
            ps.setLong(2, userId);
            ps.setString(3, "T9 测试评论");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("comment 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }
}
