package io.github.yjhhhaaa06.videoweb.coupon.dao;

import io.github.yjhhhaaa06.videoweb.coupon.model.vo.CouponOrderVO;
import io.github.yjhhhaaa06.videoweb.coupon.model.vo.CouponVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 优惠券数据访问。
 *
 * <p>迁移自 TV {@code com.itheima.coupon.dao.CouponDao}——《迁移参照系》§2.1 的机械改动：
 * <ul>
 *   <li>删掉首参 {@code Connection conn}（连接由 Spring 事务绑定）</li>
 *   <li>删掉 {@code throws SQLException}（{@code DataAccessException} 接管）</li>
 *   <li>{@code ?} + {@code setXxx(n, v)} 改为 {@code #{name}} 命名参数</li>
 *   <li>{@code ResultSet} 手工遍历 + 手搭 {@code Map} → {@code <resultMap>} 显式映射</li>
 *   <li>{@code @Component} 改为 {@code @Mapper}</li>
 *   <li>{@code Statement.RETURN_GENERATED_KEYS} 手工取键 → {@code useGeneratedKeys}
 *       （本切片无插入需回填主键，故未用到）</li>
 * </ul>
 * <b>SQL 文本、表名、列名、条件、排序——原样保留</b>（见 {@code CouponMapper.xml} 内逐条 TV 对照注释）。
 *
 * <h2>只搬 4 个方法（能力闭环，不按文件横向搬运）+ `T64` 新增 1 个</h2>
 * TV 的 {@code CouponDao} 共 5 个方法，{@code findById} **未搬**：
 * 它在 TV 全仓（main/test/tools）**零引用**，是 dead code——
 * 既非本切片交付的能力，也非任何下游模块的依赖。搬它只会带来无主的维护面。
 * <p>{@link #findCouponCode} 是 `T64` **新增**的（TV 没有）：幂等重放要按 {@code (coupon,user)}
 * 窄查原券码，见其 Javadoc。
 */
@Mapper
public interface CouponDao {

    /**
     * 原子扣减库存，返回受影响行数。
     *
     * <p><b>本方法承载 C-1 的核心并发语义</b>（《事务边界决策表》§二·B）：
     * "库存充足"与"活动时间窗内"两个条件**必须在同一条 UPDATE 的 WHERE 里**，
     * 且 {@code stock = stock - 1} 由 DB 做原子增减。
     * 返回 0 即"库存不足 / 未开始 / 已结束"三合一。
     *
     * <p>⚠️ 不得改写成"先 SELECT 判断条件、再 UPDATE"——那会引入 TOCTOU 窗口；
     * 也不得把时间窗条件的判定挪到 Java 层。详见决策表 C-1 的反模式警告。
     */
    int deductStock(@Param("couponId") long couponId);

    /**
     * 插入抢券订单。
     *
     * <p>撞唯一键 {@code uk_coupon_user}（同一用户并发重复抢）或 {@code uk_coupon_code}（券码撞车）
     * 时，MyBatis 的异常翻译会抛 {@code DuplicateKeyException}，并让所在事务整笔回滚。
     * <p>★ `T64` 起这两种撞键**不再等价**：{@code CouponService#grabCoupon} 在事务外回查
     * {@link #findCouponCode}，命中 ⇒ 幂等返回原券码；查不到 ⇒ 撞的是 {@code uk_coupon_code}
     * （真故障）⇒ 原样重抛 ⇒ {@code GlobalExceptionHandler} 映射 409 {@code SYSTEM_DUPLICATE_KEY}。
     * 业务层**不判断** MySQL 错误码 1062（决策表 C-1 要点 1）。
     *
     * @return 受影响行数（正常恒为 1；MyBatis 不回填主键，本切片无需订单 id）
     */
    int insertOrder(@Param("couponId") long couponId,
                    @Param("userId") long userId,
                    @Param("couponCode") String couponCode);

    /**
     * 未结束的优惠券列表（{@code end_time > NOW()}，按 {@code begin_time} 升序）。
     *
     * <p>⚠️ 口径是"**未结束**"而非"可抢"：{@code begin_time} 在未来（尚未开始）的券也会返回。
     * 勿把 WHERE 收窄成"时间窗内"——那是行为改动。
     */
    List<CouponVO> findAvailableCoupons();

    /** 某用户的优惠券（{@code coupon_order JOIN coupon}，按抢购时间倒序）。查无记录返回空列表而非 null。 */
    List<CouponOrderVO> findOrdersByUserId(@Param("userId") long userId);

    /**
     * 某用户在某张券上的券码（`T64` 幂等重放用）；**没有订单行 ⇒ null**。
     *
     * <p>这是"重复领取"的唯一判据（不是猜唯一键）：命中即"本人已领过"，直接回原码。
     * 必须走这个窄查询——{@link #findOrdersByUserId} 会拉该用户**全部**订单再在内存里筛，
     * 既多取行，又把"是否领过"的判断摊到调用方。
     *
     * <p>⚠️ **已知边界（有意不修）**：不过滤 {@code coupon_order.status}。
     * 该列目前**只读不写**（默认 1=成功，2=已使用尚无写入方）—— 一旦将来出现"已使用/已作废"状态，
     * "领过 ⇒ 回原码"就得改成"状态仍可用才回原码"，届时与本查询一并重定。现在加过滤条件只会
     * 凭一个没有写入方的列编造语义。
     */
    String findCouponCode(@Param("couponId") long couponId, @Param("userId") long userId);
}
