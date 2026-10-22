package io.github.yjhhhaaa06.videoweb.content.event;

import io.github.yjhhhaaa06.videoweb.content.cache.ContentCountCache;
import io.github.yjhhhaaa06.videoweb.history.event.PlayCountChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 播放量 +1 ⇒ **条件 {@code INCRBY 1}** 维护 {@code content:playCount:{id}}（T48）。
 *
 * <h2>为什么由内容域订阅历史域的事件</h2>
 * {@code content.play_count} 是内容表自己的列，历史域在 {@code /history/play} 的事务里
 * 首播 +1（{@code HistoryService#play}）后只声明"这条内容的播放量变了"。
 * 内容域要做的只剩一件事：让自己的计数键跟上；键是自己的，回填 / 失效 / 增量全在
 * {@link ContentCountCache}，历史域**不碰** {@code content.cache}
 * （{@code 模块边界与依赖.md} §六；{@code ArchitectureTests} 规则 2）。
 *
 * <h2>★ {@code AFTER_COMMIT} 的语义与本任务的判别力</h2>
 * 事件在 {@code /play} 的事务**内**发布，监听器**只在提交成功后被调用**；回滚 ⇒ 完全不调用，
 * 缓存不会被"没落库的 +1"污染。若改成普通 {@code @EventListener}（或把增量挪进服务体），
 * 回滚路径就会留下 +1 的脏值。
 *
 * <p>★ 增量**不是无条件 {@code INCR}**：冷 key（未加载）时"1"不是总量 —— 写进去会用
 * 一个漏掉历史的值钉住整个 TTL（详情读成 1，列里可能是 800）。条件写由
 * {@link ContentCountCache#incrementPlayCountIfPresent} 的 Lua 保证（原子），
 * 键不存在 ⇒ 什么都不写，下一个详情走 miss 回源读列。
 * 去掉那个守卫 ⇒ {@code ContentPlayCountTests#冷key播放不写半套缓存} 必红。
 *
 * <h2>时序与失败口径</h2>
 * 在提交线程**同步**执行（不用 {@code @Async}，同 {@code CoinChangedListener}）：
 * 于是 {@code /history/play} 的 HTTP 响应返回时，键已经更新，端到端用例无需等待。
 * Redis 故障只记 WARN（{@code ContentCountCache} 内吞掉）：事务已提交，缓存故障不能反过来
 * 让一次成功的播放变成 500 —— 代价是这次增量丢失，由 TTL 到期后的回源读列自愈。
 */
@Slf4j
@Component
public class PlayCountChangedListener {

    private final ContentCountCache contentCountCache;

    public PlayCountChangedListener(ContentCountCache contentCountCache) {
        this.contentCountCache = contentCountCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPlayCountChanged(PlayCountChangedEvent event) {
        log.debug("播放事务已提交，条件累加内容播放量缓存: contentId={}", event.contentId());
        contentCountCache.incrementPlayCountIfPresent(event.contentId());
    }
}
