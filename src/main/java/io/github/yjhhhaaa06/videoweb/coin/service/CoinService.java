package io.github.yjhhhaaa06.videoweb.coin.service;

import io.github.yjhhhaaa06.videoweb.coin.cache.CoinCache;
import io.github.yjhhhaaa06.videoweb.coin.dao.ContentCoinDao;
import io.github.yjhhhaaa06.videoweb.coin.event.CoinChangedEvent;
import io.github.yjhhhaaa06.videoweb.coin.model.vo.CoinBalanceVO;
import io.github.yjhhhaaa06.videoweb.coin.model.vo.SignInVO;
import io.github.yjhhhaaa06.videoweb.common.exception.ConflictException;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.exception.NotFoundException;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.time.SiteTime;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.model.ContentAuthorAndType;
import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/**
 * 投币域业务：签到（T37，供给端）+ 投币（T38，写路径）。
 *
 * <h2>★ 单位约定：三处一起钉，别只钉一处</h2>
 * {@code users.coin_deci} 存的是**币的十分位**——{@code 15} 表示 1.5 枚，不是 15 枚。
 * 这条约定不靠记忆，靠三个同时存在的约束（{@code architecture/domain/coin/coin.md} §三）：
 * <ol>
 *   <li><b>列名带 {@code deci}</b>：DAO / SQL 两侧都不用无单位的名（{@code coin} / {@code balance}）；</li>
 *   <li><b>常量 {@link #DECI_PER_COIN}</b>：一枚硬币 = 10 个十分位。签到加多少由它传进 SQL
 *       （{@code #{deciPerCoin}}），投币的 {@code count × DECI_PER_COIN} 也用它，
 *       **不在 XML 里写 {@code 10}**；</li>
 *   <li><b>换算只发生在 VO 出口</b>：DAO / Service 内部全程整数十分位，
 *       {@link #toCoinAmount(int)} 是唯一"整数 → 小数"的桥。</li>
 * </ol>
 * 三条缺一条，单位就会开始在各处漂移（"这里乘 10 那里除 10"），而 {@code 0/1.5/2} 这类小数值
 * 让这种错误看起来"像对的"，极难在回归里发现。
 *
 * <h2>两个写路径的事务口径**刻意不同**</h2>
 * <ul>
 *   <li><b>签到不加 {@code @Transactional}</b>：幂等、并发安全、"哪一天"三件事全部收在一条 SQL 里
 *       （{@link UserDao#signIn}），单语句自带原子性，加事务只是多一次 BEGIN/COMMIT；</li>
 *   <li><b>投币必须加 {@code @Transactional}</b>：一次投币同时改四张逻辑位置——
 *       {@code content_coin}（关系行）、{@code content.coin_count}（计数）、
 *       <b>两个用户</b>的 {@code users.coin_deci}（扣款 / 收益）——四者必须同进同退，
 *       否则余额与计数会在失败窗口里漂移，且没有自愈路径。</li>
 * </ul>
 * 签到**不发领域事件**（{@code /user/me} 直读 DB，没有任何缓存要失效）；
 * 投币的缓存在 T39 接入 —— 事务内**所有写之后**发 {@link CoinChangedEvent}，由
 * {@code CoinChangedListener} 在 {@code AFTER_COMMIT} 条件写两个 key。
 *
 * <h2>读路径（T39 缓存已就位，T40 才接读面）</h2>
 * 三个读方法 + 一个失效入口都是 {@link CoinCache} 的**薄委托**：缓存是 coin 域的私有实现，
 * 跨域（T40 的 interaction / content 组装点）只走本 Service 的公开方法。
 */
@Service
public class CoinService {

    /**
     * 一枚硬币的十分位数（币 × {@value} = {@code coin_deci}）。
     *
     * <p>唯一声明点：签到加币走 {@code signIn(..., DECI_PER_COIN)}，投币扣款的
     * {@code count × DECI_PER_COIN} 也用它。**不要在 SQL 或 Controller 里再写 {@code 10}。**
     */
    public static final int DECI_PER_COIN = 10;

    private final UserDao userDao;
    private final ContentDao contentDao;
    private final ContentCoinDao contentCoinDao;
    private final CoinCache coinCache;
    private final ApplicationEventPublisher events;

