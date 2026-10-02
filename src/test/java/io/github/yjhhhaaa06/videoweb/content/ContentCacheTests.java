package io.github.yjhhhaaa06.videoweb.content;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheAside;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.content.cache.ContentRedisOps;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * S5 的**缓存语义与时序**测试 —— 本切片最有价值的测试（决策表 G-3 / G-4 / G-6 的固化）。
 *
 * <h2>为什么必须单列一个类</h2>
 * {@link ContentReadTests} 钉的是"接口返回什么"，它**完全无法**证明缓存时序是对的——
 * 那类断言在"缓存写在事务内"与"缓存写在提交后"两种实现下**都会通过**。
 * 本类专门构造只有正确实现才能通过的场面。
 *
 * <h2>★ 核心手法一：先预热缓存，再制造回滚（或提交）</h2>
 * 两个用例的**唯一差别**是事务成功还是回滚，其它前置完全相同：
 * <pre>
 * {@link #事务回滚后缓存未被污染()}  预热 content key → 更新抛异常 → 回滚 → 断言 key **保持预热原值**
 * {@link #提交后缓存被刷新()}        预热 content key → 正常提交        → 断言 key **变成新值**
 * </pre>
 * 若有人把缓存更新从 {@code AFTER_COMMIT} 改回"写在 {@code @Transactional} 方法体末尾"，
 * 第一个用例立刻失败。**这就是该机制的存在证明。**
 *
 * <h2>★ 核心手法二：独立 oracle 是 Redis 里的**原始 JSON**</h2>
 * 断言不看接口回显，而是直读 {@code content:{id}} 的 JSON 文本里含哪个标题。
 * 这样"接口恰好返回了正确值"不能冒充"缓存是对的"。
 *
 * <h2>其余不变式</h2>
 * <ul>
 *   <li>{@link #三态读命中时不回源DB()} —— hit 分支（证明"读一次 ≠ 每次打 DB"）</li>
 *   <li>{@link #空标记让不存在的id不再回源DB()} —— 防穿透（重复读不再打 DB）</li>
 *   <li>{@link #DB失败上抛而不是伪装成404()} —— ★ 两种失败的区分之一（G-4）</li>
 *   <li>{@link #Redis不可用时降级DB且不写回()} —— ★ 两种失败的区分之二（G-4 / D4）</li>
 *   <li>{@link #索引缺失时懒重建()} / {@link #索引读失败降级为空推荐()} —— 索引的两条路径</li>
 *   <li>{@link #改名后该作者的内容缓存被失效()} —— 跨域级联（UserRenamedEvent）</li>
 * </ul>
 */
class ContentCacheTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900002001";
    private static final String AUTHOR = "cache-author";
    private static final String OTHER_PHONE = "13900002002";
    private static final String OTHER = "cache-other";

    @MockitoSpyBean
    private ContentDao contentDao;

    @MockitoSpyBean
    private ContentRedisOps contentRedisOps;

    @MockitoSpyBean
    private CacheAside cacheAside;

    @BeforeEach
    void resetStubs() {
        Mockito.reset(contentDao, contentRedisOps, cacheAside);
    }

    // ========================================================================
    // ★ 事务时序：AFTER_COMMIT 的存在证明
    // ========================================================================

    @Test
    @DisplayName("★事务回滚后缓存未被污染（AFTER_COMMIT 的存在证明：不提交就绝不改缓存）")
    void 事务回滚后缓存未被污染() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "预热原标题");

        // 预热：读一次详情 ⇒ content key 被写入，值是"预热原标题"
        assertThat(get("/search/IdSearch?contentId=" + contentId, token).getStatusCode().value())
                .isEqualTo(200);
        assertThat(cachedContentJson(contentId)).as("预热必须真的写进缓存才有判别力")
                .isNotNull()
                .contains("预热原标题");

        // 让事务内的写抛异常 ⇒ 整笔回滚
        doThrow(new DataAccessException("模拟写入失败") {
        }).when(contentDao).updateContentInfo(anyLong(), anyString(), anyString());

        ResponseEntity<String> resp = postQuery("/content/update",
                query("contentId", String.valueOf(contentId), "title", "回滚后的标题"), token);
        assertThat(resp.getStatusCode().value()).as("写入失败应非 200：%s", resp.getBody())
                .isNotEqualTo(200);

        // ★ 断言：缓存**保持预热原值**。若缓存更新被挪进事务方法体（而非 AFTER_COMMIT），
        //   这里会看到"回滚后的标题"——用例即失败。
        assertThat(cachedContentJson(contentId))
                .as("事务回滚 ⇒ 监听器不触发 ⇒ 缓存不得被改动")
                .contains("预热原标题")
                .doesNotContain("回滚后的标题");
    }

    @Test
    @DisplayName("★反向对照：正常提交后缓存被刷新（排除『永不改缓存』的假绿）")
    void 提交后缓存被刷新() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "预热原标题");

        assertThat(get("/search/IdSearch?contentId=" + contentId, token).getStatusCode().value())
                .isEqualTo(200);
        assertThat(cachedContentJson(contentId)).contains("预热原标题");

        assertThat(postQuery("/content/update",
                query("contentId", String.valueOf(contentId), "title", "提交后的标题"), token)
                .getStatusCode().value()).isEqualTo(200);

        assertThat(cachedContentJson(contentId))
                .as("提交 ⇒ REFRESH 生效")
                .contains("提交后的标题");
    }

    // ========================================================================
    // 三态读
    // ========================================================================

    @Test
    @DisplayName("三态读：命中之后不再回源 DB（同一内容读两次，DB 只被查一次）")
    void 三态读命中时不回源DB() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "命中内容");

        assertThat(get("/search/IdSearch?contentId=" + contentId, null).getStatusCode().value())
                .isEqualTo(200);
        verify(contentDao, times(1)).findContent(contentId);

        assertThat(get("/search/IdSearch?contentId=" + contentId, null).getStatusCode().value())
                .isEqualTo(200);
        verify(contentDao, times(1)).findContent(contentId);
    }

    @Test
    @DisplayName("空标记：确认不存在后重复读不再回源 DB（防穿透）")
    void 空标记让不存在的id不再回源DB() {
        long missingId = 987654321L;

        assertThat(get("/search/IdSearch?contentId=" + missingId, null).getStatusCode().value())
                .isEqualTo(404);
        assertThat(redisKeys()).as("空标记必须落盘（否则每次读都要回源）")
                .contains(EMPTY_PREFIX + CONTENT_KEY_PREFIX + missingId);

        assertThat(get("/search/IdSearch?contentId=" + missingId, null).getStatusCode().value())
                .isEqualTo(404);
        verify(contentDao, times(1)).findContent(missingId);
    }

    // ========================================================================
    // ★ 两种失败必须区分（G-4 / L-5 / F-5）
    // ========================================================================

    @Test
    @DisplayName("★DB 失败上抛（500），不伪装成 404『内容不存在』")
    void DB失败上抛而不是伪装成404() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "DB 失败内容");
        clearRedis();     // 冷缓存 ⇒ 读必然走 loader

        doThrow(new DataAccessException("模拟 DB 故障") {
        }).when(contentDao).findContent(anyLong());

        ResponseEntity<String> resp = get("/search/IdSearch?contentId=" + contentId, null);
        assertThat(resp.getStatusCode().value())
                .as("降级成 null 会把『读不到』伪装成『没有』(404)；正解是 500：%s", resp.getBody())
                .isEqualTo(500);
        assertThat(redisKeys()).as("加载失败不得写空标记（不固化瞬时故障）")
                .doesNotContain(EMPTY_PREFIX + CONTENT_KEY_PREFIX + contentId);
    }

    @Test
    @DisplayName("★Redis 失败降级直查 DB：接口仍 200 且结果正确，并且**不写回**（D4）")
    void Redis不可用时降级DB且不写回() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "降级内容");
        clearRedis();

        // 只让"读探测"那一趟 pipeline 失败 ⇒ 模拟 Redis 不可达
        doThrow(new CacheUnavailableException("模拟 Redis 故障")).when(cacheAside).pipeline(any());

        ResponseEntity<String> resp = get("/search/IdSearch?contentId=" + contentId, token);
        assertThat(resp.getStatusCode().value()).as("Redis 挂掉不得让内容接口 500：%s", resp.getBody())
                .isEqualTo(200);
        assertThat(Envelope.data(resp).path("title").asString()).isEqualTo("降级内容");
        assertThat(cachedContentJson(contentId)).as("降级路径**不写回**（D4）：写回交给下次 miss 自愈")
                .isNull();
    }

    // ========================================================================
    // 索引
    // ========================================================================

    @Test
    @DisplayName("索引缺失时懒重建：清空 content:index:* 后 /start 仍能返回内容并重建索引")
    void 索引缺失时懒重建() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "懒重建内容");
        clearRedis();     // 连同索引一起清掉

        assertThat(redis.keys(CONTENT_INDEX_PREFIX + "*")).as("前置：索引确实不存在").isNullOrEmpty();

        assertThat(idsOf(Envelope.data(get("/start", null)))).contains(contentId);
        assertThat(redis.keys(CONTENT_INDEX_PREFIX + "*")).as("重建后索引 key 应存在").isNotEmpty();
    }

    @Test
    @DisplayName("索引读失败降级为空推荐（TV 的 U-11 语义）：200 + 空列表，不 500")
    void 索引读失败降级为空推荐() {
        registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        insertVideoContent(userIdOf(AUTHOR_PHONE), "降级推荐内容");
        clearRedis();

        doThrow(new CacheUnavailableException("模拟 Redis 故障")).when(contentRedisOps).readIds(anyString());

        ResponseEntity<String> resp = get("/start", null);
        assertThat(resp.getStatusCode().value()).as("索引不可用 ⇒ 空推荐，而不是 500").isEqualTo(200);
        assertThat(Envelope.data(resp).isArray()).isTrue();
        assertThat(Envelope.data(resp).size()).isZero();
    }

    // ========================================================================
    // 跨域级联：改名 → 内容缓存失效
    // ========================================================================

    @Test
    @DisplayName("改名后该作者的内容缓存被失效（authorName 冗余同步）")
    void 改名后该作者的内容缓存被失效() {
        String token = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        long contentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "改名内容");

        assertThat(detailAuthorName(contentId, token)).as("前置：缓存的 authorName = 旧名")
                .isEqualTo(AUTHOR);
        assertThat(cachedContentJson(contentId)).contains(AUTHOR);

        assertThat(postQuery("/user/changeUserName", query("userName", "cache-author-new"), token)
                .getStatusCode().value()).isEqualTo(200);

        assertThat(detailAuthorName(contentId, token)).as("改名后读自愈回填新名")
                .isEqualTo("cache-author-new");
        assertThat(cachedContentJson(contentId)).as("缓存的 JSON 不应再含旧名")
                .doesNotContain("\"" + AUTHOR + "\"");
    }

    @Test
    @DisplayName("其它用户的内容缓存不受改名影响（级联失效只针对该作者）")
    void 改名只失效自己的内容缓存() {
        String authorToken = registerAndGetToken(AUTHOR_PHONE, AUTHOR);
        String otherToken = registerAndGetToken(OTHER_PHONE, OTHER);
        long authorContentId = insertVideoContent(userIdOf(AUTHOR_PHONE), "作者的内容");
        long otherContentId = insertVideoContent(userIdOf(OTHER_PHONE), "别人的内容");

        get("/search/IdSearch?contentId=" + authorContentId, authorToken);
        get("/search/IdSearch?contentId=" + otherContentId, otherToken);
        String otherCached = cachedContentJson(otherContentId);

        postQuery("/user/changeUserName", query("userName", "cache-author-new"), authorToken);

        assertThat(cachedContentJson(otherContentId))
                .as("别人的缓存应原样保留（反向对照，排除『改名清空全部内容缓存』）")
                .isEqualTo(otherCached);
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    private String detailAuthorName(long contentId, String token) {
        return Envelope.data(get("/search/IdSearch?contentId=" + contentId, token))
                .path("authorName").asString();
    }

    private static List<Long> idsOf(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : list) {
            ids.add(node.path("id").asLong());
        }
        return ids;
    }
}
