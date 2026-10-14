package io.github.yjhhhaaa06.videoweb.favorite;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.favorite.cache.FavoriteRedisOps;
import io.github.yjhhhaaa06.videoweb.favorite.dao.FavoriteFolderDao;
import io.github.yjhhhaaa06.videoweb.favorite.dao.FavoriteItemDao;
import io.github.yjhhhaaa06.videoweb.favorite.service.FavoriteService;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 收藏二期 <b>T11：缓存层（{@code FavoriteCache} / {@code FavoriteRedisOps}）</b> 的语义与时序测试
 * （{@code 能力 二期-2}；设计篇 §3.7；口径与代价见**附录 A.3 I-11**）。
 *
 * <h2>为什么必须单列一个类</h2>
 * 其余收藏测试钉的是"接口返回什么"，它们**完全无法**证明缓存这一侧的语义是否正确：
 * 三态、空标记、失效集、提交后时机，在"有缓存/无缓存"两种实现下都能给出同样的 HTTP 响应。
 * 本类专门构造**只有正确实现才能通过**的场面，两侧各用独立 oracle：
 * <ul>
 *   <li><b>DB 侧</b>：直查 {@code favorite_item}（行数 / {@code COUNT(DISTINCT user_id)}）—— 不信接口回显；</li>
 *   <li><b>Redis 侧</b>：直读 {@link StringRedisTemplate} 的键与值（键名**硬编码字面量** —— 键形状是冻结契约）。</li>
 * </ul>
 *
 * <h2>★ 与 {@code FavoriteExistenceTests} / {@code FavoriteStatusAndCountTests} 的分工</h2>
 * 那两类的夹具在 T12 前是**直改 DB**（绕开服务写路径），因此它们的 {@code @BeforeEach} 清 Redis；
 * ★ **T12 起那两类凡"要让 count 看见"的夹具也改走真实写路径**（列只由写路径的重算维护）——
 * 分工变为：它们钉"判存在 / 计数口径"，本类钉**缓存自身的时序与失效**
 * （写路径 = {@code POST /favorite/{add,remove,move,folder/remove}}）—— 两边分工，不互相伪装。
 * 本类仍保留的直插夹具共 4 处（冷 miss 回填 / 删夹回滚 / 无事务写点用例的预热行 / folders 不缓存），
 * 全部只服务**成员集**路径，对列无感知。
 *
 * <h2>★ 写路径口径（I-11 的判别力所在，本类逐个用例钉住）</h2>
 * 收藏的唯一键是 {@code uk_folder_content}（**夹内**唯一）⇒ 同一用户可把同一内容收在多个夹里：
 * <ul>
 *   <li>{@code add}（含"收进第二个夹"）**不改变**收藏数 ⇒ 若实现做成无条件 {@code INCR} ⇒
 *       {@link #多夹收藏与逐夹移出都不漂移()} 变红；</li>
 *   <li>{@code remove}（从某夹移出）**不必然**取消收藏 ⇒ 若实现做成无条件 {@code SREM}/{@code DECR} ⇒
 *       同一个用例变红（这是本类最重要的一条判别力）；</li>
 *   <li>{@code move} 可能是"源夹本没有这条"⇒ **等价于新增收藏** ⇒ {@link #move源夹不含该内容时等价于新增收藏()}
 *       钉住"不能因为'净效应为零'而完全不动缓存"。</li>
 * </ul>
 * 故写路径只有 {@code add} 做条件写（单调、恒安全），其余一律**整体失效**。
 *
 * <h2>★ 反向验证清单（**逐条实测变红并还原**，与 T11 回写同一份记录）</h2>
 * <table>
 *   <caption>注入 → 变红</caption>
 *   <tr><th>#</th><th>注入</th><th>变红用例（条数）</th></tr>
 *   <tr><td>1</td><td>{@code ADD_LUA} 去掉 {@code DEL KEYS[2]}（不清空标记）</td>
 *       <td>{@code 空标记写路径必须清掉…}（1）</td></tr>
 *   <tr><td>2</td><td>{@code invalidateUserFavorites} 改空实现</td>
 *       <td>成员整体失效相关 5 例（多夹 / move / 删夹 / 无事务写点 / 提交成功）</td></tr>
 *   <tr><td>3</td><td>★ <b>照任务书原口径写</b>（{@code add} 条件 {@code INCR} + 写路径不做失效）</td>
 *       <td>{@code 多夹收藏与逐夹移出都不漂移}（1）—— "无条件 INCR 会在这里答 2"</td></tr>
 *   <tr><td>4</td><td>{@code move} 的缓存动作整段删掉</td>
 *       <td>{@code move源夹不含该内容时等价于新增收藏}（1）</td></tr>
 *   <tr><td>5</td><td>删夹的计数批量失效删掉</td><td>{@code 删夹后…批量失效} + {@code 提交成功后缓存被失效}（2）</td></tr>
 *   <tr><td>6</td><td>{@code AfterCommit.run} 换成事务体内直接调用</td>
 *       <td>{@code deleteFolder事务回滚后缓存不动}（1）</td></tr>
 *   <tr><td>7</td><td>{@code AfterCommit} 的"无同步活动 ⇒ 立即执行"改成**跳过**</td>
 *       <td>T11 时：无事务写点相关 3 例。⚠️ <b>T12 后不可判别</b>：四写点全升级为有事务、
 *       全走 afterCommit 注册分支，"立即执行"分支已无生产调用方 ⇒ 该注入**没有任何用例可变红**
 *       （分支保留为通用机制语义，不再有专属判别力）</td></tr>
 *   <tr><td>8</td><td>不写空标记（去掉 {@code markEmptyIfAbsent}）</td><td>空标记相关 2 例</td></tr>
 *   <tr><td>9</td><td>降级 catch 改成 rethrow（Redis 失败不降级）</td><td>降级 + DB 上抛 2 例</td></tr>
 *   <tr><td>10</td><td>降级里把 DB 失败也吞成 {@code false}</td><td>{@code DB失败不被降级吞掉}（1）</td></tr>
 * </table>
 * <p>⚠️ 注入 3 的一个发现值得记下：**单独**把 {@code add} 改成条件 {@code INCR} 时全部用例仍绿 ——
 * 因为写路径的 {@code DEL} 会把那次 {@code INCR} 抹掉。只有把"条件写 + 不失效"**按任务书原口径整体**
 * 注入，才会现形。这说明"条件写"与"失效"在本域**必须成对**（这也是 I-11 的核心结论）。
 */
class FavoriteCacheTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    /** 与 {@code FavoriteCache} / {@code CacheKeys} 的 key 工厂一致（刻意在测试里写死字面量——键形状本身是契约）。 */
    private static final String USER_FAVORITE_SET_KEY = "user:favoriteSet:";
    private static final String CONTENT_FAVORITE_COUNT_KEY = "content:favoriteCount:";
    private static final String EMPTY_PREFIX = "empty:";

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private StringRedisTemplate redis;

    /** 回滚用例用它把"提交后"这条时序钉住（外层事务置回滚 ⇒ afterCommit 不得执行）。 */
    @Autowired
    private PlatformTransactionManager txManager;

    /** spy：真实执行 + 用于"命中态不回源 DB"的调用次数断言，以及失败注入。 */
    @MockitoSpyBean
    private FavoriteItemDao itemDao;

    @MockitoSpyBean
    private FavoriteFolderDao folderDao;

    /** spy：用于把 Redis 侧打成"不可用"（降级路径的唯一注入点）。 */
    @MockitoSpyBean
    private FavoriteRedisOps favoriteRedisOps;

    /** spy：T12 起计数回源读 content.favorite_count 列（ContentDao）——"命中不回源 / 回源恰一次"与 DB 失败注入都指向它。 */
    @MockitoSpyBean
    private io.github.yjhhhaaa06.videoweb.content.dao.ContentDao contentDao;

    @BeforeEach
    void resetState() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content");
        clearRedis();
        Mockito.reset(itemDao, folderDao, favoriteRedisOps, contentDao);
    }

    // ========================================================================
    // 一、三态读：命中 / miss 回源回填 / 命中态不回源
    // ========================================================================

    @Test
    @DisplayName("★三态读：冷 miss 回源回填（键名与值可复核）→ 再读为命中态、不再打 DB")
    void 冷miss回源回填且命中态不回源() {
        TestUser me = register("13900001201", "t11-tri-state");
        long content = insertContent(me.id());
        long folder = insertFolder(me.id(), "三态夹");
        insertItem(folder, me.id(), content);            // 夹具：DB 里已有一条收藏

        String setKey = USER_FAVORITE_SET_KEY + me.id();
        assertThat(hasKey(setKey)).as("前置：Redis 里还没有这个 key（冷）").isFalse();

        // ① miss ⇒ 回源 DB + 原子回填
        assertThat(favoriteService.isFavorited(me.id(), content)).as("DB 里有行 ⇒ true").isTrue();
        verify(itemDao, times(1)).findAllFavoritedContentIds(anyLong());
        assertThat(hasKey(setKey)).as("miss 后必须回填出成员集").isTrue();
        assertThat(setContains(me.id(), content)).as("回填的值 = 该用户全量收藏").isTrue();
        assertThat(ttlSecondsOf(setKey)).as("数据 key 必须带 TTL（TTL=-1 表示永不过期 ⇒ 是缺陷）")
                .isGreaterThan(0L);
        assertThat(hasKey(EMPTY_PREFIX + setKey)).as("有数据 ⇒ 不得留空标记").isFalse();

        // ② 命中态：不得再打 DB（用调用次数证明"命中不回源"，这是缓存的立身之本）
        Mockito.clearInvocations(itemDao);
        assertThat(favoriteService.isFavorited(me.id(), content)).as("命中态答案仍正确").isTrue();
        verify(itemDao, never()).findAllFavoritedContentIds(anyLong());
        verify(itemDao, never()).existsByUserAndContent(anyLong(), anyLong());

        // ③ 批量同样走缓存：一趟 pipeline 作答，不逐条调单条
        assertThat(favoriteService.batchIsFavorited(me.id(), List.of(content, content + 1000)))
                .containsEntry(content, true)
                .containsEntry(content + 1000, false);
        verify(itemDao, never()).existsByUserAndContent(anyLong(), anyLong());
    }

    @Test
    @DisplayName("★空标记：无收藏用户读一次即不回源；数据 key 与空标记都只按需存在")
    void 空标记挡住从未收藏的用户的重复回源() {
        TestUser stranger = register("13900001202", "t11-empty");
        long content = insertContent(stranger.id());

        String setKey = USER_FAVORITE_SET_KEY + stranger.id();
        String emptyKey = EMPTY_PREFIX + setKey;

        assertThat(favoriteService.isFavorited(stranger.id(), content)).as("从没收藏 ⇒ false").isFalse();
        verify(itemDao, times(1)).findAllFavoritedContentIds(anyLong());
        assertThat(hasKey(emptyKey)).as("★ miss 且 DB 确认无数据 ⇒ 写空标记（防穿透）").isTrue();
        assertThat(hasKey(setKey)).as("空集合不建数据 key（Redis 里空集合不存在）").isFalse();
        assertThat(ttlSecondsOf(emptyKey))
                .as("空标记是**短** TTL（60s，CacheKeys.EMPTY_MARKER_TTL_SECONDS）")
                .isGreaterThan(0L)
                .isLessThanOrEqualTo(60L);

        // 再读：命中空标记 ⇒ 直接 false，**一次 DB 都不打**
        Mockito.clearInvocations(itemDao);
        assertThat(favoriteService.isFavorited(stranger.id(), content)).isFalse();
        verify(itemDao, never()).existsByUserAndContent(anyLong(), anyLong());
        verify(itemDao, never()).findAllFavoritedContentIds(anyLong());

        // 批量同款：空标记命中 ⇒ 全 false，同样不打 DB（看这个内容 + 另一个）
        assertThat(favoriteService.batchIsFavorited(stranger.id(), List.of(content, content + 1)))
                .containsEntry(content, false)
                .containsEntry(content + 1, false);
        verify(itemDao, never()).findAllFavoritedContentIds(anyLong());
    }

    @Test
    @DisplayName("★空标记写路径必须清掉，否则刚收藏的被 60 秒假否定（ADD_LUA 的 DEL KEYS[2] 的存在证明）")
    void 空标记写路径必须清掉否则刚收藏的被60秒假否定() {
        TestUser me = register("13900001203", "t11-empty-then-add");
        long content = insertContent(me.id());
        long folder = insertFolder(me.id(), "先空后收");

        String setKey = USER_FAVORITE_SET_KEY + me.id();
        String emptyKey = EMPTY_PREFIX + setKey;

        // ① 先制造"已确认无收藏"的空标记
        assertThat(favoriteService.isFavorited(me.id(), content)).isFalse();
        assertThat(hasKey(emptyKey)).as("前置：空标记已写下").isTrue();

        // ② 走**真实端点**收藏（无事务写点 ⇒ 提交后立即条件写）
        assertThat(addItem(folder, content, me.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(hasKey(emptyKey))
                .as("★ 写路径必须清掉空标记：它断言'该用户一条收藏都没有'，与'他刚收藏了这个'自相矛盾")
                .isFalse();
        assertThat(hasKey(setKey))
                .as("冷 key 的条件写**不建半套**集合（SADD 只在已加载的 key 上执行）——交给读回源整体回填")
                .isFalse();

        // ③ 读：不再被假否定挡住，且顺带把集合回填出来
        assertThat(favoriteService.isFavorited(me.id(), content))
                .as("★ 反向验证点：去掉 ADD_LUA 的 DEL 空标记 ⇒ 这里会读到空标记 ⇒ 答 false（60s 假否定）")
                .isTrue();
        assertThat(setContains(me.id(), content)).isTrue();
    }

    @Test
    @DisplayName("★计数：0 是合法值（无空标记）；miss 回源回填 → 命中不再打 DB")
    void 计数零是合法值且不写空标记() {
        TestUser me = register("13900001204", "t11-count");
        long content = insertContent(me.id());

        String countKey = CONTENT_FAVORITE_COUNT_KEY + content;

        // ① 无人收藏 ⇒ 0（不是 null、不是 404），且**不得**写空标记
        //（★ T12：回源 = contentDao.findFavoriteCountById 列点查；一期那趟实时 COUNT 已退役）
        assertThat(favoriteService.getFavoriteCount(content)).isZero();
        verify(contentDao, times(1)).findFavoriteCountById(anyLong());
        assertThat(countValue(content)).as("回填的缓存值就是 \"0\"").isEqualTo("0");
        assertThat(hasKey(EMPTY_PREFIX + countKey))
                .as("★ 计数 key 刻意无空标记：0 是合法值，写空标记会让'0 人收藏'与'未知'混淆")
                .isFalse();

        // ② 命中态不再回源（0 也要能命中）
        Mockito.clearInvocations(contentDao);
        assertThat(favoriteService.getFavoriteCount(content)).isZero();
        verify(contentDao, never()).findFavoriteCountById(anyLong());

        // ③ 换一个内容：走真实写路径收藏（T12：直插行对列不可见）⇒ 回源得 1
        long other = insertContent(me.id());
        long folder = insertFolder(me.id(), "计数的夹");
        assertThat(addItem(folder, other, me.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(favoriteService.getFavoriteCount(other)).isEqualTo(1L);
        assertThat(countValue(other)).isEqualTo("1");
        assertThat(favoriteService.getFavoriteCount(other))
                .as("独立 oracle 对查").isEqualTo(oracleDistinctUserCount(other));
    }

    // ========================================================================
    // 二、写路径：不漂移（I-11 的核心判别力）
    // ========================================================================

    @Test
    @DisplayName("★★多夹收藏与逐夹移出都不漂移（无条件 INCR / 无条件 SREM 都会在这里变红）")
    void 多夹收藏与逐夹移出都不漂移() {
        TestUser me = register("13900001205", "t11-multi-folder");
        long content = insertContent(me.id());
        long folderX = insertFolder(me.id(), "夹X");
        long folderY = insertFolder(me.id(), "夹Y");

        // ① 第一次收藏 ⇒ 收藏数 1
        assertThat(addItem(folderX, content, me.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(favoriteService.isFavorited(me.id(), content)).isTrue();
        assertThat(countViaHttp(content)).as("首次收藏 ⇒ 1").isEqualTo(1L);
        assertThat(oracleDistinctUserCount(content)).as("独立 oracle（直查 favorite_item）").isEqualTo(1L);

        // ② ★ 同一人再收进**另一个夹**：DB 多一行，但"我收藏过"与收藏数都不变
        assertThat(addItem(folderY, content, me.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(oracleRecordCount(content)).as("DB 里确实变成 2 行（多夹是合法状态）").isEqualTo(2L);
        assertThat(favoriteService.isFavorited(me.id(), content)).as("仍然收藏过").isTrue();
        assertThat(countViaHttp(content))
                .as("★ 无条件 INCR 会在这里答 2：收藏数按**人**去重，收进第二个夹不改变人数")
                .isEqualTo(1L);

        // ③ ★ 从 X 移出：Y 里还在 ⇒ 仍"已收藏"、收藏数仍 1
        assertThat(removeItems(folderX, List.of(content), me.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(oracleRecordCount(content)).isEqualTo(1L);
        assertThat(favoriteService.isFavorited(me.id(), content))
                .as("★ 无条件 SREM 会在这里答 false：'移出某个夹' **不等于** '取消收藏'")
                .isTrue();
        assertThat(countViaHttp(content))
                .as("★ 无条件 DECR 会在这里答 0：同一人还在（别的夹里）")
                .isEqualTo(1L);
        // 状态端点必须与缓存侧给同一个答案
        // ⚠️ T15 起：**不再**是"直查 DB、不吃缓存" —— getFavoriteStatus 的"未收藏"分支会先问
        //    FavoriteCache.isFavorited（同一个 user:favoriteSet:{userId}），命中 ⇒ 不查库。
        //    本行仍能成立是因为 removeItems 的写路径已把成员集整体失效 ⇒ 这里必然落到 DB 分支。
        //   （"状态端点读什么"的完整契约见 FavoriteStatusShortCircuitTests）
        assertThat(Envelope.data(statusViaHttp(content, me.token())).path("isFavorited").asBoolean()).isTrue();

        // ④ 最后一个夹也移出 ⇒ 回到未收藏、收藏数 0
        assertThat(removeItems(folderY, List.of(content), me.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(favoriteService.isFavorited(me.id(), content)).isFalse();
        assertThat(countViaHttp(content)).isZero();
        assertThat(oracleDistinctUserCount(content)).isZero();
    }

    @Test
    @DisplayName("★move：源夹不含该内容时**等价于新增收藏**（不能被\"净效应为零\"漏掉）")
    void move源夹不含该内容时等价于新增收藏() {
        TestUser me = register("13900001206", "t11-move-edge");
        long content = insertContent(me.id());
        long folderX = insertFolder(me.id(), "源夹");
        long folderY = insertFolder(me.id(), "目标夹");

        // 前置：内容**没有**被收藏过（两个夹里都没有）
        assertThat(favoriteService.isFavorited(me.id(), content)).isFalse();
        assertThat(favoriteService.getFavoriteCount(content)).isZero();

        // move(from=X, to=Y)：先插 Y 成功、再从 X 删 0 行 ⇒ 净效果是"新增一条收藏"
        assertThat(moveItem(folderX, folderY, content, me.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(oracleDistinctUserCount(content)).as("DB 终态：确实新收藏了一条").isEqualTo(1L);

        assertThat(favoriteService.isFavorited(me.id(), content))
                .as("★ 若 move 的缓存动作被\"净效应为零\"整段删掉 ⇒ 这里读到失效前的旧集合 ⇒ 答 false")
                .isTrue();
        assertThat(countViaHttp(content))
                .as("★ 同上：收藏数缓存也必须被失效（否则恒答 0）").isEqualTo(1L);
    }

    @Test
    @DisplayName("★删夹：受影响的**每个**内容的计数都要失效；同夹外的内容不受误伤")
    void 删夹后受影响内容的计数批量失效() {
        TestUser me = register("13900001207", "t11-delete-folder");
        long a = insertContent(me.id());
        long b = insertContent(me.id());
        long c = insertContent(me.id());
        long doomed = insertFolder(me.id(), "要被删的夹");
        long keep = insertFolder(me.id(), "留着的夹");

        assertThat(addItem(doomed, a, me.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(addItem(doomed, b, me.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(addItem(keep, c, me.token()).getStatusCode().value()).isEqualTo(200);

        // 预热：三个内容的计数缓存都建立起来（否则"失效"这一动作不可观察）
        assertThat(countViaHttp(a)).isEqualTo(1L);
        assertThat(countViaHttp(b)).isEqualTo(1L);
        assertThat(countViaHttp(c)).isEqualTo(1L);
        assertThat(favoriteService.isFavorited(me.id(), a)).isTrue();

        assertThat(deleteFolder(doomed, me.token()).getStatusCode().value()).isEqualTo(200);

        assertThat(oracleDistinctUserCount(a)).as("DB 终态：a 的记录随夹一并删除（R-02）").isZero();
        assertThat(oracleDistinctUserCount(b)).isZero();
        assertThat(countViaHttp(a)).as("★ 漏失效则留在缓存里的 1 会被读到").isZero();
        assertThat(countViaHttp(b)).isZero();
        assertThat(countViaHttp(c)).as("★ 对照：**另一个夹**里的 c 不该被误伤").isEqualTo(1L);
        assertThat(favoriteService.isFavorited(me.id(), a)).as("成员集整体失效 ⇒ 读回源得 false").isFalse();
        assertThat(favoriteService.isFavorited(me.id(), c)).as("对照：c 仍收藏着").isTrue();
    }

    // ========================================================================
    // 三、提交后时机（AfterCommit 的存在证明 + 两个分支各一条）
    // ========================================================================

    @Test
    @DisplayName("★deleteFolder 事务回滚后缓存不动（有事务分支：缓存动作必须挂在提交后）")
    void deleteFolder事务回滚后缓存不动() {
        TestUser me = register("13900001208", "t11-rollback");
        long content = insertContent(me.id());
        long folder = insertFolder(me.id(), "回滚夹");
        insertItem(folder, me.id(), content);

        // 前置：把成员集预热出来（此时"缓存被失效"才是可观察的）
        assertThat(favoriteService.isFavorited(me.id(), content)).isTrue();
        assertThat(setContains(me.id(), content)).as("前置：成员集已加载").isTrue();

        // ★ 让事务在**已有成功写之后**才失败 —— 包进外层事务、先让 deleteFolder 跑完、再置回滚。
        //   为什么不用"打桩让第一步 DB 写失败"：首步失败对"事务内 vs 提交后"**没有鉴别力**
        //   （两种世界都"什么都没写"）；只有"写成功了、事务却回滚"才能把两者区分开
        //   （同 AGENTS「测试纪律」的"最后一步打桩"与 LikeCacheTests 的预热—回滚手法）。
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            favoriteService.deleteFolder(me.id(), folder);
            assertThat(oracleRecordCount(content)).as("事务内：删除已生效（内存可见）").isZero();
            assertThat(hasKey(USER_FAVORITE_SET_KEY + me.id()))
                    .as("★ 事务**尚未提交** ⇒ 缓存动作（afterCommit）此刻**不得**已经发生")
                    .isTrue();
            status.setRollbackOnly();
            return null;
        });

        // ① DB 侧回滚了（夹与条目都还在）
        assertThat(oracleRecordCount(content)).as("记录仍在").isEqualTo(1L);
        assertThat(countRows("SELECT COUNT(*) FROM favorite_folder WHERE id = ?", folder)).isEqualTo(1L);

        // ② ★ 缓存侧也必须"没动过"：事务回滚 ⇒ afterCommit 回调根本不执行
        assertThat(hasKey(USER_FAVORITE_SET_KEY + me.id()))
                .as("★ 若把失效动作挪进事务体（不走 AfterCommit）⇒ 这里 key 已被删 ⇒ 变红")
                .isTrue();
        assertThat(setContains(me.id(), content)).as("成员集内容原样").isTrue();
    }

    @Test
    @DisplayName("★反向对照：同一套预热、事务正常提交 ⇒ 缓存被正确失效（排除\"缓存永不失效\"的假绿）")
    void 提交成功后缓存被失效() {
        TestUser me = register("13900001209", "t11-commit");
        long content = insertContent(me.id());
        long folder = insertFolder(me.id(), "正常删的夹");
        // ★ T12：收藏走真实写路径（直插行对列不可见，count 预热读会拿 0）
        assertThat(addItem(folder, content, me.token()).getStatusCode().value()).isEqualTo(200);

        assertThat(favoriteService.isFavorited(me.id(), content)).isTrue();
        assertThat(favoriteService.getFavoriteCount(content)).isEqualTo(1L);
        assertThat(hasKey(USER_FAVORITE_SET_KEY + me.id())).isTrue();
        assertThat(hasKey(CONTENT_FAVORITE_COUNT_KEY + content)).isTrue();

        favoriteService.deleteFolder(me.id(), folder);

        assertThat(oracleDistinctUserCount(content)).as("DB 终态：条目一并删除").isZero();
        assertThat(hasKey(USER_FAVORITE_SET_KEY + me.id()))
                .as("★ 成员集整体失效（DEL 数据 key）").isFalse();
        assertThat(hasKey(EMPTY_PREFIX + USER_FAVORITE_SET_KEY + me.id()))
                .as("★ 失效集 = 数据 key + 空标记（只 DEL 一半会留下'假空'窗口）").isFalse();
        assertThat(hasKey(CONTENT_FAVORITE_COUNT_KEY + content)).as("计数 key 也必须失效").isFalse();
        assertThat(favoriteService.isFavorited(me.id(), content)).as("读自愈后答案正确").isFalse();
        assertThat(favoriteService.getFavoriteCount(content)).isZero();
    }

    @Test
    @DisplayName("★addItem/removeItems 写后返回时缓存确实已更新（T12 起两写点有事务 ⇒ AfterCommit 走 afterCommit 注册分支：提交即回调，先于 HTTP 响应）")
    void add与remove写后返回时缓存已更新() {
        TestUser me = register("13900001210", "t11-no-tx");
        long a = insertContent(me.id());
        long b = insertContent(me.id());
        long folder = insertFolder(me.id(), "无事务写点");
        insertItem(folder, me.id(), a);

        // 前置：成员集已加载（只有已加载的 key 才做条件写；冷 key 不写半套）
        assertThat(favoriteService.isFavorited(me.id(), a)).isTrue();
        assertThat(setContains(me.id(), a)).isTrue();

        // ① add（T12 起有事务：计数重算引入第二条写）⇒ afterCommit 回调在提交时执行，
        //    先于方法返回 ⇒ 断言到达时条件 SADD 必须已生效
        assertThat(addItem(folder, b, me.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(setContains(me.id(), b))
                .as("★ 若缓存动作没挂在 AfterCommit 上（事务内直接碰 / 根本不执行）⇒ 这里为 false ⇒ 变红")
                .isTrue();
        assertThat(favoriteService.isFavorited(me.id(), b)).isTrue();

        // ② remove ⇒ 成员集整体失效（key 被删）
        assertThat(removeItems(folder, List.of(a), me.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(hasKey(USER_FAVORITE_SET_KEY + me.id()))
                .as("移出后成员集整体失效（下次读回源）").isFalse();
        assertThat(favoriteService.isFavorited(me.id(), a)).isFalse();
        assertThat(favoriteService.isFavorited(me.id(), b)).as("对照：没被移出的 b 仍在").isTrue();
    }

    // ========================================================================
    // 四、两种失败必须区分
    // ========================================================================

    @Test
    @DisplayName("★Redis 不可用：三个读都降级直查 DB 且不抛；/favorite/count 接口不 500")
    void Redis不可用降级查DB且接口不500() {
        TestUser me = register("13900001211", "t11-degrade");
        long content = insertContent(me.id());
        long folder = insertFolder(me.id(), "降级夹");
        // ★ T12：收藏走真实写路径（列被重算为 1）—— 直插行对 content.favorite_count 不可见。
        //   且要在 Redis 打桩**之前** add（写路径的缓存动作只 catch 降级，先写不碍事）。
        assertThat(addItem(folder, content, me.token()).getStatusCode().value()).isEqualTo(200);

        doThrow(new CacheUnavailableException("模拟 Redis 挂")).when(favoriteRedisOps)
                .existingOf(anyString(), anyString());
        doThrow(new CacheUnavailableException("模拟 Redis 挂")).when(favoriteRedisOps)
                .getString(anyString());

        assertThat(favoriteService.isFavorited(me.id(), content)).as("降级仍返回 DB 真值").isTrue();

        // ★ 降级也必须"**一趟 IN 批量**、不逐条点查"（T9 立下的纪律：否则 100 条列表退化成 100 次点查）。
        //   清掉前面的调用记录，让后面两条 verify 只反映**批量降级**这一次调用。
        Mockito.clearInvocations(itemDao);
        assertThat(favoriteService.batchIsFavorited(me.id(), List.of(content, content + 1)))
                .as("批量降级按请求 id 子集作答（不拉全量）")
                .containsEntry(content, true)
                .containsEntry(content + 1, false);
        verify(itemDao, times(1)).findFavoritedContentIds(eq(me.id()), anyList());
        verify(itemDao, never()).existsByUserAndContent(anyLong(), anyLong());

        assertThat(favoriteService.getFavoriteCount(content)).isEqualTo(1L);

        ResponseEntity<String> resp = get("/favorite/count?contentId=" + content, null);
        assertThat(resp.getStatusCode().value()).as("★ 缓存只是加速器：Redis 挂掉不许让接口 500").isEqualTo(200);
        assertThat(Envelope.data(resp).asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("★DB 失败不被降级吞掉：必须上抛（降级成 false / 0 会把\"读不到\"伪装成\"没有\"）")
    void DB失败不被降级吞掉() {
        TestUser me = register("13900001212", "t11-db-fail");
        long content = insertContent(me.id());

        // 迫使走降级分支，再让**降级里那句 DB 查询**失败
        doThrow(new CacheUnavailableException("模拟 Redis 挂")).when(favoriteRedisOps)
                .existingOf(anyString(), anyString());
        doThrow(new CacheUnavailableException("模拟 Redis 挂")).when(favoriteRedisOps)
                .getString(anyString());
        doThrow(new DataAccessException("模拟 DB 失败") {
        }).when(itemDao).existsByUserAndContent(anyLong(), anyLong());
        // ★ T12：计数回源已切到列读（ContentDao）—— DB 失败注入点随之换到这里
        doThrow(new DataAccessException("模拟 DB 失败") {
        }).when(contentDao).findFavoriteCountById(anyLong());

        assertThatThrownBy(() -> favoriteService.isFavorited(me.id(), content))
                .as("★ 只 catch CacheUnavailableException：DB 异常必须原样上抛")
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> favoriteService.getFavoriteCount(content))
                .isInstanceOf(DataAccessException.class);
    }

    // ========================================================================
    // 五、边界：folders 不得被顺手缓存（§3.7 的刻意留白）
    // ========================================================================

    @Test
    @DisplayName("★/favorite/status 的 folders 不被缓存：Redis 里不得出现任何 folders 相关 key")
    void folders不被缓存() {
        // ⚠️ T15 之后本用例名要说清一件事："folders 不被缓存"**不等于**"本端点必查 DB" ——
        //    getFavoriteStatus 的"未收藏"分支会先问 FavoriteCache.isFavorited，命中 ⇒ folders 根本不查
        //    （契约见 FavoriteStatusShortCircuitTests）。本用例站的正是**另一半**：它的夹具让成员集
        //    判定为"已收藏" ⇒ 必须落到 DB 取 folders，于是"folders 有没有被顺手写进 Redis"才是可观察的。
        TestUser me = register("13900001213", "t11-folders");
        long content = insertContent(me.id());
        long folder = insertFolder(me.id(), "不缓存的夹");
        insertItem(folder, me.id(), content);

        ResponseEntity<String> resp = statusViaHttp(content, me.token());
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.data(resp).path("folders").size()).isEqualTo(1);
        assertThat(Envelope.data(resp).path("isFavorited").asBoolean()).isTrue();

        assertThat(redisKeys().stream().filter(k -> k.toLowerCase().contains("folder")).toList())
                .as("★ folders 刻意不缓存（低频 + 未收藏时短路；失效点却有五个）⇒ Redis 里不应有任何 folder key")
                .isEmpty();
    }

    // ========================================================================
    // 夹具 / oracle / HTTP
    // ========================================================================

    private record TestUser(long id, String token) {
    }

    private TestUser register(String phone, String username) {
        String token = registerAndGetToken(phone, username, PASSWORD);
        return new TestUser(userIdOf(phone), token);
    }

    private long insertContent(long authorId) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description) VALUES (?, 1, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setString(2, "T11 测试内容 " + authorId);
            ps.setString(3, "T11 测试描述");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    private long insertFolder(long userId, String name) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO favorite_folder (user_id, name, is_default) VALUES (?, ?, 0)",
                    new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, name);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("favorite_folder 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    private void insertItem(long folderId, long userId, long contentId) {
        jdbcTemplate.update("INSERT INTO favorite_item (folder_id, user_id, content_id) VALUES (?, ?, ?)",
                folderId, userId, contentId);
    }

    // ==================== DB 侧独立 oracle（不信接口回显） ====================

    /** 收藏**行数**（多夹时会 > 人数 —— 与去重口径的对照）。 */
    private long oracleRecordCount(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_item WHERE content_id = ?", Long.class, contentId);
        return n == null ? 0L : n;
    }

    /** 收藏**人数**（接口 count 的独立复算：直查 favorite_item，不读缓存、不读接口回显）。 */
    private long oracleDistinctUserCount(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM favorite_item WHERE content_id = ?", Long.class, contentId);
        return n == null ? 0L : n;
    }

    private long countRows(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    // ==================== Redis 侧 oracle ====================

    private void clearRedis() {
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private Set<String> redisKeys() {
        Set<String> keys = redis.keys("*");
        return keys == null ? Set.of() : keys;
    }

    private boolean hasKey(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    private Long ttlSecondsOf(String key) {
        return redis.getExpire(key, TimeUnit.SECONDS);
    }

    private boolean setContains(long userId, long contentId) {
        return Boolean.TRUE.equals(
                redis.opsForSet().isMember(USER_FAVORITE_SET_KEY + userId, String.valueOf(contentId)));
    }

    private String countValue(long contentId) {
        return redis.opsForValue().get(CONTENT_FAVORITE_COUNT_KEY + contentId);
    }

    // ==================== HTTP ====================

    private ResponseEntity<String> statusViaHttp(long contentId, String token) {
        return get("/favorite/status?contentId=" + contentId, token);
    }

    /** 收藏数（走**接口**，用于验证"接口回显与缓存一致"，同时它与 oracle 对查）。 */
    private long countViaHttp(long contentId) {
        ResponseEntity<String> resp = get("/favorite/count?contentId=" + contentId, null);
        assertThat(resp.getStatusCode().value()).as("count 匿名可访问").isEqualTo(200);
        return Envelope.data(resp).asLong();
    }

    private ResponseEntity<String> addItem(long folderId, long contentId, String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("folderId", String.valueOf(folderId));
        form.add("contentId", String.valueOf(contentId));
        return postForm("/favorite/add", form, token);
    }

    private ResponseEntity<String> removeItems(long folderId, List<Long> contentIds, String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("folderId", String.valueOf(folderId));
        for (Long contentId : contentIds) {
            form.add("contentIds", String.valueOf(contentId));
        }
        return postForm("/favorite/remove", form, token);
    }

    private ResponseEntity<String> moveItem(long fromFolderId, long toFolderId, long contentId, String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("fromFolderId", String.valueOf(fromFolderId));
        form.add("toFolderId", String.valueOf(toFolderId));
        form.add("contentId", String.valueOf(contentId));
        return postForm("/favorite/move", form, token);
    }

    private ResponseEntity<String> deleteFolder(long folderId, String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("folderId", String.valueOf(folderId));
        return postForm("/favorite/folder/remove", form, token);
    }

    /** 收藏域写端点是 POST + form（{@code @RequestParam}）—— 与 like / follow 域写端点同形态。 */
    private ResponseEntity<String> postForm(String path, MultiValueMap<String, String> form, String token) {
        return send(spec -> {
            RestClient.RequestBodySpec request = spec.uri(path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED);
            if (token != null) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return request.body(form);
        });
    }
}
