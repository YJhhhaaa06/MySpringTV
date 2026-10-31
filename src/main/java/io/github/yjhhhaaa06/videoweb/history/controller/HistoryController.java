package io.github.yjhhhaaa06.videoweb.history.controller;

import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.common.web.PageParams;
import io.github.yjhhhaaa06.videoweb.history.model.dto.ReportProgressRequest;
import io.github.yjhhhaaa06.videoweb.history.model.vo.HistoryItemVO;
import io.github.yjhhhaaa06.videoweb.history.model.vo.HistoryPositionVO;
import io.github.yjhhhaaa06.videoweb.history.service.HistoryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 播放记录接口入口（`T58` 起路径资源化，播放记录专项 T47）。
 *
 * <h2>路径（`T58` 契约变更，依据《接口契约.md》§2.3/§2.4）</h2>
 * <ul>
 *   <li>历史列表：{@code GET /api/v1/history}；</li>
 *   <li>删除 / 清空历史：{@code DELETE /api/v1/history}
 *       （`R-26`；批量 id 走 query、清空走 {@code all=true}）；</li>
 *   <li>续播位置 / 进度上报：{@code GET|PUT /api/v1/contents/{id}/progress}
 *       （{@code contentId} 进路径，{@code progress} 留 JSON body）；</li>
 *   <li>播放事件：{@code POST /api/v1/contents/{id}/play} —— §2.4 例外清单：
 *       播放事件非幂等、无自然键，保持 POST；</li>
 *   <li>退出信号：{@code POST /api/v1/history/flush} —— 同例外清单：
 *       粒度是 userId 全量而非单条内容，无 {@code {id}} 可资源化。</li>
 * </ul>
 *
 * <h2>★ 鉴权：**类级** {@code @RequiresLogin}</h2>
 * 本域**全部六个端点**都只对"当前登录用户"有意义：没有公开面可言。类级注解的语义因此是准确的，也是**面向未来端点**的保护——
 * 将来往本域加端点会被自动保护。判别力由
 * {@code SecurityContractTests#history写端点需登录} 直接对 {@code RequestMappingLookup} 求值守着
 * （HTTP 侧 401 是假绿：{@code @CurrentUserId} 解析器自己会抛 401）。
 *
 * <h2>请求形态：写 {@code POST|PUT|DELETE} + JSON body（进度字段）/ query（批量 id）、读 {@code GET} + query</h2>
 * `T59` 起进度覆盖走 JSON body（{@code ReportProgressRequest}）；{@code play} / {@code flush} 无入参；
 * report 是 **PUT**（幂等覆盖 / LWW，§2.4）；删除是 **DELETE**（幂等，重复删 = 204）。
 *
 * <h2>成功信封</h2>
 * 写端点返回 {@code ApiResponse<Void>}（{@code {"code":"OK","msg":"success"}}，无 {@code data}）；
 * ★ 删除返回 **204 无 body**（§2.4 的"重复 DELETE = 204"，与 {@code DELETE /favorites} 同形，
 * 访问日志记 {@code code=OK}）；历史列表返回分页信封，续播位置返回 {@code {"progress":N}}。
 * 错误码见 {@code history.md} §八（400 负进度 / 图文 / 删除参数、404 内容不存在、401 未登录）。
 *
 * <h2>端点全集</h2>
 * 写路径两段（T47）+ 列表 + 续播位置 + 删除构成六端点全集；
 * 读端点用 {@code GET} ⇒ **不登记限流权重**（权重表只覆盖非 GET）。
 */
@RestController
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
    @GetMapping("/api/v1/history")
    public ApiResponse<PageResult<HistoryItemVO>> list(@CurrentUserId long userId,
                                                       @RequestParam(required = false) String page,
                                                       @RequestParam(required = false) String pageSize) {
        return ApiResponse.success(historyService.list(userId,
                PageParams.normalizePage(page),
                PageParams.normalizePageSize(pageSize, HISTORY_PAGE_SIZE_MAX, HISTORY_PAGE_SIZE_DEFAULT)));
    }

    // ==================== 读：续播位置 ====================

    /**
     * 续播位置：{@code GET /api/v1/contents/{id}/progress}（`T58` 起 {@code contentId} 进路径），
     * {@code data} 是 {@code {"progress":N}}。
     *
     * <p>★ <b>不校验内容、不返回 404</b>：内容已删 / 不存在都照常回答 —— 后者无记录 ⇒ {@code 0}。
     * 内容是死是活由详情的 404 负责（{@code history.md} §七.2）。
     */
    @GetMapping("/api/v1/contents/{id}/progress")
    public ApiResponse<HistoryPositionVO> position(@CurrentUserId long userId,
                                                   @PathVariable("id") long contentId) {
        return ApiResponse.success(new HistoryPositionVO(historyService.position(userId, contentId)));
    }

    /**
     * 退出信号（`R-19` / `T51-2`）：{@code POST /api/v1/history/flush}，**无参数**（userId 取自登录态）。
     *
     * <p>★ 语义是"把这个用户的**全部**脏进度同步写进库"，粒度是 **userId 全量**而不是 contentId：
     * 客户端退出时本来就该全 flush，不用它记住"这次看了哪些"；多标签下任一标签退出全写一遍，
     * 比"各管各的"简单且不漏。这也是它在 §2.4 例外清单里保持 POST 且不挂 {@code {id}} 的原因。
     *
     * <p>★ 落库失败**仍返回 200**：数据还在缓冲里，轮询会兜底。返回 5xx 只会让客户端重试、
     * 放大流量，而重试并不能让数据库恢复（history-上报缓冲.md} §5）。
     */
    @PostMapping("/api/v1/history/flush")
    public ApiResponse<Void> flush(@CurrentUserId long userId) {
        historyService.flush(userId);
        return ApiResponse.success();
    }

    // ==================== 写：删除 / 清空 ====================

    /**
     * 删除历史（`R-26`）：{@code DELETE /api/v1/history}，两种形态**互斥**。
     *
     * <ul>
     *   <li>批量：{@code ?contentIds=1,2,3}（逗号分隔或重复参数，Spring 都绑定；去重后 ≤ 500）；</li>
     *   <li>清空：{@code ?all=true}（无 id）。</li>
     * </ul>
     *
     * <h2>★ 为什么"清空"必须显式 {@code all=true}，而不是"不传 contentIds"</h2>
     * 删除是**不可逆**的。让"少传一个参数"表达"删掉全部"，等于把一次前端 bug 变成一次数据事故；
     * 显式开关把这种 bug 退化成 **400**（"请指定要删除的记录，或用 all=true 清空历史"）。
     *
     * <h2>★ 为什么批量 id 走 query（而不是 body）</h2>
     * §2.5 定稿：DELETE 一律无 body。批量 id 是"选择参数"而非实体内容，与
     * {@code DELETE /api/v1/favorites?folderId=&contentIds=} 同形。
     *
     * <h2>为什么是 204 而不是 200 + 信封</h2>
     * §2.4：**重复 DELETE = 204**（不报 409 / 404）。"没有这条记录"与"删成功"在用户视角没有区别，
     * 而 404 会给"id 打错"开一个静默成功口。★ 但**缺参仍是 400** —— 那是调用方没说要删什么。
     *
     * @param contentIds 要删除的内容 id（`all=false` 时必填）
     * @param all        `true` = 清空该用户全部历史；与 `contentIds` 互斥
     */
    @DeleteMapping("/api/v1/history")
    public ResponseEntity<Void> delete(@CurrentUserId long userId,
                                       @RequestParam(required = false) List<Long> contentIds,
                                       @RequestParam(required = false) Boolean all) {
        historyService.delete(userId, contentIds, Boolean.TRUE.equals(all));
        return ResponseEntity.noContent().build();
    }

    // ==================== 写：progress / play ====================
    /**
     * 上报播放进度：{@code PUT /api/v1/contents/{id}/progress}（`T58` 起 {@code contentId} 进路径；
     * `T59` 载荷面：{@code progress} 改为 JSON body {@code {"progress": N}}，单位**秒**）。
     *
     * <p>**PUT**（`T58` 契约变更，依据《接口契约.md》§2.4）：重复上报同一进度是幂等的
     * （后到即新，LWW 覆盖语义）。★ `R-19`（T51-2）起**只写 Redis 缓冲，不落库**：
     * 落库由 {@code /api/v1/history/flush}（退出信号）或空闲轮询完成。
     *
     * <p>Redis 熔断 / 不可用 ⇒ **503 + `Retry-After`**（不降级直写库：那会在故障瞬间把所有心跳
     * 压进数据库，正是本方案要避免的）。客户端应按 `Retry-After` 退避、收到 200 再恢复频率。
     *
     * @param contentId 内容 id（路径）
     * @param request   已看到的秒数（整数）；负数 ⇒ 400；超过时长按当前时长截断（不报错）
     */
    @PutMapping("/api/v1/contents/{id}/progress")
    public ApiResponse<Void> report(@CurrentUserId long userId,
                                    @PathVariable("id") long contentId,
                                    @RequestBody @Valid ReportProgressRequest request) {
        historyService.report(userId, contentId, request.progress());
        return ApiResponse.success();
    }

    /**
     * 播放事件：{@code POST /api/v1/contents/{id}/play}（`T58` 起 {@code contentId} 进路径）。
     * §2.4 例外清单：播放事件非幂等、无自然键，保持 POST。
     *
     * <p>同一用户对该内容**永远只计一次**（一人一次口径）⇒ 重复调用幂等 200、播放量不变。
     * 行不存在时会建行（{@code progress=0}）—— 否则"先 play 后 report"会丢播放量。
     */
    @PostMapping("/api/v1/contents/{id}/play")
    public ApiResponse<Void> play(@CurrentUserId long userId,
                                  @PathVariable("id") long contentId) {
        historyService.play(userId, contentId);
        return ApiResponse.success();
    }
}
