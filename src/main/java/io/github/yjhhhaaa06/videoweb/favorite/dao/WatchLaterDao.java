package io.github.yjhhhaaa06.videoweb.favorite.dao;

import io.github.yjhhhaaa06.videoweb.favorite.model.vo.WatchLaterItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 稍后再看数据访问（表 {@code watch_later}，V4 建，收藏三期 T17）。
 *
 * <h2>三条口径（写调用方前先读）</h2>
 * <ol>
 *   <li>★ <b>{@code add} 是 upsert（移到最前）</b>：{@code INSERT … ON DUPLICATE KEY UPDATE
 *       create_time = CURRENT_TIMESTAMP}（撞 {@code uk_user_content}）—— 重复 add **不新增行、
 *       不改 id、只把时间刷成当前** ⇒ 按 {@code create_time DESC} 重排即到最前（B 站实测，
 *       需求篇 §六）。**不写"先查后插"**（并发下先查后插必然漏，同 {@code uk_folder_content} 的处置）。</li>
 *   <li>★ <b>{@code remove} 不校验内容存在性</b>（同 **G11** 的理由）：直接按
 *       {@code (user_id, content_id)} 删行 —— 失效条目**必须删得掉**（列表里看得见占位就得能清）。
 *       幂等：命中的行少给（甚至 0 行）不算错。</li>
 *   <li>★ <b>容量判据排除自身</b>：{@link #countByUserIdExcludingContent} 的 {@code content_id <> ?}
 *       让"已在池中的内容再次加入（移到最前）"在**已满时仍成功**，只有"加**新**内容且已满"才 400
 *       （见 {@code WatchLaterService.add}）。</li>
 * </ol>
 *
 * <h2>表上的两个索引（各自服务什么）</h2>
 * <ul>
 *   <li>{@code uk_user_content (user_id, content_id)} —— 同一用户同一内容至多一条：
 *       重复 add **撞唯一键**（靠它 + upsert 实现"移到最前"），不靠"先查后插"；</li>
 *   <li>{@code idx_user_time (user_id, create_time)} —— 列表按加入时间倒序分页。</li>
 * </ul>
 *
 * <p>🚫 <b>红线：本域不得接 feed</b>（G10）—— 不得调 {@code FeedDelivery.deliver}、不得发
 * {@code ContentPublishedEvent}（同 {@code FavoriteItemDao}）。
 */
@Mapper
public interface WatchLaterDao {

    /**
     * 加入稍后再看（**insert 或"移到最前"**，T17）。
     *
     * <p>SQL：{@code INSERT INTO watch_later (user_id, content_id) VALUES (?, ?)
     * ON DUPLICATE KEY UPDATE create_time = CURRENT_TIMESTAMP}。
     * 撞 {@code uk_user_content} 时走 UPDATE 分支 ⇒ 只改 {@code create_time}，
     * {@code id} / {@code user_id} / {@code content_id} 原样不动。
     *
     * <p>⚠️ 内容存在性与"是否视频"由调用方先校验（{@code ContentDao.findAliveContentType}，
     * 带 {@code is_deleted = 0} 且 {@code type = 1}）—— 本条 SQL **不碰 content 表**。
     * 容量也由调用方先判（{@code countByUserIdExcludingContent}）。
     *
     * @param userId    当前登录用户
     * @param contentId 内容 id（调用方保证是未软删视频）
     * @return 受影响行数（1 = 新插；2 = 撞唯一键并真的改了时间；0 = 撞键但值未变 —— 都不参与判定）
     */
    int upsert(@Param("userId") long userId, @Param("contentId") long contentId);

    /**
     * 从稍后再看移出若干条（T17：批量移出）。
     *
     * <p>★ 刻意**不校验内容存在性**（G11 红线）：一加校验，失效条目就永远清不掉。
     * 幂等：命中的行少给（甚至 0 行）不算错（同 {@code FavoriteItemDao.deleteByFolderAndContentIds}）。
     *
     * @param userId     当前登录用户（只删他自己的）
     * @param contentIds 要移出的内容 id 集合（**非空**；空集由 service 拦成 400，
     *                   否则 {@code IN ()} 是 SQL 语法错）
     * @return 实际删除的行数（≤ 列表长度；差异来自本就不在池中的 id，属正常）
     */
    int deleteByUserAndContentIds(@Param("userId") long userId,
                                  @Param("contentIds") List<Long> contentIds);

    /**
     * 该用户稍后再看的条数、**排除给定内容自身**（T17 容量判据）。
     *
     * <p>★ 排除自身是"移到最前"语义的关键：{@code add} 一个**已在池中**的内容时，
     * 它自己不占"新增名额" ⇒ 池已满（100）时它**仍加得进去**（只把时间刷新）。
     * 若不过滤自身，已满时连"重复 add"都会被 400 —— 与 B 站实测语义相悖（需求篇 §六）。
     *
     * @param userId    当前登录用户
     * @param contentId 本次要 add 的内容 id（从计数里排除）
     * @return 除该内容外的池内条数
     */
    int countByUserIdExcludingContent(@Param("userId") long userId, @Param("contentId") long contentId);

    /**
     * 该用户稍后再看的**总条数**（T17：{@code /watchlater/list} 的 {@code total}）。
     *
     * <p>数的是 {@code watch_later} **行**（与列表 {@code LIMIT/OFFSET} 同一对象）——
     * 失效条目**照常计入、照常占位**（与收藏夹列表 G12 / R-07 同口径）。
     *
     * @param userId 当前登录用户
     * @return 总条数（空池 ⇒ 0）
     */
    int countByUserId(@Param("userId") long userId);

    /**
     * 该用户稍后再看的一页（T17）—— 按**加入时间倒序**；
     * 只返回**本域两列**（{@code contentId} / {@code addedTime}），
     * {@code invalid} / {@code title} / {@code coverUrl} / {@code authorName} 由
     * {@code WatchLaterService.list} 经 {@code ContentService.loadContentVOs} 组装。
     *
     * <h2>★ 三条口径（与 {@code FavoriteItemDao.findPageByFolderId} 同款）</h2>
     * <ol>
     *   <li><b>判失效不在这里</b>：内容可不可用由 content 装载契约折叠（R-11），本 SQL 不碰 content 表；</li>
     *   <li><b>分页单位是 {@code watch_later} 行</b>：一整页都是失效条目也**照常返回整页**
     *       （跳过 ⇒ 用户看不到 ⇒ 永远清不掉）；</li>
     *   <li><b>顺序 {@code create_time DESC, id DESC}</b>：{@code create_time} 是秒精度，
     *       同秒多条并列时补 {@code id DESC} 作稳定 tie-breaker（不重不漏）。</li>
     * </ol>
     *
     * @param userId 当前登录用户
     * @param offset 偏移量（service 由 {@code (page-1) * pageSize} 算出；用 {@code long} 防 int 溢出）
     * @param limit  页大小（已由 Controller 归一到 1..域级上限）
     * @return 该页的记录（含失效行；本域两列有值，其余四列待 service 组装）；空池 ⇒ 空列表
     */
    List<WatchLaterItemVO> findPageByUserId(@Param("userId") long userId,
                                            @Param("offset") long offset,
                                            @Param("limit") int limit);
}
