package io.github.yjhhhaaa06.videoweb.content.controller;

import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.common.web.PageParams;
import io.github.yjhhhaaa06.videoweb.content.cache.ContentCache;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.content.service.ContentStatusFiller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
 * TV 清单不含 {@code /start} ⇒ 匿名可访问；带 token 则填充 isLiked / isFollowed
 * （{@code @CurrentUserId(required = false)}）。
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
    @GetMapping("/start")
    public ApiResponse<List<ContentVO>> start(@CurrentUserId(required = false) Long userId,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(required = false) String categoryId) {
        List<ContentVO> recommend = contentCache.getRecommendByFilter(
                PageParams.parseIntOrNull(type),
                PageParams.parseIntOrNull(categoryId),
                RECOMMEND_LIMIT);
        contentStatusFiller.fillLikeAndFollowBatch(recommend, userId);
        return ApiResponse.success(recommend);
    }
}
