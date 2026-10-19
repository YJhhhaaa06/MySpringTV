package io.github.yjhhhaaa06.videoweb.coin.event;

import io.github.yjhhhaaa06.videoweb.coin.cache.CoinCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 投币缓存更新器 —— 在**事务提交后**把变更同步进 Redis。
 *
 * <h2>★ {@code AFTER_COMMIT} 的语义与存在证明</h2>
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}：事件在事务内发布，监听器
 * **只在事务成功提交后**才被调用；事务回滚 ⇒ **完全不调用**。
 * 于是"回滚后缓存被污染"从"靠注释纪律"变成"框架层面不可能发生"。
 *
 * <p>配套测试（关键，勿删）：{@code CoinCacheTests#事务回滚后两个key都不存在}（外层事务
 * 回滚 + 预热两个 key）与 {@code #写路径最后一步失败时两个key都不存在}（打桩
 * {@code ContentDao.updateCoinCount} 让事务在**已有成功写之后**失败）。前者是
 * {@code AFTER_COMMIT} 的真正存在证明：**事件已经发布、事务却回滚**，缓存必须没动过。
 * 若把本注解改成普通的 {@code @EventListener}（或把缓存写挪进服务体），该用例立刻变红。
 *
 * <h2>两个实现要点（同 {@code LikeChangedListener}）</h2>
 * ① <b>不用 {@code @Async}</b>：缓存更新在提交线程同步执行，语义与既有域一致；
 *    异步会引入"提交后缓存短暂未更新"的新窗口，且让测试需要等待。
 * ② <b>{@code fallbackExecution} 保持默认 false</b>：投币写路径**一律**在
 *    {@code @Transactional} 方法内发布（{@code CoinService#add}），没有"无事务也要更新缓存"
 *    的场景；将来若出现，必须显式打开并在此说明理由。
 */
@Slf4j
@Component
public class CoinChangedListener {

    private final CoinCache coinCache;

    public CoinChangedListener(CoinCache coinCache) {
        this.coinCache = coinCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCoinChanged(CoinChangedEvent event) {
        log.debug("投币事务已提交，条件写投币缓存: user={}, content={}, newAmount={}, delta={}",
                event.userId(), event.contentId(), event.newAmount(), event.delta());
        coinCache.onCoinChanged(event.userId(), event.contentId(), event.newAmount(), event.delta());
    }
}
