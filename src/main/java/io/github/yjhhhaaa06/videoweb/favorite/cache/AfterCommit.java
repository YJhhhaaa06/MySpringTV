package io.github.yjhhhaaa06.videoweb.favorite.cache;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 「**DB 提交后才碰缓存**」的落点（收藏二期 T11 / I-11）。
 *
 * <h2>为什么不是 {@code favorite.event} + {@code @TransactionalEventListener(AFTER_COMMIT)}</h2>
 * 本仓其余域的写后副作用都走"自域事件 + AFTER_COMMIT 监听器"，但收藏域走不通，两条硬理由：
 * <ol>
 *   <li>★ <b>T11 时点的理由（历史）：收藏当时有两个无事务写点</b> —— {@code addItem} /
 *       {@code removeItems} 单写不套事务（事务边界篇 §四），而
 *       {@code @TransactionalEventListener} 的 {@code fallbackExecution} 默认 {@code false}
 *       （领域事件篇 §5.2）⇒ 无事务时发布的事件**不会被投递给 AFTER_COMMIT 监听器**，
 *       缓存会**静默永不更新**。★ **T12 起两写点因计数重算升级为有事务**（见
 *       {@code FavoriteService} 类注释"事务"节），这条理由的"现状"已消失 ——
 *       但它记录的是 I-11 拍板时的事实与权衡，保留为历史口径；且"收藏域不该有
 *       {@code event} 包"（下条）与 {@code fallbackExecution} 的机制语义**不随 T12 改变**，
 *       本机制因此不迁移到事件形态。</li>
 *   <li><b>收藏域本来就不该有 {@code event} 包</b>：{@code FavoriteService} 类注释已定性
 *       "收藏不是**别人**要响应的事实"（事件范式的目的是跨域解耦；收藏的缓存是自己私有实现细节，
 *       没有任何外部订阅方）。为"自己给自己发事件"建一个包，是把机制用在了它不适用的地方。</li>
 * </ol>
 * 本类只在**一处**表达"提交后"，且用完即弃（无状态）——不引入新 bean，也不让 `event` 包出现。
 *
 * <h2>两条分支的语义（缺一即错）</h2>
 * <table>
 *   <caption>执行时机</caption>
 *   <tr><th>当前线程</th><th>行为</th><th>为什么正确</th></tr>
 *   <tr><td>有事务同步活动（★ T12 起：四个写点 {@code addItem} / {@code removeItems} /
 *       {@code moveItem} / {@code deleteFolder} 全在此分支——计数重算引入第二条写）</td>
 *       <td>登记 {@code afterCommit} 回调</td>
 *       <td>事务回滚 ⇒ 回调**根本不执行** ⇒ 缓存不被污染（回滚不留痕由框架保证，同 AFTER_COMMIT 监听器）</td></tr>
 *   <tr><td>无同步活动（★ T12 起生产代码**无调用方**落此分支；机制保留为通用语义）</td>
 *       <td>**立即执行**</td>
 *       <td>无事务 = 自动提交，DAO 方法返回时写已落库 ⇒ 此刻碰缓存正是"提交后"</td></tr>
 * </table>
 * ⚠️ 第二分支**不能**简化成"没有事务就跳过"：T11 时点的收藏请求全部落在无事务写点，
 * "跳过"会让它们完全不更新缓存，且症状是"缓存慢慢过期自愈"——**静默且难定位**。
 * （T12 后无生产调用方，该论断针对的是机制本身的鲁棒性。）
 * 判别力由 {@code FavoriteCacheTests} 的两条用例承担：① 事务回滚后 Redis 里**不存在**
 * 收藏 key（打桩让事务内最后一步失败）；② add / remove 写后返回时缓存**确实**已更新
 * （T12 起两写点走 afterCommit 注册分支：提交即回调，先于方法返回）。
 *
 * <h2>代价（如实登记）</h2>
 * 与 AFTER_COMMIT 监听器相比，本机制**不进入** Spring 的事件体系：谁在提交后做了什么，
 * 只能读调用点（{@code FavoriteService} 里每个写点末尾的 {@code AfterCommit.run(...)}）
 * —— 这处"可读性"换的是"不建 event 包"。已知代价，选择接受（I-11）。
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    /**
     * 登记动作：在**当前事务提交后**执行；当前无事务同步活动时**立即执行**。
     *
     * <p>⚠️ {@code action} 自身的异常语义：{@code afterCommit} 阶段抛出的异常会向上冒到
     * 调用提交的那一层。故传入的动作必须是"失败也只记日志、不影响业务"的（本仓缓存的
     * 写/失效方法都只 catch {@link io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException}
     * 并降级）——**已提交的业务不能因为缓存动作失败而对外表现为失败**。
     */
    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
