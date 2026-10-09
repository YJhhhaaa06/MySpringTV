package io.github.yjhhhaaa06.videoweb.favorite.controller;

import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteFolderVO;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.PublicFavoriteFolderVO;
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
 *   <tr><td>{@code GET /favorite/folder/public?userId=X}</td><td>T3</td><td>✅ 本任务落地（匿名可访问）</td></tr>
 *   <tr><td>{@code POST /favorite/add}</td><td rowspan="3">收藏写路径 = <b>T4</b></td><td>✅ 本任务落地</td></tr>
 *   <tr><td>{@code POST /favorite/remove}（批量）</td><td>✅ 本任务落地</td></tr>
 *   <tr><td>{@code POST /favorite/move}</td><td>✅ 本任务落地</td></tr>
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
 * （"四端点判为需登录" + "公开端点 {@code isFalse}"的反向对照，T3 已补 —— 否则"整个
 * {@code /favorite} 被放行"与"逐端点声明正确"在断言上无法区分）。
 *
 * <p>⚠️ 公开端点（{@link #publicFolders}）**连 {@code @CurrentUserId} 都不注入** ——
 * 它没有"匿名可见 + 已登录则个性化"的需求（看别人的公开夹与"我是谁"无关），
 * 注入了反而会为将来"是本人就显示私密"这类分支留下入口（R-08 明确否决的形态）。
 * 别照抄 {@code /profile} 的 {@code @CurrentUserId(required = false)}：那条端点有个性化需求，
 * 这条没有。
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
 * 404 夹不存在 / 内容不存在或已失效（add / move） · 403 不是自己的夹 ·
 * 409 默认夹不可删 / 重复收藏 · 400 夹名为空或超长 / 更新时一个字段都没给 /
 * {@code isPrivate} 格式非法 / 移出内容列表为空 · 401 未登录
 * —— 分界线与理由见 {@code FavoriteService} 的类注释。
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
     * 更新收藏夹（**部分更新**：改名 / 私密开关，给了才改）。成功响应 {@code data} 是字符串「修改成功」。
     *
     * <p>{@code name} 与 {@code isPrivate} **至少给一个**（都没给 → 400）；给了哪个改哪个。
     * 默认夹也能改名、也能设私密（只有删除才拦）。"简介"在需求篇是「待定」——不实现。
     * 口径与理由见 {@code FavoriteService.updateFolder}。
     *
     * @param folderId  目标夹；他人的夹 → 403、不存在 → 404、是默认夹则**允许**
     * @param name      新夹名；不传 = 不改名；空白 / 超长 → 400（**400 先于 403/404**）
     * @param isPrivate 私密开关；不传 = 不动它。接受 {@code 1/true/0/false}
     *                  （大小写不敏感、两端空白忽略，与 {@code /content/commentEnabled} 同一值集）；
     *                  给了但格式非法 → 400（同样先于 403/404）
     */
    @RequiresLogin
    @PostMapping("/folder/update")
    public ApiResponse<String> updateFolder(@CurrentUserId long userId,
                                           @RequestParam long folderId,
                                           @RequestParam(required = false) String name,
                                           @RequestParam(required = false) String isPrivate) {
        favoriteService.updateFolder(userId, folderId, name, parseIsPrivate(isPrivate));
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
     * <p>⚠️ 这里**没有**"看谁的"参数：他人视角是独立端点（{@link #publicFolders}），
     * 让两者共用一条 SQL、靠参数分流是 R-08 明确否决的形态。
     */
    @RequiresLogin
    @GetMapping("/folder/list")
    public ApiResponse<List<FavoriteFolderVO>> listFolders(@CurrentUserId long userId) {
        return ApiResponse.success(favoriteService.listMyFolders(userId));
    }

    // ==================== 他人视角公开夹（T3：读 = GET + query，**匿名可访问**） ====================

    /**
     * 看他人（或任何人）的**公开**收藏夹 —— 私密夹完全不出现（R-08，即"私密"一期的可观察落点）。
     *
     * <p>★ <b>匿名可访问</b>（分期篇 §3.3 冻结契约）：本方法**没有任何鉴权注解**、
     * 也不注入 {@code @CurrentUserId} —— 看别人的公开夹与"我是谁"无关。
     * 带不带 token、带的是不是坏 token 都不影响结果（{@code JwtAuthFilter} 对
     * {@code requiresLogin == false} 的请求按匿名放行，见 {@code common/security/JwtAuthFilter}）。
     *
     * <p>{@code data} 是数组；<b>无公开夹 / 用户不存在时都返回 {@code []}</b>（不是 404）——
     * 不校验 {@code userId} 存在性，避免给"这个用户存不存在"开探测口；空态也是合法状态。
     * 条目形状只有 {@code name} + {@code itemCount}（R-08「一期只给名称 + 视频数」，
     * 键集由 {@code FavoritePrivacyTests} 断言钉死）。顺序同"我的夹列表"（默认夹置顶 + 创建序）。
     *
     * @param userId 被查看的用户 id（必传）；缺参 / 非数字 → 400（Spring 参数绑定 → 全局出口）
     */
    @GetMapping("/folder/public")
    public ApiResponse<List<PublicFavoriteFolderVO>> publicFolders(@RequestParam long userId) {
        return ApiResponse.success(favoriteService.listPublicFolders(userId));
    }

    // ==================== 收藏写路径（T4：写 = POST + form） ====================

    /**
     * 收藏内容到指定夹。成功响应 {@code data} 是字符串「收藏成功」。
     *
     * <p>★ <b>{@code folderId} 可不传 = 收进默认夹</b>（需求篇 §二「没选夹自动进默认收藏夹」）；
     * 默认夹不存在时**就地懒建**（R-01，全系统唯一建默认夹的路径 —— 新用户拿不到
     * 默认夹 id，夹列表对他是空态）。口径：失效内容 404（不可移入）、
     * 重复收藏 409（撞 {@code uk_folder_content} 的幂等拒绝）、他人的夹 403。
     * 详见 {@code FavoriteService.addItem}。
     *
     * @param contentId 内容 id（必传）
     * @param folderId  目标夹；缺省 = 默认夹（必要时懒建）；夹不存在 → 404、不是我的 → 403
     */
    @RequiresLogin
    @PostMapping("/add")
    public ApiResponse<String> addItem(@CurrentUserId long userId,
                                       @RequestParam long contentId,
                                       @RequestParam(required = false) Long folderId) {
        favoriteService.addItem(userId, folderId, contentId);
        return ApiResponse.success("收藏成功");
    }

    /**
     * 从指定夹移出收藏（**批量**：夹内勾选多条一次移出）。成功响应 {@code data} 是字符串「移出成功」。
     *
     * <p>★ <b>本端点不校验内容存在性</b>（G11 红线 —— 失效条目必须删得掉），
     * 且幂等：记录已不存在（陈旧页面）也照常 200、删除 0 行不算错。
     * ⚠️ 只动 {@code folderId} 这一个夹：同内容在别的夹的记录**不受影响**（需求篇 §三）。
     * 详见 {@code FavoriteService.removeItems}。
     *
     * @param folderId   从哪个夹移出；夹不存在 → 404、不是我的 → 403
     * @param contentIds 要移出的内容 id 集合；逗号分隔（{@code contentIds=1,2}）与
     *                   重复参数（{@code contentIds=1&contentIds=2}）两种形态 Spring 都绑定；
     *                   空 / 缺参 → 400
     */
    @RequiresLogin
    @PostMapping("/remove")
    public ApiResponse<String> removeItems(@CurrentUserId long userId,
                                           @RequestParam long folderId,
                                           @RequestParam List<Long> contentIds) {
        favoriteService.removeItems(userId, folderId, contentIds);
        return ApiResponse.success("移出成功");
    }

    /**
     * 把一条收藏从源夹挪到目标夹。成功响应 {@code data} 是字符串「移动成功」。
     *
     * <p>独立端点而非前端的 remove + add 两连击：两次调用之间没有原子性，
     * add 失败会让条目从源夹消失（失效内容本该"不可移动但**保留原位**"，R-07）。
     * 口径：失效内容 404、目标夹已有同内容 409（源夹保留）、from == to 也是 409；
     * 两条写在同一事务（{@code FavoriteService.moveItem}）。
     *
     * @param fromFolderId 源夹；不存在 → 404、不是我的 → 403
     * @param toFolderId   目标夹；同上
     * @param contentId    内容 id；内容不存在或已失效 → 404（条目留在源夹）
     */
    @RequiresLogin
    @PostMapping("/move")
    public ApiResponse<String> moveItem(@CurrentUserId long userId,
                                        @RequestParam long fromFolderId,
                                        @RequestParam long toFolderId,
                                        @RequestParam long contentId) {
        favoriteService.moveItem(userId, fromFolderId, toFolderId, contentId);
        return ApiResponse.success("移动成功");
    }

    // ==================== 参数解析 ====================

    /**
     * 解析私密开关：{@code 1/true} → {@code TRUE}，{@code 0/false} → {@code FALSE}
     * （大小写不敏感、两端空白忽略），其它 → 400。
     *
     * <p>值集与 {@code ContentController.parseEnabled} 对齐（"布尔型 form 参数"在本仓的既有口径）；
     * 那处是 TV 逐字保真、不可复用，故这里是第二份实现（rule of three 未到，不抽公共）。
     *
     * @param raw {@code null} = 本次不动私密开关（**不**参与解析）；非 null 但解析不出 → 400
     */
    private static Boolean parseIsPrivate(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase()) {
            case "1", "true" -> Boolean.TRUE;
            case "0", "false" -> Boolean.FALSE;
            default -> throw new ParamException("isPrivate格式错误，应为 0/1 或 true/false");
        };
    }
}