    public CoinService(UserDao userDao, ContentDao contentDao, ContentCoinDao contentCoinDao,
                       CoinCache coinCache, ApplicationEventPublisher events) {
        this.userDao = userDao;
        this.contentDao = contentDao;
        this.contentCoinDao = contentCoinDao;
        this.coinCache = coinCache;
        this.events = events;
    }

    /**
     * 每日签到（幂等）。
     *
     * <p>流程只有两步：先尝试原子条件写（{@code 1} 行 = 本次到账），再读回最新余额。
     * <b>没有"先查签到日再判断"</b>——那个窗口正是并发双击重复到账的来源
     * （反向验证：删掉 {@code UserMapper.xml#signIn} 的 {@code WHERE} 守卫，
     * {@code CoinSignInTests} 的并发用例会看到 {@code coin_deci == 20}）。
     *
     * @param userId 当前登录用户
     * @return 余额（一位小数）+ 本次是否真的到账
     */
    public SignInVO signIn(long userId) {
        boolean credited = userDao.signIn(userId, SiteTime.today(), DECI_PER_COIN) > 0;
        Integer balanceDeci = userDao.findCoinDeci(userId);
        // 用户必然存在（token 指向它），null 只可能是"读余额前被删"这种窗口；
        // 按 0 处理比拆箱 NPE / 500 更符合"读接口不该因竞态崩"的口径。
        return new SignInVO(toCoinAmount(balanceDeci == null ? 0 : balanceDeci), credited);
    }

