package io.github.yjhhhaaa06.videoweb.history.service;

import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.model.ContentType;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import io.github.yjhhhaaa06.videoweb.history.dao.WatchHistoryDao;
import io.github.yjhhhaaa06.videoweb.history.event.PlayCountChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 播放记录写路径（{@code /history/report} 与 {@code /history/play}）。
 *
 * <p>设计 / 口径的唯一事实源：{@code architecture/domain/history/history.md} §四。
 * 本类只做"校验 → 写"的编排，两件事分别对应两个端点：
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
@Service
public class HistoryService {

    private final WatchHistoryDao watchHistoryDao;
    private final ContentService contentService;
    private final ContentDao contentDao;
    private final ApplicationEventPublisher events;

    public HistoryService(WatchHistoryDao watchHistoryDao,
                          ContentService contentService,
                          ContentDao contentDao,
                          ApplicationEventPublisher events) {
        this.watchHistoryDao = watchHistoryDao;
        this.contentService = contentService;
        this.contentDao = contentDao;
        this.events = events;
    }

    /**
     * 上报播放进度（{@code POST /history/report}）：**一条 ODKU，不加事务**。
     *
     * <p>校验顺序（参数形态 → 存在性 → 类型，与设计 §四.1 一致）：
     * <ol>
     *   <li>{@code progress < 0} ⇒ 400「播放进度不能为负数」；</li>
     *   <li>取不到内容 ⇒ 404「内容不存在」；</li>
     *   <li>图文 ⇒ 400「只能记录视频的播放进度」；</li>
     *   <li>超过时长的进度按**当前**时长 clamp（时长未知则不 clamp）——
     *       不报错：客户端心跳/重试本来就会偶尔越过末尾。</li>
     * </ol>
     *
     * <p>★ 本方法**不写 {@code play_count}**（{@code /play} 是唯一计数入口）——
     * 这也是 {@link WatchHistoryDao#upsertProgress} 的返回值**不能**用来判首播的原因。
     */
    public void report(long userId, long contentId, int progressSeconds) {
        if (progressSeconds < 0) {
            throw new ParamException("播放进度不能为负数");
        }
        ContentVO content = requireVideoContent(contentId);
        Integer duration = content.getDurationSeconds();
        int clamped = duration == null ? progressSeconds : Math.min(progressSeconds, duration);
        watchHistoryDao.upsertProgress(userId, contentId, clamped);
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
