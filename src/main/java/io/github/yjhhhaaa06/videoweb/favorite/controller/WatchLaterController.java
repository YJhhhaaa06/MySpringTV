package io.github.yjhhhaaa06.videoweb.favorite.controller;

import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.common.web.PageParams;
import io.github.yjhhhaaa06.videoweb.favorite.model.dto.MoveToFolderRequest;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.WatchLaterItemVO;
import io.github.yjhhhaaa06.videoweb.favorite.service.WatchLaterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 稍后再看接口入口（`T58` 起路径资源化：{@code /api/v1/watch-later}，收藏三期 T17）。
 *
 * <h2>端点归属（{@code favorite.md} §九）</h2>
 * <table>
 *   <caption>四端点（路径为 `T58` 迁移后的资源化形态）</caption>
 *   <tr><th>端点</th><th>说明</th></tr>
 *   <tr><td>{@code GET /api/v1/watch-later}</td><td>清单分页（加入时间倒序；失效条目占位）</td></tr>
 *   <tr><td>{@code PUT /api/v1/watch-later/{id}}</td><td>加入（**只视频**；重复 ⇒ 移到最前）</td></tr>
 *   <tr><td>{@code DELETE /api/v1/watch-later}</td><td>批量移出（不校验内容存在性、幂等）</td></tr>
 *   <tr><td>{@code PUT /api/v1/watch-later/{id}/folder}</td><td>移入收藏夹（{@code folderId} 缺省 = 默认夹）</td></tr>
 * </table>
 *
 * <h2>★ 鉴权：逐端点 {@code @RequiresLogin}，**绝不**类级</h2>
 * 本域**全部**四端点都需登录（稍后再看是"我的私有池"，没有公开面）—— 即便如此也**逐端点**声明，
 * 不学 {@code LikeController} 的类级注解：类级一旦被人复制到别的"混合公开/私有"域就是 401 事故
 * （{@code FavoriteController} 的类注释已专门记录这个教训）。判别力由
 * {@code SecurityContractTests.watchlater四端点需登录} 直接对 {@code RequestMappingLookup} 求值守着。
 *
 * <h2>请求形态：写 {@code PUT/DELETE} + {@code x-www-form-urlencoded}、读 {@code GET} + query</h2>
 * 完全沿袭收藏域的既有风格（{@code @RequestParam} 同时覆盖 form 与 query 两种位置）；
 * `T58` 只把被寻址的 {@code contentId} 搬进路径。
 *
 * <h2>★ 红线：除本清单外，任何地方都看不出"某视频是否在稍后再看中"</h2>
 * 本类**没有**状态查询端点（旧的 {@code /watchlater/status} 从未存在、`T58` 也不新增），
 * 也**不**让"是否已加入"出现在 {@code interaction} /
 * {@code /contents/{id}/favorite} / 任何 {@code Content*VO} 里（favorite-需求.md §六）。
 *
 * <h2>成功信封 / 错误码</h2>
 * 三个写端点返回**中文文案字符串**（{@code data} 是字符串；与收藏域三条写路径同款）。
 * 400 非视频 / 空 {@code contentIds} / 池已满（新增内容） / 缺参 · 404 内容不存在或已软删（add、move）
 * 或目标夹不存在（move） · 403 目标夹不是自己的（move） · 409 目标夹已有同内容（move，稍后再看保留）
 * · 401 未登录。口径与理由见 {@code WatchLaterService} 类注释。
 * ⚠️ 清单端点 {@code GET /api/v1/watch-later} **刻意没有 404**：空池是合法状态，返回 {@code []}。
 */
@RestController
public class WatchLaterController {

    /** 清单 pageSize 上限（各域自持常量，见 {@code PageParams}）。与 {@code FavoriteController} 同量级取 100。 */
    private static final int WATCH_LATER_PAGE_SIZE_MAX = 100;

    /** 清单 pageSize 缺省（"回看"场景一屏尽量大，缺省与上限一致）。 */
    private static final int WATCH_LATER_PAGE_SIZE_DEFAULT = 100;

    private final WatchLaterService watchLaterService;

    public WatchLaterController(WatchLaterService watchLaterService) {
        this.watchLaterService = watchLaterService;
    }

    // ==================== 读：清单分页 ====================

