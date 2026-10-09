package io.github.yjhhhaaa06.videoweb.favorite.service;

import io.github.yjhhhaaa06.videoweb.common.exception.ConflictException;
import io.github.yjhhhaaa06.videoweb.common.exception.ForbiddenException;
import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import io.github.yjhhhaaa06.videoweb.favorite.dao.FavoriteFolderDao;
import io.github.yjhhhaaa06.videoweb.favorite.dao.FavoriteItemDao;
import io.github.yjhhhaaa06.videoweb.favorite.model.entity.FavoriteFolder;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteFolderBriefVO;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteFolderVO;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteItemVO;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteStatusVO;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.PublicFavoriteFolderVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 收藏业务（收藏夹 CRUD / 收藏与移出 / 状态与计数 / 夹内分页）。
 *
 * <h2>分期落点（T1 骨架 / T2 夹 CRUD / T3 私密与公开夹 / T4 写路径 / T5 状态与计数）</h2>
 * 夹 CRUD = **T2**；私密开关 + 他人公开夹 = **T3**；写路径（收藏/移出/移动）= **T4**；
 * 状态与计数 = **T5**（本类 {@link #getFavoriteStatus} / {@link #getFavoriteCount}）；
 * 夹内分页 = **T6**（本类 {@link #listFolderItems}）。本类承担**装配关系**
 * （controller → 本类 → 两个 DAO）与**边界声明**，其余方法随任务逐个补。
 *
 * <h2>一期红线（写方法前先读，别让实现把口径吃掉）</h2>
 * <ul>
 *   <li>🚫 <b>本域不得接 feed</b>（G10）：不调 {@code FeedDelivery.deliver}、不发
 *       {@code ContentPublishedEvent}。feed 关注流的**唯一**驱动源是
 *       {@code ContentService} 发的该事件。⚠️ 这是"不要做某事"，**写成测试必假绿**（无实现可去掉），
 *       只能靠 review 守。</li>
 *   <li>🚫 <b>一期不写缓存、不写冗余计数</b>（G7）：没有 {@code favorite.cache} 包，
 *       不维护 {@code content.favorite_count}（R-06：一期实时
 *       {@code COUNT(DISTINCT user_id)}，二期才上冗余列）。发现自己需要缓存 ⇒ 先停下改分期。</li>
 *   <li>★ <b>{@code remove} 不校验内容存在性</b>（G11）：见 {@link #removeItems} 与
 *       {@code FavoriteItemDao} 的口径 1。失效条目**能移出**；反向：{@code add}/{@code move}
 *       照常校验（{@code isContentExist} 带 {@code is_deleted = 0}），
 *       「失效不可移入」由此**自然产生**，没有专门分支。</li>
 *   <li>跨域只走三条合法通道：对方的 Service 公开方法 / 同事务内的 DAO 薄依赖 / 自己的
 *       {@code event} 包订阅别人的事件（见 {@code architecture/tech/模块边界与依赖.md} §三）。
 *       ⚠️ 本域**没有** {@code event} 包也不该有：收藏不是"别人要响应的事实"。
 *       T4 新增一条 **DAO 薄依赖**：{@code ContentDao.isContentExist}（收藏/移动前判内容存在，
 *       与 like 域对 content/comment 的薄依赖同形态——同事务内一条窄查询，不碰对方的
 *       service/model/cache）。
 *       ★ T6 残留提交（2026-10-09）又新增一条 **Service 契约**：夹内列表的展示字段与失效判定
 *       整体走 {@code ContentService.loadContentVOs}（缓存支撑的批量装载）—— 原先的
 *       "自持 SQL 直连 content / content_media / users + 封面规则副本 + URL 前缀第二份实现"
 *       （I-07 ①②③）已整体删除。</li>
 * </ul>
 *
 * <h2>事务</h2>
 * 写路径的切分口径见 {@code architecture/tech/事务边界.md}；{@code @Transactional} 标在
 * **本类的 public 入口**上，别标在同类内部调用的私有方法上（自调用绕过代理 ⇒ 静默失效）。
 * <ul>
 *   <li>{@link #moveItem} —— **两条写必须同进同退**（插入目标夹 + 从源夹删除）⇒
 *       {@code @Transactional}；先插后删：目标夹撞唯一键（409）时源夹必然未动；</li>
 *   <li>{@link #deleteFolder} —— **两条写必须同进同退**（删条目 + 删夹）⇒ {@code @Transactional}；</li>
 *   <li>{@link #createFolder} / {@link #updateFolder} / {@link #addItem}（显式夹）/
 *       {@link #removeItems} —— **各只有一条写**，本身即原子 ⇒ **不加事务**
 *       （§四"纯读/单写不为取连接套事务"）；{@code addItem} 懒建路径虽是两条写，但
 *       "空默认夹"是合法终态、与收藏记录无完整性耦合，见 {@link #addItem} 方法注释；</li>
 *   <li>{@link #listMyFolders} / {@link #listPublicFolders} / {@link #getFavoriteStatus} /
 *       {@link #getFavoriteCount} / {@link #listFolderItems} —— **纯读 ⇒ 不加事务**（§四）；
 *       {@link #listFolderItems} 是"本域一条 SELECT（{@code total}）+ 一次 content 契约批量读
 *       + 本域一条页内行 SELECT"，全是纯读、**没有原子性需求**
 *       （与 {@code ProfileService} 的"用户行 + 总数 + 窗口 id"同款）：
 *       页与总数之间若插入了新收藏，用户看到的是"翻页时刻的快照略有偏移"——
 *       这是 page/size 分页的固有语义，**不是**靠事务能修的（真要一致得上快照隔离）；
 *       状态那条虽有"查目录 + 推导布尔"，但只是**一趟查询 + 一次 Java 推导**，不是两条写。</li>
 * </ul>
 *
 * <h2>★ 懒建与"列表空态"为什么自洽（T2 前提自审）</h2>
 * R-01 拍板默认夹**懒建**（首次收藏时建，落点 T4），故"这个用户一个夹都没有"是**合法状态**：
 * 新注册用户在还没收藏过任何东西时，{@code GET /favorite/folder/list} 返回 {@code []}。
 * 看似"空列表很怪"，但另一条路（读时补建）更差：
 * <ul>
 *   <li>GET 变成**有副作用的写**（每次刷新列表都可能插一行）；</li>
 *   <li>且要求"读的时候必须有登录态能建夹"——把公开读能力绑死在写权限上；</li>
 *   <li>并发补建还要靠 {@code uk_user_default} 兜（本可以不引入的竞态）。</li>
 * </ul>
 * ⇒ 保持懒建 + 空列表；空态是**前端要渲染的一种正常响应**（T7），不是错误。
 * 本类不暴露独立的"建默认夹"端点 —— 懒建只发生在首次收藏的路径里
 * （{@link #ensureDefaultFolder}，T4 落地），没有别的消费方。
 *
 * <h2>归属与不存在的错误码口径（对齐既有语义，别自创）</h2>
 * <table>
 *   <caption>更新 / 删除的两道校验</caption>
 *   <tr><th>情形</th><th>异常</th><th>HTTP</th></tr>
 *   <tr><td>夹不存在</td><td>{@link NotFoundException}「收藏夹不存在」</td><td>404</td></tr>
 *   <tr><td>夹存在但不是我的</td><td>{@link ForbiddenException}「只能操作自己的收藏夹」</td><td>403</td></tr>
 *   <tr><td>是默认夹还要删</td><td>{@link ConflictException}「默认收藏夹不可删除」</td><td>409</td></tr>
 *   <tr><td>名称给了但为空 / 超长</td><td>{@link ParamException}</td><td>400</td></tr>
 *   <tr><td>更新时 name 与 isPrivate <b>一个都没给</b></td><td>{@link ParamException}</td><td>400</td></tr>
 * </table>
 * "不存在 404 / 不是自己的 403"是本仓既有口径（{@code CommentService.deleteCommentByUser}、
 * {@code ContentService} 的作者写路径同款），**刻意不改成"一律 404"**：
 * 与既有端点保持一致比这里多得一点隐私更值。
 *
 * <p>⚠️ <b>更新路径上 400 判在 403/404 之前</b>（"参数形态"先于"资源归属"）。这不影响正常前端
 * （它不会拿非法名字去改别人的夹），但**必须钉死并写进测试**：否则两者都会"看起来对"，
 * 而将来重构时谁先谁后会随手漂移。口径理由见 {@link #updateFolder} 方法体注释。
 *
 * <h2>★ 私密（{@code is_private}）的语义 —— 它的可观察落点只有一条：他人视角端点</h2>
 * 自己的夹自己永远看得见（{@code /folder/list} **含私密**），所以"设私密"这个动作
 * 在本域内部**没有任何可观察差异** —— 它的全部对外行为都发生在
 * {@code GET /favorite/folder/public?userId=X} 上（R-08）。
 * <ul>
 *   <li>公开端点**永远只返回 {@code is_private = 0}**，包括夹主本人来调也一样 ——
 *       本端点表达的是"他人视角"这条路径，"我的"走 {@code /folder/list} 另一条。
 *       ⚠️ 刻意**不**注入 {@code @CurrentUserId} 做"是本人就显示私密"的分支：
 *       分支一写错就是**直接泄露私密夹**，而这条分支一期没有任何需求（R-08 只要"他人视角"）；</li>
 *   <li>默认夹**可以**设为私密（需求篇只规定"默认夹删不掉"；B 站口径同此），
 *       不写针对 {@code is_default} 的特判；</li>
 *   <li>更新路径**不得**因为"私密与否"改变错误码（404/403/400 的口径对公开夹与私密夹一视同仁），
 *       否则等于给"这个夹是不是私密"开了个探测口（T2 交接红线③）；</li>
 *   <li>⚠️ {@code is_private} 是**载荷字段**，不参与任何权限判定：私密夹的主人照常能改名 / 改回公开 /
 *       删除它（判定只用"归属"与"是否默认夹"两条，见 {@link #requireOwnedFolder}）。</li>
 * </ul>
 */
@Service
public class FavoriteService {

    /** 名称长度上限 —— 与列 {@code favorite_folder.name varchar(100)} 对齐。 */
    private static final int NAME_MAX_LENGTH = 100;

    /** 懒建默认夹的名字（需求篇 §二「默认收藏夹」；用户之后可照常改名，重名不校验——同 {@link #createFolder}）。 */
    private static final String DEFAULT_FOLDER_NAME = "默认收藏夹";

    /**
     * 失效内容的占位标题（R-03：标题位写「内容已失效」）。
     *
     * <p>⚠️ 它是**对外的显示文案**，不是错误提示 —— 与"原标题"互斥：失效条目**任何情况下**
     * 都不带原标题（否则等于把作者已删的内容重新暴露出去）。
     */
    private static final String INVALID_CONTENT_TITLE = "内容已失效";

    private final FavoriteFolderDao folderDao;
    private final FavoriteItemDao itemDao;
    private final ContentDao contentDao;
    private final ContentService contentService;

    public FavoriteService(FavoriteFolderDao folderDao, FavoriteItemDao itemDao, ContentDao contentDao,
                           ContentService contentService) {
        this.folderDao = folderDao;
        this.itemDao = itemDao;
        this.contentDao = contentDao;
        this.contentService = contentService;
    }

    // ========================================================================
    // 夹 CRUD（T2）
    // ========================================================================

    /**
     * 新建收藏夹（**自建夹**）。
     *
     * <p>成功响应 {@code data} 是字符串「创建成功」——沿袭本仓全部写端点的信封形状
     * （{@code /like/*}、{@code /follow/*}、{@code /comment/*} 都是中文文案，不是对象）。
     * 前端建完夹要拿 id 的话**重新拉一次列表**即可（列表本就带 id），不值得为此改信封形状。
     *
     * <h2>刻意不做的三件事</h2>
     * <ol>
     *   <li><b>不查重名</b>：表上没有 {@code (user_id, name)} 唯一键 ⇒ 同名夹是合法状态
     *       （B 站未公示该规则，属"治理"，三期谈）。故**不能**写"先 SELECT 同名再 INSERT"——
     *       那既拦不住并发，又平白多一次查询，还会把"允许重名"这个事实伪装成"碰巧没撞上"。</li>
     *   <li><b>不做容量上限</b>：一期反面清单明确不做（B 站数字未公示，别照抄）。</li>
     *   <li><b>不建默认夹</b>：R-01 懒建，落点是 T4 的首次收藏。</li>
     * </ol>
     *
     * @param name 夹名；空白或超长 ⇒ 400
     */
    public void createFolder(long userId, String name) {
        folderDao.insertFolder(userId, requireValidName(name));
    }

    /**
     * 我的收藏夹列表（含每夹条目数），**含私密夹**。
     *
     * <p>顺序契约（默认夹置顶 + 创建序）在 {@code FavoriteFolderDao.findByUserIdWithItemCount}
     * 的 SQL 里；本方法只做透传，不排序（排序要么在 SQL、要么在 Java，**不两处各来一半**）。
     *
     * <p>⚠️ 只能看**自己**的夹：本方法签名里没有"看谁的"参数 —— 他人视角是**另一条端点**
     * （{@link #listPublicFolders}），它有它自己的过滤条件。
     * 让"我的"和"他人的"共用一条 SQL、靠"传没传 userId"分流，正是 R-08 否决的形态。
     */
    public List<FavoriteFolderVO> listMyFolders(long userId) {
        return folderDao.findByUserIdWithItemCount(userId);
    }

    /**
     * 他人视角的公开夹列表（{@code GET /favorite/folder/public?userId=X}）——
     * 私密（{@code is_private = 1}）的夹**完全不出现**，这就是"私密"一期的可观察落点（R-08）。
     *
     * <h2>三条口径（写改动前先读）</h2>
     * <ol>
     *   <li><b>不校验 userId 存在性</b>：查不到就是空列表（返回 {@code []}，不是 404）。
     *       404 会给"这个用户存不存在"开探测口，而本端点对"用户不存在"与"用户没有任何公开夹"
     *       本就给不出不同答案（空态都是合法状态，同 R-01 的懒建口径）；</li>
     *   <li><b>与当前登录用户无关</b>：本方法签名里没有"当前用户"参数，Controller 也不注入
     *       {@code @CurrentUserId} —— 夹主本人调这条端点同样只看得到公开夹（"我的"走
     *       {@link #listMyFolders}）。刻意不加"是本人就显示私密"的分支：分支写错即泄露，
     *       且一期没有该需求（R-08 只要"他人视角"）；</li>
     *   <li><b>过滤只在这一条 SQL 上</b>（{@code findPublicByUserIdWithItemCount} 的
     *       {@code AND f.is_private = 0}）：这条过滤是 T3 反向验证的注入点 ——
     *       去掉它，{@code FavoritePrivacyTests} 必须变红（T3 验收；T8 复核）。</li>
     * </ol>
     *
     * <p>顺序与条目数口径同 {@link #listMyFolders}（SQL 里复用同一顺序：默认夹置顶 + 创建序）。
     */
    public List<PublicFavoriteFolderVO> listPublicFolders(long userId) {
        return folderDao.findPublicByUserIdWithItemCount(userId);
    }

    /**
     * 部分更新：改名 / 私密开关（**给了才改**；T2 的"只改名且 name 必填"在 T3 升级为本形态）。
     *
     * <p>分期篇 §3.3 把该端点画成"改名 / 简介 / 私密开关（部分更新）"，三者归属：
     * <b>私密开关 = T3</b>（本任务落地）；<b>简介在需求篇里是「待定」</b>（未纳入一期，不实现）。
     * T2 交接①明确要求在此把 {@code name} 一并改成可选 —— 否则"文档说可部分更新、
     * 实现却要求 name 必填"会长期漂移。
     *
     * <h2>参数契约（顺序即契约，见方法体注释）</h2>
     * <ul>
     *   <li>{@code name}：{@code null} = 不改名；给了就按 {@link #createFolder} 同一口径校验
     *       （空白 / 超长 ⇒ 400）——"新建能建的名字"与"改名能改成的名字"不允许两套规则；</li>
     *   <li>{@code isPrivate}：{@code null} = 不动它；格式（{@code 0/1/true/false} 等）由
     *       Controller 先行解析，非法格式在**进本方法之前**已是 400；</li>
     *   <li>两者都 {@code null} ⇒ 400「没有要修改的字段」——空更新多半是调用方漏传字段的 bug，
     *       静默 200 会让它**永远不被发现**（"改成功了"与"什么都没改"在响应上不可区分）；</li>
     *   <li>默认夹**可以**改名、也**可以**设私密（需求篇只规定"默认夹删不掉"）。</li>
     * </ul>
     * ⚠️ 本方法**不**依 {@code is_private} 做任何权限判定（它是载荷字段，见类注释"私密语义"节）。
     *
     * @param name      新夹名或 {@code null}；空白 / 超长 ⇒ 400
     * @param isPrivate 私密开关或 {@code null}
     *                  —— ⚠️ **400（含"两个都没给"）判在 403/404 之前**（见方法体注释与类注释的表注）
     */
    public void updateFolder(long userId, long folderId, String name, Boolean isPrivate) {
        // ★ 顺序：**参数形态先于资源归属**。两类拒因（400 / 403）互不依赖，谁先谁来定契约，
        //   故这里钉死并写进测试（改名的错误线用例在 T2；"空更新 400"与
        //   "非法 isPrivate 400"在 T3 的用例里，且都覆盖"对别人的夹也返回 400"）。
        //   理由：① 参数校验**不碰库**（省一次 SELECT，且不合法输入根本不需要知道夹在不在）；
        //   ② 让"非法输入 ⇒ 400"不随资源状态漂移（同一份非法请求，无论夹是谁的、在不在，都是 400）。
        if (name == null && isPrivate == null) {
            throw new ParamException("没有要修改的字段（name / isPrivate 至少提供一个）");
        }
        String validName = name == null ? null : requireValidName(name);
        FavoriteFolder folder = requireOwnedFolder(userId, folderId);
        folderDao.updateNameAndPrivacy(folder.getId(), validName, isPrivate);
    }

    /**
     * 删除收藏夹 —— **夹内的收藏记录一并删除**（R-02），两者必须同事务。
     *
     * <p>★ <b>默认夹拒绝删除</b>（需求篇 §二「删不掉」）：判据是行上的 {@code is_default}，
     * 不是名字（用户可以把默认夹改名叫"默认收藏夹"，也可以把自建夹改名成同一个名字 ——
     * 按名字判会拦错人）。
     *
     * <p>⚠️ 这里**只删本域两张表**，不碰内容（内容不归收藏所有）：
     * "夹里的视频消失"是**关系消失**，视频本身当然还在（需求篇 §二原话）。
     * 也**不维护任何计数列**（R-06：一期 {@code favorite_count} 不读不写）。
     *
     * @param folderId 目标夹
     */
    @Transactional
    public void deleteFolder(long userId, long folderId) {
        FavoriteFolder folder = requireOwnedFolder(userId, folderId);
        if (folder.defaultFolder()) {
            throw new ConflictException("默认收藏夹不可删除");
        }
        // 先删条目再删夹：同事务 ⇒ 要么都成，要么都不成。
        // 顺序上"先子后父"只为与将来的外键（若加）一致，当前无外键、顺序不影响正确性。
        itemDao.deleteByFolderId(folderId);
        folderDao.deleteById(folderId);
    }

    // ========================================================================
    // 收藏写路径（T4：add / remove / move）
    // ========================================================================

    /**
     * 收藏一条内容进一个夹（{@code POST /favorite/add}）。
     *
     * <h2>校验顺序（对齐 {@code /like/*} 写路径「先校验存在性」的参照）</h2>
     * <ol>
     *   <li><b>内容存在性 → 404「内容不存在」</b>：{@code isContentExist} 的 SQL 带
     *       {@code is_deleted = 0} ⇒ 失效内容（作者删 1 / 下架 2）进不来 ——
     *       「失效不可移入」**自然产生**，没有专门分支（分期篇 §3.5 的设计根因）。
     *       ★ 放在**最前**还有一个懒建路径特有的理由：见下文"刻意不做"；</li>
     *   <li><b>夹归属 → 404/403</b>（显式夹时）：复用 {@link #requireOwnedFolder}，
     *       与改名/删除同一口径；</li>
     *   <li><b>重复收藏 → 409</b>：不预查，撞唯一键 {@code uk_folder_content} 由
     *       {@code DuplicateKeyException} 全局映射（并发安全，"幂等拒绝"——
     *       第二次收藏不产生副作用、也不算错误状态被 500 化）。</li>
     * </ol>
     *
     * <h2>★ {@code folderId == null}：默认夹懒建（R-01 的唯一落点）</h2>
     * 需求篇 §二：「收藏时没选夹，内容自动进默认收藏夹」。新用户**没有任何途径**
     * 先拿到默认夹 id（夹列表是空态、一期没有"建默认夹"端点），所以"不带夹收藏"
     * 必须由本方法就地懒建 —— 这是全系统**唯一**会创建默认夹的路径。
     * 懒建靠 {@code ON DUPLICATE KEY UPDATE} 撞 {@code uk_user_default} 跳过（见
     * {@code FavoriteFolderDao.insertDefaultFolderIfAbsent}），并发首次收藏不会重复建夹。
     *
     * <h2>刻意不做：懒建路径不加 {@code @Transactional}</h2>
     * "建默认夹 + 插收藏记录"是两条写，但二者**没有完整性耦合**：即使后者失败，
     * 留下的"空默认夹"也是合法终态 —— 它正是该用户首次收藏成功后会拥有的状态
     * （对照 {@link #moveItem}：留下的是"两边都有"的不一致，才必须同事务）。
     * 且内容校验在最前 ⇒ 404/409 路径不会留下任何行（"被拒的请求不留痕"），
     * 剩下的只有 DB 级故障，为它套事务是"缺原子性需求硬加事务"（事务边界篇 §7.1.5）。
     *
     * @param userId    当前登录用户（即收藏人）
     * @param folderId  目标夹；{@code null} = 没选夹 → 收进（必要时懒建的）默认夹
     * @param contentId 内容 id；内容不存在或已失效 → 404
     */
    public void addItem(long userId, Long folderId, long contentId) {
        if (!contentDao.isContentExist(contentId)) {
            throw new NotFoundException("内容不存在");
        }
        long targetFolderId;
        if (folderId == null) {
            targetFolderId = ensureDefaultFolder(userId);
        } else {
            requireOwnedFolder(userId, folderId);
            targetFolderId = folderId;
        }
        itemDao.insertItem(targetFolderId, userId, contentId);
    }

    /**
     * 从一个夹移出若干条收藏记录（{@code POST /favorite/remove}，批量：夹内勾选多条一次移出）。
     *
     * <h2>★ 红线：本方法**不校验内容存在性**（G11，T4 最重要的口径）</h2>
     * 直接按 {@code (folder_id, content_id)} 删行。失效内容的收藏记录**能移出** ——
     * 用户在夹里看得见占位卡片，若这里加了存在性校验，占位就**永远清不掉**
     * （B 站实测口径，R-07）。这与 {@code /like/*} 写路径「先校验存在性」的惯例
     * **刻意不同**，也与 {@link #addItem}/{@link #moveItem} **刻意不对称** ——
     * 两处校验、一处不校验，是同一条业务规则的两面（分期篇 §3.5 的表格）。
     *
     * <h2>其余口径</h2>
     * <ul>
     *   <li><b>夹归属照常校验</b>（404/403）：G11 只豁免"内容"，不豁免"夹" ——
     *       移出的前提是动自己的夹；</li>
     *   <li><b>幂等</b>：记录不存在（陈旧页面 / 已被移出）⇒ 删除 0 行，**照常 200**，
     *       不报 404/409。不做"先查记录存在性"的预检 —— 批量场景下查与删之间的并发移出
     *       会造成假错，且删除本就是单向、无冲突的操作（{@code FavoriteItemDao} 同口径）；</li>
     *   <li><b>别的夹不受影响</b>：DELETE 只作用于 {@code folderId} 这一个夹
     *       （需求篇 §三「若该内容还在别的夹里，那些夹里的不受影响」）；</li>
     *   <li><b>空列表 → 400</b>：一个 id 都不给是调用方 bug，静默 200 会让
     *       "什么都没发生"伪装成"移出成功"（同 {@link #updateFolder} 空更新的口径）。</li>
     * </ul>
     *
     * <p>⚠️ 单条 {@code DELETE ... IN} 原生原子 ⇒ 不加 {@code @Transactional}（事务边界篇 §一）。
     *
     * @param userId     当前登录用户
     * @param folderId   从哪个夹移出；不存在 → 404、不是我的 → 403
     * @param contentIds 要移出的内容 id（可含失效内容；空 → 400）
     */
    public void removeItems(long userId, long folderId, List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            throw new ParamException("没有要移出的内容（contentIds 不能为空）");
        }
        requireOwnedFolder(userId, folderId);
        itemDao.deleteByFolderAndContentIds(folderId, contentIds);
    }

    /**
     * 把一条收藏从 A 夹挪到 B 夹（{@code POST /favorite/move}）。
     *
     * <h2>★ 为什么 move 是独立端点，而不是前端的 remove + add 两次调用</h2>
     * T4 前提自审的结论（G13 授权自行决断，但事务边界要讲清楚）：两次 HTTP 调用之间
     * 没有任何原子性可言 —— remove 成功、add 失败（目标夹撞唯一键 / 内容恰好失效）时，
     * 条目已经从源夹消失且**不会出现在目标夹**：正常内容表现为"条目丢了"，
     * 失效内容更糟 —— B 站口径是"失效条目不可移动但**保留原位**"（R-07），
     * 而两段式会把它从原位删掉。故 move 必须是**一个事务内的单端点**。
     *
     * <h2>事务与顺序：先插后删（{@code @Transactional}）</h2>
     * 两条写（插入目标夹 + 从源夹删除）必须同进同退，否则留下"两边都有"或"两边都没有"。
     * 先插后删让最常见的失败（目标夹已有同内容 ⇒ 撞 {@code uk_folder_content} ⇒ 409）
     * 发生在删除之前 —— 409 时源夹原样保留；即便 DB 在删除一步出错，事务回滚也兜住。
     * ★ {@code from == to} 不写专门分支：插入撞自己的唯一键 ⇒ 自然 409、什么都不变。
     *
     * <h2>口径细节</h2>
     * <ul>
     *   <li><b>内容存在性校验</b>（同 {@link #addItem}）：失效内容 → 404，条目**留在原位**
     *       ——「失效不可移动」自然产生；</li>
     *   <li><b>两个夹都校验归属</b>（404/403）：源夹与目标夹都得是我的；</li>
     *   <li><b>移动后的收藏时间是新的</b>：实现是"目标夹新增一行 + 源夹删一行"，
     *       新行的 {@code create_time} 是移动时刻（可读作"收进目标夹的时间"）——
     *       需求篇对"移动是否保留原收藏时间"无约定，取最简单且自洽的实现（T6 夹内列表
     *       按 {@code create_time} 倒序，移动过的条目排在前面）。</li>
     * </ul>
     *
     * @param userId       当前登录用户
     * @param fromFolderId 源夹；不存在 → 404、不是我的 → 403
     * @param toFolderId   目标夹；同上；已有同内容 → 409（源夹保留）
     * @param contentId    内容 id；不存在或已失效 → 404（条目留在源夹）
     */
    @Transactional
    public void moveItem(long userId, long fromFolderId, long toFolderId, long contentId) {
        if (!contentDao.isContentExist(contentId)) {
            throw new NotFoundException("内容不存在");
        }
        requireOwnedFolder(userId, fromFolderId);
        requireOwnedFolder(userId, toFolderId);
        // 先插后删：见方法注释"事务与顺序"。
        itemDao.insertItem(toFolderId, userId, contentId);
        itemDao.deleteByFolderAndContentIds(fromFolderId, List.of(contentId));
    }

    // ========================================================================
    // 状态与计数（T5：读路径）
    // ========================================================================

    /**
     * 当前用户对某内容的收藏状态（{@code GET /favorite/status}）——需求篇 §三「打开内容页能看到
     * "已收藏"，并知道**收在了哪些夹**」。
     *
     * <h2>★ 一趟查询 + 一次推导，{@code isFavorited} 不是第二处事实源</h2>
     * 只打一次 {@link FavoriteItemDao#findFoldersByUserAndContent} 拿"收在哪些夹"，
     * {@code isFavorited} 由 {@code folders.isEmpty()} **推导**（收藏必有归属 ⇒
     * 已收藏 ⟺ 至少落在一个夹里，结构性成立）。不另发一条 {@code EXISTS} ——
     * 两条查询会引入"两处事实源"，且毫无必要。不变量由
     * {@code FavoriteStatusAndCountTests} 钉死。
     *
     * <h2>刻意不做的事</h2>
     * <ul>
     *   <li><b>不校验内容存在性</b>：状态只反映"我收没收藏"这个事实，内容不存在 / 从没被收藏
     *       都返回 {@code favorited=false, folders=[]}（200，不是 404）—— 与
     *       {@code /like/content/status} 同款；且状态端点不该给"内容存不存在"开探测口；</li>
     *   <li><b>不判内容有效性</b>：失效内容（含已删下架与"装不出来"的，R-11）的记录仍在，故
     *       "我还收着它"照常显示（R-03 不自动清理；占位渲染是 T6 夹内列表的事）；</li>
     *   <li><b>别人的收藏不影响</b>：判据是 {@code (当前用户, 内容)}。</li>
     * </ul>
     *
     * <p>⚠️ 只查**当前用户**自己的记录 ⇒ 需登录（{@code @RequiresLogin} + {@code @CurrentUserId}）；
     * 与匿名可访问的 {@link #getFavoriteCount} 是**两条**端点、两个信任边界。
     */
    public FavoriteStatusVO getFavoriteStatus(long userId, long contentId) {
        List<FavoriteFolderBriefVO> folders = itemDao.findFoldersByUserAndContent(userId, contentId);
        FavoriteStatusVO status = new FavoriteStatusVO();
        status.setFolders(folders);
        status.setIsFavorited(!folders.isEmpty());
        return status;
    }

    /**
     * 某内容的收藏数（{@code GET /favorite/count}）—— <b>按人去重</b>（R-06，需求篇 §六）。
     *
     * <p>实时 {@code COUNT(DISTINCT user_id)}（口径与耗时基线见
     * {@link FavoriteItemDao#countDistinctUserByContentId}）；一期不读不写
     * {@code content.favorite_count} 列（R-06 —— 那列一期只建不用）。
     *
     * <p>★ <b>公开数据、匿名可访问</b>：需求篇 §三「内容页显示"多少人收藏了它"」，而内容页
     * 匿名可看 ⇒ 本端点**无** {@code @RequiresLogin}、**无** {@code @CurrentUserId}。
     * ⚠️ 与 {@code /like/content/count} 的"需登录"**刻意不同**：那条是 TV 的
     * {@code /like} 前缀保护惯性（老 pytest 断言无 token 401），收藏是新域、无此包袱，
     * 按"这个数谁看得到"的真实口径定（收藏数本就是公开展示数）。
     *
     * <p>不校验 {@code contentId} 存在性：不存在 / 无人收藏都是 {@code 0}（不 404）。
     */
    public long getFavoriteCount(long contentId) {
        return itemDao.countDistinctUserByContentId(contentId);
    }

    // ========================================================================
    // 夹内列表分页（T6：读路径）
    // ========================================================================

    /**
     * 夹内内容分页（{@code GET /favorite/list?folderId=&page=&pageSize=}）——
     * 需求篇 §三「打开某个夹，按**收藏时间倒序**看里面的内容」。
     *
     * <h2>★ 分页单位是<b>收藏记录行</b>，不是"内容"（G12 / R-07）</h2>
     * 失效条目**占一条、返回一条脱敏占位**，一整页都是失效条目也**照常返回整页**。
     * ⚠️ 理由不是"顺便"，而是可操作性的前提：跳过的后果是用户**看不到**这些条目，
     * 看不到 ⇒ 也就**永远清不掉**（R-07 把"可移出"作为失效条目的保留理由）。
     * 故 {@code total} 与"每夹条目数"（{@link #listMyFolders} 的 {@code itemCount}）
     * 必须是同一个数 —— 两条端点各说一套，用户就会看到"夹上写 5 条、进去只有 3 条"。
     *
     * <h2>★ 判失效走 content 装载契约，占位形态在本域（T6 残留提交 2026-10-09）</h2>
     * 先查本域页内行（{@code FavoriteItemDao.findPageByFolderId}，只有
     * {@code id} / {@code contentId} / {@code favoriteTime}），再**一趟批量**调
     * {@link ContentService#loadContentVOs}（缓存支撑——跨域只走对方 Service 契约，
     * 见 {@code 模块边界与依赖.md} §四）取展示字段；**契约"取不到"即失效** ——
     * 不存在 / 已删下架 / 媒体损坏 / 未知类型统一渲染占位
     * （口径"看不了 ≈ 失效"，{@code CURRENT_NEEDS.md} **R-11**）。
     * 占位形态仍是本域的显示决策：标题 = 「内容已失效」、封面与作者 = {@code null}
     * ——"内容可不可用"归 content 域、"失效长什么样"归本域，两个注入点独立（反向验证都能红）。
     * ⚠️ 别把这段搬去复用 {@code ProfileService} 的填充逻辑：那处是"取不到就跳过"，
     * 与收藏夹的"占位保留"是两套口径（{@code ProfileService.java:109}）。
     * ⚠️ 已知边缘态（R-11 已拍板接受）：数据损坏（如视频行缺失）时本列表占位，但
     * {@code add}/{@code move}（{@code isContentExist} 只看 {@code is_deleted}）仍收得进。
     *
     * <h2>归属与不存在的口径</h2>
     * 复用 {@link #requireOwnedFolder}：夹不存在 ⇒ 404、不是我的 ⇒ 403
     * （与改名 / 删除 / 移出同一口径，见类注释的表）。
     * ⚠️ 本端点是**"我的"视角**，他人视角一期只有 {@link #listPublicFolders}
     * （名称 + 视频数，没有夹 id ⇒ 打不开别人的夹，R-08）。
     *
     * <h2>刻意不做</h2>
     * <ul>
     *   <li><b>不过滤失效</b>：那正是"占位"要表达的东西；
     *       {@code total} 与页内行都由 {@code favorite_item} 决定（content 契约只提供展示字段，
     *       一个"装不出来"都不会让行消失）；</li>
     *   <li><b>不加事务</b>：本域两条纯 SELECT + 一次 content 契约批量读，无原子性需求
     *       （见类注释"事务"节）；</li>
     *   <li><b>空夹返回 {@code list=[]} + {@code total=0}</b>（不是 404）—— 与 R-01 懒建的
     *       "空态是合法状态"同款口径。</li>
     * </ul>
     *
     * @param userId   当前登录用户（夹必须属于他）
     * @param folderId 目标夹；不存在 → 404、不是我的 → 403
     * @param page     页码（≥1，由 Controller 归一）
     * @param pageSize 页大小（1..域级上限，由 Controller 归一）
     */
    public PageResult<FavoriteItemVO> listFolderItems(long userId, long folderId, int page, int pageSize) {
        requireOwnedFolder(userId, folderId);
        int total = itemDao.countByFolderId(folderId);
        // ⚠️ 用 long 防 int 溢出：`page` 没有上限（PageParams 只归一 ≤0 与非数字），
        //    page=2147483647 时 (page-1)*pageSize 会溢出成负数 ⇒ `OFFSET -200` 是 SQL 语法错 ⇒ 500。
        //    一个"翻到不存在的页"的请求不该 500 —— 与 FeedService / CommentService 同款写法。
        long offset = (long) (page - 1) * pageSize;
        List<FavoriteItemVO> rows = itemDao.findPageByFolderId(folderId, offset, pageSize);
        // ★ 一趟批量读 content 基本信息（缓存支撑；一次请求一次调用，不逐条 —— 批量语义原样透传）。
        //   返回条数可能少于入参（"装不出来"的被契约跳过），"缺"就是本列表的失效判据（R-11）。
        List<Long> contentIds = new ArrayList<>(rows.size());
        for (FavoriteItemVO row : rows) {
            contentIds.add(row.getContentId());
        }
        Map<Long, ContentVO> byContentId = new HashMap<>(contentIds.size());
        for (ContentVO content : contentService.loadContentVOs(contentIds)) {
            byContentId.put(content.getId(), content);
        }
        List<FavoriteItemVO> items = new ArrayList<>(rows.size());
        for (FavoriteItemVO row : rows) {
            items.add(toItemVO(row, byContentId.get(row.getContentId())));
        }
        return new PageResult<>(items, total, page, pageSize);
    }

    // ========================================================================
    // 私有校验（只写业务，不吃注解 —— 运行在调用方事务里）
    // ========================================================================

    /**
     * 组装一条列表条目：正常卡片抄 content 契约的展示字段，失效行**构造**占位（R-03 / R-11）。
     *
     * <h2>★ 这是"失效长什么样"的唯一落点</h2>
     * 需求篇 §六「失效条目里能看到什么：只有占位…**不返回原标题 / 封面 / 作者信息**」。
     * 失效行的三个字段**一个都不能给**：只换标题不置空作者 ⇒ 作者仍可见；
     * 只置空封面不换标题 ⇒ 标题仍可见。故这里是**一个分支内的三次赋值**，
     * 不写成三个各自判断的条件（那会让"漏掉一件"看起来也像对的）。
     *
     * <p>⚠️ 与旧实现的差别：展示字段**不再从 SQL 的原值里脱敏**，而是从 content 契约拿
     * ——"装不出来"的条目在契约里根本不存在 ⇒ 天然拿不到标题 / 封面 / 作者；
     * 封面 URL 也已由 content 域拼好前缀（{@code ContentCache.jointUrl} 一份实现），
     * 本域不再持有第二份前缀实现（I-07 ②）。
     *
     * @param row     Mapper 查出的行（本域三列有值；**就地修改**并返回 —— 本 VO 一身两职）
     * @param content content 装载契约给出的展示条目；{@code null} = 装不出来 ⇒ 占位
     */
    private FavoriteItemVO toItemVO(FavoriteItemVO row, ContentVO content) {
        if (content == null) {
            row.setInvalid(Boolean.TRUE);
            row.setTitle(INVALID_CONTENT_TITLE);
            row.setCoverUrl(null);
            row.setAuthorName(null);
            return row;
        }
        row.setInvalid(Boolean.FALSE);
        row.setTitle(content.getTitle());
        row.setCoverUrl(content.getCoverUrl());
        row.setAuthorName(content.getAuthorName());
        return row;
    }

    /**
     * 默认夹懒建（R-01）：不存在则建（名字「默认收藏夹」），返回夹 id。
     *
     * <p>"存在才跳过"的并发安全由唯一键 {@code uk_user_default} 兜
     * （{@code FavoriteFolderDao.insertDefaultFolderIfAbsent} 的注释）——
     * 本方法因此**不需要**外层事务：两个并发的首次收藏要么一建一跳、要么都跳，殊途同归。
     *
     * <p>⚠️ 懒建之后按业务键回查 id（两次 mapper 调用可能各借各的连接，
     * 连接级 {@code LAST_INSERT_ID} 会取错，见 {@code findDefaultFolderId} 注释）。
     * 回查为 {@code null} 只可能是唯一键兜底失效的库级异常 ⇒ 交给全局出口 500。
     */
    private long ensureDefaultFolder(long userId) {
        folderDao.insertDefaultFolderIfAbsent(userId, DEFAULT_FOLDER_NAME);
        Long id = folderDao.findDefaultFolderId(userId);
        if (id == null) {
            throw new IllegalStateException("默认夹懒建后查不到行（uk_user_default 应保证行存在）");
        }
        return id;
    }

    /**
     * 取回一个夹并校验归属（404 / 403 的分界线）。
     *
     * <p>⚠️ 本方法是**私有**的、被同类 public 方法调用 ⇒ 即使打上 {@code @Transactional}
     * 也不会生效（自调用绕过代理）。这里刻意**不打**：它只读一行，归属校验的原子性由
     * 调用方的事务（{@link #deleteFolder}）兜。
     */
    private FavoriteFolder requireOwnedFolder(long userId, long folderId) {
        FavoriteFolder folder = folderDao.findById(folderId);
        if (folder == null) {
            throw new NotFoundException("收藏夹不存在");
        }
        if (folder.getUserId() == null || folder.getUserId() != userId) {
            throw new ForbiddenException("只能操作自己的收藏夹");
        }
        return folder;
    }

    /**
     * 夹名归一与校验：去首尾空白后必须非空、且 ≤ 100 字符（列 {@code varchar(100)}）。
     *
     * <h2>为什么必须在这里拦，不能交给 DB</h2>
     * 超长名交给 MySQL 的后果是取决于 {@code sql_mode} 的
     * "截断（静默存错名字）或报错（{@code DataTruncation} ⇒ 全局出口 500）"——
     * 500 是"我们没料到的错"，而 400 是"你给的名字不合法"。**同一件事必须在同一层表达**。
     * 空白名不拦的话会存进一个"看不见的夹"（列表里一行空白，用户既点不进也删不掉）。
     *
     * <p>返回**归一后的值**（去首尾空白）供直接落库：调用方拿到的就是将要写入的值，
     * 不给"我 trim 了但库里存的是原值"留缝。
     */
    private static String requireValidName(String name) {
        if (name == null || name.isBlank()) {
            throw new ParamException("收藏夹名称不能为空");
        }
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new ParamException("收藏夹名称不能超过 " + NAME_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }
}
