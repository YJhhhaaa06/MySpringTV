package io.github.yjhhhaaa06.videoweb.content.controller;

import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.Public;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.common.web.PageParams;
import io.github.yjhhhaaa06.videoweb.content.cache.ContentCache;
import io.github.yjhhhaaa06.videoweb.content.model.ContentCountField;
import io.github.yjhhhaaa06.videoweb.content.model.vo.RecommendPageVO;
import io.github.yjhhhaaa06.videoweb.content.service.ContentCountFiller;
import io.github.yjhhhaaa06.videoweb.content.service.ContentStatusFiller;
import io.github.yjhhhaaa06.videoweb.interaction.model.InteractionDimension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 首页推荐（`T58` 契约变更，依据《接口契约.md》§2.3：{@code GET /api/v1/contents/recommended}，
 * 原 {@code /start}；S5）。翻页口径见
 * {@code architecture/domain/content/content-推荐流.md}。
 *
 * <h2>★ 本端点没有 Service 层（三个"真实陷阱"之一）</h2>
 * TV 的 {@code StartController} **直接调 {@code ContentCache}** + {@code ContentStatusFiller}
 * ——缓存类承担了业务装配。迁移时不能"只搬 Cache 不搬语义"：
 * {@code type}/{@code categoryId} 的参数校验、随机序、游标解码、惰性探测，
 * 以及"Redis 挂掉 ⇒ 空推荐"的降级，全都在 {@link ContentCache} 里。
 * 故新实现保持同一形状（Controller → Cache），**不**为了"对称"而硬造一个 Service：
 * 硬造一层只会把校验与取页搬来搬去，反而偏离 TV 的行为位置。
 *
 * <h2>分页：游标，不是页码</h2>
 * 响应是 {@code {list, nextCursor}}（不是裸数组）。首页无限加载只需要"下一页"，
 * 不需要总数，也不需要页码 —— 随机序里页码没有稳定含义
 * （见 {@code architecture/domain/content/content-推荐流.md} §2.3）。
 * {@code pageSize} 缺省 {@value #RECOMMEND_PAGE_SIZE_DEFAULT}、上限 {@value #RECOMMEND_PAGE_SIZE_MAX}；
 * {@code cursor} 由上一页给出，**原样回传**即可，客户端不得解析。
 * ⚠️ 旧的 {@code limit} 参数**不识别**（传了不生效）——它从来不是契约，分页一律走 {@code pageSize}。
 *
 * <h2>鉴权：公开</h2>
 * TV 清单不含 {@code /start} ⇒ 匿名可访问；带 token 则填充互动态
 * （{@code @CurrentUserId(required = false)}）；匿名 ⇒ 每条 {@code "interaction": null}。
 *
 * <h2>★ 一个"看起来像 bug、其实是规格"的点（勿改）</h2>
 * <b>非法 {@code type}/{@code categoryId} 静默变 null</b>：TV 的 {@code parseParam}
 * 对非数字**不报错**，一律返回 null（= 不过滤）。而 {@code type}/{@code categoryId}
 * 的**取值范围**校验（{@code 1..2} / {@code 0..9}）在缓存层内做，越界抛
 * {@code ParamException} → 400。两个"非法"的定义不同：**语法**非法静默、
 * **语义**非法报错——保持这个不对称。
 */
@RestController
public class StartController {

    /** 首页一屏的缺省条数：5 列 × 4 行。 */
    private static final int RECOMMEND_PAGE_SIZE_DEFAULT = 20;

    /** 单页上限：再大就接近"一次拉全站"，失去无限加载的意义。 */
    private static final int RECOMMEND_PAGE_SIZE_MAX = 50;

    /**
     * ★ 首页带哪几维互动态（R-13 的**编译期常量**；改这一行即可，不破契约）。
     *
     * <p>只带 {@code FOLLOW}：对标 B站 首页卡片只显示「已关注」，不显示点赞 / 收藏
     * ⇒ 那两维本面**不查**（对应 JSON 里是 {@code null}，前端静默忽略）。
     * ⚠️ 这是**常量**不是契约：响应键集恒四维（{@code interaction} 里未选中的
     * {@code isLiked} / {@code isFavorited} 为 {@code null}，{@code coin} 作为第四维**恒**
     * {@code null} —— 列表不查该维），将来要加回来只改本行。
     */
    private static final Set<InteractionDimension> START_DIMENSIONS = Set.of(InteractionDimension.FOLLOW);

    /** 首页 / 相关推荐都是**网格卡片**：只显示播放量（GAP-01）。 */
    private static final Set<ContentCountField> START_COUNT_FIELDS = Set.of(ContentCountField.PLAY);

    private final ContentCache contentCache;
    private final ContentStatusFiller contentStatusFiller;
    private final ContentCountFiller contentCountFiller;

    public StartController(ContentCache contentCache, ContentStatusFiller contentStatusFiller,
                           ContentCountFiller contentCountFiller) {
        this.contentCache = contentCache;
        this.contentStatusFiller = contentStatusFiller;
        this.contentCountFiller = contentCountFiller;
    }

    /**
     * 首页推荐（{@code GET /api/v1/contents/recommended?cursor=&pageSize=&type=&categoryId=}）。
     *
     * <p>★ 路由优先级（Spring PathPattern：字面量段优先于 {@code {id}}）：本路径不会落进
     * {@code GET /api/v1/contents/{id}}（详情）——`T58` 起两者共存于 SearchController 同前缀。
     *
     * @param cursor     上一页的 {@code nextCursor}；缺省 / 非法 ⇒ 新会话（第一页）
     * @param pageSize   条数；缺省 {@value #RECOMMEND_PAGE_SIZE_DEFAULT}、上限
     *                   {@value #RECOMMEND_PAGE_SIZE_MAX}、非数字静默回落
     * @param type       内容类型：1=视频 2=图文；空 / 0 / 非数字 ⇒ 不过滤
     * @param categoryId 分区 id：0..9；空 / 非数字 ⇒ 不过滤
     * @return 一页推荐 + 下一页游标（可能为空：无内容 / 索引装载失败降级 / 已翻到底）
     */
    @Public
    @GetMapping("/api/v1/contents/recommended")
    public ApiResponse<RecommendPageVO> start(@CurrentUserId(required = false) Long userId,
                                              @RequestParam(required = false) String cursor,
                                              @RequestParam(required = false) String pageSize,
                                              @RequestParam(required = false) String type,
                                              @RequestParam(required = false) String categoryId) {
        RecommendPageVO page = contentCache.getRecommendPage(
                PageParams.parseIntOrNull(type),
                PageParams.parseIntOrNull(categoryId),
                cursor,
                PageParams.normalizePageSize(pageSize, RECOMMEND_PAGE_SIZE_MAX, RECOMMEND_PAGE_SIZE_DEFAULT));
        contentStatusFiller.fillInteractionBatch(page.list(), userId, START_DIMENSIONS);
        // GAP-01：网格卡片只显示播放量；公共计数不看登录态（匿名同样填）。
        contentCountFiller.fillCountsBatch(page.list(), START_COUNT_FIELDS);
        return ApiResponse.success(page);
    }
}
