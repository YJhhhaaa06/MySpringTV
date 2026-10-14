package io.github.yjhhhaaa06.videoweb.favorite.service;

import io.github.yjhhhaaa06.videoweb.common.config.FavoriteLimitProperties;
import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import io.github.yjhhhaaa06.videoweb.favorite.dao.WatchLaterDao;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.WatchLaterItemVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 稍后再看业务（收藏三期 <b>T17</b>）。
 *
 * <h2>它是什么、不是什么</h2>
 * 一个**独立于收藏夹**的"稍后再看"池：**只装视频**、按加入时间倒序、可批量移出、可"移入收藏夹"。
 * ⚠️ 它与收藏**不共表**（用户 2026-10-14 拍板）：共表会让 {@code /favorite/status}、
 * {@code /favorite/count}、夹列表、公开夹四条读语义全要加"排除稍后再看"、漏一处即静默错答案；
 * 独立表后**收藏侧一行不改**（决策见 {@code 收藏功能-三期设计.md} §二 / §四）。
 *
 * <h2>★ 三条实测语义（需求篇 §六 / 骨架 §四.1）</h2>
 * <ol>
 *   <li><b>只能加视频</b>：{@link #add} / {@link #move} 先经 {@link #requireVideoContent}
 *       —— 不存在 / 已软删 ⇒ 404；存在但非视频（{@code type = 2} 图文）⇒ 400「只能添加视频」；</li>
 *   <li><b>已在池中再次添加 ⇒ 移到最前</b>：{@link #add} 走 {@code upsert}（撞唯一键只刷时间）
 *       —— 不新增行、不改 id；★ 容量判据用 {@code countByUserIdExcludingContent} **排除自身**
 *       ⇒ 已满（100）时"重复 add"仍成功，只有"加新内容且已满"才 400；</li>
 *   <li><b>除清单外无状态显示</b>：★ 本类**不提供**任何"是否在稍后再看"的查询 ——
 *       不加 status 端点、不让它出现在 {@code interaction} / {@code /favorite/status} 里
 *       （需求篇 §六；违规注入见 {@code WatchLaterIsolationTests}）。</li>
 * </ol>
 *
 * <h2>装载契约复用（不得自持第二份实现）</h2>
 * {@link #list} 先查本域页内两列（{@code WatchLaterDao.findPageByUserId}），再**一趟批量**调
 * {@link ContentService#loadContentVOs} 取展示字段；「契约取不到」即失效 ⇒ 占位（口径"看不了 ≈ 失效"，
 * R-11）。⚠️ 封面选取与 URL 前缀只有 content 域一份实现，本域**零接触** content/content_media/users 表
 * （一期 I-07 的教训）。
 * ⚠️ 占位分支（{@code content == null} 时三次赋值：标题「内容已失效」、封面 null、作者 null）
 * 与 {@code FavoriteService.toItemVO} 同口径、**代码各自一份**（两处 VO 类型不同）——
 * 改动一处必须同步另一处，由两组对角用例守着。
 *
 * <h2>事务</h2>
 * <ul>
 *   <li>{@link #add} / {@link #remove} —— **各只有一条写** ⇒ **不加事务**（事务边界篇 §一）；</li>
 *   <li>{@link #move} —— **两条写必须同进同退**（加入目标夹 + 从稍后再看删行）⇒ {@code @Transactional}。
 *       "加入目标夹"**复用 {@link FavoriteService#addItem}**（它已含内容存在性 / 归属 / 容量 /
 *       {@code favorite_count} 重算 / 缓存失效五件事）—— 别在本地重写这套。
 *       目标夹已有同内容 ⇒ 撞 {@code uk_folder_content} ⇒ 409，**事务回滚 ⇒ 稍后再看保留**。</li>
 * </ul>
 */
@Service
public class WatchLaterService {

    /** 只收视频（{@code content.type = 1}）；非视频（{@code 2} 图文）⇒ 400。 */
    private static final int CONTENT_TYPE_VIDEO = 1;

    /**
     * 失效内容的占位标题（R-03）—— 与 {@code FavoriteService.INVALID_CONTENT_TITLE} **同字面量**
     * （两处各持一份常量，因两个 Service 的占位对象不同；口径源头 = 需求篇 §六）。
     */
    private static final String INVALID_CONTENT_TITLE = "内容已失效";

    private final WatchLaterDao watchLaterDao;
    private final ContentDao contentDao;
    private final ContentService contentService;
    private final FavoriteService favoriteService;
    private final FavoriteLimitProperties limits;

    public WatchLaterService(WatchLaterDao watchLaterDao, ContentDao contentDao, ContentService contentService,
                             FavoriteService favoriteService, FavoriteLimitProperties limits) {
        this.watchLaterDao = watchLaterDao;
        this.contentDao = contentDao;
        this.contentService = contentService;
        this.favoriteService = favoriteService;
        this.limits = limits;
    }

    // ========================================================================
    // 写路径（add / remove / move）
    // ========================================================================

    /**
     * 加入稍后再看（{@code POST /watchlater/add}）。
     *
     * <h2>校验顺序</h2>
     * <ol>
     *   <li><b>内容 → 404/400</b>（{@link #requireVideoContent}）：不存在 / 已软删 ⇒ 404；
     *       非视频 ⇒ 400「只能添加视频」；</li>
     *   <li><b>容量 → 400</b>（{@link #requireCapacity}）：除自身外已达上限（默认 {@code 100}）⇒ 400。
     *       ★ 排除自身 ⇒ 已在池中的内容**重复 add 仍成功**（只把时间刷新 ⇒ 移到最前）；</li>
     *   <li><b>upsert</b>：撞唯一键只刷时间，不新增行 —— 幂等由唯一键兜，不"先查后插"。</li>
     * </ol>
     * ⚠️ 与收藏的容量口径一致：这是**状态型 400**（要查库、要知道是"哪个内容"）；
     * 但与收藏不同，这里**没有归属面**（池天然只属于当前用户），故不存在"400 先于/后于 403"的排序问题。
     *
     * @param userId    当前登录用户
     * @param contentId 内容 id；不存在 / 已软删 ⇒ 404、非视频 ⇒ 400
     */
    public void add(long userId, long contentId) {
        requireVideoContent(contentId);
        requireCapacity(userId, contentId);
        watchLaterDao.upsert(userId, contentId);
    }

    /**
     * 从稍后再看移出若干条（{@code POST /watchlater/remove}，批量）。
     *
     * <p>★ <b>本方法不校验内容存在性</b>（同 **G11** 的理由 —— 失效条目必须删得掉）：
     * 直接按 {@code (user_id, content_id)} 删行。幂等：本就不在池中（陈旧页面 / 已移出）⇒ 删 0 行，
     * **照常 200**。空列表 ⇒ 400（一个 id 都不给多半是调用方 bug，静默 200 会让"什么都没发生"
     * 伪装成"移出成功"）。
     *
     * @param userId     当前登录用户
     * @param contentIds 要移出的内容 id（可含失效内容；空 / {@code null} ⇒ 400）
     */
    public void remove(long userId, List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            throw new ParamException("没有要移出的内容（contentIds 不能为空）");
        }
        watchLaterDao.deleteByUserAndContentIds(userId, contentIds);
    }

    /**
     * 把一条内容从稍后再看**移入收藏夹**（{@code POST /watchlater/move}）。
     *
     * <h2>它是一个事务内的"加入目标夹 + 从池中移除"</h2>
     * 两条写必须同进同退：只加入不移除 ⇒ 内容既在池里又在夹里（"移"没完成）；只移除不加入 ⇒ 内容丢了。
     * "加入目标夹"**复用 {@link FavoriteService#addItem}**（内容存在性 / 目标夹归属 / 容量 /
     * {@code favorite_count} 重算 / 缓存失效都在那里，见其注释）；它标了 {@code @Transactional}
     * （REQUIRED）⇒ **汇入本方法的事务**，与随后的删行同进同退。
     *
     * <h2>失败面（先后即契约）</h2>
     * <ol>
     *   <li><b>内容 404 / 非视频 400</b>：{@link #requireVideoContent}（同 {@link #add}）；</li>
     *   <li><b>目标夹归属 404/403 · 已满 400 · 同内容 409 · 内容失效 404</b>：
     *       全部由 {@code FavoriteService.addItem} 给出（口径同 {@code /favorite/add}）；
     *       ⚠️ 目标夹已有同内容 ⇒ 409 且**事务回滚 ⇒ 稍后再看条目保留**（不是"移出后丢失"）；</li>
     *   <li><b>从池中移除</b>：命中 0 行也算成功（幂等）。</li>
     * </ol>
     *
     * <p>★ <b>{@code folderId} 缺省 = 默认夹（懒建）</b>（D4，2026-10-14 拍板）：语义与
     * {@code /favorite/add} 的 {@code folderId} 完全一致（缺省 ⇒ 收进必要时懒建的默认夹）。
     *
     * <p>⚠️ <b>不要求"内容本就在池中"</b>（与 {@code favorite/move} 对陈旧 source 的处置同款）：
     * 不在池中时，加入仍然成功、删行命中 0 行 —— 净效果等价于一次"加入收藏夹"，
     * 而那是用户用 {@code /favorite/add} 本就能做的事，**不构成越权、也不产生数据错误**。
     *
     * @param userId    当前登录用户
     * @param contentId 内容 id；不存在 / 已软删 ⇒ 404、非视频 ⇒ 400
     * @param folderId  目标夹；{@code null} = 没选夹 → 收进（必要时懒建的）默认夹
     */
    @Transactional
    public void move(long userId, long contentId, Long folderId) {
        requireVideoContent(contentId);
        favoriteService.addItem(userId, folderId, contentId);
        watchLaterDao.deleteByUserAndContentIds(userId, List.of(contentId));
    }

    // ========================================================================
    // 读路径（list）
    // ========================================================================

    /**
     * 稍后再看清单分页（{@code GET /watchlater/list?page=&pageSize=}）——
     * 按**加入时间倒序**；失效条目返回脱敏占位（与收藏夹列表**同口径**，R-03 / R-11）。
     *
     * <h2>★ 分页单位是 {@code watch_later} 行</h2>
     * {@code total} **包含**失效条目、失效记录**占一条、返回一条占位**；一整页都是失效条目
     * 也**照常返回整页**（跳过 ⇒ 用户看不到 ⇒ 也就永远清不掉，G12 / R-07 同款理由）。
     *
     * <h2>装载契约</h2>
     * 先查本域页内两列，再**一趟批量**调 {@link ContentService#loadContentVOs} 取展示字段；
     * 返回条数可能少于入参（"装不出来"的被契约跳过），"缺"就是本列表的失效判据。
     *
     * <p>⚠️ 本端点是"我的"私有视角（只需登录，无归属参数）。空池 ⇒ {@code list=[]} + {@code total=0}
     * （不是 404 —— 与"空态是合法状态"同口径）。
     *
     * @param userId   当前登录用户
     * @param page     页码（≥1，由 Controller 归一）
     * @param pageSize 页大小（1..域级上限，由 Controller 归一）
     */
    public PageResult<WatchLaterItemVO> list(long userId, int page, int pageSize) {
        int total = watchLaterDao.countByUserId(userId);
        // ⚠️ 用 long 防 int 溢出：page 无上限 ⇒ (page-1)*pageSize 溢出成负数会让 `OFFSET -…` 变 SQL 语法错 ⇒ 500。
        long offset = (long) (page - 1) * pageSize;
        List<WatchLaterItemVO> rows = watchLaterDao.findPageByUserId(userId, offset, pageSize);

        List<Long> contentIds = new ArrayList<>(rows.size());
        for (WatchLaterItemVO row : rows) {
            contentIds.add(row.getContentId());
        }
        Map<Long, ContentVO> byContentId = new HashMap<>(contentIds.size());
        for (ContentVO content : contentService.loadContentVOs(contentIds)) {
            byContentId.put(content.getId(), content);
        }
        List<WatchLaterItemVO> items = new ArrayList<>(rows.size());
        for (WatchLaterItemVO row : rows) {
            items.add(toItemVO(row, byContentId.get(row.getContentId())));
        }
        return new PageResult<>(items, total, page, pageSize);
    }

    // ========================================================================
    // 私有校验 / 组装（只写业务，不吃注解）
    // ========================================================================

    /**
     * 视频门禁（T17）：内容**未软删**且是视频，否则拒绝。
     *
     * <p>一趟 {@link ContentDao#findAliveContentType} 同时给出两条拒因：
     * 无行 / 已软删（{@code null}）⇒ 404「内容不存在」；非视频（{@code type != 1}）⇒ 400「只能添加视频」。
     * ★ 由 {@code WatchLaterFlowTests.非视频内容不能加入} 钉住；反向注入"去掉 type 判据"⇒ 必红。
     *
     * @param contentId 内容 id
     */
    private void requireVideoContent(long contentId) {
        Integer type = contentDao.findAliveContentType(contentId);
        if (type == null) {
            throw new NotFoundException("内容不存在");
        }
        if (type != CONTENT_TYPE_VIDEO) {
            throw new ParamException("只能添加视频");
        }
    }

    /**
     * 容量（T17）：除自身外已达 {@code video.favorite.watch-later-max-items}（默认 100）⇒ 400。
     *
     * <p>★ <b>排除自身</b>（{@code countByUserIdExcludingContent}）是"移到最前"语义的关键：
     * 已在池中的内容重复 add 时它自己不算"新增名额" ⇒ 已满时**仍成功**；只有加**新**内容且已满才 400。
     * ⚠️ **软上限**（{@code COUNT → INSERT} 非原子，同 T16 / {@code F-23}）：真并发下可瞬时越界，
     * 越界后自限；**读路径不因越界做任何隐藏或限制**。
     *
     * @param userId    当前登录用户
     * @param contentId 本次要 add 的内容 id（从计数里排除）
     */
    private void requireCapacity(long userId, long contentId) {
        int max = limits.watchLaterMaxItems();
        if (watchLaterDao.countByUserIdExcludingContent(userId, contentId) >= max) {
            throw new ParamException("稍后再看已满（最多 " + max + " 条）");
        }
    }

    /**
     * 组装一条列表条目：正常卡片抄 content 契约的展示字段，失效行**构造**占位（R-03 / R-11）。
     *
     * <p>★ 与 {@code FavoriteService.toItemVO} **同口径、各自一份**（两处 VO 类型不同）：
     * 失效行三个字段**一个都不能给**（只换标题不置空作者 ⇒ 作者仍可见；只置空封面不换标题 ⇒ 标题仍可见），
     * 故这里是**一个分支内的三次赋值**，不写成三个各自判断的条件。
     *
     * @param row     Mapper 查出的行（本域两列有值；**就地修改**并返回 —— 本 VO 一身两职）
     * @param content content 装载契约给出的展示条目；{@code null} = 装不出来 ⇒ 占位
     */
    private WatchLaterItemVO toItemVO(WatchLaterItemVO row, ContentVO content) {
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
}
