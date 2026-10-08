package io.github.yjhhhaaa06.videoweb.favorite.service;

import io.github.yjhhhaaa06.videoweb.common.exception.ConflictException;
import io.github.yjhhhaaa06.videoweb.common.exception.ForbiddenException;
import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.favorite.dao.FavoriteFolderDao;
import io.github.yjhhhaaa06.videoweb.favorite.dao.FavoriteItemDao;
import io.github.yjhhhaaa06.videoweb.favorite.model.entity.FavoriteFolder;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteFolderVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收藏业务（收藏夹 CRUD / 收藏与移出 / 状态与计数 / 夹内分页）。
 *
 * <h2>分期落点（T1 只落骨架，T2 交付夹 CRUD）</h2>
 * 夹 CRUD = **T2**（本文件当前的全部方法）；私密与公开夹 = T3；写路径（收藏/移出/移动）= T4；
 * 状态与计数 = T5；夹内分页 = T6。本类此刻承担的是**装配关系**
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
 *   <li>★ <b>{@code remove} 不校验内容存在性</b>（G11）：见 {@code FavoriteItemDao} 的口径 1。
 *       T2 移出的是"整个夹"（{@link #deleteFolder}），不涉及内容存在性；收藏/移出归 T4。</li>
 *   <li>跨域只走三条合法通道：对方的 Service 公开方法 / 同事务内的 DAO 薄依赖 / 自己的
 *       {@code event} 包订阅别人的事件（见 {@code architecture/tech/模块边界与依赖.md} §三）。
 *       ⚠️ 本域**没有** {@code event} 包也不该有：收藏不是"别人要响应的事实"。
 *       ★ T2 的四个方法**零跨域调用**（只碰自己两张表）——所以本任务不需要在 ArchUnit 之外
 *       新增任何依赖表态。</li>
 * </ul>
 *
 * <h2>事务</h2>
 * 写路径（尤其是"删夹一并删条目" R-02）必须同事务，切分口径见
 * {@code architecture/tech/事务边界.md}；{@code @Transactional} 标在**本类的 public 入口**上，
 * 别标在同类内部调用的私有方法上（自调用绕过代理 ⇒ 静默失效）。
 * <ul>
 *   <li>{@link #deleteFolder} —— **两条写必须同进同退**（删条目 + 删夹）⇒ {@code @Transactional}；</li>
 *   <li>{@link #createFolder} / {@link #renameFolder} —— **各只有一条写**，本身即原子 ⇒ **不加事务**
 *       （§四"纯读/单写不为取连接套事务"）；</li>
 *   <li>{@link #listMyFolders} —— 单条 SELECT ⇒ 不加事务（§四）。</li>
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
 * 本类**不提供**"取或建默认夹"的方法 —— 那是 T4 收藏路径的单点，没有别的消费方。
 *
 * <h2>归属与不存在的错误码口径（对齐既有语义，别自创）</h2>
 * <table>
 *   <caption>改名 / 删除的两道校验</caption>
 *   <tr><th>情形</th><th>异常</th><th>HTTP</th></tr>
 *   <tr><td>夹不存在</td><td>{@link NotFoundException}「收藏夹不存在」</td><td>404</td></tr>
 *   <tr><td>夹存在但不是我的</td><td>{@link ForbiddenException}「只能操作自己的收藏夹」</td><td>403</td></tr>
 *   <tr><td>是默认夹还要删</td><td>{@link ConflictException}「默认收藏夹不可删除」</td><td>409</td></tr>
 *   <tr><td>名称为空 / 超长</td><td>{@link ParamException}</td><td>400</td></tr>
 * </table>
 * "不存在 404 / 不是自己的 403"是本仓既有口径（{@code CommentService.deleteCommentByUser}、
 * {@code ContentService} 的作者写路径同款），**刻意不改成"一律 404"**：
 * 与既有端点保持一致比这里多得一点隐私更值。
 *
 * <p>⚠️ <b>改名路径上 400 判在 403/404 之前</b>（"参数形态"先于"资源归属"）。这不影响正常前端
 * （它不会拿非法名字去改别人的夹），但**必须钉死并写进测试**：否则两者都会"看起来对"，
 * 而将来重构时谁先谁后会随手漂移。口径理由见 {@link #renameFolder} 方法体注释。
 */
@Service
public class FavoriteService {

    /** 名称长度上限 —— 与列 {@code favorite_folder.name varchar(100)} 对齐。 */
    private static final int NAME_MAX_LENGTH = 100;

    private final FavoriteFolderDao folderDao;
    private final FavoriteItemDao itemDao;

    public FavoriteService(FavoriteFolderDao folderDao, FavoriteItemDao itemDao) {
        this.folderDao = folderDao;
        this.itemDao = itemDao;
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
     * （{@code GET /favorite/folder/public?userId=X}，T3），它有它自己的过滤条件。
     * 让"我的"和"他人的"共用一条 SQL、靠"传没传 userId"分流，正是 R-08 否决的形态。
     */
    public List<FavoriteFolderVO> listMyFolders(long userId) {
        return folderDao.findByUserIdWithItemCount(userId);
    }

    /**
     * 改名（默认夹也能改）。
     *
     * <p>⚠️ <b>T2 只做改名</b>。分期篇 §3.3 把该端点画成"改名 / 简介 / 私密开关（部分更新）"，
     * 但它们各有归属：<b>私密开关归 T3</b>（那一期的可观察落点是他人视角端点），
     * <b>简介在需求篇里是「待定」</b>（未纳入一期）。故本方法只收 {@code name} 且**必填**。
     * T3 加入可选字段时，形态应变为"给了才改"的部分更新——届时 {@code name} 也变可选，
     * 由 T3 一起调整（本条注释即交接说明）。
     *
     * @param name 新夹名；空白或超长 ⇒ 400（同 {@link #createFolder} 的口径，
     *             不让"新建能建的名字"与"改名能改成的名字"两套规则漂移）
     *             —— ⚠️ **400 判在 403/404 之前**（见方法体注释与类注释的表注）
     */
    public void renameFolder(long userId, long folderId, String name) {
        // ★ 顺序：**参数形态先于资源归属**。两类拒因（400 / 403）互不依赖，谁先谁来定契约，
        //   故这里钉死并写进测试（改名的错误线用例）。
        //   理由：① 参数校验**不碰库**（省一次 SELECT，且不合法输入根本不需要知道夹在不在）；
        //   ② 让"非法输入 ⇒ 400"不随资源状态漂移（同一份非法请求，无论夹是谁的、在不在，都是 400）。
        String validName = requireValidName(name);
        FavoriteFolder folder = requireOwnedFolder(userId, folderId);
        folderDao.updateName(folder.getId(), validName);
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
    // 私有校验（只写业务，不吃注解 —— 运行在调用方事务里）
    // ========================================================================

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
