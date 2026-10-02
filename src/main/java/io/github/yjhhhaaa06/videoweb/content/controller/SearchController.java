package io.github.yjhhhaaa06.videoweb.content.controller;

import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.common.web.PageParams;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentDetailVO;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搜索与详情接口（S5）。
 *
 * <p>迁移自 TV {@code com.itheima.content.controller.SearchController}——对照片段：
 * <table>
 *   <caption>TV → 新实现</caption>
 *   <tr><th>TV</th><th>新实现</th></tr>
 *   <tr><td>{@code @WebServlet("/search/*")} + {@code switch(pathInfo)}（{@code /IdSearch} / {@code /keywordSearch}）</td>
 *       <td>{@code @RestController} + {@code @GetMapping}（路径大小写逐字保留：<b>{@code IdSearch}</b> 首字母大写）</td></tr>
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
 *       由全局出口转 400。状态码更正确，且旧 pytest 只断言"缺 contentId → 400"。</li>
 *   <li><b>不再支持 GET + JSON body</b>：TV 为兼容某个前端写法保留了该分支。
 *       新实现按方法声明语义（{@code GET} 用 query）。旧 pytest 的两处调用都是 query。</li>
 *   <li><b>{@code total} 仍是 SQL 命中总数</b>（不是返回条数）——若某条内容在此刻被删，
 *       list 会短一条而 total 不变。TV 原样，不再是"缺陷"而是**已固化的语义**。</li>
 * </ol>
 */
@RestController
@RequestMapping("/search")
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
     * 按 id 查内容详情（{@code GET /search/IdSearch?contentId=X}）。
     *
     * <p>缺 {@code contentId} → 400（TV 文案 {@code "contentId不能为空"}）；
     * 内容不存在 / 已软删 / 媒体损坏 → 404（TV 文案 {@code "找不到对应内容"}）。
     */
    @GetMapping("/IdSearch")
    public ApiResponse<ContentDetailVO> idSearch(@CurrentUserId(required = false) Long userId,
                                                 @RequestParam(required = false) Long contentId) {
        if (contentId == null) {
            throw new ParamException("contentId不能为空");
        }
        ContentDetailVO detail = contentService.getContentDetailVO(contentId, userId);
        if (detail == null) {
            throw new NotFoundException("找不到对应内容");
        }
        return ApiResponse.success(detail);
    }

    /**
     * 关键词搜索（{@code GET /search/keywordSearch?keyword=&page=&pageSize=}）。
     *
     * <p>关键词空白 → 400（TV 文案 {@code "输入不能为空"}）。
     * 分页：缺省 == 显式 {@code page=1&pageSize=100}；{@code pageSize=999 → 100}；
     * {@code pageSize=51} 原样回显；非法值回落缺省（**不是 500**，T19 顺带修掉的旧坑）。
     */
    @GetMapping("/keywordSearch")
    public ApiResponse<PageResult<ContentVO>> keywordSearch(@CurrentUserId(required = false) Long userId,
                                                            @RequestParam(required = false) String keyword,
                                                            @RequestParam(required = false) String page,
                                                            @RequestParam(required = false) String pageSize) {
        if (keyword == null || keyword.isBlank()) {
            throw new ParamException("输入不能为空");
        }
        return ApiResponse.success(contentService.search(
                keyword.trim(),
                userId,
                PageParams.normalizePage(page),
                PageParams.normalizePageSize(pageSize, SEARCH_PAGE_SIZE_MAX, SEARCH_PAGE_SIZE_DEFAULT)));
    }
}
