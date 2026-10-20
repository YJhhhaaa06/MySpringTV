package io.github.yjhhhaaa06.videoweb.content.controller;

import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.Public;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.common.web.PageParams;
import io.github.yjhhhaaa06.videoweb.content.cache.ContentCache;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.content.service.ContentStatusFiller;
import io.github.yjhhhaaa06.videoweb.interaction.model.InteractionDimension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 首页推荐（{@code GET /start}，S5）。
 *
 * <h2>★ 本端点没有 Service 层（三个"真实陷阱"之一）</h2>
 * TV 的 {@code StartController} **直接调 {@code ContentCache.getRecommendByFilter}** +
 * {@code ContentStatusFiller}——缓存类承担了业务装配。迁移时不能"只搬 Cache 不搬语义"：
 * {@code type}/{@code categoryId} 的参数校验、去重、shuffle、惰性探测、
 * 以及"Redis 挂掉 ⇒ 空推荐"的降级，全都在 {@link ContentCache} 里。
 * 故新实现保持同一形状（Controller → Cache），**不**为了"对称"而硬造一个 Service：
 * 硬造一层只会把校验与随机抽样搬来搬去，反而偏离 TV 的行为位置。
 *
 * <h2>鉴权：公开</h2>
 * TV 清单不含 {@code /start} ⇒ 匿名可访问；带 token 则填充互动态
 * （{@code @CurrentUserId(required = false)}）；匿名 ⇒ 每条 {@code "interaction": null}。
 *
 * <h2>★ 两个"看起来像 bug、其实是规格"的点（勿改）</h2>
 * <ol>
 *   <li><b>请求参数 {@code limit} 被忽略</b>：TV 固定取 {@code 12} 条。
 *       旧 pytest {@code S-04} 就是传 {@code ?limit=10} 然后断言"非空列表"——它并不校验条数。
 *       新实现保持固定 12 条（{@link #RECOMMEND_LIMIT}），不"顺手"支持 limit。</li>
 *   <li><b>非法 {@code type}/{@code categoryId} 静默变 null</b>：TV 的 {@code parseParam}
 *       对非数字**不报错**，一律返回 null（= 不过滤）。而 {@code type}/{@code categoryId}
 *       的**取值范围**校验（{@code 1..2} / {@code 0..9}）在缓存层内做，越界抛
 *       {@code ParamException} → 400。两个"非法"的定义不同：**语法**非法静默、
 *       **语义**非法报错——保持这个不对称。</li>
 * </ol>
 */
@RestController
public class StartController {

    /** 首页固定推荐条数。TV 硬编码 12；请求参数 {@code limit} **有意忽略**（见类注释）。 */
    private static final int RECOMMEND_LIMIT = 12;

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

    private final ContentCache contentCache;
    private final ContentStatusFiller contentStatusFiller;

    public StartController(ContentCache contentCache, ContentStatusFiller contentStatusFiller) {
        this.contentCache = contentCache;
        this.contentStatusFiller = contentStatusFiller;
    }

    /**
     * 首页推荐（{@code GET /start?type=&categoryId=}）。
     *
     * @param type       内容类型：1=视频 2=图文；空 / 0 / 非数字 ⇒ 不过滤
     * @param categoryId 分区 id：0..9；空 / 非数字 ⇒ 不过滤
     * @return 推荐列表（可能为空：无内容 / 索引装载失败降级）
     */
    @Public
    @GetMapping("/start")
    public ApiResponse<List<ContentVO>> start(@CurrentUserId(required = false) Long userId,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(required = false) String categoryId) {
        List<ContentVO> recommend = contentCache.getRecommendByFilter(
                PageParams.parseIntOrNull(type),
                PageParams.parseIntOrNull(categoryId),
                RECOMMEND_LIMIT);
        contentStatusFiller.fillInteractionBatch(recommend, userId, START_DIMENSIONS);
        // T43：列表不再返回任何公共计数（计数只在详情平铺）⇒ 这里不再有第二趟批量。
        return ApiResponse.success(recommend);
    }
}
