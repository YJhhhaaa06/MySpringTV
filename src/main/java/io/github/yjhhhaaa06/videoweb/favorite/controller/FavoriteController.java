package io.github.yjhhhaaa06.videoweb.favorite.controller;

import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteFolderVO;
import io.github.yjhhhaaa06.videoweb.favorite.service.FavoriteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收藏接口入口（路径前缀 {@code /favorite}）。
 *
 * <h2>端点归属（分期篇 §3.3 是冻结契约，逐个任务补）</h2>
 * <table>
 *   <caption>哪个任务交付哪条端点</caption>
 *   <tr><th>端点</th><th>任务</th><th>状态</th></tr>
 *   <tr><td>{@code POST /favorite/folder/add|update|remove}</td><td rowspan="2">夹 CRUD = <b>T2</b></td><td rowspan="2">✅ 本任务落地</td></tr>
 *   <tr><td>{@code GET /favorite/folder/list}</td></tr>
 *   <tr><td>{@code GET /favorite/folder/public?userId=X}</td><td>T3</td><td>未落地</td></tr>
 *   <tr><td>{@code POST /favorite/add|remove|move}</td><td>T4</td><td>未落地</td></tr>
 *   <tr><td>{@code GET /favorite/status|count}</td><td>T5</td><td>未落地</td></tr>
 *   <tr><td>{@code GET /favorite/list}</td><td>T6</td><td>未落地</td></tr>
 * </table>
 *
 * <h2>★ 鉴权：绝不能照抄 {@code LikeController} 的类级 {@code @RequiresLogin}</h2>
 * like 域 8 个端点**全部**需登录，故用类级注解把"这一族整体受保护"写在一处。
 * <b>收藏域不是</b>：{@code GET /favorite/folder/public?userId=X} 是**他人视角的公开读**
 * （分期篇 §3.3 明写"匿名可访问，只返回公开"），一旦图省事打上类级 {@code @RequiresLogin}，
 * 这个端点就会 401 —— 而且**不会有人发现**（自己的私密夹自己照样看得见）。
 * 故本类的鉴权一律**逐端点**声明（{@code @RequiresLogin} 通过 {@code RequestMappingLookup}
 * 反射判定，见 {@code common/security/JwtAuthFilter}，没有 TV 那两份需手工维护的路径清单）。
 * 判别力由 {@code SecurityContractTests} 直接对 {@code RequestMappingLookup} 求值守着
 * （"四端点判为需登录"），T3 落地公开端点时须补一条 {@code isFalse} 的反向断言。
 *
 * <h2>请求形态：写 {@code POST} + {@code x-www-form-urlencoded}、读 {@code GET} + query</h2>
 * 沿袭 {@code /like/*} 与 {@code /follow/*} 的既有风格（见 T1 时本类的注释）。
 * 收藏是新域、**没有 TV 老前端要兼容**，选 form 而非 JSON 的理由只有一条：
 * 与同类的"容器域"（like/follow）保持一致，且 {@code @RequestParam} 同时覆盖 form 与 query
 * 两种位置（旧 pytest/前端两种调法都不会 400）。
 *
 * <h2>成功信封</h2>
 * 三个写端点返回**中文文案字符串**（{@code data} 是字符串），与 {@code /like/add}、
 * {@code /follow/add}、{@code /comment/delete} 逐字同款；{@code /folder/list} 的 {@code data}
 * 是**数组**（空态是 {@code []}，不是 404、不是缺 {@code data} 键）。形状与理由见
 * {@code FavoriteFolderVO} 的类注释。
 *
 * <h2>错误码</h2>
 * 404 夹不存在 · 403 不是自己的夹 · 409 默认夹不可删 · 400 夹名为空或超长 ·
 * 401 未登录 —— 分界线与理由见 {@code FavoriteService} 的类注释。
 */
@RestController
@RequestMapping("/favorite")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    // ==================== 夹 CRUD（T2：写 = POST + form） ====================

    /**
     * 新建收藏夹。成功响应 {@code data} 是字符串「创建成功」。
     *
     * <p>建出来的永远是**自建夹**（{@code is_default = 0}）。
     * 默认夹由首次收藏懒建（R-01，T4），本端点不碰它。
     *
     * @param name 夹名；缺参 → 400（Spring 抛 {@code MissingServletRequestParameterException}）、
     *             空白 / 超长 → 400（service 侧 {@code ParamException}）
     */
    @RequiresLogin
    @PostMapping("/folder/add")
    public ApiResponse<String> addFolder(@CurrentUserId long userId, @RequestParam String name) {
        favoriteService.createFolder(userId, name);
        return ApiResponse.success("创建成功");
    }

    /**
     * 改收藏夹名（默认夹也能改）。成功响应 {@code data} 是字符串「修改成功」。
     *
     * <p>⚠️ 本端点 T2 **只改名**；"私密开关"归 T3、"简介"在需求篇是「待定」——
     * 理由与交接说明见 {@code FavoriteService.renameFolder}。
     *
     * @param folderId 目标夹；他人的夹 → 403、不存在 → 404、是默认夹则**允许**（只有删除才拦）
     */
    @RequiresLogin
    @PostMapping("/folder/update")
    public ApiResponse<String> updateFolder(@CurrentUserId long userId,
                                           @RequestParam long folderId,
                                           @RequestParam String name) {
        favoriteService.renameFolder(userId, folderId, name);
        return ApiResponse.success("修改成功");
    }

    /**
     * 删除收藏夹（**夹内收藏记录一并删除**，R-02）。成功响应 {@code data} 是字符串「删除成功」。
     *
     * <p>★ <b>默认夹 → 409，夹与其条目都原样保留</b>（需求篇 §二「删不掉」）。
     * 前端理应不给默认夹渲染删除按钮，但后端这道闸不依赖前端（契约测试守着）。
     *
     * @param folderId 目标夹；他人的夹 → 403、不存在 → 404
     */
    @RequiresLogin
    @PostMapping("/folder/remove")
    public ApiResponse<String> removeFolder(@CurrentUserId long userId, @RequestParam long folderId) {
        favoriteService.deleteFolder(userId, folderId);
        return ApiResponse.success("删除成功");
    }

    // ==================== 夹列表（T2：读 = GET + query） ====================

    /**
     * 我的收藏夹列表（含每夹条目数，**含私密夹**）。
     *
     * <p>{@code data} 是数组；**一个夹都没有时返回 {@code []}**（不是 404、不是报错）——
     * 那是"新注册、还没收藏过任何东西"的合法状态（R-01 懒建，自洽性论证见
     * {@code FavoriteService} 类注释）。
     *
     * <p>顺序：默认夹置顶，其余按创建先后。{@code itemCount} 数的是**收藏记录数**
     * （失效内容的记录也计入，R-07）。
     *
     * <p>⚠️ 这里**没有**"看谁的"参数：他人视角是独立端点（T3），
     * 让两者共用一条 SQL、靠参数分流是 R-08 明确否决的形态。
     */
    @RequiresLogin
    @GetMapping("/folder/list")
    public ApiResponse<List<FavoriteFolderVO>> listFolders(@CurrentUserId long userId) {
        return ApiResponse.success(favoriteService.listMyFolders(userId));
    }
}
