package io.github.yjhhhaaa06.videoweb.favorite.controller;

import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.Public;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.common.web.PageParams;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteItemVO;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteFolderVO;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteStatusVO;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.PublicFavoriteFolderVO;
import io.github.yjhhhaaa06.videoweb.favorite.service.FavoriteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收藏接口入口（`T58` 起路径资源化：{@code /api/v1/favorite-folders}、{@code /api/v1/favorites}、
 * {@code /api/v1/contents/{id}/favorite}）。
 *
 * <h2>端点归属（favorite.md §三 是冻结契约，逐个任务补）</h2>
 * <table>
 *   <caption>哪个任务交付哪条端点（路径为 `T58` 迁移后的资源化形态）</caption>
 *   <tr><th>端点</th><th>任务</th><th>状态</th></tr>
 *   <tr><td>{@code POST|PUT|DELETE /api/v1/favorite-folders[/{id}]}</td><td rowspan="2">夹 CRUD = <b>T2</b></td><td rowspan="2">✅ 本任务落地</td></tr>
 *   <tr><td>{@code GET /api/v1/favorite-folders}</td></tr>
 *   <tr><td>{@code GET /api/v1/users/{id}/favorite-folders}</td><td>T3</td><td>✅ 本任务落地（匿名可访问）</td></tr>
 *   <tr><td>{@code GET /api/v1/favorite-folders/{id}/items}</td><td>T18</td><td>✅ 本任务落地（匿名可访问；看他人公开夹的内容）</td></tr>
 *   <tr><td>{@code POST /api/v1/favorites}</td><td rowspan="3">收藏写路径 = <b>T4</b></td><td>✅ 本任务落地</td></tr>
 *   <tr><td>{@code DELETE /api/v1/favorites}（批量）</td><td>✅ 本任务落地</td></tr>
 *   <tr><td>{@code PUT /api/v1/favorites/{id}}（移动）</td><td>✅ 本任务落地</td></tr>
 *   <tr><td>{@code GET /api/v1/contents/{id}/favorite}</td><td rowspan="2">状态与计数 = <b>T5</b></td><td rowspan="2">✅ 本任务落地</td></tr>
 *   <tr><td>{@code GET /api/v1/contents/{id}/favorite-count}</td></tr>
 *   <tr><td>{@code GET /api/v1/favorites}</td><td>T6</td><td>✅ 本任务落地（夹内分页，含失效占位）</td></tr>
 * </table>
 *
 * <h2>★ 鉴权：绝不能照抄 {@code LikeController} 的类级 {@code @RequiresLogin}</h2>
 * like 域 8 个端点**全部**需登录，故用类级注解把"这一族整体受保护"写在一处。
 * <b>收藏域不是</b>：{@code GET /api/v1/users/{id}/favorite-folders} 是**他人视角的公开读**
 * （favorite.md §三 明写"匿名可访问，只返回公开"），一旦图省事打上类级 {@code @RequiresLogin}，
 * 这个端点就会 401 —— 而且**不会有人发现**（自己的私密夹自己照样看得见）。
 * 故本类的鉴权一律**逐端点**声明（{@code @RequiresLogin} 通过 {@code RequestMappingLookup}
 * 反射判定，见 {@code common/security/JwtAuthFilter}，没有 TV 那两份需手工维护的路径清单）。
 * 判别力由 {@code SecurityContractTests} 直接对 {@code RequestMappingLookup} 求值守着
 * （"四端点判为需登录" + "公开端点 {@code isFalse}"的反向对照，T3 已补 —— 否则"整个
 * {@code /api/v1/favorite-folders} 被放行"与"逐端点声明正确"在断言上无法区分）。
 *
 * <p>⚠️ 公开端点（{@link #publicFolders}）**连 {@code @CurrentUserId} 都不注入** ——
 * 它没有"匿名可见 + 已登录则个性化"的需求（看别人的公开夹与"我是谁"无关），
 * 注入了反而会为将来"是本人就显示私密"这类分支留下入口（R-08 明确否决的形态）。
 * 别照抄 {@code /profile} 的 {@code @CurrentUserId(required = false)}：那条端点有个性化需求，
 * 这条没有。
 *
 * <p>★ <b>T5 又加了一条匿名端点</b>（{@link #contentCount}）：{@code /favorite-count} 是
 * 公开展示数（favorite-需求.md §三「内容页显示"多少人收藏了它"」），同样**不注入** {@code @CurrentUserId}
 * ——它不需要个性化。★ <b>T18 再加第三条</b>（{@link #publicFolderItems}，他人公开夹的内容）。
 * 三条匿名端点（{@code /users/{id}/favorite-folders}、{@code /favorite-folders/{id}/items}、
 * {@code /favorite-count}）都**没有**任何
 * 鉴权注解，{@code JwtAuthFilter} 对 {@code requiresLogin == false} 的请求按匿名放行。
 * 判别力由 {@code SecurityContractTests} 守着（status {@code true} / count {@code false} 的
 * **成对**断言 + 两条公开端点 {@code false} 的断言 ——
 * 否则"整个收藏域被放行"与"逐端点声明正确"在断言上无法区分）。
 *
 * <h2>请求形态：写 {@code POST/PUT/DELETE} + {@code x-www-form-urlencoded}、读 {@code GET} + query</h2>
 * 沿袭 {@code /like/*} 与 {@code /follow/*} 的既有风格（见 T1 时本类的注释）。
 * 收藏是新域、**没有 TV 老前端要兼容**，选 form 而非 JSON 的理由只有一条：
 * 与同类的"容器域"（like/follow）保持一致，且 {@code @RequestParam} 同时覆盖 form 与 query
 * 两种位置（旧 pytest/前端两种调法都不会 400）。
 * `T58` 只把**被寻址的标识**搬进路径（{@code @PathVariable}），其余字段仍在 query/form。
 *
 * <h2>成功信封</h2>
 * 三个写端点返回**中文文案字符串**（{@code data} 是字符串），与 {@code PUT /api/v1/contents/{id}/like}
 * 等同类写路径、{@code DELETE /api/v1/comments/{id}} 逐字同款；
 * {@code GET /api/v1/favorite-folders} 的 {@code data}
 * 是**数组**（空态是 {@code []}，不是 404、不是缺 {@code data} 键）。形状与理由见
 * {@code FavoriteFolderVO} 的类注释。
 *
 * <h2>错误码</h2>
 * 404 夹不存在 / 内容不存在或已失效（add / move） · 403 不是自己的夹 ·
 * 409 默认夹不可删 / 重复收藏 · 400 夹名为空或超长 / 更新时一个字段都没给 /
 * {@code isPrivate} 格式非法 / 移出内容列表为空 / <b>夹数或夹内条数超上限（T16）</b> · 401 未登录
 * —— 分界线与理由见 {@code FavoriteService} 的类注释。
 * ⚠️ T16 的容量 400 判在 403/404 **之后**（与"参数型 400 先于归属"刻意分家）：
 * 容量是资源状态、不是参数形态，提前判会把"他人夹满/未满"变成可探测信号。
 *
 * <p>⚠️ <b>两条读端点（{@code /contents/{id}/favorite}、{@code /contents/{id}/favorite-count}）
 * 刻意没有 404</b>：内容不存在 /
 * 从未被收藏 ⇒ 分别是"未收藏（{@code folders=[]}）"与 {@code 0}，都是合法状态。
 * 给它们加 404 会同时给"这个内容 id 存不存在"开探测口（无端扩大攻击面）。
 */
@RestController
public class FavoriteController {

    /** 收藏夹内列表 pageSize 上限（各域自持常量，见 {@code PageParams}）。 */
    private static final int FAVORITE_PAGE_SIZE_MAX = 100;

    /**
     * 收藏夹内列表 pageSize 缺省（各域自持常量，见 {@code PageParams}）。
     *
     * <p>取 100 而不是 20：与内容类列表（首页 / 主页 / 搜索 / feed，`T58` 后为
     * /api/v1/contents/recommended、/api/v1/users/{id}、/api/v1/contents、/api/v1/feed）同为 100
     * —— 收藏夹是"回看"场景，一屏装不下时用户更可能翻页而不是
     * 缩小信封；且缺省与上限一致，省掉"缺省 < 上限"这一层不必存在的差异
     * （对照 comment 域是 500/200：那里的一屏主楼本来就大得多，属另一类量级）。
     */
    private static final int FAVORITE_PAGE_SIZE_DEFAULT = 100;

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    // ==================== 夹 CRUD（T2；`T58` 起写 = POST/PUT/DELETE） ====================

    /**
     * 新建收藏夹（{@code POST /api/v1/favorite-folders}）。成功响应 {@code data} 是字符串「创建成功」。
     *
     * <p>建出来的永远是**自建夹**（{@code is_default = 0}）。
     * 默认夹由首次收藏懒建（R-01，T4），本端点不碰它。
     *
     * @param name 夹名；缺参 → 400（Spring 抛 {@code MissingServletRequestParameterException}）、
     *             空白 / 超长 → 400（service 侧 {@code ParamException}）、
     *             自建夹已达 20 个 → 400（T16；默认夹不计入，见 {@code FavoriteLimitProperties}）
     */
    @RequiresLogin
    @PostMapping("/api/v1/favorite-folders")
    public ApiResponse<String> addFolder(@CurrentUserId long userId, @RequestParam String name) {
        favoriteService.createFolder(userId, name);
        return ApiResponse.success("创建成功");
    }

    /**
     * 更新收藏夹（**部分更新**：改名 / 私密开关，给了才改）。成功响应 {@code data} 是字符串「修改成功」。
     *
     * <p>{@code name} 与 {@code isPrivate} **至少给一个**（都没给 → 400）；给了哪个改哪个。
     * 默认夹也能改名、也能设私密（只有删除才拦）。"简介"在favorite-需求.md是「待定」——不实现。
     * 口径与理由见 {@code FavoriteService.updateFolder}。
     *
     * <p>`T58` 起目标夹走路径（{@code PUT /api/v1/favorite-folders/{id}}，§2.4「幂等替换」），
     * 夹 id 不再能从 query/form 传 —— 路径变量是**唯一**寻址方式。
     *
     * @param folderId  路径变量 {@code {id}}：目标夹；他人的夹 → 403、不存在 → 404、是默认夹则**允许**
     * @param name      新夹名；不传 = 不改名；空白 / 超长 → 400（**400 先于 403/404**）
     * @param isPrivate 私密开关；不传 = 不动它。接受 {@code 1/true/0/false}
     *                  （大小写不敏感、两端空白忽略，与 {@code /content/commentEnabled} 同一值集）；
     *                  给了但格式非法 → 400（同样先于 403/404）
     */
    @RequiresLogin
    @PutMapping("/api/v1/favorite-folders/{id}")
    public ApiResponse<String> updateFolder(@CurrentUserId long userId,
                                           @PathVariable("id") long folderId,
                                           @RequestParam(required = false) String name,
                                           @RequestParam(required = false) String isPrivate) {
        favoriteService.updateFolder(userId, folderId, name, parseIsPrivate(isPrivate));
        return ApiResponse.success("修改成功");
    }

    /**
     * 删除收藏夹（**夹内收藏记录一并删除**，R-02）。成功响应 {@code data} 是字符串「删除成功」。
     *
     * <p>★ <b>默认夹 → 409，夹与其条目都原样保留</b>（favorite-需求.md §二「删不掉」）。
     * 前端理应不给默认夹渲染删除按钮，但后端这道闸不依赖前端（契约测试守着）。
     *
     * <p>`T58` 起目标夹走路径（{@code DELETE /api/v1/favorite-folders/{id}}，§2.4「删除」）。
     *
     * @param folderId 路径变量 {@code {id}}：目标夹；他人的夹 → 403、不存在 → 404
     */
    @RequiresLogin
    @DeleteMapping("/api/v1/favorite-folders/{id}")
    public ResponseEntity<Void> removeFolder(@CurrentUserId long userId, @PathVariable("id") long folderId) {
        favoriteService.deleteFolder(userId, folderId);
        return ResponseEntity.noContent().build();
    }

    // ==================== 夹列表（T2：读 = GET + query） ====================

    /**
     * 我的收藏夹列表（含每夹条目数，**含私密夹**）。端点 {@code GET /api/v1/favorite-folders}。
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
    @GetMapping("/api/v1/favorite-folders")
    public ApiResponse<List<FavoriteFolderVO>> listFolders(@CurrentUserId long userId) {
        return ApiResponse.success(favoriteService.listMyFolders(userId));
    }

    // ==================== 他人视角公开夹（T3：读 = GET；**匿名可访问**；T18 补"点开看内容"） ====================

    /**
     * 看他人（或任何人）的**公开**收藏夹 —— 私密夹完全不出现（R-08，即"私密"一期的可观察落点）。
     * 端点 `T58` 起为 {@code GET /api/v1/users/{id}/favorite-folders}（用户维度子资源，
     * 被查看者 id 走路径；§2.3）。
     *
     * <p>★ <b>匿名可访问</b>（favorite.md §三 冻结契约）：本方法**没有任何鉴权注解**、
     * 也不注入 {@code @CurrentUserId} —— 看别人的公开夹与"我是谁"无关。
     * 带不带 token、带的是不是坏 token 都不影响结果（{@code JwtAuthFilter} 对
     * {@code requiresLogin == false} 的请求按匿名放行，见 {@code common/security/JwtAuthFilter}）。
     *
     * <p>{@code data} 是数组；<b>无公开夹 / 用户不存在时都返回 {@code []}</b>（不是 404）——
     * 不校验 {@code userId} 存在性，避免给"这个用户存不存在"开探测口；空态也是合法状态。
     * 条目形状只有 {@code name} + {@code itemCount}（R-08「一期只给名称 + 视频数」，
     * 键集由 {@code FavoritePrivacyTests} 断言钉死）。顺序同"我的夹列表"（默认夹置顶 + 创建序）。
     *
     * @param userId 路径变量 {@code {id}}：被查看的用户 id；非数字 → 400（Spring 路径变量绑定 → 全局出口）
     */
    @Public
    @GetMapping("/api/v1/users/{id}/favorite-folders")
    public ApiResponse<List<PublicFavoriteFolderVO>> publicFolders(@PathVariable("id") long userId) {
        return ApiResponse.success(favoriteService.listPublicFolders(userId));
    }

    /**
     * 看他人**公开**收藏夹里的内容（{@code GET /api/v1/favorite-folders/{id}/items}，T18）——
     * favorite-需求.md §二「看别人的收藏夹」的"点开看内容"（一期 R-08 只给了"名称 + 数量"那一半）。
     *
     * <p>★ <b>匿名可访问</b>（与 {@link #publicFolders} 同款）：本方法**没有任何鉴权注解**、
     * 也不注入 {@code @CurrentUserId} —— 看别人的**公开**夹与"我是谁"无关。⚠️ <b>绝不</b>图省事
     * 给本类加类级 {@code @RequiresLogin}：那会把 {@code /users/{id}/favorite-folders} 与本条
     * 一并误拦成 401
     * （见类注释"鉴权"节 —— favorite.md §三 已专门警告，本类逐端点声明是为此刻准备的）。
     *
     * <p>★ <b>私密夹与不存在的夹返回同一口径</b>（<b>404 + 同一文案</b>）—— 服务端强制
     * {@code is_private = 0}，两者**不可区分**；若给"私密"单独一个 403，就等于对外暴露
     * "该夹存在且是私密的"这个可探测信号（详见 {@code FavoriteService.listPublicFolderItems}）。
     *
     * <p>{@code data} 是分页信封 {@code PageResult<FavoriteItemVO>}（与夹内列表 {@code GET /api/v1/favorites}
     * **同形**，
     * **不新造** T18 红线④）：失效条目照常返回脱敏占位（R-03）；{@code total} **含**失效条目（R-07）；
     * 空公开夹 ⇒ {@code list=[]} + {@code total=0}（不 404）。
     *
     * @param folderId 路径变量 {@code {id}}：目标夹；**不存在 / 是私密夹 ⇒ 同为 404**（不可区分）
     * @param page     页码（缺省/非数字/≤0 → 1，由 {@code PageParams} 归一）
     * @param pageSize 页大小（同上 → 缺省 {@value #FAVORITE_PAGE_SIZE_DEFAULT}，恒 ≤
     *                 {@value #FAVORITE_PAGE_SIZE_MAX}）
     */
    @Public
    @GetMapping("/api/v1/favorite-folders/{id}/items")
    public ApiResponse<PageResult<FavoriteItemVO>> publicFolderItems(@PathVariable("id") long folderId,
                                                                     @RequestParam(required = false) String page,
                                                                     @RequestParam(required = false) String pageSize) {
        return ApiResponse.success(favoriteService.listPublicFolderItems(folderId,
                PageParams.normalizePage(page),
                PageParams.normalizePageSize(pageSize, FAVORITE_PAGE_SIZE_MAX, FAVORITE_PAGE_SIZE_DEFAULT)));
    }

    // ==================== 收藏写路径（T4；`T58` 起 add=POST、remove=DELETE、move=PUT） ====================

    /**
     * 收藏内容到指定夹（{@code POST /api/v1/favorites}）。成功响应 {@code data} 是字符串「收藏成功」。
     *
     * <p>★ <b>{@code folderId} 可不传 = 收进默认夹</b>（favorite-需求.md §二「没选夹自动进默认收藏夹」）；
     * 默认夹不存在时**就地懒建**（R-01，全系统唯一建默认夹的路径 —— 新用户拿不到
     * 默认夹 id，夹列表对他是空态）。口径：失效内容 404（不可移入）、
     * 重复收藏 409（撞 {@code uk_folder_content} 的幂等拒绝）、他人的夹 403、
     * <b>目标夹已满 400</b>（T16；判在 403/404 **之后** —— 容量是资源状态、不是参数形态）。
     * 详见 {@code FavoriteService.addItem}。
     *
     * <p>`T58` 起内容与夹都仍是**字段**（{@code contentId}/{@code folderId} 在 form 或 query）：
     * 这是"往集合里加一条"，被寻址的是集合本身（{@code /api/v1/favorites}），不是某个已存在的成员。
     *
     * @param contentId 内容 id（必传）
     * @param folderId  目标夹；缺省 = 默认夹（必要时懒建）；夹不存在 → 404、不是我的 → 403
     */
    @RequiresLogin
    @PostMapping("/api/v1/favorites")
    public ApiResponse<String> addItem(@CurrentUserId long userId,
                                       @RequestParam long contentId,
                                       @RequestParam(required = false) Long folderId) {
        favoriteService.addItem(userId, folderId, contentId);
        return ApiResponse.success("收藏成功");
    }

    /**
     * 从指定夹移出收藏（**批量**：夹内勾选多条一次移出）。端点
     * `T58` 起为 {@code DELETE /api/v1/favorites}
     * （§2.4「删除」）。成功响应 {@code data} 是字符串「移出成功」。
     *
     * <p>★ <b>本端点不校验内容存在性</b>（G11 红线 —— 失效条目必须删得掉），
     * 且幂等：记录已不存在（陈旧页面）也照常 200、删除 0 行不算错。
     * ⚠️ 只动 {@code folderId} 这一个夹：同内容在别的夹的记录**不受影响**（favorite-需求.md §三）。
     * 详见 {@code FavoriteService.removeItems}。
     *
     * <p>`T58` 的两处取舍（都按 §2.3 路径变量口径）：
     * <ul>
     *   <li><b>批量</b> ⇒ 内容 id 集合**不进**路径（路径变量是单数寻址），仍留 query：{@code contentIds}。</li>
     *   <li>{@code folderId} **不进**路径：它限定"从哪个夹删"，且**缺参即 400** ——
     *       若搬进路径会变成 404，把"参数没给"和"夹不存在"混成同一个信号（§2.3 的"缺参归 400"）。</li>
     * </ul>
     *
     * @param folderId   从哪个夹移出；夹不存在 → 404、不是我的 → 403
     * @param contentIds 要移出的内容 id 集合；逗号分隔（{@code contentIds=1,2}）与
     *                   重复参数（{@code contentIds=1&contentIds=2}）两种形态 Spring 都绑定；
     *                   空 / 缺参 → 400
     */
    @RequiresLogin
    @DeleteMapping("/api/v1/favorites")
    public ResponseEntity<Void> removeItems(@CurrentUserId long userId,
                                            @RequestParam long folderId,
                                            @RequestParam List<Long> contentIds) {
        favoriteService.removeItems(userId, folderId, contentIds);
        return ResponseEntity.noContent().build();
    }

    /**
     * 把一条收藏从源夹挪到目标夹。端点 `T58` 起为 {@code PUT /api/v1/favorites/{id}}
     * （§2.4「幂等设置」—— 同一 (contentId, from, to) 重复提交结果不变）。
     * 成功响应 {@code data} 是字符串「移动成功」。
     *
     * <p>独立端点而非前端的 remove + add 两连击：两次调用之间没有原子性，
     * add 失败会让条目从源夹消失（失效内容本该"不可移动但**保留原位**"，R-07）。
     * 口径：失效内容 404、★ `T59` 起目标夹已有同内容 ⇒ 幂等成功（只清源夹）、
     * {@code from == to} ⇒ 幂等 no-op、<b>目标夹已满 400</b>（T16 —— 不判就能"绕道 move 把夹撑爆"）；
     * 两条写在同一事务（{@code FavoriteService.moveItem}）。
     *
     * <p>⚠️ 进路径的是 **{@code contentId}**（被移动的那条收藏，§2.3"唯一标识被操作对象"）；
     * 源/目标夹只是移动的**参数**，仍留 form/query —— 它们不是被寻址的资源。
     *
     * @param contentId    路径变量 {@code {id}}：内容 id；内容不存在或已失效 → 404（条目留在源夹）
     * @param fromFolderId 源夹；不存在 → 404、不是我的 → 403
     * @param toFolderId   目标夹；同上
     */
    @RequiresLogin
    @PutMapping("/api/v1/favorites/{id}")
    public ApiResponse<String> moveItem(@CurrentUserId long userId,
                                        @PathVariable("id") long contentId,
                                        @RequestParam long fromFolderId,
                                        @RequestParam long toFolderId) {
        favoriteService.moveItem(userId, fromFolderId, toFolderId, contentId);
        return ApiResponse.success("移动成功");
    }

    // ==================== 收藏状态与收藏数（T5：读 = GET；contentId 进路径） ====================

    /**
     * 我对某内容的收藏状态（**是否已收藏 + 收在哪些夹**）——favorite-需求.md §三「知道收在了哪些夹」。
     * 端点 `T58` 起为 {@code GET /api/v1/contents/{id}/favorite}（内容维度单例子资源，同
     * {@code /contents/{id}/like}）。
     *
     * <p>{@code data} 是对象：{@code {"isFavorited":true,"folders":[{"id":1,"name":"默认收藏夹"}]}}；
     * 未收藏 ⇒ {@code {"isFavorited":false,"folders":[]}}（**空数组**，不是 404、不是缺 {@code data} 键）。
     * 形状与 {@code isFavorited ≡ !folders.isEmpty()} 的论证见 {@code FavoriteStatusVO} 类注释。
     *
     * <p>★ <b>需登录</b>（{@code @RequiresLogin} + {@code @CurrentUserId}）：这是"我的"私有状态，
     * 与他人视角（{@link #publicFolders}）和公开计数（{@link #contentCount}）是不同信任边界。
     * 内容不存在 / 从未被收藏 ⇒ 未收藏（**不 404**，见类注释"错误码"节）。
     *
     * @param contentId 路径变量 {@code {id}}：内容 id；非数字 → 400（Spring 路径变量绑定 → 全局出口）
     */
    @RequiresLogin
    @GetMapping("/api/v1/contents/{id}/favorite")
    public ApiResponse<FavoriteStatusVO> contentStatus(@CurrentUserId long userId,
                                                      @PathVariable("id") long contentId) {
        return ApiResponse.success(favoriteService.getFavoriteStatus(userId, contentId));
    }

    /**
     * 某内容的收藏数（**按人去重**：同一人进多夹只算 1）。端点
     * `T58` 起为 {@code GET /api/v1/contents/{id}/favorite-count}
     * （标量投影，与 {@code /contents/{id}/like-count} 同构 —— §2.3"计数走 -count 后缀，不造第 4 级"）。
     *
     * <p>{@code data} 是**整数**（JSON 形态同 {@code /api/v1/contents/{id}/like-count}；⚠️ Java 侧类型**不同**——
     * 那条是 {@code Integer}，本条是 {@code Long}，与 DAO 的 {@code COUNT} 返回一致、也免去 int 溢出的顾虑）。
     * ⚠️ 与那条的差别是**鉴权**：本端点**匿名可访问**（见类注释"鉴权"节 —— 收藏数是公开展示数，
     * 内容页匿名可看），故不注入 {@code @CurrentUserId}。读数走缓存，miss 回源读
     * {@code content.favorite_count} 冗余列（T12 起；一期是实时 {@code COUNT(DISTINCT)} 聚合）——
     * 都是域内实现细节，对外契约不变。
     *
     * <p>内容不存在 / 无人收藏 ⇒ {@code 0}（**不 404**）。
     *
     * @param contentId 路径变量 {@code {id}}：内容 id；非数字 → 400
     */
    @Public
    @GetMapping("/api/v1/contents/{id}/favorite-count")
    public ApiResponse<Long> contentCount(@PathVariable("id") long contentId) {
        return ApiResponse.success(favoriteService.getFavoriteCount(contentId));
    }

    // ==================== 夹内列表（T6：读 = GET + query） ====================

    /**
     * 夹内内容分页（**收藏时间倒序**）——favorite-需求.md §三「打开某个夹看里面的内容」。
     * 端点 `T58` 起为 {@code GET /api/v1/favorites}（{@code folderId} 留 query：是否必传取决于
     * "看哪个夹"，且缺参归 400 —— §2.3 明确"缺参是参数错误、不是路径不存在"）。
     *
     * <p>{@code data} 是分页信封 {@code {list,total,page,pageSize,totalPages}}
     * （全项目唯一的 {@code PageResult}）。★ <b>失效条目返回脱敏占位</b>：
     * {@code invalid=true}、标题是固定文案「内容已失效」、{@code coverUrl} 与
     * {@code authorName} 为 {@code null} —— <b>不返回原标题 / 封面 / 作者</b>（R-03）。
     * 占位条目**保留 {@code contentId}**（移出要它：失效条目必须删得掉，见 G11）。
     *
     * <p>★ <b>分页单位是收藏记录行</b>（G12）：{@code total} **包含**失效条目，
     * 与 {@code /api/v1/favorite-folders} 的 {@code itemCount} 是同一个数；一整页都是失效条目
     * 也照常返回满页（跳过 ⇒ 用户看不到 ⇒ 也就永远清不掉）。
     *
     * <p>⚠️ <b>需登录 + 夹必须属于自己</b>（404 不存在 / 403 不是我的）：
     * 本端点是"我的夹内视角"；他人视角只有 {@link #publicFolders}（只给名称 + 视频数，
     * 没有夹 id ⇒ 打不开别人的夹，R-08）与 {@link #publicFolderItems}（公开夹内容）。
     * 空夹 ⇒ {@code list=[]} + {@code total=0}（不 404）。
     *
     * <p>分页归一：{@code page} 缺省/非数字/≤0 → 1；{@code pageSize} 同上 →
     * {@value #FAVORITE_PAGE_SIZE_DEFAULT}，且恒 ≤ {@value #FAVORITE_PAGE_SIZE_MAX}
     * （各域自持常量，见 {@code PageParams} 类注释 —— 收藏夹与内容列表同量级，取 100/100）。
     * ⚠️ 参数名是 {@code pageSize}（与 {@code PageResult.pageSize} 及全项目其余分页端点一致）；
     * 任务清单里速记的 {@code size} **不采用** —— 两个名字表达同一件事会让契约分裂。
     *
     * @param folderId 目标夹（必传）；他人的夹 → 403、不存在 → 404
     */
    @RequiresLogin
    @GetMapping("/api/v1/favorites")
    public ApiResponse<PageResult<FavoriteItemVO>> listItems(@CurrentUserId long userId,
                                                             @RequestParam long folderId,
                                                             @RequestParam(required = false) String page,
                                                             @RequestParam(required = false) String pageSize) {
        return ApiResponse.success(favoriteService.listFolderItems(userId, folderId,
                PageParams.normalizePage(page),
                PageParams.normalizePageSize(pageSize, FAVORITE_PAGE_SIZE_MAX, FAVORITE_PAGE_SIZE_DEFAULT)));
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
            default -> throw new ParamException(ErrorCode.PARAM_INVALID, "isPrivate格式错误，应为 0/1 或 true/false");
        };
    }
}
