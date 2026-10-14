package io.github.yjhhhaaa06.videoweb.favorite;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.favorite.cache.FavoriteRedisOps;
import io.github.yjhhhaaa06.videoweb.favorite.dao.FavoriteItemDao;
import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 收藏三期 <b>T15：{@code /favorite/status} 短路</b> 的契约与性能形态测试
 * （{@code 能力 三期-3}；设计篇 §3.7；决策的来龙去脉见 {@code FURTHER_ISSUES.md} <b>F-17</b>）。
 *
 * <h2>为什么单列一个类</h2>
 * {@code FavoriteStatusAndCountTests} 钉的是"状态 + 计数"的两个**业务口径**（去重 / 失效 / 鉴权 / 形状），
 * 它**不需要**知道答案从 DB 还是从缓存来；本类钉的恰好反过来：**未收藏时这套答案<b>一次库都不查</b>**。
 * 这要求 {@code @MockitoSpyBean}（数 DAO 被调用几次）⇒ 会换掉 Spring context，放进那个类会让
 * 它的全部用例都背上 spy 的成本。两类分工不互相伪装（同 like / content 域的惯例）。
 *
 * <h2>★ 本类的判据不是"更快"，而是"同一个语义不该有两个来源"</h2>
 * 本仓无流量，省下的是亚毫秒级点查 ⇒ **收益测不出**。它值得做的理由在 {@code detail.js}：
 * {@code initFavorite()} 读详情内联（**缓存**成员集）、{@code loadPicker()} 读 {@code /favorite/status}
 * （<b>T15 前是纯 DB</b>），两者把答案写进**同一个按钮态**。
 * ⇒ 本类把"二者现在是同一个来源"这件事做成可复核的断言（见最后一个用例）。
 *
 * <h2>覆盖（期望值全部出自契约，盖住实现照样写得出来）</h2>
 * <ol>
 *   <li><b>未收藏 ⇒ 零查库</b>（{@link #未收藏第二次请求零查库()}）：冷 miss 走单飞回源 + 回填 ⇒
 *       <b>第二次起一次库都不查</b>。★ 这条同时是本端点的"**自愈**"证明 —— 它把原本
 *       "每请求一次点查且永不收敛"的形态收敛掉了；</li>
 *   <li><b>对照：已收藏 ⇒ 仍查 {@code folders}</b>（{@link #已收藏内容仍取folders且布尔由folders推导()}）：
 *       布尔仍由 {@code folders.isEmpty()} 推导 ⇒ 保证 {@code isFavorited ≡ !folders.isEmpty()}；</li>
 *   <li><b>容忍度边界</b>（{@link #缓存陈旧时响应仍自洽但不保证与DB一致()}）：缓存陈旧时答"未收藏"，
 *       **响应当内必须自洽**，但**不保证与 DB 一致** —— 这是本次口径变更被接受的边界，不是缺陷；</li>
 *   <li><b>Redis 挂 ⇒ 降级走 DB</b>（{@link #Redis挂降级走DB且接口不500()}）：答案回到 DB 真相、接口不 500；</li>
 *   <li><b>验收⑤：按钮态与弹窗态不再打架</b>（{@link #详情页按钮态与弹窗态同源()}）：三种状态下两个入口答案一致。</li>
 * </ol>
 *
 * <h2>★ 反向注入（逐条实测变红并还原，记录见 T15 回写）</h2>
 * <table>
 *   <caption>注入 → 变红</caption>
 *   <tr><th>#</th><th>注入</th><th>变红用例</th></tr>
 *   <tr><td>1</td><td>去掉短路（{@code getFavoriteStatus} 回到"只查 DB"）</td>
 *       <td>{@code 未收藏第二次请求零查库}（1）</td></tr>
 *   <tr><td>2</td><td>短路放宽成"照缓存答 true 也不取 folders"</td>
 *       <td>{@code 已收藏内容仍取folders…}（1）—— 会得到 {@code {isFavorited:true, folders:[]}}</td></tr>
 *   <tr><td>3</td><td>miss 不回填（把冷 miss 换成一次目标点查）</td>
 *       <td>{@code 未收藏第二次请求零查库}（1）—— 冷状态不再自愈，第二次仍打 DB</td></tr>
 *   <tr><td>4</td><td>Redis 异常改为 rethrow（不降级）</td>
 *       <td>{@code Redis挂降级走DB且接口不500}（1）</td></tr>
 * </table>
 */
class FavoriteStatusShortCircuitTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    /** 与 {@code CacheKeys} / {@code FavoriteCache} 一致（刻意写死字面量——键形状本身是契约）。 */
    private static final String USER_FAVORITE_SET_KEY = "user:favoriteSet:";
    private static final String EMPTY_PREFIX = "empty:";

    private static final int CONTENT_TYPE_VIDEO = 1;
    private static final int MEDIA_TYPE_VIDEO = 1;
    private static final int MEDIA_TYPE_COVER = 3;

    @Autowired
    private StringRedisTemplate redis;

    /**
     * ★ 只 spy {@code FavoriteItemDao}（{@code findFoldersByUserAndContent} 是本端点的 DB 入口）：
     * "零查库"这类断言<b>必须数 DB 侧</b>，数 {@code FavoriteCache} 被调几次没有判别力
     * （缓存被调 1 次 ≠ 底层只查 0 趟 —— 同 T13 {@code ContentReadTests} 的教训：要 spy 下游）。
     */
    @MockitoSpyBean
    private FavoriteItemDao itemDao;

    /** Redis 侧 failure 注入点（降级路径只有这里可打）。 */
    @MockitoSpyBean
    private FavoriteRedisOps favoriteRedisOps;

    @BeforeEach
    void resetState() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content_media");
        jdbcTemplate.update("DELETE FROM content");
        clearRedis();
        Mockito.reset(itemDao, favoriteRedisOps);
    }

    // ========================================================================
    // 1. 未收藏 ⇒ 零查库（验收① + "自愈"）
    // ========================================================================

    @Test
    @DisplayName("★未收藏：冷 miss 回源回填后**第二次一次库都不查**（hit-empty 断路；零查库由 spy 钉住）")
    void 未收藏第二次请求零查库() {
        TestUser me = register("13800003101", "t15-selfheal");
        long content = insertContent(me.id());

        // ① 冷 miss：成员集未装载 ⇒ 单飞回源（全量收藏 id）+ 原子回填；未收藏 ⇒ **不** JOIN 取 folders
        Mockito.clearInvocations(itemDao);
        JsonNode first = Envelope.data(status(me.token, content));
        assertThat(first.path("isFavorited").asBoolean()).as("DB 里没有收藏记录").isFalse();
        assertThat(first.path("folders").size()).isZero();
        verify(itemDao, times(1)).findAllFavoritedContentIds(anyLong());
        verify(itemDao, never()).findFoldersByUserAndContent(anyLong(), anyLong());
        assertThat(hasKey(EMPTY_PREFIX + USER_FAVORITE_SET_KEY + me.id()))
                .as("★ 空集写空标记（防穿透）—— 这正是第二次能短路的原因")
                .isTrue();

        // ② ★ 第二次：一次库都不查（三个可能的 DB 入口全部 never）
        Mockito.clearInvocations(itemDao);
        JsonNode second = Envelope.data(status(me.token, content));
        assertThat(second.path("isFavorited").asBoolean()).as("答案不变").isFalse();
        assertThat(second.path("folders").size()).as("★ 空态仍是**空数组**，不是 null / 不是缺键").isZero();
        verify(itemDao, never()).findFoldersByUserAndContent(anyLong(), anyLong());
        verify(itemDao, never()).findAllFavoritedContentIds(anyLong());
        verify(itemDao, never()).existsByUserAndContent(anyLong(), anyLong());
    }

    // ========================================================================
    // 2. 对照：已收藏 ⇒ 仍落库取 folders（验收② + 响应自洽）
    // ========================================================================

    @Test
    @DisplayName("★对照：已收藏内容仍取 folders（布尔由 folders 推导）；同一用户的未收藏内容则零查库")
    void 已收藏内容仍取folders且布尔由folders推导() {
        TestUser me = register("13800003102", "t15-control");
        long favorited = insertContent(me.id());
        long notFavorited = insertContent(me.id());
        long folder = insertFolder(me.id(), "T15 对照夹");
        // ★ T12 起的规矩：夹具走**真实写路径**（列只由写路径的重算维护）
        assertThat(addItem(folder, favorited, me.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(oracleRecordCount(favorited)).as("独立 oracle：确实有一条收藏记录").isEqualTo(1L);

        // ① 已收藏：必须取 folders，布尔由它推导（而不是照缓存答 true）
        Mockito.clearInvocations(itemDao);
        JsonNode yes = Envelope.data(status(me.token, favorited));
        assertThat(yes.path("isFavorited").asBoolean()).isTrue();
        assertThat(names(yes.path("folders"))).containsExactly("T15 对照夹");
        verify(itemDao, times(1)).findFoldersByUserAndContent(eq(me.id()), eq(favorited));
        assertThat(yes.path("isFavorited").asBoolean())
                .as("★ 响应自洽：isFavorited ≡ !folders.isEmpty()")
                .isEqualTo(yes.path("folders").size() > 0);

        // ② ★ 同一时刻、同一用户、另一个内容 ⇒ 热成员集已含① ⇒ SISMEMBER=false ⇒ 零查库
        //    （这就是 F-17 原文那句"key 存在且 SISMEMBER 明确为 false 才短路"的落点）
        Mockito.clearInvocations(itemDao);
        JsonNode no = Envelope.data(status(me.token, notFavorited));
        assertThat(no.path("isFavorited").asBoolean()).isFalse();
        assertThat(no.path("folders").size()).isZero();
        verify(itemDao, never()).findFoldersByUserAndContent(anyLong(), anyLong());
        verify(itemDao, never()).findAllFavoritedContentIds(anyLong());
        assertThat(hasKey(USER_FAVORITE_SET_KEY + me.id()))
                .as("前置：成员集已装载（①那次冷 miss 回源的产物）").isTrue();
    }

    // ========================================================================
    // 3. 容忍度边界（验收③）
    // ========================================================================

    @Test
    @DisplayName("★容忍度边界：缓存陈旧时**响应仍自洽**，但**不保证与 DB 一致**（这是 T15 的口径变更，不是缺陷）")
    void 缓存陈旧时响应仍自洽但不保证与DB一致() {
        TestUser me = register("13800003103", "t15-stale");
        long content = insertContent(me.id());
        long folder = insertFolder(me.id(), "夹");

        // ① 先让成员集装载（此刻 DB 里确实没有收藏）⇒ Redis 留下空标记
        assertThat(Envelope.data(status(me.token, content)).path("isFavorited").asBoolean()).isFalse();

        // ② 绕开写路径**直接改库**（= 数据修复 / 测试夹具这类场景）⇒ 缓存完全不知情
        insertItem(folder, me.id(), content);
        assertThat(oracleRecordCount(content)).as("★ DB 真相：这里确实有一条收藏记录").isEqualTo(1L);

        // ③ 本端点仍答"未收藏" —— ★ 这就是本次被接受的口径边界
        Mockito.clearInvocations(itemDao);
        JsonNode data = Envelope.data(status(me.token, content));
        assertThat(data.path("isFavorited").asBoolean())
                .as("★ 成员集陈旧 ⇒ 答未收藏。用例在这里显式声明：**本端点不再保证与 DB 一致**")
                .isFalse();
        verify(itemDao, never()).findFoldersByUserAndContent(anyLong(), anyLong());

        // ④ 但**响应自洽**无条件成立 —— 两个字段不许互相打架
        assertThat(data.path("isFavorited").asBoolean())
                .as("★ 自洽：短路分支给的是 false + 空数组，不会出现 {true,[]}")
                .isEqualTo(data.path("folders").size() > 0);
        assertThat(data.path("folders").isArray()).as("folders 键必须在且是数组").isTrue();
    }

    // ========================================================================
    // 4. Redis 挂 ⇒ 降级走 DB
    // ========================================================================

    @Test
    @DisplayName("★Redis 不可用 ⇒ 降级走 DB：答案回到 DB 真相（folders 照常给出），接口不 500")
    void Redis挂降级走DB且接口不500() {
        TestUser me = register("13800003104", "t15-degrade");
        long content = insertContent(me.id());
        long folder = insertFolder(me.id(), "降级夹");
        // 写在打桩**之前**（否则写路径自己的缓存动作也会被桩打挂）
        assertThat(addItem(folder, content, me.token()).getStatusCode().value()).isEqualTo(200);

        // ★ 降级的注入点是 {@code existingOf}（三态读的第一段）；一并打挂 SISMEMBER 支路
        doThrow(new CacheUnavailableException("模拟 Redis 挂")).when(favoriteRedisOps)
                .existingOf(anyString(), anyString());
        doThrow(new CacheUnavailableException("模拟 Redis 挂")).when(favoriteRedisOps)
                .setIsMember(anyString(), anyLong());

        Mockito.clearInvocations(itemDao);
        ResponseEntity<String> resp = status(me.token, content);
        assertThat(resp.getStatusCode().value()).as("★ 缓存只是加速器：Redis 挂掉不许让接口 500").isEqualTo(200);

        JsonNode data = Envelope.data(resp);
        assertThat(data.path("isFavorited").asBoolean()).as("降级 ⇒ 答案来自 DB（仍是 true）").isTrue();
        assertThat(names(data.path("folders"))).as("folders 照常给出，不是空数组").containsExactly("降级夹");
        verify(itemDao, times(1)).existsByUserAndContent(eq(me.id()), eq(content));
        verify(itemDao, times(1)).findFoldersByUserAndContent(eq(me.id()), eq(content));
    }

    // ========================================================================
    // 5. 验收⑤：详情页按钮态 与 收藏弹窗态 在同一场景下不再互相打架
    // ========================================================================

    @Test
    @DisplayName("★验收⑤：同一场景下详情内联 interaction.isFavorited 与 /favorite/status.isFavorited 始终一致（三种状态）")
    void 详情页按钮态与弹窗态同源() {
        TestUser me = register("13800003105", "t15-same-source");
        long video = insertVideoContent(me.id(), "T15 同源视频");
        long folder = insertFolder(me.id(), "同源夹");

        // ① 未收藏：两个来源都答 false（T15 前：一个读缓存一个读 DB，陈旧时会在同一个按钮上打架）
        assertThat(detailIsFavorited(video, me.token())).as("详情页按钮（详情内联）").isFalse();
        assertThat(statusIsFavorited(video, me.token())).as("收藏弹窗（/favorite/status）").isFalse();

        // ② 收藏后：都答 true
        assertThat(addItem(folder, video, me.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(detailIsFavorited(video, me.token())).as("详情页按钮").isTrue();
        assertThat(statusIsFavorited(video, me.token())).as("收藏弹窗").isTrue();

        // ③ 移出后：都回到 false
        assertThat(removeItems(folder, List.of(video), me.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(detailIsFavorited(video, me.token())).as("详情页按钮").isFalse();
        assertThat(statusIsFavorited(video, me.token())).as("收藏弹窗").isFalse();
    }

    // ========================================================================
    // HTTP
    // ========================================================================

    private ResponseEntity<String> status(String token, long contentId) {
        return get("/favorite/status?contentId=" + contentId, token);
    }

    /** 详情页内联态（{@code GET /search/IdSearch} 的 {@code data.interaction.isFavorited}）。 */
    private boolean detailIsFavorited(long contentId, String token) {
        ResponseEntity<String> resp = get("/search/IdSearch?contentId=" + contentId, token);
        assertThat(resp.getStatusCode().value()).as("详情应 200: %s", resp.getBody()).isEqualTo(200);
        return Envelope.data(resp).path("interaction").path("isFavorited").asBoolean();
    }

    /** 收藏弹窗态（{@code GET /favorite/status} 的 {@code data.isFavorited}）。 */
    private boolean statusIsFavorited(long contentId, String token) {
        return Envelope.data(status(token, contentId)).path("isFavorited").asBoolean();
    }

    // ========================================================================
    // 夹具与独立 oracle
    // ========================================================================

    private record TestUser(long id, String token) {
    }

    private TestUser register(String phone, String username) {
        String token = registerAndGetToken(phone, username, PASSWORD);
        return new TestUser(userIdOf(phone), token);
    }

    /** 一条能被 content 装载契约**装出来**的视频（视频行 + 封面行缺一即 404）。 */
    private long insertVideoContent(long authorId, String title) {
        long contentId = insertContent(authorId);
        insertMedia(contentId, "/upload/video/" + contentId + ".mp4", MEDIA_TYPE_VIDEO, 1);
        insertMedia(contentId, "/upload/cover/" + contentId + ".png", MEDIA_TYPE_COVER, 1);
        return contentId;
    }

    private long insertContent(long authorId) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description, category_id) VALUES (?, ?, ?, ?, 1)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setInt(2, CONTENT_TYPE_VIDEO);
            ps.setString(3, "T15 测试内容 " + authorId);
            ps.setString(4, "T15 测试描述");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    private void insertMedia(long contentId, String url, int type, int sort) {
        jdbcTemplate.update("INSERT INTO content_media (content_id, url, type, sort) VALUES (?, ?, ?, ?)",
                contentId, url, type, sort);
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

    /** 直接插收藏记录（绕开写路径 ⇒ 缓存不知情 —— 正是用例 3 要制造的"陈旧缓存"场景）。 */
    private void insertItem(long folderId, long userId, long contentId) {
        jdbcTemplate.update("INSERT INTO favorite_item (folder_id, user_id, content_id) VALUES (?, ?, ?)",
                folderId, userId, contentId);
    }

    /** ★ 独立 oracle：DB 真相（不读缓存、不读接口回显）。 */
    private long oracleRecordCount(long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_item WHERE content_id = ?", Long.class, contentId);
        return n == null ? 0L : n;
    }

    // ==================== Redis 侧 oracle ====================

    private void clearRedis() {
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private boolean hasKey(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    // ==================== 写端点（POST + form，同 favorite 域其它测试） ====================

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

    private static List<String> names(JsonNode folders) {
        List<String> names = new ArrayList<>();
        for (JsonNode node : folders) {
            names.add(node.path("name").asString());
        }
        return names;
    }
}
