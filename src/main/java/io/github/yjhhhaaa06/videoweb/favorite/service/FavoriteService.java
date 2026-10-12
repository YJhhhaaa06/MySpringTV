package io.github.yjhhhaaa06.videoweb.favorite.service;

import io.github.yjhhhaaa06.videoweb.common.exception.ConflictException;
import io.github.yjhhhaaa06.videoweb.common.exception.ForbiddenException;
import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.model.ContentFavoriteCount;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import io.github.yjhhhaaa06.videoweb.favorite.cache.AfterCommit;
import io.github.yjhhhaaa06.videoweb.favorite.cache.FavoriteCache;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 收藏业务（收藏夹 CRUD / 收藏与移出 / 状态与计数 / 夹内分页）。
 *
 * <h2>分期落点（T1 骨架 / T2 夹 CRUD / T3 私密与公开夹 / T4 写路径 / T5 状态与计数）</h2>
 * 夹 CRUD = **T2**；私密开关 + 他人公开夹 = **T3**；写路径（收藏/移出/移动）= **T4**；
 * 状态与计数 = **T5**（本类 {@link #getFavoriteStatus} / {@link #getFavoriteCount}）；
 * 夹内分页 = **T6**（本类 {@link #listFolderItems}）；判存在（互动域的组装契约）= **T9**
 * （本类 {@link #isFavorited} / {@link #batchIsFavorited}）；
 * 缓存层 = **T11**（{@code favorite.cache} 包；本类四条读 / 四个写点的接线，见下节"写路径的缓存时机"）。
 * 本类承担**装配关系**（controller → 本类 → 两个 DAO）与**边界声明**，其余方法随任务逐个补。
 *
 * <h2>一期红线（写方法前先读，别让实现把口径吃掉）</h2>
 * <ul>
 *   <li>🚫 <b>本域不得接 feed</b>（G10）：不调 {@code FeedDelivery.deliver}、不发
 *       {@code ContentPublishedEvent}。feed 关注流的**唯一**驱动源是
 *       {@code ContentService} 发的该事件。⚠️ 这是"不要做某事"，**写成测试必假绿**（无实现可去掉），
 *       只能靠 review 守。</li>
 *   <li><b>一期不写缓存、不写冗余计数</b>（G7）—— ★ 那条**已在二期全部兑现**：
 *       T11 落地 {@code favorite.cache} 包（{@code FavoriteCache} + {@code FavoriteRedisOps}，
 *       设计篇 §3.7）；T12 启用冗余计数列 {@code content.favorite_count}（R-06）：本类
 *       {@link #recountFavoriteCounts} 在**四个写点的事务内**批量重算维护它（§3.2，
 *       不做增量——理由见该方法注释），读路径经 {@code FavoriteCache#loadFavoriteCount}
 *       读列（T12 切换）。</li>
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
 *   <li>{@link #addItem} / {@link #removeItems} —— 一期各自只有一条写、**刻意不加事务**
 *       （§四"纯读/单写不为取连接套事务"）；★ **T12 起升级为 {@code @Transactional}**：
 *       计数重算（{@link #recountFavoriteCounts}）是第二条写，且与关系行有**完整性耦合**
 *       （列与明细必须在同一提交里变化，否则不变量在提交窗口内被打破）——
 *       这正是"缺原子性需求就不加事务"的反面：原子性需求出现了，事务就该加。
 *       {@code addItem} 懒建路径仍是"空默认夹"合法终态的口径不变，见 {@link #addItem} 方法注释；</li>
 *   <li>{@link #createFolder} / {@link #updateFolder} —— **各只有一条写**，本身即原子 ⇒
 *       **不加事务**（§四"纯读/单写不为取连接套事务"）；</li>
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
 * <h2>★ 写路径的缓存时机（T11）：**DB 提交之后**才碰缓存</h2>
 * 四个写点（{@link #addItem} / {@link #removeItems} / {@link #moveItem} / {@link #deleteFolder}）
 * 都在 DB 写**成功返回之后**才调 {@link AfterCommit#run} 注册缓存动作。
 * ⚠️ T12 起**四个写点全部有事务**（addItem / removeItems 因计数重算升级，见上节）⇒
 * 全部走"登记 {@code afterCommit} 回调"分支：事务回滚 ⇒ 回调**根本不执行** ⇒ 缓存不被污染
 * （与 AFTER_COMMIT 监听器同样的框架级保证）。{@link AfterCommit} 的"无同步活动 ⇒ 立即执行"
 * 分支仍是它的既有语义（现无调用方落在该分支，保留为通用机制）。
 * ⚠️ <b>不要改成 {@code favorite.event} 自域事件</b> —— 在
 * {@code @TransactionalEventListener} 的 {@code fallbackExecution=false} 默认值下，"无事务也要执行"
 * 的场景一旦出现（T12 之前曾有：{@code addItem}/{@code removeItems} 刻意无事务）事件会被
 * **静默丢弃**，且与"收藏域不该有 event 包"的既有定位冲突。取舍与代价登记在
 * {@code CURRENT_ISSUES.md} **I-11**。
 *
 * <h2>★ 写路径的缓存动作（T11）：只有 {@code add} 条件写，其余整体失效</h2>
 * 起因是收藏的**夹内唯一**模型（{@code uk_folder_content}）：同一用户可把同一内容收在多个夹里 ⇒
 * {@code add}/{@code remove} **不必然**改变"我收藏过"与收藏数，{@code move} 甚至可能是新增收藏。
 * 故四个写点分两档（逐条理由见 {@code FavoriteCache} 类注释）：
 * <ul>
 *   <li>{@link #addItem} ⇒ 条件 {@code SADD}（单调、恒安全）+ 该内容计数 {@code DEL}；</li>
 *   <li>{@link #removeItems} / {@link #moveItem} / {@link #deleteFolder} ⇒ 成员集**整体失效**
 *       （{@code DEL} 数据 key + 空标记），计数 {@code DEL}（删夹按受影响内容集批量
 *       {@code DEL}，见 {@link #deleteFolder}）。</li>
 * </ul>
 * ⚠️ 缓存动作**失败不影响已提交的业务**：{@code FavoriteCache} 的写/失效方法只 catch
 * {@code CacheUnavailableException} 并记日志，交由 TTL 自愈（不回抛、不 500）。
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

    /**
     * 收藏缓存（T11）。⚠️ 注入点是**具体类型** {@code FavoriteCache}（不是抽象基类
     * {@code RedisOps}）—— 后者标 {@code @Component} 就会 {@code NoUniqueBeanDefinitionException}
     * （见 {@code RedisOps} 类注释）。
     *
     * <p>本类**只**经它读写缓存，不直接碰 Redis：键形状 / 三态 / 失效集都收在 {@code favorite.cache} 包内，
     * 换实现不改本类（一期的测试是这层的回归网）。
     */
    private final FavoriteCache favoriteCache;

    public FavoriteService(FavoriteFolderDao folderDao, FavoriteItemDao itemDao, ContentDao contentDao,
                           ContentService contentService, FavoriteCache favoriteCache) {
        this.folderDao = folderDao;
        this.itemDao = itemDao;
        this.contentDao = contentDao;
        this.contentService = contentService;
        this.favoriteCache = favoriteCache;
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
     * 收藏数（{@code content.favorite_count}）由 {@link #recountFavoriteCounts} 在**本事务内**
     * 对受影响内容集批量重算（T12；一期是"不维护任何计数列"，R-06 的"列只建不用"已随二期退役）。
     *
     * @param folderId 目标夹
     */
    @Transactional
    public void deleteFolder(long userId, long folderId) {
        FavoriteFolder folder = requireOwnedFolder(userId, folderId);
        if (folder.defaultFolder()) {
            throw new ConflictException("默认收藏夹不可删除");
        }
        // ★ T11 缓存（1/2）+ T12 重算范围：失效/重算集必须**在删之前**取 —— 收藏数既是缓存
        //   key（content:favoriteCount:{id}）也是重算范围，都是**内容维度**的，
        //   记录删掉后就再也查不出"刚才动了哪些内容"。与下面两条 DELETE 同事务
        //   ⇒ 取到的 id 集与实际删掉的行不会错位（并发收藏也不会）。
        List<Long> affectedContentIds = itemDao.findContentIdsByFolderId(folderId);
        // 先删条目再删夹：同事务 ⇒ 要么都成，要么都不成。
        // 顺序上"先子后父"只为与将来的外键（若加）一致，当前无外键、顺序不影响正确性。
        itemDao.deleteByFolderId(folderId);
        folderDao.deleteById(folderId);
        // ★ T12 计数重算：两条 DELETE 已在本事务内生效 ⇒ 聚合看到的是"删完之后"的状态；
        //   受影响内容集复用上面那份（findContentIdsByFolderId 的 Javadoc 明说 T12 复用，
        //   别新写一条同形 SQL）。空夹 ⇒ 空集 ⇒ recount 直接返回（合法路径，不发 SQL）。
        recountFavoriteCounts(affectedContentIds);
        // ★ T11 缓存（2/2）：**提交后**才碰（本方法有事务 ⇒ 回滚则整个回调不执行，缓存不被污染）。
        //   成员集整体失效（"删夹"使该用户的收藏集变化，逐条 SREM 无法表达"是否仍在别的夹"）
        //   + 受影响内容的计数批量失效（一趟 DEL，不循环往返）。
        AfterCommit.run(() -> {
            favoriteCache.invalidateUserFavorites(userId);
            favoriteCache.invalidateFavoriteCounts(affectedContentIds);
        });
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
     * <h2>★ 事务（T12 起）：{@code @Transactional} —— 重算引入了第二条写</h2>
     * 一期本方法**刻意不加事务**："建默认夹 + 插收藏记录"没有完整性耦合，留下的"空默认夹"
     * 也是合法终态 —— 那条口径仍然成立。但 T12 的计数重算（{@link #recountFavoriteCounts}）
     * 是**第二条写**，且与插入行有**完整性耦合**（列与明细必须同进同退，否则不变量
     * {@code favorite_count == COUNT(DISTINCT user_id)} 在提交窗口内被打破）——
     * 原子性需求出现了，事务就该加（事务边界篇 §一"记录 + 计数"同款）。
     * 懒建（{@link #ensureDefaultFolder}）因此也进了事务：并发首次收藏仍由
     * {@code uk_user_default} 兜重复，事务只改变"何时可见"，不改变其安全性。
     *
     * @param userId    当前登录用户（即收藏人）
     * @param folderId  目标夹；{@code null} = 没选夹 → 收进（必要时懒建的）默认夹
     * @param contentId 内容 id；内容不存在或已失效 → 404
     */
    @Transactional
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
        // ★ T12 计数重算（本事务内）：插入已生效 ⇒ 聚合看到"收完之后"的状态。
        //   409 路径在上一步就抛了（事务回滚），不会走到这里 ⇒ 被拒的请求不留计数痕迹。
        recountFavoriteCounts(List.of(contentId));
        // ★ T11 缓存（提交后）：本方法**有事务**（T12 起）⇒ AfterCommit 登记 afterCommit 回调，
        //   回滚则整个回调不执行。409 路径同样不会走到这里 ⇒ 被拒的请求不留缓存痕迹。
        //   ① 成员侧走**唯一**的条件写 SADD：收藏的变化是**单调**的（收藏过 ⇒ 写后仍收藏过；
        //      "收进第二个夹"时 SADD 是幂等 no-op）⇒ 不需要知道"他以前是否收藏过这条"；
        //   ② 计数侧一律 DEL：本方法可能把收藏数 +1，也可能**不变**（该内容他已在别的夹），
        //      而我们不为此多查一次库 —— 失效让读回源永远给出正确答案（T12 后回源读列，
        //      而列已被上面那次重算与本事务对齐）。
        AfterCommit.run(() -> {
            favoriteCache.favoriteAdded(userId, contentId);
            favoriteCache.invalidateFavoriteCount(contentId);
        });
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
     * <p>⚠️ 一期是单条 {@code DELETE ... IN} 原生原子 ⇒ 不加事务；★ **T12 起加
     * {@code @Transactional}**：计数重算是第二条写，与删除行有完整性耦合（同 {@link #addItem}
     * 的论证）。重算范围 = 请求的 contentIds（幂等：命中 0 行的 id 重算只是把正确的值再写一遍）。
     *
     * @param userId     当前登录用户
     * @param folderId   从哪个夹移出；不存在 → 404、不是我的 → 403
     * @param contentIds 要移出的内容 id（可含失效内容；空 → 400）
     */
    @Transactional
    public void removeItems(long userId, long folderId, List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            throw new ParamException("没有要移出的内容（contentIds 不能为空）");
        }
        requireOwnedFolder(userId, folderId);
        itemDao.deleteByFolderAndContentIds(folderId, contentIds);
        // ★ T12 计数重算（本事务内）：删除已生效 ⇒ 聚合看到"删完之后"的状态。
        //   传**请求的 id 集**而不是"实际删掉的"（DELETE 无 RETURNING 取不到）——
        //   重算是幂等复算，多算未被命中的 id 只是写回一个本就正确的值。
        recountFavoriteCounts(contentIds);
        // ★ T11 缓存（提交后）：成员集**整体失效** —— "从某个夹移出" **不等于**"取消收藏"
        //   （同一内容可能仍收在别的夹里，逐条 SREM 会给出错的答案），而"这个人现在还收不收着它"
        //   要额外查一次库；对低频的移出操作，整体失效（下次读回源一次）是更划算的正确选择。
        //   ⚠️ 命中 0 行（陈旧页面 / 重复移出）**也照样失效**：失效是幂等的，
        //   而"少失效一次"会让缓存留着一个已变的答案，危险得多。
        AfterCommit.run(() -> {
            favoriteCache.invalidateUserFavorites(userId);
            favoriteCache.invalidateFavoriteCounts(contentIds);
        });
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
     * 两条关系写（插入目标夹 + 从源夹删除）必须同进同退，否则留下"两边都有"或"两边都没有"。
     * 先插后删让最常见的失败（目标夹已有同内容 ⇒ 撞 {@code uk_folder_content} ⇒ 409）
     * 发生在删除之前 —— 409 时源夹原样保留；即便 DB 在删除一步出错，事务回滚也兜住。
     * ★ T12 起事务里还有第三条语句：计数重算（{@link #recountFavoriteCounts}，见方法体注释
     * —— 任务书"三条路径"漏了 move，边缘态会打破不变量，用户 2026-10-12 拍板接入）。
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
        // ★ T12 计数重算（本事务内）。⚠️ 任务书原文只列 add / remove / deleteFolder 三条路径，
        //   但 move **必须**接入（用户 2026-10-12 拍板）：边缘态"源夹不含该内容"（陈旧页面 /
        //   前端传错 from）时插入照常成功、源侧删 0 行 ⇒ 等价于一次**新增收藏** —— 计数 +1，
        //   与 T11 在缓存侧记录的同一边缘态（I-11）同源。重算幂等，常态下只是把正确的值再写一遍。
        recountFavoriteCounts(List.of(contentId));
        // ★ T11 缓存（提交后）：**也走整体失效**，而不是"净效应为零 ⇒ 不写"。
        //   常态下 move 确实不改收藏态（人还是收着这条、收藏数也不变），但"源夹其实没含这条"
        //   （陈旧页面 / 前端传错 from）时 insert 照样成功、源侧删 0 行 ⇒ 等价于一次**新增收藏**；
        //   整体失效在那个边缘态下也给出正确答案，代价（移动后该用户下次读全量回源一次）
        //   与 move 的低频相称。取舍见 CURRENT_ISSUES.md I-11。
        AfterCommit.run(() -> {
            favoriteCache.invalidateUserFavorites(userId);
            favoriteCache.invalidateFavoriteCount(contentId);
        });
    }

    // ========================================================================
    // 收藏数冗余列的批量重算（T12：二期-3，§3.2 —— 四条写路径的统一出口）
    // ========================================================================

    /**
     * 对一批内容**事务内批量重算** {@code content.favorite_count}（T12 统一入口）。
     *
     * <h2>两步，各一趟往返</h2>
     * <ol>
     *   <li>{@link FavoriteItemDao#countDistinctUsersByContentIds} —— 一趟
     *       {@code GROUP BY content_id} 聚合（看到的是**本事务改完之后**的 favorite_item）；</li>
     *   <li>{@link ContentDao#updateFavoriteCounts} —— 一趟 CASE-WHEN 批量 UPDATE 写回绝对值。</li>
     * </ol>
     * 两步都必须运行在**调用方的事务里**（四个调用点全部 {@code @Transactional}）：
     * 计数列与 {@code favorite_item} 的增删同进同退，否则不变量
     * {@code favorite_count == COUNT(DISTINCT user_id)} 在提交窗口内被打破
     * （不变量本身由 T12 的独立 oracle 用例钉住）。
     *
     * <h2>★ 为什么是重算（绝对值），不是增量（±1）—— §3.2 的排除口径</h2>
     * {@code favorite_item} 的唯一键是 {@code uk_folder_content}（<b>夹内</b>唯一），
     * 与判定维度 {@code (user_id, content_id)} 不重合：同一用户并发往两个不同夹收同一内容时，
     * 两个事务都判"无行"、都 +1、撞不到唯一键 ⇒ 增量偏大且永不自愈；
     * {@code SELECT … FOR UPDATE} 也不可用（判"不存在"时 RC 下不加锁形同虚设、
     * RR 下 gap lock 共享 ⇒ 死锁）。重算一次消掉四件事：判"第一次收藏"、判"移出最后一个夹"、
     * 增量判错方向的那类并发漂移、"需要同维唯一键"。代价（每次写多一趟聚合 + 一趟批量 UPDATE）
     * 与写频相称。
     *
     * <p>⚠️ <b>残留并发窗口（如实登记，G14）</b>：重算消掉的是"增量判错方向"那一类漂移；
     * 两个**不同用户**并发首次收藏同一内容时，两个事务的聚合各自读提交前的快照，
     * **后提交者把陈旧绝对值写回**（content 行锁只保证写串行，不保证值新鲜）
     * ⇒ 列短暂偏小，直到该内容下次被任何写路径波及（重算幂等 ⇒ 自愈）。
     * 这是"拍板重算制 + 排除 FOR UPDATE"下接受的固有代价（无真实流量 ⇒ 该窗口纯为决策展示对象），
     * 不变量 oracle（测试夹具顺序化）验证的是"每条写路径之后不变量成立"，不验证并发交错的中间态。
     *
     * <h2>口径细节</h2>
     * <ul>
     *   <li><b>空集直接返回</b>：删空夹是合法路径（{@code deleteFolder} 的失效集可为空）；
     *       空 IN 是 SQL 语法错，且"没有内容受影响"时无需任何查询；</li>
     *   <li><b>先去重</b>：{@code removeItems} 的入参可能重复；同一 id 算两遍无害但白耗；</li>
     *   <li><b>聚合不出的 id 补 0</b>：收藏清空 / 从未有人收藏 / 幽灵 id 都不在 GROUP BY 结果里，
     *       但"清零"同样要落列（把最后一个夹移走后，计数必须从 1 变 0）；</li>
     *   <li><b>幽灵 id 无害</b>：content 行不存在的 id 在 UPDATE 的 {@code WHERE id IN} 里
     *       匹配 0 行，不报错；</li>
     *   <li>⚠️ 本方法是**私有**的、被同类 public 方法调用 ⇒ 即使打上 {@code @Transactional}
     *       也不会生效（自调用绕过代理）——刻意不打：它不界定事务边界，只运行在调用方事务里。</li>
     * </ul>
     *
     * <p>⚠️ 缓存不在本方法里碰：重算发生在事务内，缓存动作由各写点经 {@link AfterCommit}
     * 在**提交后**失效（见类注释"写路径的缓存时机"）—— 两个时机刻意分开。
     *
     * @param contentIds 受影响的内容 id 集（可为空 ⇒ 什么都不做）
     */
    private void recountFavoriteCounts(Collection<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return;
        }
        List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(contentIds));
        Map<Long, Long> counted = new HashMap<>(distinctIds.size());
        for (ContentFavoriteCount row : itemDao.countDistinctUsersByContentIds(distinctIds)) {
            counted.put(row.getContentId(), row.getFavoriteCount());
        }
        List<ContentFavoriteCount> full = new ArrayList<>(distinctIds.size());
        for (Long contentId : distinctIds) {
            // 聚合不出 ⇒ 0（收藏清空 / 从未有人收藏）。幽灵 id 也补 0，UPDATE 匹配 0 行自然无害。
            full.add(new ContentFavoriteCount(contentId, counted.getOrDefault(contentId, 0L)));
        }
        contentDao.updateFavoriteCounts(full);
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
     * <p>★ <b>T11 起走缓存</b>：{@code content:favoriteCount:{id}}（{@code GET}），
     * miss 时**单飞回源**并回填、Redis 失败降级直查 DB（接口不 500）。
     * ★ <b>T12 起回源读 {@code content.favorite_count} 列</b>（主键点查）—— 列由四条写路径的
     * **事务内批量重算**（{@link #recountFavoriteCounts}）维护，一期那套实时
     * {@code COUNT(DISTINCT)} 聚合已退役（其"笨查询"口径现在是 T12 oracle 用例的独立复算基准；
     * 耗时基线与切换记录见 {@code 收藏功能-分期与设计.md} §3.2）。
     * 端点契约（路径 / 匿名可访问 / {@code 0} 而不是 404）**一字不变** —— 缓存与列都是域内实现细节。
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
        return favoriteCache.getFavoriteCount(contentId);
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
     * （口径"看不了 ≈ 失效"，{@code 收藏功能-分期与设计.md 附录A.2} **R-11**）。
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
    // 判存在（T9：互动域的组装契约 —— 不 JOIN 的轻量版）
    // ========================================================================

    /**
     * 当前用户**是否收藏过**某内容（T9）—— 供互动域组装详情响应
     * （R-10 / {@code 收藏功能-分期与设计.md} §3.6）。
     *
     * <h2>★ 与 {@link #getFavoriteStatus} 的区别：只判存在、**不 JOIN**</h2>
     * {@code /favorite/status} 要回答"**收在哪些夹**"（故 JOIN {@code favorite_folder} 取夹名）；
     * 本方法只回答"我收藏过没有" ⟺ 存在一条 {@code (user_id, content_id)} 记录 ——
     * 一趟 {@code idx_user_content} 等值点查（DAO 口径见
     * {@code FavoriteItemDao.existsByUserAndContent}）。
     *
     * <p>★ <b>T11 的缓存落点就是本方法</b>：二期给"我是否收藏过"上了 Redis Set 后，
     * 读路径变成"空标记 → {@code SISMEMBER} → miss 回源"，换的是本方法**内部**的实现，
     * 签名与口径不变 —— 互动域只依赖本 Service 契约，对它无感（{@code 模块边界与依赖.md} §四）。
     * 实现委托 {@link FavoriteCache#isFavorited}（三态 / 单飞 / 降级都在那里）。
     *
     * <h2>口径</h2>
     * <ul>
     *   <li><b>不判内容有效性</b>：失效内容的记录照样在（R-03 / R-07）⇒ 仍答 true
     *       （"我还收着它"是事实）；</li>
     *   <li><b>不校验 userId 存在性</b>：查不到记录就是 false（同 {@code /favorite/status}
     *       的"空态是合法状态"口径，不给存在性开探测口）；</li>
     *   <li>同一人把同一内容收进**多个夹**也只答 true（判据是 {@code (user_id, content_id)}
     *       的记录存在性，不是按夹数）。</li>
     * </ul>
     *
     * @param userId    当前登录用户（互动域只在登录时才调本方法 —— 匿名没有"我的状态"）
     * @param contentId 内容 id
     * @return 收藏过 ⇒ {@code true}
     */
    public boolean isFavorited(long userId, long contentId) {
        return favoriteCache.isFavorited(userId, contentId);
    }

    /**
     * 批量形态（T9）：入参每个内容都回一条、缺失补 {@code false}（完整映射，不重不漏）——
     * 供互动域的**批量契约**组装列表（R-10 §3.6）。
     *
     * <p>★ <b>一趟查询，不循环调单条</b>：命中态用**一趟 pipeline** 做 N×{@code SISMEMBER}；
     * 降级态用一条 {@code user_id = ? AND content_id IN (…)} 取命中子集、再在 Java 侧补 false
     * —— 逐条调 {@link #isFavorited} 会让 100 条列表变 100 次点查（互动域类注释同款纪律）。
     *
     * <p>口径与 {@link #isFavorited} 完全一致（不 JOIN / 不判内容有效性 / 按人去重）；
     * T11 上缓存后签名与返回形状**一字不变**，实现委托 {@link FavoriteCache#batchIsFavorited}。
     *
     * @param userId     当前登录用户
     * @param contentIds 内容 id 集（空 / {@code null} ⇒ 空 Map；**不**发查询 —— 空 IN 是语法错）
     * @return contentId → 是否已收藏（每个入参 id 恰好一条；重复 id 收敛为一条）
     */
    public Map<Long, Boolean> batchIsFavorited(long userId, List<Long> contentIds) {
        return favoriteCache.batchIsFavorited(userId, contentIds);
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
