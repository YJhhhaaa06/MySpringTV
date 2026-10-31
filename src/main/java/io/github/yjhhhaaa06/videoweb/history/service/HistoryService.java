package io.github.yjhhhaaa06.videoweb.history.service;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.exception.ServiceBusyException;
import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.model.ContentType;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import io.github.yjhhhaaa06.videoweb.history.cache.HistoryReportBuffer;
import io.github.yjhhhaaa06.videoweb.history.dao.PlayDedupDao;
import io.github.yjhhhaaa06.videoweb.history.dao.WatchHistoryDao;
import io.github.yjhhhaaa06.videoweb.history.event.PlayCountChangedEvent;
import io.github.yjhhhaaa06.videoweb.history.model.vo.HistoryItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 播放记录业务：写路径（进度上报 {@code PUT /api/v1/contents/{id}/progress} 与播放事件
 * {@code POST /api/v1/contents/{id}/play}）+ 读路径
 * （历史列表 {@code GET /api/v1/history}、续播位置 {@code GET /api/v1/contents/{id}/progress}）。
 *
 * <p>设计 / 口径的唯一事实源：{@code architecture/domain/history/history.md} §四（写）/ §七（读）。
 * 本类只做"校验 → 写"与"取页 → 组装"的编排：
 *
 * <table>
 *   <caption>两端点的分工（拆端点是本域的核心决策）</caption>
 *   <tr><th>端点</th><th>写什么</th><th>事务</th><th>为什么不合并</th></tr>
 *   <tr><td>{@code /report}</td><td>只写 {@code progress_seconds}（一条 ODKU）</td><td>**不加**</td>
 *       <td>单条 ODKU 自带原子性；且它**不需要判首播** ⇒ 绕开 affected-rows 歧义</td></tr>
 *   <tr><td>{@code /play}</td><td>去重账本一行 + {@code content.play_count}</td><td>**加**</td>
 *       <td>两位必须同进同退，否则不变量 {@code play_count == COUNT(content_play_dedup)} 没有自愈路径</td></tr>
 * </table>
 *
 * <h2>校验复用内容域 Service（不新增读边）</h2>
 * 404（不存在 / 已软删 / 媒体损坏）与 400（图文）都靠
 * {@link ContentService#loadContentVOs} 一次装载判定 —— 它返回空列表即"取不到"，
 * 与收藏夹内列表的失效判据同源。顺带拿 {@code durationSeconds} 做进度 clamp，
 * **不新增 content 域方法、不新增 DAO 读边**（{@code 模块边界与依赖.md} §三 合法 Service 契约）。
 *
 * <h2>★ 不做的两件事（刻意）</h2>
 * <ul>
 *   <li><b>不做"进度只能增"</b>：后到即新（LWW）。重看从头会把进度改回 0 —— 用户拍板接受，
 *       代价见 {@code history.md} §十 #1；</li>
 *   <li><b>不按内容做特例</b>：图文在 {@code /play} 与 {@code /report} 都被 400 拦下，
 *       没有"图文也计播放量"的分支。</li>
 * </ul>
 *
 * <h2>`R-26`（2026-10-31）：历史可删 + 播放量改「每人·每内容·每自然日一次」</h2>
 * 旧口径把去重标记 {@code watch_history.play_counted} 长在历史行上 ⇒ 删历史会把标记一起删掉、
 * 再播被判首播、播放量重复 +1，于是"历史不可删"成了代价。现在标记搬进独立账本
 * {@code content_play_dedup}（{@link PlayDedupDao}）：**历史行只存进度、可以删；账本只增、删不到**。
 * 判首播仍是 {@code INSERT IGNORE} 的 1/0，但判定维度从"这对 (user, content) 有没有行"
 * 变成"**今天**这对 (user, content, play_date) 有没有行"。
 */
@Slf4j
@Service
public class HistoryService {

    /**
     * 失效内容的占位标题 —— 与 {@code WatchLaterService.INVALID_CONTENT_TITLE}、{@code FavoriteService}
     * 的**同字面量**（各持一份常量，因三处占位对象类型不同；口径源头 =
     * {@code architecture/domain/history/history-需求.md} §二「失效视频」）。
     */
    private static final String INVALID_CONTENT_TITLE = "内容已失效";

    /**
     * 一次批量删除的条数上限（`R-26`）。
     *
     * <p>★ 这不是拍脑袋的数字，判据有两条：① 实测 B站 历史页"全选"一次能选中 **259** 条
     * （2026-10-31 用户截图），上限必须明显大于它；② 批量 id 只能走 query（§2.5：DELETE 无 body），
     * 而请求行+请求头的容器上限是 **8 KB** —— 500 条 id 约 3.5 KB，留一半余量。
     * 没有上限的话，超过 URL 长度时的失败形态是容器抛 414/431，**不可解释**。
     */
    private static final int MAX_DELETE_BATCH = 500;

    private final WatchHistoryDao watchHistoryDao;
    private final PlayDedupDao playDedupDao;
    private final ContentService contentService;
    private final ContentDao contentDao;
    private final ApplicationEventPublisher events;
    private final HistoryReportBuffer reportBuffer;
    private final HistoryFlushService flushService;

    public HistoryService(WatchHistoryDao watchHistoryDao,
                          PlayDedupDao playDedupDao,
                          ContentService contentService,
                          ContentDao contentDao,
                          ApplicationEventPublisher events,
                          HistoryReportBuffer reportBuffer,
                          HistoryFlushService flushService) {
        this.watchHistoryDao = watchHistoryDao;
        this.playDedupDao = playDedupDao;
        this.contentService = contentService;
        this.contentDao = contentDao;
        this.events = events;
        this.reportBuffer = reportBuffer;
        this.flushService = flushService;
    }

    /**
     * 上报播放进度（{@code PUT /api/v1/contents/{id}/progress}，`T58` 起 contentId 进路径、方法定 PUT）：**校验后写 Redis 缓冲，不落库**。
     *
     * <p>★ `R-19`（2026-10-22）起的契约变更：本方法不再写 `watch_history`，落库由
     * {@code POST /api/v1/history/flush}（退出信号）或空闲轮询完成。接口形态（200 / 400 / 404）未变。
     *
     * <p>校验顺序（**免费校验 → 熔断探测 → 需要 IO 的校验**）：
     * <ol>
     *   <li>{@code progress < 0} ⇒ 400（纯内存判断，零成本，不因后端故障而改变语义）；</li>
     *   <li>缓冲后端不可用 ⇒ **503 + Retry-After**（★ 必须在 {@code loadContentVOs} **之前**：
     *       熔断时它会降级查库，先校验就等于替 DB 加了一次压，正好违反本方案的前提）；</li>
     *   <li>取不到内容 ⇒ 404；图文 ⇒ 400；</li>
     *   <li>超过时长的进度按**当前**时长 clamp（时长未知则不 clamp）——
     *       不报错：客户端心跳/重试本来就会偶尔越过末尾。</li>
     * </ol>
     *
     * <p>★ 本方法**不写 {@code play_count}**（{@code /play} 是唯一计数入口），也**不碰**
     * 去重账本 {@code content_play_dedup}（落库时同样不碰，见 {@code HistoryFlushService}）。
     */
    public void report(long userId, long contentId, int progressSeconds) {
        if (progressSeconds < 0) {
            throw new ParamException(ErrorCode.PARAM_INVALID, "播放进度不能为负数");
        }
        if (!reportBuffer.isBackingStoreAvailable()) {
            throw busy();
        }
        ContentVO content = requireVideoContent(contentId);
        Integer duration = content.getDurationSeconds();        int clamped = duration == null ? progressSeconds : Math.min(progressSeconds, duration);
        try {
            reportBuffer.record(userId, contentId, clamped);
        } catch (CacheUnavailableException e) {
            // 探测通过后 Redis 又掉了（窗口极窄）：仍按"可重试"出口，不降级直写库。
            throw busy();
        }
    }

    /**
     * 退出信号（{@code POST /api/v1/history/flush}）：把该用户**全部**脏缓冲同步落库。
     *
     * <p>★ 粒度是 **userId 全量**（不是 contentId）：客户端退出时本来就该全 flush，
     * 不用它记住"这次看了哪些"；多标签下任一标签退出全写一遍，比"各管各的"简单且不漏。
     *
     * <p>★ 落库失败**仍返回成功**（200）：数据还留在缓冲里，轮询会兜底（history-上报缓冲.md} §5）。
     * 返回 5xx 只会让客户端重试、放大流量，而重试并不能让 DB 恢复。
     */
    public void flush(long userId) {
        try {
            flushService.flushUser(userId);
        } catch (CacheUnavailableException e) {
            log.warn("播放记录 flush 时缓冲不可用，数据留在原处等兜底: userId={}", userId);
        }
    }

    private ServiceBusyException busy() {
        return new ServiceBusyException("服务繁忙，请稍后重试", reportBuffer.retryAfterSeconds());
    }

    /**
     * 删除历史（{@code DELETE /api/v1/history}，`R-26`）：批量删若干条，或 {@code all=true} 清空。
     *
     * <h2>为什么"删历史"现在才敢做</h2>
     * 旧口径下去重标记 {@code play_counted} 长在历史行上，删行会连带丢掉"这个人计过数"的证据
     * ⇒ 再播被判首播、播放量重复 +1。`R-26` 把证据搬进账本 {@code content_play_dedup} 后，
     * 删行**碰不到计数** —— 于是"删掉今天的历史、今天再看"不会重复计，而"明天再看"会正常再计一次。
     *
     * <h2>★ 三条刻意</h2>
     * <ul>
     *   <li><b>不校验内容存在性</b>（同收藏域 G11 红线）：失效占位（「内容已失效」）必须删得掉，
     *       否则那几条永远清不掉；</li>
     *   <li><b>不动 {@code content.play_count}</b>：删历史不减播放量（B站 同口径）。这也意味着
     *       本方法**不产生跨域写**、不需要锁 {@code content} 行；</li>
     *   <li><b>命中 0 行不算错</b>：幂等 204（陈旧页面 / 别的标签页已删 / id 打错都走这条路）。
     *       与"缺参 400"是两件事 —— 后者是调用方根本没说要删什么。</li>
     * </ul>
     *
     * <h2>★ 必须连带清 Redis 缓冲</h2>
     * 缓冲里可能还压着这批内容的脏进度（`/report` 只写缓冲不落库）。不清的话，下一次
     * {@code /flush} 或轮询兜底会把行**原样写回来**。清理失败**不阻断删除**：删除是用户意图，
     * 不该因缓存抖动而失败 —— 代价是"极窄窗口内可能复活一条"，已登记为已知代价。
     *
     * <p>★ 不加事务：一条 {@code DELETE} 本身就是原子的；缓冲清理不属于同一原子域（也不该是 —— 
     * 让 DB 事务包住一次 Redis 往返只会把失败面拉长）。
     *
     * @param userId     当前登录用户（进 WHERE，天然收窄作用域）
     * @param contentIds 要删的内容 id；{@code all=false} 时必填、去重后 ≤ {@value #MAX_DELETE_BATCH}
     * @param all        清空该用户全部历史
     */
    public void delete(long userId, List<Long> contentIds, boolean all) {
        boolean hasIds = contentIds != null && !contentIds.isEmpty();
        if (all && hasIds) {
            throw new ParamException(ErrorCode.PARAM_INVALID, "contentIds 与 all=true 不能同时指定");
        }
        if (all) {
            watchHistoryDao.deleteAllByUserId(userId);
            forgetBuffered(userId, null);
            return;
        }
        if (!hasIds) {
            throw new ParamException(ErrorCode.PARAM_INVALID, "请指定要删除的记录（contentIds），或用 all=true 清空历史");
        }
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(contentIds));
        if (distinct.size() > MAX_DELETE_BATCH) {
            throw new ParamException(ErrorCode.PARAM_INVALID,
                    "一次最多删除 " + MAX_DELETE_BATCH + " 条历史记录");
        }
        watchHistoryDao.deleteByUserIdAndContentIds(userId, distinct);
        forgetBuffered(userId, distinct);
    }

    /**
     * 静默清缓冲：失败只记 WARN，不影响删除结果（见 {@link #delete} 的说明）。
     *
     * @param contentIds {@code null} = 清空整键
     */
    private void forgetBuffered(long userId, List<Long> contentIds) {
        try {
            if (contentIds == null) {
                reportBuffer.forgetAll(userId);
            } else {
                reportBuffer.forget(userId, contentIds);
            }
        } catch (CacheUnavailableException e) {
            log.warn("删除历史时缓冲清理失败（这些内容可能在下次 flush / 轮询兜底时复活）: userId={}, contents={}",
                    userId, contentIds == null ? "ALL" : contentIds.size());
        }
    }

    /**
     * 播放事件（{@code POST /api/v1/contents/{id}/play}）：**当天首播去重后** {@code play_count + 1}，单事务。
     *
     * <p>`R-26` 起两步（设计 §四.2）：
     * <ol>
     *   <li>{@code INSERT IGNORE INTO content_play_dedup (user_id, content_id, play_date=<今天>)}
     *       ⇒ 1 = 今天首播；0 = 今天已计过（幂等 200）；</li>
     *   <li>今天首播 ⇒ {@code content.play_count + 1} + 发 {@link PlayCountChangedEvent}；</li>
     *   <li>**无条件**建历史行（{@code INSERT IGNORE}，{@code progress=0}）—— 只在首播时建的话，
     *       "删了历史又重看"会让页面在播、列表里却没有它。</li>
     * </ol>
     * ① 与 ② 必须同进同退：账本记了但计数没加 ⇒ 这一天永远拿不到这次播放量（账本只增，
     * 没有自愈路径）。
     *
     * <p>★ 与旧实现的差别：**没有**第二条条件 {@code UPDATE}（{@code markCountedIfNotCounted}）——
     * 日期进了主键，"今天有没有计过"由一条 {@code INSERT IGNORE} 直接回答，语句更少、
     * 也不再有两个语句之间的锁序窗口。
     *
     * <p>锁序：先 {@code content_play_dedup} 再 {@code watch_history} 再 {@code content}；
     * 内容删除既不碰账本也不碰历史行 ⇒ 无反向锁序。
     *
     * <p>⚠️ 重复播放**不报错**（幂等 200）：客户端重试 / 多标签页并发都是正常现象。
     */
    @Transactional
    public void play(long userId, long contentId) {
        requireVideoContent(contentId);

        boolean firstPlayToday = playDedupDao.insertIgnore(userId, contentId, PlayDay.today()) == 1;
        watchHistoryDao.insertIgnoreEmpty(userId, contentId);
        if (!firstPlayToday) {
            return;
        }
        contentDao.updatePlayCount(contentId);
        events.publishEvent(new PlayCountChangedEvent(contentId));
    }

    /**
     * 历史列表分页（{@code GET /api/v1/history?page=&pageSize=}）：按**最后上报时间倒序**
     * （{@code update_time DESC, content_id DESC}）；失效条目返回**占位**（{@code history.md} §七.1）。
     *
     * <h2>★ 分页单位是 {@code watch_history} 行</h2>
     * {@code total} **包含**失效条目；失效记录**占一条、返回一条占位**；一整页都是失效条目
     * 也**照常返回整页**（跳过 ⇒ 用户看不到 ⇒ 永远没人处理，且 {@code total} 与页内条数会打架）。
     *
     * <h2>装载契约</h2>
     * 先查本域三列，再**一趟批量**调 {@link ContentService#loadContentVOs} 取展示字段。
     * 返回条数可能少于入参（"装不出来"的被契约跳过），"缺"就是本列表的**唯一失效判据**
     * —— **不是** {@code durationSeconds == null}，也**不是** {@code file_exists = 0}。
     *
     * <p>⚠️ 本端点是"我的"私有视角（只需登录，无归属参数）。空历史 ⇒ {@code list=[]} +
     * {@code total=0}（不是 404）。
     *
     * @param userId   当前登录用户
     * @param page     页码（≥1，由 Controller 归一）
     * @param pageSize 页大小（1..域级上限，由 Controller 归一）
     */
    public PageResult<HistoryItemVO> list(long userId, int page, int pageSize) {
        int total = watchHistoryDao.countByUserId(userId);
        // ⚠️ 用 long 防 int 溢出：page 无上限 ⇒ (page-1)*pageSize 溢出成负数会让 `OFFSET -…` 变 SQL 语法错 ⇒ 500。
        long offset = (long) (page - 1) * pageSize;
        List<HistoryItemVO> rows = watchHistoryDao.findPageByUserId(userId, offset, pageSize);

        List<Long> contentIds = new ArrayList<>(rows.size());
        for (HistoryItemVO row : rows) {
            contentIds.add(row.getContentId());
        }
        Map<Long, ContentVO> byContentId = new HashMap<>(contentIds.size());
        for (ContentVO content : contentService.loadContentVOs(contentIds)) {
            byContentId.put(content.getId(), content);
        }
        List<HistoryItemVO> items = new ArrayList<>(rows.size());
        for (HistoryItemVO row : rows) {
            items.add(toItemVO(row, byContentId.get(row.getContentId())));
        }
        return new PageResult<>(items, total, page, pageSize);
    }

    /**
     * 组装一条历史条目：正常行抄 content 契约的展示字段，失效行**构造**占位。
     *
     * <p>★ 与 {@code WatchLaterService#toItemVO} **同口径、各自一份**（两处 VO 类型不同）：
     * 失效行四个展示字段**一个都不能给**（只换标题不置空作者 ⇒ 作者仍可见；只置空封面不换标题
     * ⇒ 标题仍可见），故这里是**一个分支内的四次赋值**，不写成四个各自判断的条件。
     * ★ {@code progress} **不在**清空之列：它是历史数据（"看到第几秒"仍然真实）。
     *
     * @param row     Mapper 查出的行（本域三列有值；**就地修改**并返回 —— 本 VO 一身两职）
     * @param content content 装载契约给出的展示条目；{@code null} = 装不出来 ⇒ 占位
     */
    private HistoryItemVO toItemVO(HistoryItemVO row, ContentVO content) {
        if (content == null) {
            row.setInvalid(Boolean.TRUE);
            row.setTitle(INVALID_CONTENT_TITLE);
            row.setCoverUrl(null);
            row.setAuthorName(null);
            row.setDurationSeconds(null);
            return row;
        }
        row.setInvalid(Boolean.FALSE);
        row.setTitle(content.getTitle());
        row.setCoverUrl(content.getCoverUrl());
        row.setAuthorName(content.getAuthorName());
        row.setDurationSeconds(content.getDurationSeconds());
        return row;
    }

    /**
     * 续播位置（{@code GET /api/v1/contents/{id}/progress}）：**只点查 DB**（{@code uk_user_content}）。
     *
     * <p>★ `R-20`（2026-10-22）起**不再读缓冲**，推翻了 `R-19` 契约④的"读缓冲优先"。
     * 两条依据：
     * <ol>
     *   <li><b>正常路径上读缓冲是纯支出</b>：{@code /flush} 成功后 {@code ACK_LUA} 会把该用户的
     *       hash 整个 {@code DEL}（{@code HLEN == 0} 分支）⇒ 下次本端点**必然 miss**，
     *       等于白付一趟 {@code HGET}；收益只存在于"崩溃 / 没发 flush / flush 失败"三条异常路径。</li>
     *   <li><b>崩溃续播改由客户端承担</b>：客户端本地有 {@code history:pending:{contentId}}
     *       （最后一条成功上报的进度），初始化时取 {@code max(服务端值, 本地值)} 定位播放头
     *       —— 不依赖服务端缓冲，也就不会因为"补报早于定位"而读到旧值。
     *       见 {@code history-客户端契约.md} §6.2。</li>
     * </ol>
     * 代价：**多设备 + 双方都未正常退出**时，另一台设备补报的旧值可能覆盖缓冲里的新值
     * （LWW 无守卫）⇒ 该会话进度倒退。已作为已知代价登记（极低频，用户 2026-10-22 接受）。
     *
     * <p>★ <b>不校验内容、不返回 404</b>（{@code history.md} §七.2）：内容已删也照样回答进度
     * —— 内容是死是活由详情 404 负责，这里只回答"我的进度是多少"。无记录 ⇒ {@code 0}
     * （不是 404、不是 {@code null}：前端拿到 0 就能从头播）。
     *
     * <p>⚠️ 这里**不读 {@code durationSeconds}**：时长随详情返回，本端点不重复读一次内容。
     *
     * @param userId    当前登录用户
     * @param contentId 内容 id（**不判存在性**：不存在 ⇒ 无行 ⇒ 0）
     * @return 上次看到的秒数；无记录 ⇒ 0
     */
    public int position(long userId, long contentId) {
        Integer progress = watchHistoryDao.findProgress(userId, contentId);
        return progress == null ? 0 : progress;
    }

    /**
     * 取"可播放的视频内容"：取不到 ⇒ 404，图文 ⇒ 400。
     *
     * <p>★ <b>"媒体损坏"的唯一判据是 {@code loadContentVOs} 取不到</b>（装载契约：
     * {@code type=1} 视频行缺失即视为不可用）。**不是** {@code durationSeconds == null}
     * （解析失败仍可播），也**不是** {@code content_media.file_exists == 0}
     * （扫描标记缺失不等于不能播）—— 把这两者当 404 会把正常视频判死（{@code history.md} §八）。
     */
    private ContentVO requireVideoContent(long contentId) {
        List<ContentVO> loaded = contentService.loadContentVOs(List.of(contentId));
        if (loaded.isEmpty()) {
            throw new NotFoundException(ErrorCode.CONTENT_NOT_FOUND, "内容不存在");
        }
        ContentVO content = loaded.get(0);
        if (content.getType() != ContentType.VIDEO.getTypeNumber()) {
            throw new ParamException(ErrorCode.HISTORY_TYPE_INVALID, "只能记录视频的播放进度");
        }
        return content;
    }
}
