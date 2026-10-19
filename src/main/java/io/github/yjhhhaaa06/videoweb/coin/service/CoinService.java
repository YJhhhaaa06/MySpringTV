package io.github.yjhhhaaa06.videoweb.coin.service;

import io.github.yjhhhaaa06.videoweb.coin.model.vo.SignInVO;
import io.github.yjhhhaaa06.videoweb.common.time.SiteTime;
import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 投币域业务（T37：硬币供给端；投币写路径 T38 在此续建）。
 *
 * <h2>★ 单位约定：三处一起钉，别只钉一处</h2>
 * {@code users.coin_deci} 存的是**币的十分位**——{@code 15} 表示 1.5 枚，不是 15 枚。
 * 这条约定不靠记忆，靠三个同时存在的约束（{@code architecture/domain/coin/coin.md} §四）：
 * <ol>
 *   <li><b>列名带 {@code deci}</b>：DAO / SQL 两侧都不用无单位的名（{@code coin} / {@code balance}）；</li>
 *   <li><b>常量 {@link #DECI_PER_COIN}</b>：一枚硬币 = 10 个十分位。签到加多少由它传进 SQL
 *       （{@code #{deciPerCoin}}），不在 XML 里写 {@code 10}；</li>
 *   <li><b>换算只发生在 VO 出口</b>：DAO / Service 内部全程整数十分位，
 *       {@link #toCoinAmount(int)} 是唯一"整数 → 小数"的桥。</li>
 * </ol>
 * 三条缺一条，单位就会开始在各处漂移（"这里乘 10 那里除 10"），而 {@code 0/1.5/2} 这类小数值
 * 让这种错误看起来"像对的"，极难在回归里发现。
 *
 * <h2>签到为什么这么短</h2>
 * 幂等、并发安全、"哪一天"三件事全部收在一条 SQL 里（{@link UserDao#signIn} 的条件写）。
 * 因此这里**不加 {@code @Transactional}**——单语句自带原子性，加事务只是多一次 BEGIN/COMMIT；
 * 也**不发领域事件**——{@code /user/me} 直读 DB，没有任何为签到而存在的缓存要失效
 * （设计 §五.2）。
 */
@Service
public class CoinService {

    /**
     * 一枚硬币的十分位数（币 × {@value} = {@code coin_deci}）。
     *
     * <p>唯一声明点：签到加币走 {@code signIn(..., DECI_PER_COIN)}，投币扣款的
     * {@code count × DECI_PER_COIN} 也在 T38 用这个常量。**不要在 SQL 或 Controller 里再写 {@code 10}。**
     */
    public static final int DECI_PER_COIN = 10;

    private final UserDao userDao;

    public CoinService(UserDao userDao) {
        this.userDao = userDao;
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
     * 十分位整数 → 硬币金额（恒一位小数，**精确、不舍入**）。
     *
     * <p>{@code 15 → 1.5}、{@code 10 → 1.0}、{@code 0 → 0.0}。
     * {@link BigDecimal#movePointLeft(int)} 在十进制下是精确移位（不是除法），
     * 因此不会出现 {@code double} 的 {@code 0.1} 尾差；{@code setScale(1)} 只为把
     * {@code 10 → 1} 也统一成 {@code 1.0}（响应体的"一位小数"是契约，不能时有时无）。
     *
     * <p>⚠️ <b>与 {@code UserService#getProfile} 里的换算必须逐字一致</b>（那里刻意不调本方法，
     * 以避免新增 {@code user → coin} 依赖边）——口径见 {@code architecture/domain/coin/coin.md} §四。
     * 改这里就同步改那里，反之亦然。
     */
    public static BigDecimal toCoinAmount(int deci) {
        return BigDecimal.valueOf(deci).movePointLeft(1).setScale(1);
    }
}
