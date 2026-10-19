package io.github.yjhhhaaa06.videoweb.coin.event;

/**
 * 投币状态变更事件 —— 把"缓存更新"从**注释纪律**变成**类型保证**
 * （模式与 {@code like.event.LikeChangedEvent} 同款，见
 * {@code architecture/tech/events/领域事件与解耦.md}）。
 *
 * <h2>为什么用事件而不是"事务方法体末尾顺手写缓存"</h2>
 * 写在事务 lambda 之后，靠的是代码行序 + 注释；一旦有人把那一行挪进事务体，
 * 就是"事务回滚但缓存已改"。改为事件后，监听器标
 * {@code @TransactionalEventListener(AFTER_COMMIT)} ⇒ **事务没提交就绝不会被调用**，
 * 时序由框架保证，而不是由注释保证。
 *
 * <h2>★ 字段集只包含"写缓存真正需要的"（这是本事件与 like 事件的关键差异）</h2>
 * <ul>
 *   <li>{@link #newAmount()} —— 该用户对该内容投币后的**累计枚数**（1 或 2）。
 *       用于 {@code HSET} **精确回放**：投币是单行聚合（{@code uk_user_content}），
 *       写"新总量"天然幂等，重复投递同一事件不会漂移（不像 like 那样 {@code INCR}）；</li>
 *   <li>{@link #delta()} —— 本次投出的枚数。用于 {@code content:coinCount} 的条件
 *       {@code INCRBY}（该计数单调只增，投币不可取消）。</li>
 * </ul>
 *
 * <p>★ <b>刻意不加 {@code authorId}</b>：作者余额（{@code users.coin_deci}）**没有缓存**，
 * 余额读出走 {@code /user/me} 直读 DB。事件只该携带"写缓存要用的东西"；加一个没有消费者的
 * 字段就是**无主字段**——它会让读者误以为"有作者侧缓存要一起更新"，也会在字段改名时
 * 制造一条假的迁移负担。
 *
 * <p>⚠️ 事件在投币事务**内**、且**所有写之后**发布（{@code CoinService#add} 末尾）：
 * {@code AFTER_COMMIT} 监听器要求发布时事务同步仍处于活动状态，事务外发布会被静默丢弃
 * （{@code fallbackExecution} 默认 false）。
 *
 * @param userId     投币的人
 * @param contentId  被投币的内容 id
 * @param newAmount  投币后该用户对该内容的**累计枚数**（1 或 2）
 * @param delta      本次投出的枚数（1 或 2）
 */
public record CoinChangedEvent(long userId, long contentId, int newAmount, int delta) {
}
