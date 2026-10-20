package io.github.yjhhhaaa06.videoweb.coin.dao;

import io.github.yjhhhaaa06.videoweb.coin.model.ContentCoinAmount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 投币记录（{@code content_coin} 表）数据访问。
 *
 * <h2>按表命名，镜像 {@code ContentLikeDao}</h2>
 * 一个域一张自有表、一个 DAO —— 与 like 域同形（{@code ContentLikeDao} 对 {@code content_like}）。
 * 不叫 {@code CoinDao}：投币之外还有余额（{@code users} 的列，归 {@code UserDao}）与计数
 * （{@code content.coin_count}，归 {@code ContentDao}），本类**只**管关系行。
 *
 * <h2>★ 单行聚合、不可取消、只存 1~2</h2>
 * {@code (user_id, content_id)} 唯一，一行累计一个人对一条内容的投币枚数（`amount ∈ {1,2}`）。
 * **没有删除 / 取消路径** —— 投币一经投出不可撤回（需求 §三）。因此本类只有"加"，
 * 没有 like 那样的 {@code deleteLike}。
 *
 * <p>⚠️ 被拒的投币尝试**不落行**：写路径整体在一个事务里，封顶 / 余额不足都是抛异常回滚
 * ⇒ {@link #insertIgnoreZero} 刚插的 `amount = 0` 行会一起消失
 * （`architecture/domain/coin/coin.md` §四.1 与 `coin/` 的验收②）。
 */
@Mapper
public interface ContentCoinDao {

    /**
     * 保证"用户 × 内容"这一行存在（不存在则插 `amount = 0`；已存在则 0 行、不报错）。
     *
     * <p>SQL: {@code insert ignore into content_coin (user_id, content_id, amount) values (?, ?, 0)}。
     *
     * <p>★ <b>它只负责"保证行存在"，不承担封顶。</b>封顶在 {@link #addAmount} 的
     * {@code amount + ? <= 2} 上。为什么必须分两步：条件累加需要一行可 UPDATE 的目标，
     * 而"先 SELECT 再决定插不插"会引入读写窗口（并发下两个线程都判"无行"）。
     * {@code INSERT IGNORE} 把"插或忽略"压成一条原子语句，唯一键
     * {@code uk_user_content} 是它的判据。
     *
     * <p>⚠️ {@code INSERT IGNORE} 对**已存在行**是 0 行、不抛错 —— 这正是要的：
     * 存在与否都不影响后续的条件累加，调用方**不需要**看返回值。
     *
     * @return 受影响行数（1 = 本次插入；0 = 行已存在）
     */
    int insertIgnoreZero(@Param("userId") long userId, @Param("contentId") long contentId);

    /**
     * 条件累加投币枚数：只有"加完仍不超过 2"才写。
     *
     * <p>SQL: {@code update content_coin set amount = amount + ? where user_id = ? and content_id = ?
     * and amount + ? <= 2}。
     *
     * <p>★ <b>必须返回影响行数</b>：0 行 ⇒ 封顶（调用方答 409「对本稿件投币枚数已用完」）。
     * 这里没有"先读 amount 再判断"的代码——那个窗口在并发下会让两个请求都读到 1、都写成 2 以上。
     * 反向验证：<b>去掉 {@code amount + ? <= 2} 这一句，并发各投 1 枚会突破封顶 2</b>
     * （{@code CoinAddTests} 的并发用例必红）。
     *
     * <p>⚠️ 已知代价（设计 §九 #1）：条件写分不出"已经投满 2"与"只剩 1 枚却想一次投 2"，
     * 两者都答 409。前端按 `0/1/2` 只给出合法选项，正常客户端不可达。
     *
     * @param delta 本次投出的枚数（1 或 2，由 Service 先校验）
     * @return 受影响行数（0 = 被封顶条件拦住；行不存在同样 0 行，但调用方已先 {@link #insertIgnoreZero}）
     */
    int addAmount(@Param("userId") long userId, @Param("contentId") long contentId,
                  @Param("delta") int delta);

    /**
     * 我在这条内容上投了几枚（单列窄查询）。
     *
     * <p>返回**包装类型** {@link Integer}：无行 ⇒ {@code null}（"从没投过"）。
     * 声明成 {@code int} 会在无行时拆箱 NPE。调用方要把 {@code null} 与 0 分开解读时
     * 才有意义（T40 的互动态读面用它区分"没投过"与"投过 0 枚"——后者不存在）。
     *
     * @return 枚数（1 或 2）；无行 ⇒ {@code null}
     */
    Integer findAmount(@Param("userId") long userId, @Param("contentId") long contentId);

    /**
     * 该用户投过币的**全部**内容及枚数（缓存全量回填用，T39）。
     *
     * <p>SQL 带 {@code amount > 0}：这是"投过币"的口径。理论上不存在 `amount = 0` 的行
     * （写路径拒单即回滚，见类注释），这条过滤是**按语义写**、不是按"当前实现可能留下的脏行"写——
     * 即便将来出现 0 行，也不会被当成"投过"回填进 HASH。
     *
     * <p>硬币稀缺 ⇒ 单用户投过的内容数远小于 {@code user:likeSet}，全量装载是可接受的
     * （设计 §五）。
     *
     * @return 扁平行列表；一枚都没投过 ⇒ 空列表（不是 null）
     */
    List<ContentCoinAmount> findAmountsByUser(@Param("userId") long userId);
}
