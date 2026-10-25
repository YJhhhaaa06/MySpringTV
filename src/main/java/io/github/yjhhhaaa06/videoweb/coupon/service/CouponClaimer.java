package io.github.yjhhhaaa06.videoweb.coupon.service;

import io.github.yjhhhaaa06.videoweb.common.exception.ConflictException;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.coupon.dao.CouponDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 抢券的**写入层**：「扣库存 + 插订单」这一个原子动作。
 *
 * <h2>为什么单独一个 bean（`T64`）</h2>
 * 幂等重放要求「撞唯一键后**在原事务结束之后**再回查订单表」——
 * 回查必须发生在**没有事务**的编排层（{@link CouponService#grabCoupon}），
 * 否则 ① 在事务内 catch 住异常再正常返回，{@code deductStock} 会**随事务提交**（库存白扣）；
 * ② MySQL 默认 RR 下同事务内的一致性读**看不到赢家刚提交的行**（回查拿到 null）。
 *
 * <p>把事务方法留在 {@code CouponService} 里就行不通了：同一个类内的自调用**绕过 Spring 代理**，
 * {@code @Transactional} 完全不生效（CM-2 同款坑）。所以这里独立成 bean：
 * 异常穿出本类的 {@code @Transactional} 方法 ⇒ 整笔回滚（库存还原）⇒ 调用方再回查。
 *
 * <p>⚠️ 本类的 {@code @Transactional} 是**语义的一部分**，不是装饰：去掉它，
 * 重复抢券时刚扣掉的库存不会还原（{@code CouponConcurrencyTests} 的 stock 断言会红）。
 */
@Service
public class CouponClaimer {

    private final CouponDao couponDao;

    public CouponClaimer(CouponDao couponDao) {
        this.couponDao = couponDao;
    }

    /**
     * 扣库存并写订单，成功返回 16 位券码。
     *
     * <p><b>决策表 C-1：✅ 保持单事务。</b>「扣库存 + 插订单」必须原子——
     * 否则会出现"白扣库存"或"超发"。
     *
     * <p>两条失败路径都在事务内抛异常，故都会回滚：
     * <ul>
     *   <li>{@code deductStock} 返回 0 ⇒ {@link ConflictException}
     *       "库存不足或活动未开始/已结束"。三语义在 Service 层不可区分——
     *       判定条件全在那条 SQL 的 WHERE 里（见 {@code CouponMapper.xml}）。</li>
     *   <li>{@code insertOrder} 撞唯一键 ⇒ {@code DuplicateKeyException}（{@code DataAccessException} 子类，
     *       是 RuntimeException ⇒ 默认触发回滚）。
     *       <b>回滚是关键</b>：重复抢券时刚扣掉的库存必须被还原，即"库存只扣一次"。</li>
     * </ul>
     * 两种异常都由调用方 {@link CouponService#grabCoupon} 在**本事务已结束之后**处理，
     * 本方法不自行 catch（catch 在这里会把回滚变成提交）。
     *
     * <p>⚠️ 本方法**不得**把时间窗判断挪进 Java，也不得把 {@code insertOrder} 移出事务——
     * 见决策表 C-1 的两条反模式警告。
     *
     * @param couponId 优惠券 id（控制器已做非空/正数校验）
     * @param userId   当前登录用户 id
     */
    @Transactional
    public String claim(long couponId, long userId) {
        int rows = couponDao.deductStock(couponId);
        if (rows == 0) {
            throw new ConflictException(ErrorCode.COUPON_UNAVAILABLE, "库存不足或活动未开始/已结束");
        }

        String couponCode = generateCouponCode();
        couponDao.insertOrder(couponId, userId, couponCode);
        return couponCode;
    }

    /**
     * 生成券码：{@code UUID} 去横线后取前 16 位并大写。
     *
     * <p>TV 原实现原样保留（《切片计划》§一 要点 3）。字符集为 {@code [0-9A-F]}（UUID 是十六进制），
     * 更宽的 {@code [0-9A-Z]} 断言同样成立。
     */
    private String generateCouponCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }
}
