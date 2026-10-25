package io.github.yjhhhaaa06.videoweb.coupon.service;

import io.github.yjhhhaaa06.videoweb.common.exception.ConflictException;
import io.github.yjhhhaaa06.videoweb.coupon.dao.CouponDao;
import io.github.yjhhhaaa06.videoweb.coupon.model.vo.CouponOrderVO;
import io.github.yjhhhaaa06.videoweb.coupon.model.vo.CouponVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

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
 *       <p>★ `T64` 起<b>重复领券不再 409</b>：改为幂等重放（200 + 原券码）。
 *       做法是**回查订单表**（确定事实），<b>不是</b> catch 1062 后猜键 ——
 *       出口拿到的 {@code DuplicateKeyException} **无法知道撞的是 {@code uk_coupon_user}
 *       还是 {@code uk_coupon_code}**，硬猜会把"券码随机撞车"这种真故障报成"您已抢过"。
 *       判据见 {@link #grabCoupon}：回查命中 ⇒ 原码；查不到 ⇒ 原样重抛（真故障仍是 409
 *       {@code SYSTEM_DUPLICATE_KEY}）。C-1 的「不判 1062」**保持不变**。</p></li>
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
    private final CouponClaimer couponClaimer;

    public CouponService(CouponDao couponDao, CouponClaimer couponClaimer) {
        this.couponDao = couponDao;
        this.couponClaimer = couponClaimer;
    }

    // ========================================================================
    // 抢券：无事务的编排层（回查 → 写入 → 撞键后再回查）
    // ========================================================================

    /**
     * 抢券，返回 16 位券码。
     *
     * <p><b>幂等口径（`T64`，依据需求编号 `N4` §G·P0-3）</b>：
     * 同一 {@code (couponId, userId)} 重复领取 ⇒ 仍返回**原券码**（200），
     * 库存不重复扣、订单仍 1 行。这是"客户端丢 2xx 后自动重试"的前提。
     *
     * <p>★ <b>优先级：回查在前</b> —— 已领过 ⇒ 直接回原码，**与库存 / 时间窗无关**；
     * 只有**未领过**的人才可能因库存 0 / 未开始 / 已结束拿到
     * {@link ConflictException}（{@code COUPON_UNAVAILABLE}）。反过来写（先扣减后判断）
     * 会让"活动刚结束的已领用户重试"变成 409，正是本任务要消掉的那类窗口。
     *
     * <h2>为什么本方法**不带** {@code @Transactional}</h2>
     * 它只做编排，真正的原子动作在 {@link CouponClaimer#claim}（另一个 bean 的
     * {@code @Transactional} 方法）。这里再加事务会同时毁掉两件事：
     * <ol>
     *   <li>撞键异常被本层 catch 时，内层事务会**随本层事务提交**（若此处也用同一事务）
     *       ⇒ {@code deductStock} 白扣；</li>
     *   <li>MySQL 默认 RR 下，同事务内的一致性读**看不到赢家刚提交的行** ⇒ 回查必拿 null。</li>
     * </ol>
     * 拆开的顺序保证：异常穿出 {@link CouponClaimer#claim} 的 {@code @Transactional} 边界 ⇒
     * 整笔回滚（库存还原、连接归还）⇒ 本层再回查时读的是**赢家已提交**的行。
     *
     * <p>⚠️ <b>不许在这里猜键</b>：不能把 {@code DuplicateKeyException} 一律当"已领"。
     * 回查查不到 ⇒ 说明撞的是 {@code uk_coupon_code}（券码随机撞车，真故障）⇒ 原样重抛。
     * 同理 {@link ConflictException}（并发窗口里赢家抢走了最后一张库存，本请求的
     * {@code deductStock} 匹配 0 行）也要回查一次——已领过的人不该因为库存刚好归零而拿到 409。
     *
     * <p>⚠️ <b>不得</b>用 {@code SELECT … FOR UPDATE} 串行化（本仓已排除该手法），
     * 也不得把 {@code insertOrder} 移出事务（见决策表 C-1 的两条反模式警告）。
     *
     * @param couponId 优惠券 id（控制器已做非空/正数校验）
     * @param userId   当前登录用户 id
     */
    public String grabCoupon(long couponId, long userId) {
        // ① 已领过：直接给原码（此路径**不扣库存**，也不判时间窗/库存）
        String claimed = couponDao.findCouponCode(couponId, userId);
        if (claimed != null) {
            return claimed;
        }

        // ② 未领过：进入原子写入（扣减 + 插入，同一事务）
        try {
            return couponClaimer.claim(couponId, userId);
        } catch (DuplicateKeyException | ConflictException ex) {
            // ③ 撞键（输家）或"最后一张库存被赢家拿走后本请求扣减 0 行"：
            //    此刻内层事务**已回滚结束**，回查是独立连接、能看到赢家已提交的行。
            //    命中 ⇒ 幂等重放；未命中 ⇒ 真失败（撞 uk_coupon_code / 库存或时间窗确实不满足），
            //    保持原异常类型与文案不变。
            String replayed = couponDao.findCouponCode(couponId, userId);
            if (replayed != null) {
                return replayed;
            }
            throw ex;
        }
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
}
