package io.github.yjhhhaaa06.videoweb.content.controller;

import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.Public;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.common.web.PageParams;
import io.github.yjhhhaaa06.videoweb.content.model.ContentType;
import io.github.yjhhhaaa06.videoweb.content.model.SearchOrder;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentDetailVO;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搜索与详情接口（S5；`T58` 契约变更，依据《接口契约.md》§2.3 起路径资源化）。
 *
 * <p>迁移自 TV {@code com.itheima.content.controller.SearchController}——对照片段：
 * <table>
 *   <caption>TV → 新实现</caption>
 *   <tr><th>TV</th><th>新实现</th></tr>
 *   <tr><td>{@code @WebServlet("/search/*")} + {@code switch(pathInfo)}（{@code /IdSearch} / {@code /keywordSearch}）</td>
 *       <td>{@code @RestController} + {@code @GetMapping}（`T58` 起：详情 = {@code GET /api/v1/contents/{id}}、
 *       搜索 = {@code GET /api/v1/contents}——keyword 留 query）</td></tr>
 *   <tr><td>{@code (Long) req.getAttribute("userId")}（**可空**：公开端点，带 token 则个性化）</td>
 *       <td>{@code @CurrentUserId(required = false) Long userId}（S5 为此给注解加了 {@code required}）</td></tr>
 *   <tr><td>{@code Content-Length > 0} 时改读 JSON body（{@code RequestParser.parse}）</td>
 *       <td>**只支持 query 参数**——GET 带 body 不是有效用法；见下方"刻意收窄"</td></tr>
 *   <tr><td>{@code BaseServletUtil.parsePage / parsePageSize(req,100,100)}</td>
 *       <td>{@link PageParams}（口径逐字相同）</td></tr>
 * </table>
 *
 * <h2>鉴权：公开（无 {@code @RequiresLogin}）</h2>
 * TV {@code AuthFilter} 的两份清单都不含 {@code /search/**} ⇒ 匿名可访问。
 * ★ 与 {@code @CurrentUserId(required = false)} 配套：**公开端点不得标 {@code @RequiresLogin}**
 * （两者语义相反），已由 {@code SecurityContractTests} 的"公开端点不需登录"固化为契约。
 *
 * <h2>三处刻意的契约差异（均属"改进"而非回归，逐条记录）</h2>
 * <ol>
 *   <li><b>非法 {@code contentId} 由 500 变 400</b>：TV 的 {@code Long.parseLong} 抛
 *       {@code NumberFormatException} 未被捕获 ⇒ 500。新实现用 {@code Long} 参数绑定，
 *       由全局出口转 400。状态码更正确。
 *       ★ `T58` 后"缺 {@code contentId} → 400"的形态随 {@code contentId} 进路径而消失
 *       （少路径段 = 无路由匹配 = 404，不可表达；TV 文案 {@code "contentId不能为空"} 一并退役）。</li>
 *   <li><b>不再支持 GET + JSON body</b>：TV 为兼容某个前端写法保留了该分支。
 *       新实现按方法声明语义（{@code GET} 用 query）。旧 pytest 的两处调用都是 query。</li>
 *   <li><b>{@code total} 仍是 SQL 命中总数</b>（不是返回条数）——若某条内容在此刻被删，
 *       list 会短一条而 total 不变。TV 原样，不再是"缺陷"而是**已固化的语义**。</li>
 * </ol>
 */
@RestController
public class SearchController {

    /** T19：search 域 pageSize 上限（原为该域"无上限"的唯一分页入口）。 */
    private static final int SEARCH_PAGE_SIZE_MAX = 100;

    /** T19：search 域**信封大小**——前端只传 {@code page} 时后端返回的条数（原为硬编码缺省 12）。 */
    private static final int SEARCH_PAGE_SIZE_DEFAULT = 100;

    private final ContentService contentService;

    public SearchController(ContentService contentService) {
        this.contentService = contentService;
    }

    /**
     * 按 id 查内容详情（`T58` 契约变更：{@code GET /api/v1/contents/{id}}，原 {@code /search/IdSearch?contentId=}）。
     *
     * <p>内容不存在 / 已软删 / 媒体损坏 → 404（TV 文案 {@code "找不到对应内容"}）。
     */
    @Public
    @GetMapping("/api/v1/contents/{id}")
    public ApiResponse<ContentDetailVO> idSearch(@CurrentUserId(required = false) Long userId,
                                                 @PathVariable("id") Long contentId) {
        ContentDetailVO detail = contentService.getContentDetailVO(contentId, userId);
        if (detail == null) {
            throw new NotFoundException(ErrorCode.CONTENT_NOT_FOUND, "找不到对应内容");
        }
        return ApiResponse.success(detail);
    }

    /**
     * 关键词搜索（`T58` 契约变更：{@code GET /api/v1/contents?keyword=&page=&pageSize=}，
     * 原 {@code /search/keywordSearch}——keyword 与分页均留 query）。
     *
     * <p>关键词空白 → 400（TV 文案 {@code "输入不能为空"}）。
     * 分页：缺省 == 显式 {@code page=1&pageSize=100}；{@code pageSize=999 → 100}；
     * {@code pageSize=51} 原样回显；非法值回落缺省（**不是 500**，T19 顺带修掉的旧坑）。
     *
     * <h2>★ `GAP-10`（2026-10-27）：两个**可选**筛选 / 排序参数</h2>
     * <ul>
     *   <li>{@code type=VIDEO|POST} —— 内容类型筛选；缺省 / 空串 = **不筛**（"综合"）。</li>
     *   <li>{@code order=LATEST|PLAY} —— 排序；缺省 {@code LATEST}
     *       = {@code create_time DESC, id DESC}，**与加参数前逐字一致**。</li>
     * </ul>
     * 两个参数都**只影响 SQL 的 WHERE / ORDER BY**：命中总数与当页 id 来自同一组条件
     * （{@code countKeywordSearch} 与 {@code keywordSearchInBrief} 共用 mapper 片段），
     * 否则 {@code total} 会按全类型算、列表按筛选后算 ⇒ 页码多出来、最后一页可能空。
     *
     * <h2>为什么声明成枚举，而不是 {@code String} 再自己判</h2>
     * 非法值由 Spring 抛 {@code MethodArgumentTypeMismatchException} → 全局出口的
     * {@code 400 + errors[{field:"type"}]}（契约 §2.5.1 类目④），**不写一行校验代码**。
     * 收 {@code String} 再手判会落进 `I-26`（手写校验不产 {@code errors}），且要写两段。
     *
     * <p>★ <b>大小写敏感</b>：Spring 的枚举转换就是 {@code Enum.valueOf} 精确匹配 ⇒
     * {@code type=video} 是 400，不是"宽容当 VIDEO"。故意不加大小写归一：Body 侧枚举
     * （Jackson）同样精确匹配，两套口径不一致比"严格"更坏。
     *
     * <p>★ <b>为什么不静默回落</b>（像 {@code page}/{@code pageSize} 那样）：分页错只是少几条，
     * 而筛选错会**让用户以为"没有这类内容"** —— 必须响亮地 400。
     *
     * <p>⚠️ 已知的词汇不统一：{@code GET /contents/recommended?type=1|2} 用**数字**（TV 遗留：
     * 空 / 0 / 非数字 ⇒ 不过滤），本端点按契约 §2.5「枚举用语义字符串」定成 {@code VIDEO|POST}。
     * 两者故意不统一；recommended 那套是死口径（前端未消费），删改它属另一个决定。
     *
     * <p>⚠️ <b>{@code type=POST&order=PLAY} 不设特例</b>（2026-10-27 用户拍板）：图文的
     * {@code content.play_count} **结构性恒 0**（{@code /play} 只收视频，见
     * {@code HistoryService#requireVideoContent}）⇒ 该组合的次序退化为 {@code id DESC}（= 最新）。
     * 后端**照收不报错**（少一条只在一种组合下成立的校验），由前端**不为图文渲染「最多播放」**。
     * 这是登记过的已知代价，不是缺陷。
     */
    @Public
    @GetMapping("/api/v1/contents")
    public ApiResponse<PageResult<ContentVO>> keywordSearch(@CurrentUserId(required = false) Long userId,
                                                            @RequestParam(required = false) String keyword,
                                                            @RequestParam(required = false) ContentType type,
                                                            @RequestParam(required = false) SearchOrder order,
                                                            @RequestParam(required = false) String page,
                                                            @RequestParam(required = false) String pageSize) {
        if (keyword == null || keyword.isBlank()) {
            throw new ParamException(ErrorCode.PARAM_INVALID, "输入不能为空");
        }
        return ApiResponse.success(contentService.search(
                keyword.trim(),
                userId,
                PageParams.normalizePage(page),
                PageParams.normalizePageSize(pageSize, SEARCH_PAGE_SIZE_MAX, SEARCH_PAGE_SIZE_DEFAULT),
                type,
                order));
    }
}
