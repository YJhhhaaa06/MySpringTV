package io.github.yjhhhaaa06.videoweb.coupon.service;

import io.github.yjhhhaaa06.videoweb.common.exception.ConflictException;
import io.github.yjhhhaaa06.videoweb.coupon.dao.CouponDao;
import io.github.yjhhhaaa06.videoweb.coupon.model.vo.CouponOrderVO;
import io.github.yjhhhaaa06.videoweb.coupon.model.vo.CouponVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 优惠券业务。
 *
 * <p>迁移自 TV {@code com.itheima.coupon.service.CouponService}。
 * 骨架（{@code transactionTemplate.execute} / {@code conn} 穿透 / {@code catch (SQLException)} 包装）
 * 已删除，业务语义（校验口径、异常类型、事务边界）逐条保留——
 * 对照与反模式警告见《事务边界决策表》§二·B（C-1 ~ C-3）。
 *
 * <h2>与 TV 的三处差异（均已表态，勿当缺陷"修正"）</h2>
 * <ol>
 *   <li><b>C-1 删掉 MySQL 错误码 1062 判断</b>：Spring 把唯一键冲突翻译为
 *       {@code DuplicateKeyException}，由 {@code GlobalExceptionHandler} 统一映射为 409。
 *       副作用是重复抢券的 {@code msg} 由 <i>"您已抢过该优惠券"</i> 变为 <i>"操作冲突"</i>——
 *       HTTP 状态码与 body {@code code} 仍是 409 不变（DoD 冻结的是路径/信封/状态码）。</li>
 *   <li><b>C-2 / C-3 去掉读路径的事务包装</b>：TV 用 {@code transactionTemplate.execute}
 *       仅为了"顺手拿到 Connection"，纯读无原子性需求（同决策表 U-3 / U-4）。</li>
 *   <li><b>不再手写 {@code ServerException} 包装与 SEVERE 堆栈日志</b>：TV 的
 *       "包装点即源头"日志纪律由 {@code GlobalExceptionHandler} 单一出口承担
 *       （{@code DataAccessException} → 记堆栈 500），业务代码里不再有 try/catch。</li>
 * </ol>
 */
@Service
public class CouponService {

    private final CouponDao couponDao;

    public CouponService(CouponDao couponDao) {
        this.couponDao = couponDao;
    }

    // ========================================================================
    // C-1：grabCoupon —— 单事务（扣库存 + 插订单必须原子）
    // ========================================================================

    /**
     * 抢券，成功返回 16 位券码。
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
     *       是 RuntimeException ⇒ 默认触发回滚）⇒ 出口映射 409。
     *       <b>回滚是关键</b>：重复抢券时刚扣掉的库存必须被还原，即"库存只扣一次"。</li>
     * </ul>
     *
     * <p>⚠️ 本方法**不得**把时间窗判断挪进 Java，也不得把 {@code insertOrder} 移出事务——
     * 见决策表 C-1 的两条反模式警告。
     *
     * @param couponId 优惠券 id（控制器已做非空/正数校验）
     * @param userId   当前登录用户 id
     */
    @Transactional
    public String grabCoupon(long couponId, long userId) {
        int rows = couponDao.deductStock(couponId);
        if (rows == 0) {
            throw new ConflictException("库存不足或活动未开始/已结束");
        }

        String couponCode = generateCouponCode();
        couponDao.insertOrder(couponId, userId, couponCode);
        return couponCode;
    }

    // ========================================================================
    // C-2 / C-3：读路径 —— ⚠️ 有意改进，去掉事务包装
    // ========================================================================

    /**
     * 未结束的优惠券列表（公开端点，无需登录）。
     *
     * <p><b>决策表 C-2：⚠️ 有意改进</b>——TV 此处套了 {@code transactionTemplate.execute}，
     * 但纯读无写、无原子性需求，那个事务只是"取连接的手段"。新实现直查，不加注解。
     */
    public List<CouponVO> listAvailableCoupons() {
        return couponDao.findAvailableCoupons();
    }

    /**
     * 我的优惠券列表。
     *
     * <p><b>决策表 C-3：⚠️ 有意改进</b>——同 C-2，纯读去事务。
     *
     * @param userId 当前登录用户 id（由 {@code @CurrentUserId} 注入，端点已声明 {@code @RequiresLogin}）
     */
    public List<CouponOrderVO> listMyCoupons(long userId) {
        return couponDao.findOrdersByUserId(userId);
    }

    // ========================================================================
    // 券码生成（TV 原样保留：UUID 去横线取前 16 位转大写）
    // ========================================================================

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
