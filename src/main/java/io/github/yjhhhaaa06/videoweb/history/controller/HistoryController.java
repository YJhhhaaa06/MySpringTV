package io.github.yjhhhaaa06.videoweb.history.controller;

import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.history.service.HistoryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 播放记录接口入口（路径前缀 {@code /history}，播放记录专项 T47）。
 *
 * <h2>★ 鉴权：**类级** {@code @RequiresLogin}（与 {@code /watchlater} 的逐端点刻意不同）</h2>
 * 本域**全部**端点（含下一批的 {@code /list}、{@code /position}）都只对"当前登录用户"有意义：
 * 没有公开面可言。类级注解的语义因此是准确的，也是**面向未来端点**的保护——
 * 将来往 {@code /history/**} 加端点会被自动保护。判别力由
 * {@code SecurityContractTests#history写端点需登录} 直接对 {@code RequestMappingLookup} 求值守着
 * （HTTP 侧 401 是假绿：{@code @CurrentUserId} 解析器自己会抛 401）。
 *
 * <h2>请求形态：写 {@code POST} + {@code x-www-form-urlencoded}</h2>
 * 沿袭 {@code /like/*}、{@code /watchlater/*} 的既有风格（{@code @RequestParam} 同时覆盖 form 与 query）。
 *
 * <h2>成功信封</h2>
 * 两个端点都返回空数据体（{@code {"code":200,"msg":"success"}}）；
 * 错误码见 {@code history.md} §八（400 负进度 / 图文、404 内容不存在、401 未登录）。
 *
 * <h2>分批交付说明</h2>
 * 本批（T47）只落**写路径**两个端点；{@code /list}（历史列表）与 {@code /position}（续播位置）
 * 属于下一批读面。播放量虽然已在写，但**详情的 {@code playCount} 字段也在下一批**
 * ⇒ 本批结束时"播放量写进去了但还看不见"，这是计划内的中间态（每个 commit 仍可编译可测）。
 */
@RestController
@RequestMapping("/history")
@RequiresLogin
public class HistoryController {

    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

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
