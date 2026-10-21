package io.github.yjhhhaaa06.videoweb.history.controller;

import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.common.web.PageParams;
import io.github.yjhhhaaa06.videoweb.history.model.vo.HistoryItemVO;
import io.github.yjhhhaaa06.videoweb.history.model.vo.HistoryPositionVO;
import io.github.yjhhhaaa06.videoweb.history.service.HistoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 播放记录接口入口（路径前缀 {@code /history}，播放记录专项 T47）。
 *
 * <h2>★ 鉴权：**类级** {@code @RequiresLogin}（与 {@code /watchlater} 的逐端点刻意不同）</h2>
 * 本域**全部四个端点**（{@code /report}、{@code /play}、{@code /list}、{@code /position}）
 * 都只对"当前登录用户"有意义：没有公开面可言。类级注解的语义因此是准确的，也是**面向未来端点**的保护——
 * 将来往 {@code /history/**} 加端点会被自动保护。判别力由
 * {@code SecurityContractTests#history写端点需登录} 直接对 {@code RequestMappingLookup} 求值守着
 * （HTTP 侧 401 是假绿：{@code @CurrentUserId} 解析器自己会抛 401）。
 *
 * <h2>请求形态：写 {@code POST} + {@code x-www-form-urlencoded}、读 {@code GET} + query</h2>
 * 沿袭 {@code /like/*}、{@code /watchlater/*} 的既有风格（{@code @RequestParam} 同时覆盖 form 与 query）。
 *
 * <h2>成功信封</h2>
 * 两个写端点返回空数据体（{@code {"code":200,"msg":"success"}}）；{@code /list} 返回分页信封，
 * {@code /position} 返回 {@code {"progress":N}}。错误码见 {@code history.md} §八
 * （400 负进度 / 图文、404 内容不存在、401 未登录）。
 *
 * <h2>分批交付说明</h2>
 * 写路径两段（T47）+ 列表（{@code /list}）+ 续播位置（{@code /position}）构成四端点全集；
 * 读端点用 {@code GET} ⇒ **不登记限流权重**（权重表只覆盖非 GET）。
 */
@RestController
@RequestMapping("/history")
@RequiresLogin
public class HistoryController {

    /** 列表 pageSize 上限（各域自持常量，见 {@code PageParams}）。与 {@code FavoriteController} 同量级取 100。 */
    private static final int HISTORY_PAGE_SIZE_MAX = 100;

    /** 列表 pageSize 缺省：一屏 20 条（{@code history-需求.md} §二「分页」）。 */
    private static final int HISTORY_PAGE_SIZE_DEFAULT = 20;

    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    // ==================== 读：历史列表 ====================

    /**
     * 历史列表分页（**最后观看时间倒序**，同一秒按内容 id 倒序）。
     * {@code data} 是分页信封 {@code {list,total,page,pageSize,totalPages}}；
     * ★ 失效条目返回占位（{@code invalid=true}、标题「内容已失效」、封面 / 作者 / 时长 {@code null}），
     * 但**保留 {@code contentId} 与 {@code progress}**。
     *
     * <p>分页单位是 {@code watch_history} 行：{@code total} **包含**失效条目，一整页都是失效条目也照常返回满页。
     * 空历史 ⇒ {@code list=[]} + {@code total=0}（不 404）。
     *
     * <p>分页归一：{@code page} 缺省/非数字/≤0 → 1；{@code pageSize} 同上 → 20，且恒 ≤ 100。
     */
    @GetMapping("/list")
    public ApiResponse<PageResult<HistoryItemVO>> list(@CurrentUserId long userId,
                                                       @RequestParam(required = false) String page,
                                                       @RequestParam(required = false) String pageSize) {
        return ApiResponse.success(historyService.list(userId,
                PageParams.normalizePage(page),
                PageParams.normalizePageSize(pageSize, HISTORY_PAGE_SIZE_MAX, HISTORY_PAGE_SIZE_DEFAULT)));
    }

    // ==================== 读：续播位置 ====================

    /**
     * 续播位置：{@code GET /history/position?contentId=}，{@code data} 是 {@code {"progress":N}}。
     *
     * <p>★ <b>不校验内容、不返回 404</b>：内容已删 / 不存在都照常回答 —— 后者无记录 ⇒ {@code 0}。
     * 内容是死是活由详情的 404 负责（{@code history.md} §七.2）。
     */
    @GetMapping("/position")
    public ApiResponse<HistoryPositionVO> position(@CurrentUserId long userId,
                                                   @RequestParam long contentId) {
        return ApiResponse.success(new HistoryPositionVO(historyService.position(userId, contentId)));
    }

    // ==================== 写：report / play ====================

    /**
     * 上报播放进度：{@code POST /history/report?contentId=&progress=}（进度单位**秒**）。
     *
     * <p>★ **不在这里计播放量**——{@code /play} 是唯一计数入口。重复上报同一进度是幂等的
     * （但会把该条历史排到最前，见 {@code WatchHistoryDao#upsertProgress}）。
     *
     * @param progress 已看到的秒数（整数）；负数 ⇒ 400；超过时长按当前时长截断（不报错）
     */
    @PostMapping("/report")
    public ApiResponse<Void> report(@CurrentUserId long userId,
                                    @RequestParam long contentId,
                                    @RequestParam int progress) {
        historyService.report(userId, contentId, progress);
        return ApiResponse.success();
    }

    /**
     * 播放事件：{@code POST /history/play?contentId=}。
     *
     * <p>同一用户对该内容**永远只计一次**（一人一次口径）⇒ 重复调用幂等 200、播放量不变。
     * 行不存在时会建行（{@code progress=0}）—— 否则"先 play 后 report"会丢播放量。
     */
    @PostMapping("/play")
    public ApiResponse<Void> play(@CurrentUserId long userId,
                                  @RequestParam long contentId) {
        historyService.play(userId, contentId);
        return ApiResponse.success();
    }
}