    /**
     * 给内容投币（single transaction）。
     *
     * <h2>★ 校验顺序就是契约（设计 §四.1 / §七），别调换</h2>
     * <ol>
     *   <li>{@code count ∉ {1,2}} ⇒ 400「投币数量只能是 1 或 2」；</li>
     *   <li>内容不存在 / 已软删 ⇒ 404「内容不存在」（**一趟窄查询**同时取作者与类型）；</li>
     *   <li>{@code type != 1}（图文帖）⇒ 400「该内容不支持投币」；</li>
     *   <li>自己给自己投 ⇒ 400「不能给自己投币」；</li>
     *   <li>已投满 2 枚 ⇒ 409「对本稿件投币枚数已用完」；</li>
     *   <li>余额不足 ⇒ 400「硬币不足」；</li>
     *   <li>成功 ⇒ 200「投币成功」+ 回带新余额。</li>
     * </ol>
     * ★ <b>5 先于 6</b>：两者同时不满足时答 <b>409</b>（资源状态内部另定次序）。
     * 因此"封顶的条件写"必须排在"扣款的条件写"之前——顺序反过来，
     * 「已满 + 余额不足」会答 400，与契约不符（{@code CoinAddTests} 有一条专门钉它）。
     *
     * <h2>两台原子条件写（没有"先读后写"的窗口）</h2>
     * <pre>
     * INSERT IGNORE INTO content_coin … VALUES (?, ?, 0);          -- 只保证行存在
     * UPDATE content_coin SET amount = amount + ? … AND amount + ? &lt;= 2;  -- 0 行 ⇒ 409
     * UPDATE users SET coin_deci = coin_deci - ? WHERE … AND coin_deci &gt;= ?;  -- 0 行 ⇒ 400
     * </pre>
     * 并发两次各投 1 枚不会突破封顶 2，也不会把余额扣成负数。
     *
     * <h2>★ 异常即整笔回滚</h2>
     * 封顶 / 余额不足都在**已有写之后**抛出（第 5 步的 {@code INSERT IGNORE} 与累加已发生），
     * 靠 {@code @Transactional} 把它们一起撤掉 ⇒ 被拒的投币**不落 {@code amount = 0} 行、
     * 也不扣款、不动作者余额、不动 {@code coin_count}**（验收②「零副作用」）。
     * ⚠️ 这正是为什么本方法不能"分几段事务"或把扣款放在事务外：那会留下半套数据。
     *
     * <h2>收益换算为什么是 {@code +count}</h2>
     * 作者按 {@code 1/10} 收币：{@code count} 枚 × {@code 1/10} 枚 = {@code count} 个十分位
     * ⇒ 直接 {@code updateCoinDeci(authorId, count)}，**不乘 10**（设计 §三）。
     *
     * @param userId    投票者（当前登录用户）
     * @param contentId 内容 id
     * @param count     投出的枚数（1 或 2）
     * @return 投币后的余额（一位小数）
     */
    @Transactional
    public CoinBalanceVO add(long userId, long contentId, int count) {
        // ① 参数形态：先挡掉一切非法枚数，避免后续把它写进 SQL。
        if (count != 1 && count != 2) {
            throw new ParamException(ErrorCode.PARAM_INVALID, "投币数量只能是 1 或 2");
        }

        // ② 存在性 + ③ 类型 + ④ 归属：一趟窄查询取回两列，顺序按设计 §四.1。
        ContentAuthorAndType content = contentDao.findAliveContentAuthorAndType(contentId);
        if (content == null) {
            throw new NotFoundException(ErrorCode.CONTENT_NOT_FOUND, "内容不存在");
        }
        if (content.type() == null || content.type() != 1) {
            throw new ParamException(ErrorCode.COIN_NOT_SUPPORTED, "该内容不支持投币");
        }
        if (content.authorId() != null && content.authorId() == userId) {
            throw new ParamException(ErrorCode.COIN_SELF_FORBIDDEN, "不能给自己投币");
        }

        // ⑤ 封顶：先保证行存在，再条件累加。影响 0 行 ⇒ 已满（或这一次加完会超 2）。
        //    ★ 必须在扣款之前（5 先于 6）：「已满 + 余额不足」要答 409，不是 400。
        contentCoinDao.insertIgnoreZero(userId, contentId);
        if (contentCoinDao.addAmount(userId, contentId, count) == 0) {
            throw new ConflictException(ErrorCode.COIN_ALREADY_FULL, "对本稿件投币枚数已用完");
        }

        // ⑥ 余额：条件扣款是"够不够"的唯一判据（0 行 ⇒ 硬币不足）。禁止先查余额再减。
        // ⑦ 作者收益 +count 个十分位（= count 枚的 1/10），计数 +count。
        //    ★ ⑥⑦ 各锁 users 一行，**必须按 id 升序取锁**：A→B 与 B→A 并发互投时，
        //      固定"投票者先、作者后"会成环 ⇒ InnoDB 1213 ⇒ 500（与设计 §九 #5 的
        //      content_coin 锁升级窗口不是同一对语句）。先入账后扣款不影响语义 ——
        //      扣款 0 行抛异常 ⇒ 整笔回滚，作者收益不会残留。
        int costDeci = count * DECI_PER_COIN;
        Long authorId = content.authorId();
        if (authorId != null && authorId < userId) {
            userDao.updateCoinDeci(authorId, count);
            if (userDao.deductCoinDeci(userId, costDeci) == 0) {
                throw new ParamException(ErrorCode.COIN_INSUFFICIENT, "硬币不足");
            }
        } else {
            if (userDao.deductCoinDeci(userId, costDeci) == 0) {
                throw new ParamException(ErrorCode.COIN_INSUFFICIENT, "硬币不足");
            }
            userDao.updateCoinDeci(authorId, count);
        }
        contentDao.updateCoinCount(contentId, count);

        Integer balanceDeci = userDao.findCoinDeci(userId);
        // 投票者必然存在（token 指向它 + 上一步扣款命中）；null 只可能是"读余额前被删"的窗口，
        // 按 0 处理比拆箱 NPE / 500 更符合"读回执不该因竞态崩"的口径（同 signIn）。
        int balance = balanceDeci == null ? 0 : balanceDeci;

        // ⑧ T39：缓存事件必须在**事务内**、且**所有写之后**发布。
        //    - 必须在事务内：监听器是 @TransactionalEventListener(AFTER_COMMIT)，
        //      事务外/提交后发布会被静默丢弃（fallbackExecution 默认 false）；
        //    - 必须在所有写之后：newAmount 要反映本次投币后的**累计枚数**（精确回放用）。
        //    newAmount 必然非 null（同事务内 insertIgnoreZero 保证了行存在、addAmount 已命中）。
        Integer newAmount = contentCoinDao.findAmount(userId, contentId);
        events.publishEvent(new CoinChangedEvent(userId, contentId, newAmount, count));

        return new CoinBalanceVO(toCoinAmount(balance));
    }

    // ========================================================================
    // 读：缓存是私有实现，跨域只走 Service（T40 的组装点从这里取）
    // ========================================================================

