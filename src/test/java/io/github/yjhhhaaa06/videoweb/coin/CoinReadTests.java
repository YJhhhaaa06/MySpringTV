package io.github.yjhhhaaa06.videoweb.coin;

import io.github.yjhhhaaa06.videoweb.coin.service.CoinService;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 投币专项 T40 验收：**读面接入** —— 互动态第四维 {@code interaction.coin} +
 * 公共计数 {@code coinCount}（设计 {@code architecture/domain/coin/coin.md} §七）。
 *
 * <h2>每条断言的期望值来自业务口径（不是照抄实现）</h2>
 * <ul>
 *   <li><b>详情（视频）</b>：{@code interaction.coin} = "我投了几枚"（{@code 0/1/2}），
 *       {@code coinCount} = "这条被投了几枚" —— 两者分别用 {@code content_coin.amount} /
 *       {@code content.coin_count} **独立复算**（{@code AGENTS.md} 纪律②）；</li>
 *   <li><b>图文帖</b>：投币维**不适用** ⇒ {@code interaction.coin = null}（不是 0）；
 *       而公共计数照常存在（0）—— "不适用"与"是 0 枚"必须分得开；</li>
 *   <li><b>匿名</b>：{@code interaction} 整体 {@code null}（不是四维全 null 的空壳）；
 *       {@code coinCount} 仍返回 —— 公共计数与登录态无关；</li>
 *   <li><b>列表</b>：{@code /start} / {@code /search/keywordSearch} / {@code /profile} 的
 *       {@code interaction.coin} **恒 {@code null}**（契约，见 {@code InteractionDimension} 没有
 *       {@code COIN} 常量），{@code coinCount} 与 DB 复算一致；</li>
 *   <li><b>键集</b>：详情与列表面的 {@code interaction} 都**恰好四维**
 *       {@code {isLiked, isFavorited, isFollowed, coin}}。</li>
 * </ul>
 *
 * <h2>★ 反向验证（改哪一行必红）</h2>
 * <ul>
 *   <li>{@code InteractionService#getStatus} **不给** {@code coin} 赋值（恒 null）⇒
 *       {@link #详情视频_投币后coin与coinCount都与DB一致()}、
 *       {@link #详情图文_coin为null但coinCount有值()}（键集仍在但值 null）、
 *       {@link #详情与列表的interaction恰好四维()}（视频详情的 coin 值断言）变红；</li>
 *   <li>把 {@code type == 1} 的分流写反 / 去掉（图文也查）⇒ 图文用例的
 *       {@code coin == null} 变红；</li>
 *   <li>三个组装点漏掉 {@code fillCoinCountBatch} ⇒ 对应列表用例的 {@code coinCount} 变红；</li>
 *   <li>公共计数放进互动态的匿名短路里 ⇒ 匿名列表用例（本类的匿名 {@code /start} 断言）变红；</li>
 *   <li>把批量改成逐条（或让缓存事件不生效）⇒ {@link #投币后立即读详情_缓存写后读回填链路通()}
 *       会读到陈旧的 0。</li>
 * </ul>
 *
 * <p>⚠️ 本类需要**真媒体行**：{@code AbstractCoinIntegrationTest#insertContent} 只插 content 行，
 * 读面会把它当"媒体损坏"跳过 / 404 —— 故用 {@code insertVideoContent} / {@code insertPostContent}。
 */
class CoinReadTests extends AbstractCoinIntegrationTest {

    /**
     * 用来钉"列表走**一趟批量**，不循环单条"（T40 红线③）。
     *
     * <p>值断言抓不到"把批量换成 for 循环"——两种实现返回的数字一样，差别只在往返次数。
     * 故这里对公开 Service 做 spy：列表请求应恰好一趟 {@code batchGetCoinCount}、
     * 单条读零次。
     */
    @MockitoSpyBean
    private CoinService coinService;

    /** reset 必须在被测请求之前（父类先清 DB / Redis，本方法再清桩）。 */
    @BeforeEach
    void resetCoinServiceSpy() {
        Mockito.reset(coinService);
    }

    // ========================================================================
    // 1. 详情（视频）：coin = 我投了几枚，coinCount = 公共计数
    // ========================================================================

    @Test
    @DisplayName("详情（视频）：投票者投 1 枚 ⇒ interaction.coin=1、coinCount=1，均与 DB 列复算一致")
    void 详情视频_投币后coin与coinCount都与DB一致() {
        TestUser author = registerUser("13900004001", "coinread_author");
        TestUser voter = registerUser("13900004011", "coinread_voter");
        setCoinDeci(voter.id(), 10);                       // 1.0 枚，够投 1 枚
        long contentId = insertVideoContent(author.id(), "读面详情视频");

        assertThat(postForm("/coin/add", coinForm(contentId, 1), voter.token()).getStatusCode().value())
                .as("前置：投币必须成功，否则本用例没有判别力").isEqualTo(200);

        ResponseEntity<String> resp = get("/search/IdSearch?contentId=" + contentId, voter.token());
        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        JsonNode data = Envelope.data(resp);

        // 独立 oracle：两个 DB 列先复算一次业务口径，再与接口回显比对
        Integer amountInDb = amountOf(voter.id(), contentId);
        int countInDb = coinCountOf(contentId);
        assertThat(amountInDb).as("content_coin.amount：投票者投了 1 枚").isEqualTo(1);
        assertThat(countInDb).as("content.coin_count：内容累计 1 枚").isEqualTo(1);

        assertThat(data.path("interaction").isObject()).as("登录 ⇒ interaction 是对象").isTrue();
        assertThat(data.path("interaction").path("coin").asInt())
                .as("★ interaction.coin = 我投了几枚，与 content_coin.amount 一致")
                .isEqualTo(amountInDb);
        assertThat(data.path("coinCount").asLong())
                .as("★ coinCount = 公共计数，与 content.coin_count 一致")
                .isEqualTo(countInDb);
    }

    // ========================================================================
    // 2. 详情（图文）：该维不适用 ⇒ null（不是 0），但公共计数照常有值
    // ========================================================================

    @Test
    @DisplayName("详情（图文）：interaction.coin 是 null（该维不适用，不是 0）；coinCount 有值（0）")
    void 详情图文_coin为null但coinCount有值() {
        TestUser author = registerUser("13900004002", "coinread_post_author");
        TestUser viewer = registerUser("13900004012", "coinread_post_viewer");
        long contentId = insertPostContent(author.id(), "读面详情图文");

        JsonNode data = Envelope.data(get("/search/IdSearch?contentId=" + contentId, viewer.token()));

        JsonNode interaction = data.path("interaction");
        assertThat(interaction.isObject()).as("登录 ⇒ interaction 是对象").isTrue();
        assertThat(interaction.has("coin"))
                .as("★ 键必须在（键集恒四维），值是 null —— 不能用'缺键'表达不适用").isTrue();
        assertThat(interaction.path("coin").isNull())
                .as("★ 图文帖不适用投币维 ⇒ null（不是 0：0 会是'视频但没投过'的意思）").isTrue();

        assertThat(data.path("coinCount").isNumber())
                .as("公共计数与内容类型无关，图文也必须有 coinCount").isTrue();
        assertThat(data.path("coinCount").asLong())
                .as("独立 oracle：图文内容没人投过 ⇒ 0").isEqualTo(coinCountOf(contentId));
    }

    // ========================================================================
    // 3. 匿名：interaction 整体 null；coinCount 仍在
    // ========================================================================

    @Test
    @DisplayName("匿名详情：interaction 整体 null（不是四维全 null 的空壳）；coinCount 仍返回")
    void 匿名详情_interaction整体为null但coinCount仍在() {
        TestUser author = registerUser("13900004003", "coinread_anon_author");
        long contentId = insertVideoContent(author.id(), "读面匿名视频");

        ResponseEntity<String> resp = get("/search/IdSearch?contentId=" + contentId, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode data = Envelope.data(resp);

        assertThat(data.has("interaction")).as("匿名也输出 interaction 键（值为 null）").isTrue();
        assertThat(data.path("interaction").isNull())
                .as("★ 匿名 ⇒ 整体 null（不是 {}、不是四个 null 的空壳）").isTrue();
        assertThat(data.path("interaction").isObject()).isFalse();

        assertThat(data.path("coinCount").isNumber())
                .as("★ 公共计数与登录态无关 ⇒ 匿名也返回").isTrue();
        assertThat(data.path("coinCount").asLong())
                .as("独立 oracle：无人投币 ⇒ 0").isEqualTo(coinCountOf(contentId));
    }

    // ========================================================================
    // 4. 列表：coin 恒 null，coinCount 与 DB 一致（含匿名也要有 coinCount）
    // ========================================================================

    @Test
    @DisplayName("列表：/start 与 /search 的 interaction.coin 恒 null；coinCount 与 DB 一致（匿名也有）")
    void 列表coin恒null且coinCount与DB一致() {
        TestUser author = registerUser("13900004004", "coinread_list_author");
        TestUser voter = registerUser("13900004014", "coinread_list_voter");
        setCoinDeci(voter.id(), 10);
        long contentId = insertVideoContent(author.id(), "coinreadlist 读面列表内容");

        assertThat(postForm("/coin/add", coinForm(contentId, 1), voter.token()).getStatusCode().value())
                .as("前置：先给内容投 1 枚，coinCount 才有非零判别力").isEqualTo(200);
        int countInDb = coinCountOf(contentId);
        assertThat(countInDb).as("前置：DB 计数为 1").isEqualTo(1);

        // ---- /start（登录）：item 的 coin 恒 null、coinCount 有值 ----
        JsonNode startItem = itemOf(Envelope.data(get("/start", voter.token())), contentId);
        assertThat(startItem.path("interaction").isObject())
                .as("登录 ⇒ interaction 仍是对象").isTrue();
        assertThat(startItem.path("interaction").path("coin").isNull())
                .as("★ 列表不查投币维 ⇒ coin 恒 null（契约，不是漏填）").isTrue();
        assertThat(startItem.path("coinCount").asLong())
                .as("/start 的 coinCount 与 content.coin_count 一致").isEqualTo(countInDb);

        // ---- /search/keywordSearch（登录）：同样恒 null ----
        JsonNode searchItem = itemOf(
                Envelope.data(get("/search/keywordSearch?keyword=coinreadlist", voter.token())).path("list"),
                contentId);
        assertThat(searchItem.path("interaction").isObject()).isTrue();
        assertThat(searchItem.path("interaction").path("coin").isNull())
                .as("★ /search 的 coin 也恒 null").isTrue();
        assertThat(searchItem.path("coinCount").asLong())
                .as("/search 的 coinCount 与 DB 一致").isEqualTo(countInDb);

        // ---- /start（匿名）：interaction 整体 null，但**公共计数仍然在** ----
        JsonNode anonItem = itemOf(Envelope.data(get("/start", null)), contentId);
        assertThat(anonItem.path("interaction").isNull())
                .as("匿名 ⇒ interaction 整体 null").isTrue();
        assertThat(anonItem.path("coinCount").asLong())
                .as("★ 匿名列表也必须显示 coinCount（它不看登录态）").isEqualTo(countInDb);
    }

    // ========================================================================
    // 5. /profile：coin 恒 null；coinCount 与 DB 一致
    // ========================================================================

    @Test
    @DisplayName("主页：条目 interaction.coin 恒 null；coinCount 与 content.coin_count 一致")
    void 主页coin恒null且coinCount与DB一致() {
        TestUser author = registerUser("13900004005", "coinread_profile_author");
        TestUser voter = registerUser("13900004015", "coinread_profile_voter");
        setCoinDeci(voter.id(), 10);
        long contentId = insertVideoContent(author.id(), "读面主页内容");

        assertThat(postForm("/coin/add", coinForm(contentId, 1), voter.token()).getStatusCode().value())
                .as("前置：投币成功").isEqualTo(200);
        int countInDb = coinCountOf(contentId);

        JsonNode page = Envelope.data(
                get("/profile?userId=" + author.id(), voter.token())).path("contentPage");
        JsonNode item = itemOf(page.path("list"), contentId);
        assertThat(item.path("interaction").isObject()).as("登录 ⇒ interaction 是对象").isTrue();
        assertThat(item.path("interaction").path("coin").isNull())
                .as("★ 主页条目不带投币维 ⇒ coin 恒 null").isTrue();
        assertThat(item.path("coinCount").asLong())
                .as("主页的 coinCount 与 DB 一致").isEqualTo(countInDb);
    }

    // ========================================================================
    // 6. 键集：详情 + 一个列表面各断言一次「恰好四维」
    // ========================================================================

    @Test
    @DisplayName("键集：详情与列表面的 interaction 都恰好 {isLiked,isFavorited,isFollowed,coin}")
    void 详情与列表的interaction恰好四维() {
        TestUser author = registerUser("13900004006", "coinread_keys_author");
        TestUser viewer = registerUser("13900004016", "coinread_keys_viewer");
        long contentId = insertVideoContent(author.id(), "读面键集内容");

        // 详情
        JsonNode detailInteraction = Envelope.data(
                get("/search/IdSearch?contentId=" + contentId, viewer.token())).path("interaction");
        assertThat(keysOf(detailInteraction))
                .as("★ 详情子对象键集：恰好四维（T40 起多出 coin）")
                .containsExactlyInAnyOrder("isLiked", "isFavorited", "isFollowed", "coin");
        assertThat(detailInteraction.path("coin").isInt())
                .as("视频详情：coin 是数量（未投过 ⇒ 0）").isTrue();

        // 列表面（/start）
        JsonNode listInteraction = itemOf(Envelope.data(get("/start", viewer.token())), contentId)
                .path("interaction");
        assertThat(keysOf(listInteraction))
                .as("★ 列表面子对象键集：同样恰好四维（coin 键在、值是 null）")
                .containsExactlyInAnyOrder("isLiked", "isFavorited", "isFollowed", "coin");
        assertThat(listInteraction.path("coin").isNull())
                .as("列表：coin 为 null 但**键仍在**").isTrue();
    }

    // ========================================================================
    // 7. 读的是缓存：投币后不重启、立即读详情就看到新值（AFTER_COMMIT 条件写链路）
    // ========================================================================

    @Test
    @DisplayName("★缓存链路：先读详情暖缓存（coin=0/coinCount=0）→ 投币 → 立即再读得 1/1")
    void 投币后立即读详情_缓存写后读回填链路通() {
        TestUser author = registerUser("13900004007", "coinread_cache_author");
        TestUser voter = registerUser("13900004017", "coinread_cache_voter");
        setCoinDeci(voter.id(), 10);
        long contentId = insertVideoContent(author.id(), "读面缓存视频");

        // 暖缓存：此时 user:coinAmount 会落**空标记**、content:coinCount 会落 "0"
        JsonNode before = Envelope.data(get("/search/IdSearch?contentId=" + contentId, voter.token()));
        assertThat(before.path("interaction").path("coin").asInt()).isZero();
        assertThat(before.path("coinCount").asLong()).isZero();

        assertThat(postForm("/coin/add", coinForm(contentId, 1), voter.token()).getStatusCode().value())
                .isEqualTo(200);

        // 不重启、不手动清缓存：读面必须立刻反映已提交的写。
        // 若 CoinChangedListener 不在 AFTER_COMMIT 条件写两个 key（或不清空标记），这里会读到 0。
        JsonNode after = Envelope.data(get("/search/IdSearch?contentId=" + contentId, voter.token()));
        assertThat(after.path("interaction").path("coin").asInt())
                .as("★ 我投了几枚 = 1（命中缓存新值 / 空标记已清）").isEqualTo(1);
        assertThat(after.path("coinCount").asLong())
                .as("★ 公共计数 = 1（条件 INCRBY 生效）").isEqualTo(1);

        // 独立 oracle 再钉一次：缓存值不是凭空来的
        assertThat(amountOf(voter.id(), contentId)).isEqualTo(1);
        assertThat(coinCountOf(contentId)).isEqualTo(1);
    }

    // ========================================================================
    // 8. ★ 列表走批量：一趟 batchGetCoinCount、单条零次（T40 红线③）
    // ========================================================================

    @Test
    @DisplayName("★列表的投币数是一趟批量：batchGetCoinCount 恰一次、getCoinCount 零次（不得循环单条）")
    void 列表投币数一趟批量不循环调单条() {
        TestUser author = registerUser("13900004008", "coinread_batch_author");
        TestUser viewer = registerUser("13900004018", "coinread_batch_viewer");
        long contentId = insertVideoContent(author.id(), "读面批量内容");
        setCoinCount(contentId, 2);

        // /start 的 data 本身就是数组（不是 {list:…}），同本类第 4 条用例。
        JsonNode list = Envelope.data(get("/start", viewer.token()));
        assertThat(itemOf(list, contentId).path("coinCount").asLong())
                .as("前置：列表里确有这条、且值是 2（否则调用次数断言没有意义）").isEqualTo(2);

        // ★ 判别力：把 fillCoinCountBatch 改成 for + getCoinCount，这里 times(1) 会变 times(N)；
        //   值断言抓不到这种退化（数字一样，只是往返 N 次）。
        verify(coinService, times(1)).batchGetCoinCount(anyCollection());
        verify(coinService, never()).getCoinCount(anyLong());
        verify(coinService, never()).getCoinAmount(anyLong(), anyLong());
    }

    // ========================================================================
    // 夹具
    // ========================================================================

    /** 投币端点：{@code POST + form}，带 {@code contentId} / {@code count}（同 CoinAddTests）。 */
    private static MultiValueMap<String, String> coinForm(long contentId, int count) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("contentId", String.valueOf(contentId));
        form.add("count", String.valueOf(count));
        return form;
    }

    /** 从列表节点里取指定 id 的条目（找不到直接失败——防"夹具没造上 / 被读面跳过"的假绿）。 */
    private static JsonNode itemOf(JsonNode list, long contentId) {
        for (JsonNode node : list) {
            if (node.path("id").asLong() == contentId) {
                return node;
            }
        }
        throw new AssertionError("列表里应包含内容 " + contentId + "，实际：" + list);
    }

    private static List<String> keysOf(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }
}