    /**
     * 稍后再看清单分页（**加入时间倒序**）。{@code data} 是分页信封
     * {@code {list,total,page,pageSize,totalPages}}；★ 失效条目返回脱敏占位
     * （{@code invalid=true}、标题「内容已失效」、封面与作者 {@code null}），占位**保留 {@code contentId}**（移出要它）。
     *
     * <p>端点 `T58` 起为 {@code GET /api/v1/watch-later}。
     *
     * <p>分页单位是 {@code watch_later} 行：{@code total} **包含**失效条目，一整页都是失效条目也照常返回满页。
     * 空池 ⇒ {@code list=[]} + {@code total=0}（不 404）。
     *
     * <p>分页归一：{@code page} 缺省/非数字/≤0 → 1；{@code pageSize} 同上 → 100，且恒 ≤ 100。
     */
    @RequiresLogin
    @GetMapping("/api/v1/watch-later")
    public ApiResponse<PageResult<WatchLaterItemVO>> list(@CurrentUserId long userId,
                                                          @RequestParam(required = false) String page,
                                                          @RequestParam(required = false) String pageSize) {
        return ApiResponse.success(watchLaterService.list(userId,
                PageParams.normalizePage(page),
                PageParams.normalizePageSize(pageSize, WATCH_LATER_PAGE_SIZE_MAX, WATCH_LATER_PAGE_SIZE_DEFAULT)));
    }

    // ==================== 写：add / remove / move（`T58`：PUT / DELETE / PUT） ====================

    /**
     * 加入稍后再看。端点 `T58` 起为 {@code PUT /api/v1/watch-later/{id}}（§2.4「幂等设置」：
     * 重复加入 = 移到最前，状态收敛为"在池中"）。`T59` 起成功 = **200 + 无 data**。
     *
     * <p>★ <b>只收视频</b>：非视频内容 ⇒ 400「只能添加视频」、内容不存在 / 已软删 ⇒ 404。
     * ★ <b>重复加入 ⇒ 移到最前</b>（不新增、不报错）；★ <b>池已满（100）</b>时，加**新**内容 ⇒ 400，
     * 但"已在池中的内容"再次加入 **仍成功**。
     *
     * @param contentId 路径变量 {@code {id}}：内容 id；非数字 → 400
     */
    @RequiresLogin
    @PutMapping("/api/v1/watch-later/{id}")
    public ApiResponse<Void> add(@CurrentUserId long userId, @PathVariable("id") long contentId) {
        watchLaterService.add(userId, contentId);
        return ApiResponse.success();
    }

    /**
     * 从稍后再看批量移出。端点 `T58` 起为 {@code DELETE /api/v1/watch-later}
     * （§2.4「删除」；批量集合不进路径 —— 路径变量是单数寻址）。`T59` 起成功 = <b>204 无体</b>。
     *
     * <p>★ <b>不校验内容存在性</b>（同 G11：失效条目必须删得掉）、**幂等**（本就不在池中 ⇒ 照常 204）。
     *
     * @param contentIds 要移出的内容 id 集合；逗号分隔（{@code contentIds=1,2}）与重复参数
     *                   （{@code contentIds=1&contentIds=2}）Spring 都绑定；空 / 缺参 ⇒ 400
     */
    @RequiresLogin
    @DeleteMapping("/api/v1/watch-later")
    public ResponseEntity<Void> remove(@CurrentUserId long userId,
                                       @RequestParam List<Long> contentIds) {
        watchLaterService.remove(userId, contentIds);
        return ResponseEntity.noContent().build();
    }

    /**
     * 把一条内容从稍后再看**移入收藏夹**（**同事务**：加入目标夹 + 从池中移除）。
     * 端点 `T58` 起为 {@code PUT /api/v1/watch-later/{id}/folder}
     * （§2.4「幂等设置」+ §2.3「子资源」：目标夹是本条池项的一个属性，不是独立资源）。
     * `T59` 起成功 = **200 + 无 data**，入参改为 JSON body {@code {"folderId": N}}。
     *
     * <p>口径：非视频 400 · 内容不存在 / 已软删 404 · 目标夹不存在 404 / 非我 403 / 已满 400 ·
     * ★ `T59` 起目标夹已有同内容 ⇒ **幂等成功**（只清稍后再看条目）。详见 {@code WatchLaterService.move}。
     *
     * <p>⚠️ 进路径的是 {@code contentId}（被移动的池项，§2.3"唯一标识被操作对象"）；
     * {@code folderId} 走 body 且**可不传**（缺省 = 默认夹）—— 搬进路径会把"没给"变成 404
     * （§2.3 的"缺参归 400"）。
     *
     * @param contentId 路径变量 {@code {id}}：内容 id；非数字 → 400
     * @param request   {@code folderId} 目标夹；**缺省 = 默认夹（必要时懒建）**，
     *                  同 {@code POST /api/v1/favorites}
     */
    @RequiresLogin
    @PutMapping("/api/v1/watch-later/{id}/folder")
    public ApiResponse<Void> move(@CurrentUserId long userId,
                                  @PathVariable("id") long contentId,
                                  @RequestBody MoveToFolderRequest request) {
        watchLaterService.move(userId, contentId, request.folderId());
        return ApiResponse.success();
    }
}
