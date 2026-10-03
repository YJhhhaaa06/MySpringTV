package io.github.yjhhhaaa06.videoweb.feed.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 收件箱窗口落库 DAO（承接 TV {@code feed.dao.FeedInboxDao}，179 行 / 6 方法）。
 *
 * <p>写扩散把一条新内容写进作者每个粉丝的收件箱**DB 真相表** {@code feed_inbox}
 * （{@code (user_id, content_id)} 唯一键 = 幂等去重 + 窗口读覆盖索引）；重建侧则**整窗替换**
 * 该用户的窗口行并维护 {@code feed_inbox_sync}（窗口同步状态，"存在即已同步"）。
 *
 * <h2>迁移口径（《迁移参照系》§2.1 的机械改动）</h2>
 * <ul>
 *   <li>删首参 {@code Connection conn}（连接由 Spring 事务绑定）、删 {@code throws SQLException}；</li>
 *   <li>{@code ?} + {@code setLong} → {@code #{name}}；</li>
 *   <li>{@code StringBuilder} 拼 {@code VALUES (?,?),(?,?),…} → {@code <foreach>}（"白赚"项）；</li>
 *   <li>{@code @Component} → {@code @Mapper}。</li>
 * </ul>
 * <b>SQL 文本、表名、列名、{@code INSERT IGNORE} 语义——原样保留。</b>
 *
 * <p>写入形态：fanout 一轮粉丝窗口 = **一条多行 INSERT**（{@link #insertIgnoreBatch}）；
 * 重建 = {@link #deleteByUser} + {@link #insertBatch}（同一事务内）。不引入 {@code addBatch} /
 * 逐行插入（多行 VALUES 一趟往返，与 {@code ContentDao} 动态 IN 子句的拼装范式同构）。
 */
@Mapper
public interface FeedInboxDao {

    /**
     * 批量幂等落库：向 {@code fanIds} 每个用户的收件箱追加 {@code contentId}。
     *
     * <p>TV: {@code INSERT IGNORE INTO feed_inbox (user_id, content_id) VALUES (?,?),(?,?),…}
     *
     * <p>用 {@code INSERT IGNORE}：重复行靠 {@code uk_user_content} 静默忽略 ⇒ 幂等
     * （MQ automatic recovery 重发 / 消费重试重放均无副作用）。
     *
     * @param contentId 内容 id
     * @param fanIds    粉丝 id 列表（一轮窗口）；**调用方保证非空**（{@code <foreach>} 不接受空集合）
     * @return 实际新增行数（重复行被 IGNORE，不计入）
     */
    int insertIgnoreBatch(@Param("contentId") long contentId, @Param("fanIds") List<Long> fanIds);

    // ==================== 重建侧（窗口替换 + 同步状态） ====================

    /**
     * 清空某用户收件箱窗口（重建第一步，"先清后建"的载体 = DB 真相表）。
     *
     * <p>顺序红线（TV 原注记）：本方法在重建事务内**先于**窗口重查执行，且其后事务内
     * **不再有任何删除动作**（窗口写入用 {@link #insertBatch} 的 {@code INSERT IGNORE}）——
     * 这样"清"与"建"之间到达的并发 fanout 增量只会**并集**进同一份快照（只多不丢）。
     *
     * @return 删除行数
     */
    int deleteByUser(@Param("userId") long userId);

    /**
     * 窗口批量落库（重建产物）：向**同一用户**写入一批 {@code contentId}（一条多行 {@code INSERT IGNORE}）。
     *
     * <p>用 {@code INSERT IGNORE} 而非裸 {@code INSERT}：与并发 fanout 的 {@link #insertIgnoreBatch}
     * 保持同一"幂等、无覆盖"语义，使"重建整窗替换"与"fanout 增量"交错时结果为**并集**。
     *
     * @param contentIds 窗口内容 id（已归并去重降序裁剪）；**调用方保证非空**
     * @return 实际新增行数
     */
    int insertBatch(@Param("userId") long userId, @Param("contentIds") List<Long> contentIds);

    /**
     * 窗口同步状态落库（"存在即已同步"）。
     *
     * <p>必须与窗口替换**同一事务**调用：否则"同步态已写但窗口回滚"会让读侧（读态闸门
     * 「未同步 → 回退纯拉」）误信一个陈旧窗口。**空窗口同样写**（"空但已同步"）。
     *
     * <p>TV: {@code INSERT INTO feed_inbox_sync (user_id) VALUES (?)
     * ON DUPLICATE KEY UPDATE sync_time = CURRENT_TIMESTAMP}
     * （MySQL 命中唯一键仍消耗一次自增 id——仅影响观感，无正确性影响，TV 原样不规避）。
     */
    int upsertSync(@Param("userId") long userId);

    // ==================== 读侧（同步闸门 + 窗口读取） ====================

    /**
     * 读态闸门：{@code userId} 的收件箱窗口**是否已同步**。
     *
     * <p>语义 = "存在即已同步"：只有**重建**会写本表，而 fanout 只向 {@code feed_inbox} 追增、
     * **从不写同步状态** ⇒ 没有本行 = 该用户从没成功重建过 ⇒ 窗口不可作为读源（读侧回退纯拉）。
     *
     * <p>成本 = 一条 {@code uk_user} 唯一键点查（每请求一次，不做缓存）。
     * {@code feed_inbox_sync} 行**只 upsert、从不删除** ⇒ "曾同步 ⇒ 现在仍同步"恒成立。
     *
     * <p>写法：TV 是 {@code SELECT 1 … LIMIT 1} + {@code rs.next()}；
     * 这里用 {@code EXISTS}(0/1)（与 {@code ContentDao.isContentExist} 同款），避免无行 → null → 拆箱 NPE。
     */
    boolean existsSync(@Param("userId") long userId);

    /**
     * 读某用户收件箱窗口的**全部** contentId（收件箱腿的 DB 装载器）。
     *
     * <p>**升序**返回（{@code ORDER BY content_id}）：与 {@code feed:inbox:{id}} 缓存 ZSet 的
     * score 序（score = contentId ⇒ ZRANGE 升序）**同向** ⇒ "缓存命中"与"回源回填"两条路径的成员序一致，
     * 读侧统一做一次内存反序即得内容倒序。
     *
     * <p>范围 = 该用户窗口的**全量行**（表侧由重建裁剪到 C、fanout 只追增），走
     * {@code uk_user_content(user_id, content_id)} 唯一键的索引区间扫描。
     *
     * <p>⚠️ **无 LIMIT**（TV 原样，已登记为已知读放大：久未重建的用户表侧可膨胀）。
     */
    List<Long> findInboxContentIds(@Param("userId") long userId);
}
