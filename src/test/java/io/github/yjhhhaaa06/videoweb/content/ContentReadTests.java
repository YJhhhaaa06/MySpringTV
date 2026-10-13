package io.github.yjhhhaaa06.videoweb.content;

import io.github.yjhhhaaa06.videoweb.interaction.service.InteractionService;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * S5：content **读路径**与作者写路径的 HTTP 契约测试。
 *
 * <h2>这些用例钉的是什么</h2>
 * 旧 pytest 里对应四份规格：{@code test_smoke.py}（{@code S-04} 首页 / {@code S-06} 详情 /
 * {@code S-08} 媒体字段）、{@code test_content_paging.py}（T19 分页口径 + T15 同秒 tie-breaker）、
 * {@code test_comment_enabled.py}（开关评论区）、{@code test_edit_work.py}（编辑文案）。
 *
 * <h2>★ 两条纪律（《测试策略》§四）</h2>
 * <ol>
 *   <li><b>断言可观察行为</b>：HTTP 状态 + 信封 + DB 终态。不断言内部调用（那交给
 *       {@link ContentCacheTests}）。</li>
 *   <li><b>独立 oracle</b>：关键数据直查列 / {@code COUNT(*)}，**不信任接口回显**。
 *       例如"开关生效了"不是看响应，而是直查 {@code content.comment_enabled} 列。</li>
 * </ol>
 *
 * <h2>★ 互动态的位置（R-10 / T10 / **T13**）</h2>
 * 详情响应的互动态收在 {@code interaction} 子对象（顶层 {@code isLiked}/{@code isFollowed}
 * 已在 T10 删除——这是 R-10 有意的 breaking 面，见 {@code 收藏功能-分期与设计.md} §3.6）；
 * **匿名 ⇒ {@code "interaction": null}**（不是 {@code {}}、不是三个 {@code false}）。
 * <p>★ **T13（R-13）起列表同形**：{@code /start} / {@code /search/keywordSearch} 也把互动态
 * 收进 {@code interaction}（顶层两键删除），且**三维三态** —— {@code null} = 本面不查该维：
 * {@code /start} 只带 {@code {关注}}、{@code /search/keywordSearch} 带 **0 维**（对象仍在、
 * 三维全 {@code null}）。详见 {@code ContentVO} 类注释的常量表。
 * ⚠️ 0 维的"对象仍在"是刻意的：{@code interaction: null} 是**匿名**的语义，两者必须可区分。
 */
class ContentReadTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900001001";
    private static final String AUTHOR = "content-author";
    private static final String OTHER_PHONE = "13900001002";
    private static final String OTHER = "content-other";

    /** T10：列表填充来源改走互动域 ⇒ 用 spy 钉"批量透传、不得循环调单条"（见批量透传用例）。 */
    @MockitoSpyBean
    private InteractionService interactionService;

    @BeforeEach
    void 重置spy() {
        // spy 是单例 bean，调用记录会跨用例累积 ⇒ 每测清零（真实行为不受影响）
        Mockito.reset(interactionService);
    }

    // ========================================================================
    // /search/IdSearch
    // ========================================================================

    @Test
    @DisplayName("详情：视频内容的字段形状完整（含 videoUrl/coverUrl，不含 imageUrls 值）")
    void 详情_视频字段形状() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "详情视频标题");

        ResponseEntity<String> resp = get("/search/IdSearch?contentId=" + contentId, token);
        assertThat(resp.getStatusCode().value()).as("详情应 200: %s", resp.getBody()).isEqualTo(200);

        JsonNode data = Envelope.data(resp);
        assertThat(data.path("id").asLong()).isEqualTo(contentId);
        assertThat(data.path("authorId").asLong()).isEqualTo(authorId);
        assertThat(data.path("authorName").asString()).isEqualTo(AUTHOR);
        assertThat(data.path("type").asInt()).isEqualTo(CONTENT_TYPE_VIDEO);
        assertThat(data.path("title").asString()).isEqualTo("详情视频标题");
        assertThat(data.path("categoryId").asInt()).isEqualTo(1);
        assertThat(data.path("commentEnabled").asBoolean()).isTrue();
        assertThat(data.path("commentCount").asInt()).isZero();
        assertThat(data.path("likeCount").asInt()).isZero();
        assertThat(data.path("createTime").isMissingNode()).as("createTime 必须存在").isFalse();
        // 媒体：视频内容有 videoUrl + coverUrl；imageUrls 为 null（TV 也输出 null，不是省略）
        assertThat(data.path("videoUrl").asString()).endsWith(".mp4");
        assertThat(data.path("coverUrl").asString()).endsWith(".png");
        assertThat(data.path("imageUrls").isNull()).as("视频内容的 imageUrls 应为 null").isTrue();
        // 互动态：T10 起收进 interaction 子对象；顶层 isLiked/isFollowed 已删（R-10 的 breaking 面）
        assertThat(data.has("isLiked")).as("顶层 isLiked 已删除").isFalse();
        assertThat(data.has("isFollowed")).as("顶层 isFollowed 已删除").isFalse();
        JsonNode interaction = data.path("interaction");
        assertThat(interaction.isObject()).as("带 token（登录）⇒ interaction 是对象").isTrue();
        assertThat(keysOf(interaction)).as("子对象键集契约：恰好三维").containsExactlyInAnyOrder(
                "isLiked", "isFavorited", "isFollowed");
        assertThat(interaction.path("isLiked").asBoolean()).isFalse();
        assertThat(interaction.path("isFavorited").asBoolean()).isFalse();
        assertThat(interaction.path("isFollowed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("详情：图文内容的 imageUrls 是数组且按 sort 升序")
    void 详情_图文图片组() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertPostContent(authorId, "详情图文标题");

        ResponseEntity<String> resp = get("/search/IdSearch?contentId=" + contentId, null);
        JsonNode data = Envelope.data(resp);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(data.path("imageUrls").isArray()).isTrue();
        assertThat(data.path("imageUrls").size()).isEqualTo(2);
        assertThat(data.path("imageUrls").get(0).asString()).endsWith("_1.jpg");
        assertThat(data.path("imageUrls").get(1).asString()).endsWith("_2.jpg");
        assertThat(data.path("videoUrl").isNull()).as("图文内容的 videoUrl 应为 null").isTrue();
    }

    @Test
    @DisplayName("详情：内容不存在 / 已软删 / 媒体损坏 → 404")
    void 详情_不可见一律404() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long broken = insertBrokenVideoContent(authorId, "媒体损坏的内容");

        assertThat(get("/search/IdSearch?contentId=987654321", null).getStatusCode().value())
                .as("不存在的 id").isEqualTo(404);
        assertThat(get("/search/IdSearch?contentId=" + broken, null).getStatusCode().value())
                .as("视频行缺失 = 资源已丢失，对外等同不存在").isEqualTo(404);

        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", broken);
        assertThat(get("/search/IdSearch?contentId=" + broken, null).getStatusCode().value())
                .as("已软删").isEqualTo(404);
    }

    @Test
    @DisplayName("详情：缺 contentId → 400；非法 contentId → 400（TV 是 500，本切片有意修正）")
    void 详情_参数错误() {
        assertThat(get("/search/IdSearch", null).getStatusCode().value())
                .as("缺 contentId").isEqualTo(400);
        assertThat(get("/search/IdSearch?contentId=abc", null).getStatusCode().value())
                .as("非法 contentId（TV 此处是未捕获的 NumberFormatException → 500）").isEqualTo(400);
    }

    @Test
    @DisplayName("详情：已登录且已赞 / 已收藏 / 已关注作者 → interaction 三维各自 true；零互动对照用户全 false")
    void 详情_个性化状态() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long viewerId = userIdOf(OTHER_PHONE);
        long contentId = insertVideoContent(authorId, "个性化状态内容");

        insertContentLike(viewerId, contentId);
        jdbcTemplate.update("INSERT INTO follow (user_id, followed_user_id) VALUES (?, ?)",
                viewerId, authorId);
        // 收藏走真实写入口（不传 folderId ⇒ R-01 懒建默认夹）——内联收藏态是 R-10 的收益点，端到端点亮
        assertThat(postQuery("/favorite/add", query("contentId", String.valueOf(contentId)), token)
                .getStatusCode().value()).as("前置：收藏应成功").isEqualTo(200);

        JsonNode interaction = Envelope.data(get("/search/IdSearch?contentId=" + contentId, token))
                .path("interaction");
        assertThat(interaction.path("isLiked").asBoolean()).isTrue();
        assertThat(interaction.path("isFavorited").asBoolean())
                .as("★ 内联收藏态（R-10 收益点：详情页不必再为它单发 /favorite/status）").isTrue();
        assertThat(interaction.path("isFollowed").asBoolean()).isTrue();

        // 反向对照：换一个没点过赞 / 没收藏 / 没关注的用户 ⇒ 三维都是 false，且子对象**在场**
        // （排除"恒为 true"与"匿名态混入"两种假绿——这正是"这个断言还可能由谁产生"的自问）
        String otherToken = registerAndGetToken("13900001003", "content-third");
        JsonNode other = Envelope.data(get("/search/IdSearch?contentId=" + contentId, otherToken))
                .path("interaction");
        assertThat(other.isObject()).as("登录但零互动 ⇒ 对象在场（不是 null）").isTrue();
        assertThat(other.path("isLiked").asBoolean()).isFalse();
        assertThat(other.path("isFavorited").asBoolean()).isFalse();
        assertThat(other.path("isFollowed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("详情：匿名访问 200 且 interaction 为 null（不是 {}、不是三个 false）")
    void 详情_匿名可访问() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "匿名可见内容");

        ResponseEntity<String> resp = get("/search/IdSearch?contentId=" + contentId, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode data = Envelope.data(resp);
        assertThat(data.has("interaction")).as("匿名也输出 interaction 键（值为 null）").isTrue();
        assertThat(data.path("interaction").isNull())
                .as("★ 匿名 ⇒ null（不是 {}、不是三个 false）—— R-10 相对平铺的表达力增益")
                .isTrue();
        assertThat(data.has("isLiked")).as("顶层 isLiked 已删除").isFalse();
        assertThat(data.has("isFollowed")).as("顶层 isFollowed 已删除").isFalse();
    }

    // ========================================================================
    // /search/keywordSearch（T19 分页 + T15 tie-breaker）
    // ========================================================================

    @Test
    @DisplayName("搜索：信封形状与域级归一（缺省 100 / 超上限 100 / 51 原样 / 非法回落 100）")
    void 搜索_分页归一() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        insertPostContent(authorId, "zqtb 分页内容一");
        insertPostContent(authorId, "zqtb 分页内容二");

        JsonNode defaultData = Envelope.data(get("/search/keywordSearch?keyword=zqtb", null));
        assertThat(keysOf(defaultData)).containsExactlyInAnyOrder(
                "list", "total", "page", "pageSize", "totalPages");
        assertThat(defaultData.path("page").asInt()).isEqualTo(1);
        assertThat(defaultData.path("pageSize").asInt()).isEqualTo(PAGE_SIZE_MAX);
        assertThat(defaultData.path("total").asInt()).isEqualTo(2);

        assertThat(Envelope.data(get("/search/keywordSearch?keyword=zqtb&pageSize=999", null))
                .path("pageSize").asInt()).as("超上限截到 100").isEqualTo(100);
        assertThat(Envelope.data(get("/search/keywordSearch?keyword=zqtb&pageSize=51", null))
                .path("pageSize").asInt()).as("51 原样回显（不是截到 100）").isEqualTo(51);
        assertThat(Envelope.data(get("/search/keywordSearch?keyword=zqtb&pageSize=abc", null))
                .path("pageSize").asInt()).as("非法值回落域级信封，不是 500").isEqualTo(100);
        assertThat(Envelope.data(get("/search/keywordSearch?keyword=zqtb&page=0", null))
                .path("page").asInt()).as("page<1 归一为 1").isEqualTo(1);
    }

    @Test
    @DisplayName("搜索：缺省（不传分页）== 显式 page=1&pageSize=100 响应逐字节一致")
    void 搜索_缺省等价显式首页() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        insertPostContent(authorId, "zqtb 等价内容");

        JsonNode a = Envelope.data(get("/search/keywordSearch?keyword=zqtb", null));
        JsonNode b = Envelope.data(get("/search/keywordSearch?keyword=zqtb&page=1&pageSize=100", null));
        assertThat(a.path("list").size()).as("本用例需非空列表才有判别力").isEqualTo(1);
        assertThat(a.toString()).isEqualTo(b.toString());
    }

    @Test
    @DisplayName("搜索：关键词空白 → 400（文案 输入不能为空）")
    void 搜索_关键词空() {
        ResponseEntity<String> blank = get("/search/keywordSearch?keyword=", null);
        assertThat(blank.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(blank)).isEqualTo("输入不能为空");

        // 纯空白：必须走结构化 query，否则 RestClient 会把 %20 二次编码成字面量 "%20%20"
        // （那样服务端看到的是一个非空关键词 ⇒ 200，测试变成假红）
        ResponseEntity<String> spaces = getQuery("/search/keywordSearch", query("keyword", "   "), null);
        assertThat(spaces.getStatusCode().value()).as("%s", spaces.getBody()).isEqualTo(400);
        assertThat(Envelope.msg(spaces)).isEqualTo("输入不能为空");

        assertThat(get("/search/keywordSearch", null).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("★搜索 T15：同秒内容页间顺序 = id 降序（单字符 LIKE 与多字符 MATCH 两条分支都测）")
    void 搜索_同秒顺序为id降序() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(insertPostContent(authorId, "qz same-second " + i + " 内容"));
        }
        pinCreateTime("2001-02-03 04:05:06", ids.stream().mapToLong(Long::longValue).toArray());

        // 多字符 → MATCH ... AGAINST 分支
        assertThat(fullOrder("qz")).isEqualTo(reversed(ids));
        // 单字符 → title LIKE '%q%' 分支（本用例内库里只有这几条含 q 的内容）
        assertThat(fullOrder("q")).isEqualTo(reversed(ids));

        // pageSize=1 逐页取 ⇒ 页间不重不漏、顺序与整页一致
        List<Long> paged = new ArrayList<>();
        for (int page = 1; page <= ids.size(); page++) {
            JsonNode pageData = Envelope.data(
                    get("/search/keywordSearch?keyword=qz&page=" + page + "&pageSize=1", null));
            JsonNode list = pageData.path("list");
            assertThat(list.size()).as("pageSize=1 每页应 1 条").isEqualTo(1);
            paged.add(list.get(0).path("id").asLong());
        }
        assertThat(paged).as("页间不重不漏且顺序一致").isEqualTo(reversed(ids));
    }

    // ========================================================================
    // /start
    // ========================================================================

    @Test
    @DisplayName("/start：返回列表且包含已发布内容；请求参数 limit 被忽略（TV 固定 12）")
    void 首页推荐_列表与limit忽略() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long contentId = insertVideoContent(authorId, "首页推荐内容");

        ResponseEntity<String> resp = get("/start?limit=10", null);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode data = Envelope.data(resp);
        assertThat(data.isArray()).isTrue();
        assertThat(idsOf(data)).contains(contentId);
    }

    @Test
    @DisplayName("/start：type/categoryId 过滤与取值校验（语法非法静默、语义非法 400）")
    void 首页推荐_过滤与校验() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long videoId = insertVideoContent(authorId, "类型过滤-视频");
        long postId = insertPostContent(authorId, "类型过滤-图文");

        assertThat(idsOf(Envelope.data(get("/start?type=1", null)))).contains(videoId).doesNotContain(postId);
        assertThat(idsOf(Envelope.data(get("/start?type=2", null)))).contains(postId).doesNotContain(videoId);
        // 语法非法（非数字）静默 → 不过滤
        assertThat(idsOf(Envelope.data(get("/start?type=abc", null)))).contains(videoId, postId);
        // 语义非法（越界）→ 400（文案由缓存层给出，TV 原样）
        ResponseEntity<String> badType = get("/start?type=3", null);
        assertThat(badType.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(badType)).isEqualTo("不支持的内容类型: 3");
        ResponseEntity<String> badCategory = get("/start?categoryId=99", null);
        assertThat(badCategory.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(badCategory)).isEqualTo("不支持的分区: 99");
    }

    // ========================================================================
    // 列表路径：互动态填充来源改走互动域（T10），顶层键集**不变**
    // ========================================================================

    @Test
    @DisplayName("列表：互动态收进 interaction 子对象（顶层两键已删）；/start 只带关注维、/search 0 维（对象仍在）")
    void 列表_互动态收进interaction子对象() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long viewerId = userIdOf(OTHER_PHONE);
        long contentId = insertPostContent(authorId, "zqtb 列表互动态内容");

        insertContentLike(viewerId, contentId);
        jdbcTemplate.update("INSERT INTO follow (user_id, followed_user_id) VALUES (?, ?)",
                viewerId, authorId);

        // ---- /start：只带 {关注} ----
        JsonNode startItem = itemOf(Envelope.data(get("/start", token)), contentId);
        assertThat(startItem.has("isLiked")).as("顶层 isLiked 已删除（T13）").isFalse();
        assertThat(startItem.has("isFollowed")).as("顶层 isFollowed 已删除（T13）").isFalse();
        JsonNode startInteraction = startItem.path("interaction");
        assertThat(keysOf(startInteraction)).as("子对象键集恒三维（不靠缺键表达'不查'）")
                .containsExactlyInAnyOrder("isLiked", "isFavorited", "isFollowed");
        assertThat(startInteraction.path("isFollowed").asBoolean())
                .as("/start 带关注维 ⇒ 已关注作者为真").isTrue();
        assertThat(startInteraction.path("isLiked").isNull())
                .as("★ /start 不带赞维 ⇒ null（**不是 false**——false 会是撒谎）").isTrue();
        assertThat(startInteraction.path("isFavorited").isNull()).as("/start 不带收藏维").isTrue();

        // ---- /search/keywordSearch：0 维 ----
        JsonNode searchItem = itemOf(
                Envelope.data(get("/search/keywordSearch?keyword=zqtb", token)).path("list"), contentId);
        JsonNode searchInteraction = searchItem.path("interaction");
        assertThat(searchInteraction.isObject())
                .as("★ 0 维：**对象仍在**（若为 null 就与匿名不可区分了）").isTrue();
        assertThat(keysOf(searchInteraction)).containsExactlyInAnyOrder(
                "isLiked", "isFavorited", "isFollowed");
        assertThat(searchInteraction.path("isLiked").isNull()).as("0 维 ⇒ 赞维 null").isTrue();
        assertThat(searchInteraction.path("isFavorited").isNull()).as("0 维 ⇒ 藏维 null").isTrue();
        assertThat(searchInteraction.path("isFollowed").isNull())
                .as("★ 0 维 ⇒ 关注维 null —— 不再是 TV/旧实现那个恒 false 的谎言").isTrue();

        // ---- 匿名 ⇒ 整对象为 null（与 0 维可区分）----
        JsonNode anonStart = itemOf(Envelope.data(get("/start", null)), contentId);
        assertThat(anonStart.path("interaction").isNull()).as("匿名 ⇒ interaction 为 null").isTrue();
        JsonNode anonSearch = itemOf(
                Envelope.data(get("/search/keywordSearch?keyword=zqtb", null)).path("list"), contentId);
        assertThat(anonSearch.path("interaction").isNull()).as("匿名 ⇒ interaction 为 null").isTrue();
    }

    @Test
    @DisplayName("★列表填充批量透传：/start ⇒ 互动域批量契约一趟、单条契约零次（不得循环调单条）")
    void 列表_批量透传不循环调单条() {
        String token = registerAndGetToken(OTHER_PHONE, OTHER);
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);
        long viewerId = userIdOf(OTHER_PHONE);
        long contentId = insertPostContent(authorId, "批量透传内容");
        insertContentLike(viewerId, contentId);
        Mockito.reset(interactionService);      // 前置夹具（注册/点赞）的调用不计入

        assertThat(idsOf(Envelope.data(get("/start", token))))
                .as("本用例需非空列表才有判别力").contains(contentId);

        // ★ 判别力：把 ContentStatusFiller 改成"逐条调 getStatus"，这两个断言立即变红
        //   （T9 红线的同一纪律：100 条列表不得退化为 100 次往返；反向注入实测见 T10 回写）
        verify(interactionService, times(1)).batchGetStatus(eq(viewerId), anyList(), anySet());
        verify(interactionService, never()).getStatus(any(), anyLong(), anyLong(), anyInt());
    }

    // ========================================================================
    // /content/commentEnabled（CM-3 显式划入 S5）
    // ========================================================================

    @Test
    @DisplayName("开关评论区：作者可关可开，且 comment_enabled 列与详情回显同步变化")
    void 开关_作者可关可开() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "开关内容");

        assertThat(postQuery("/content/commentEnabled",
                query("contentId", String.valueOf(contentId), "enabled", "0"), token)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(contentCommentEnabledColumn(contentId)).as("独立 oracle：列值").isZero();
        assertThat(Envelope.data(get("/search/IdSearch?contentId=" + contentId, token))
                .path("commentEnabled").asBoolean()).as("详情回显（失效自愈后）").isFalse();

        assertThat(postQuery("/content/commentEnabled",
                query("contentId", String.valueOf(contentId), "enabled", "true"), token)
                .getStatusCode().value()).as("true 也应被接受").isEqualTo(200);
        assertThat(contentCommentEnabledColumn(contentId)).isEqualTo(1);
        assertThat(Envelope.data(get("/search/IdSearch?contentId=" + contentId, token))
                .path("commentEnabled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("开关评论区：非作者 403 且列值不变；内容不存在 404；未登录 401")
    void 开关_权限与存在性() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        String otherToken = registerAndGetToken(OTHER_PHONE, OTHER);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "权限内容");

        ResponseEntity<String> forbidden = postQuery("/content/commentEnabled",
                query("contentId", String.valueOf(contentId), "enabled", "0"), otherToken);
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(Envelope.msg(forbidden)).isEqualTo("只能操作自己的作品");
        assertThat(contentCommentEnabledColumn(contentId)).as("403 时不得改动").isEqualTo(1);

        ResponseEntity<String> notFound = postQuery("/content/commentEnabled",
                query("contentId", "987654321", "enabled", "0"), authorToken);
        assertThat(notFound.getStatusCode().value()).isEqualTo(404);
        assertThat(Envelope.msg(notFound)).isEqualTo("内容不存在");

        assertThat(postQuery("/content/commentEnabled",
                query("contentId", String.valueOf(contentId), "enabled", "0"), null)
                .getStatusCode().value()).as("未登录 → 401").isEqualTo(401);
    }

    @Test
    @DisplayName("开关评论区：enabled 缺失/非法 → 400（文案逐字保留）")
    void 开关_参数校验() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "参数校验内容");

        ResponseEntity<String> missing = postQuery("/content/commentEnabled",
                query("contentId", String.valueOf(contentId)), token);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(missing)).isEqualTo("enabled不能为空");

        ResponseEntity<String> invalid = postQuery("/content/commentEnabled",
                query("contentId", String.valueOf(contentId), "enabled", "2"), token);
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(invalid)).isEqualTo("enabled格式错误，应为 0/1 或 true/false");
    }

    // ========================================================================
    // /content/update
    // ========================================================================

    @Test
    @DisplayName("编辑文案：作者改标题与简介，列值与详情回显同步更新")
    void 编辑_作者可改() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "旧标题");

        ResponseEntity<String> resp = postQuery("/content/update",
                query("contentId", String.valueOf(contentId),
                        "title", "新标题", "description", "新简介"), token);
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        assertThat(contentTitleColumn(contentId)).as("独立 oracle：列值").isEqualTo("新标题");
        assertThat(contentDescriptionColumn(contentId)).isEqualTo("新简介");
        assertThat(Envelope.data(get("/search/IdSearch?contentId=" + contentId, token))
                .path("title").asString()).as("详情回显（REFRESH 后）").isEqualTo("新标题");
    }

    @Test
    @DisplayName("编辑文案：非作者 403；参数非法 400 且不产生任何写入")
    void 编辑_权限与参数校验() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        String otherToken = registerAndGetToken(OTHER_PHONE, OTHER);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "原标题");

        assertThat(postQuery("/content/update",
                query("contentId", String.valueOf(contentId), "title", "被别人改"), otherToken)
                .getStatusCode().value()).isEqualTo(403);
        assertThat(contentTitleColumn(contentId)).as("403 时不得改动").isEqualTo("原标题");

        assertThat(postQuery("/content/update",
                query("contentId", String.valueOf(contentId), "title", " "), authorToken)
                .getStatusCode().value()).as("空标题").isEqualTo(400);
        assertThat(Envelope.msg(postQuery("/content/update",
                query("contentId", String.valueOf(contentId), "title", " "), authorToken)))
                .isEqualTo("标题不能为空");

        assertThat(postQuery("/content/update",
                query("contentId", String.valueOf(contentId), "title", "x".repeat(51)), authorToken)
                .getStatusCode().value()).as("标题超 50").isEqualTo(400);
        assertThat(postQuery("/content/update",
                query("contentId", String.valueOf(contentId), "title", "ok",
                        "description", "y".repeat(5001)), authorToken)
                .getStatusCode().value()).as("简介超 5000").isEqualTo(400);

        assertThat(contentTitleColumn(contentId)).as("参数非法时不得改动").isEqualTo("原标题");
        assertThat(contentDescriptionColumn(contentId))
                .as("参数非法时简介也不得改动")
                .isEqualTo("原标题 的简介");

        assertThat(postQuery("/content/update",
                query("contentId", String.valueOf(contentId), "title", "ok"), null)
                .getStatusCode().value()).as("未登录 → 401").isEqualTo(401);
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    private static List<String> keysOf(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }

    private static List<Long> idsOf(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : list) {
            ids.add(node.path("id").asLong());
        }
        return ids;
    }

    /** 从列表信封里取指定 id 的条目（找不到直接失败——防"夹具没造上/被过滤掉"的假绿）。 */
    private static JsonNode itemOf(JsonNode list, long contentId) {
        for (JsonNode node : list) {
            if (node.path("id").asLong() == contentId) {
                return node;
            }
        }
        throw new AssertionError("列表里应包含内容 " + contentId + "，实际：" + list);
    }

    /** 单页取全（pageSize=100 装得下本用例的数据）并返回 id 列表。 */
    private List<Long> fullOrder(String keyword) {
        JsonNode data = Envelope.data(
                get("/search/keywordSearch?keyword=" + keyword + "&pageSize=100", null));
        assertThat(data.path("total").asInt()).isEqualTo(data.path("list").size());
        return idsOf(data.path("list"));
    }

    private static List<Long> reversed(List<Long> ids) {
        List<Long> copy = new ArrayList<>(ids);
        copy.sort(java.util.Comparator.reverseOrder());
        return copy;
    }
}
