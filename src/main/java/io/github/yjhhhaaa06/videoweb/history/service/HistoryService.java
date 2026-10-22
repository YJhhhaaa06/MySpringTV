package io.github.yjhhhaaa06.videoweb.history.service;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.exception.ServiceBusyException;
import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.model.ContentType;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import io.github.yjhhhaaa06.videoweb.history.cache.HistoryReportBuffer;
import io.github.yjhhhaaa06.videoweb.history.dao.WatchHistoryDao;
import io.github.yjhhhaaa06.videoweb.history.event.PlayCountChangedEvent;
import io.github.yjhhhaaa06.videoweb.history.model.vo.HistoryItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 播放记录业务：写路径（{@code /history/report} 与 {@code /history/play}）+ 读路径
 * （{@code /history/list} 历史列表、{@code /history/position} 续播位置）。
 *
 * <p>设计 / 口径的唯一事实源：{@code architecture/domain/history/history.md} §四（写）/ §七（读）。
 * 本类只做"校验 → 写"与"取页 → 组装"的编排：
 *
 * <table>
 *   <caption>两端点的分工（拆端点是本域的核心决策）</caption>
 *   <tr><th>端点</th><th>写什么</th><th>事务</th><th>为什么不合并</th></tr>
 *   <tr><td>{@code /report}</td><td>只写 {@code progress_seconds}（一条 ODKU）</td><td>**不加**</td>
 *       <td>单条 ODKU 自带原子性；且它**不需要判首播** ⇒ 绕开 affected-rows 歧义</td></tr>
 *   <tr><td>{@code /play}</td><td>标记位 + {@code content.play_count}</td><td>**加**</td>
 *       <td>两位必须同进同退，否则不变量 {@code play_count == COUNT(play_counted=1)} 没有自愈路径</td></tr>
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
 *   <li><b>不删历史</b>：一删就与"播放量只增"打架，且不变量无法复算（{@code history-需求.md} §六）。</li>
 * </ul>
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

    private final WatchHistoryDao watchHistoryDao;
    private final ContentService contentService;
    private final ContentDao contentDao;
    private final ApplicationEventPublisher events;
    private final HistoryReportBuffer reportBuffer;
    private final HistoryFlushService flushService;

    public HistoryService(WatchHistoryDao watchHistoryDao,
                          ContentService contentService,
                          ContentDao contentDao,
                          ApplicationEventPublisher events,
                          HistoryReportBuffer reportBuffer,
                          HistoryFlushService flushService) {
        this.watchHistoryDao = watchHistoryDao;
        this.contentService = contentService;
        this.contentDao = contentDao;
        this.events = events;
        this.reportBuffer = reportBuffer;
        this.flushService = flushService;
    }

    /**
     * 上报播放进度（{@code POST /history/report}）：**校验后写 Redis 缓冲，不落库**。
     *
     * <p>★ `R-19`（2026-10-22）起的契约变更：本方法不再写 `watch_history`，落库由
     * {@code /history/flush}（退出信号）或空闲轮询完成。接口形态（200 / 400 / 404）未变。
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
     * {@code play_counted}（落库时同样不碰，见 {@code HistoryFlushService}）。
     */
    public void report(long userId, long contentId, int progressSeconds) {
        if (progressSeconds < 0) {
            throw new ParamException("播放进度不能为负数");
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
     * 退出信号（{@code POST /history/flush}）：把该用户**全部**脏缓冲同步落库。
     *
     * <p>★ 粒度是 **userId 全量**（不是 contentId）：客户端退出时本来就该全 flush，
     * 不用它记住"这次看了哪些"；多标签下任一标签退出全写一遍，比"各管各的"简单且不漏。
     *
     * <p>★ 落库失败**仍返回成功**（200）：数据还留在缓冲里，轮询会兜底（{@code history.md} §12.5）。
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
     * 播放事件（{@code POST /history/play}）：**首播去重后** {@code play_count + 1}，单事务。
     *
     * <p>三步走（设计 §四.2）：
     * <ol>
     *   <li>{@code INSERT IGNORE ... (progress=0, play_counted=1)} ⇒ 1 = 首播；0 = 行已存在；</li>
     *   <li>行已存在 ⇒ 条件 {@code UPDATE ... WHERE play_counted = 0} ⇒ 1 = 首播，0 = 已计过（幂等 200）；</li>
     *   <li>首播 ⇒ {@code content.play_count + 1} + 发 {@link PlayCountChangedEvent}。</li>
     * </ol>
     * ① / ② 与 ③ 必须同进同退：标记位置了但计数没加 ⇒ 该用户永远拿不到这次播放量
     * （不变量 {@code play_count == COUNT(play_counted=1)} 会永久破裂，且**没有自愈路径**）。
     *
     * <p>锁序：先 {@code watch_history} 后 {@code content}；内容删除不碰历史行 ⇒ 无反向锁序。
     *
     * <p>⚠️ 重复播放**不报错**（幂等 200）：客户端重试 / 多标签页并发都是正常现象。
     */
    @Transactional
    public void play(long userId, long contentId) {
        requireVideoContent(contentId);

        boolean firstPlay = watchHistoryDao.insertIgnoreCounted(userId, contentId) == 1
                || watchHistoryDao.markCountedIfNotCounted(userId, contentId) == 1;
        if (!firstPlay) {
            return;
        }
        contentDao.updatePlayCount(contentId);
        events.publishEvent(new PlayCountChangedEvent(contentId));
    }

    /**
     * 历史列表分页（{@code GET /history/list?page=&pageSize=}）：按**最后上报时间倒序**
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
     * 续播位置（{@code GET /history/position?contentId=}）：{@code (user_id, content_id)} 点查。
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
            throw new NotFoundException("内容不存在");
        }
        ContentVO content = loaded.get(0);
        if (content.getType() != ContentType.VIDEO.getTypeNumber()) {
            throw new ParamException("只能记录视频的播放进度");
        }
        return content;
    }
}