    /**
     * 我在该内容上投了几枚（{@code 0/1/2}，供详情互动态；T40 接入）。
     *
     * <p>薄委托 {@link CoinCache#getCoinAmount}：三态读 / 空标记 / 单飞 / Redis 降级都在缓存层，
     * 本方法不再重复任何判定。
     */
    public int getCoinAmount(long userId, long contentId) {
        return coinCache.getCoinAmount(userId, contentId);
    }

    /**
     * 某内容的累计投币数（公共计数；T40 接入）。
     *
     * <p>薄委托 {@link CoinCache#getCoinCount}。★ 缓存是 coin 域的**私有实现**——
     * 跨域不得直接注入 {@code CoinCache}（ArchUnit 规则 2），必须经本方法。
     *
     * <p>⚠️ <b>T44-2 起详情详情路径改走聚合层</b>（{@code ContentCountAggregate} 一趟 MGET +
     * miss 单飞），本方法的直接调用方已清零；**有意保留**（连同 {@code CoinCache#getCoinCount}）
     * ——它是"单键读"的现成通道（对齐 {@code batchGetCoinCount} 的保留先例），删除前先读
     * {@code 缓存与数据一致性.md §五.2}。
     */
    public long getCoinCount(long contentId) {
        return coinCache.getCoinCount(contentId);
    }

    /**
     * 累计投币数的**回填转发**（T44-2）：详情计数聚合读（content 域）经 {@code InteractionService}
     * 调到这里，值来自 {@code content.coin_count} 列快照。coin 对 content 无回指边
     * （T40 判断），转发只为统一聚合通道。
     */
    public void backfillCoinCount(long contentId, long count) {
        coinCache.backfillCoinCount(contentId, count);
    }

    /**
     * 批量读多条内容的累计投币数（T39 交付，T40 曾用于列表组装）。
     *
     * <p>薄委托 {@link CoinCache#batchGetCoinCount}：一趟 {@code MGET} + 一趟 DB 批量回填，
     * **不做循环单条查询**。
     *
     * <p>⚠️ <b>T43 起当前无调用方</b>：列表面不再返回任何计数（只在详情平铺）。
     * **有意保留**（连同 4 条缓存用例）——它是推翻条件①"列表重新需要计数"的**现成通道**，
     * 届时按面调用即可，不必重写批量与回填。删它之前先读
     * {@code architecture/tech/cache/缓存与数据一致性.md} §五.1。
     */
    public Map<Long, Long> batchGetCoinCount(Collection<Long> contentIds) {
        return coinCache.batchGetCoinCount(contentIds);
    }

    /**
     * 内容被删/下架：失效其投币计数缓存（由 {@code coin.event.ContentRemovedCoinListener}
     * 在内容删除事务**提交后**调用）。
     *
     * <p>★ 只失效缓存、**不物理删 {@code content_coin} 行**：投币不可取消 + 不变量
     * {@code coin_count == SUM(amount)} 要求行留存（与 like 的 {@code deleteContentLike}/
     * {@code deleteByContentId} 有意不同，理由见 {@link CoinCache} 类注释）。
     */
    public void deleteContentCoinCount(long contentId) {
        coinCache.invalidateCoinCount(contentId);
    }

    /**
     * 十分位整数 → 硬币金额（恒一位小数，**精确、不舍入**）。
     *
     * <p>{@code 15 → 1.5}、{@code 10 → 1.0}、{@code 0 → 0.0}。
     * {@link BigDecimal#movePointLeft(int)} 在十进制下是精确移位（不是除法），
     * 因此不会出现 {@code double} 的 {@code 0.1} 尾差；{@code setScale(1)} 只为把
     * {@code 10 → 1} 也统一成 {@code 1.0}（响应体的"一位小数"是契约，不能时有时无）。
     *
     * <p>⚠️ <b>与 {@code UserService#getProfile} 里的换算必须逐字一致</b>（那里刻意不调本方法，
     * 以避免新增 {@code user → coin} 依赖边）——口径见 {@code architecture/domain/coin/coin.md} §三。
     * 改这里就同步改那里，反之亦然。
     */
    public static BigDecimal toCoinAmount(int deci) {
        return BigDecimal.valueOf(deci).movePointLeft(1).setScale(1);
    }
}
