package io.github.yjhhhaaa06.videoweb.favorite.dao;

import io.github.yjhhhaaa06.videoweb.favorite.model.entity.FavoriteFolder;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteFolderVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 收藏夹数据访问（表 {@code favorite_folder}，V2 建）。
 *
 * <h2>为什么 T1 时是空接口</h2>
 * 本类在 T1 只承担**两件可验证的事**：① 确立 {@code favorite.dao} 包与 mapper 注册路径
 * （{@code @Mapper} 由 Boot 的 mapper 扫描自动注册，与 like/comment 各域同一形态）；
 * ② 让 service 层的装配关系在**编译期**就成立。
 * 具体 SQL 由**使用它的任务**补（T2 夹 CRUD / T3 他人公开夹 / T4 移出与移动），
 * 本仓纪律是**不提前写无主代码/无主 SQL**（口径源自迁移期的"不搬无主代码"）。
 *
 * <h2>建表时的三条约束（写 SQL 前先读，别在下游重复推导）</h2>
 * <ul>
 *   <li>★ {@code uk_user_default (user_id, default_uniq)} —— <b>每用户至多一个默认夹</b>。
 *       {@code default_uniq} 是 VIRTUAL 生成列：默认夹 {@code 1}、自建夹 {@code NULL}，
 *       靠"唯一索引不约束 NULL"让自建夹可多条。R-01 的**懒建**（首次收藏才建默认夹）
 *       并发重复只能靠它兜住 —— {@code INSERT ... WHERE NOT EXISTS} 兜不住（两个事务都能
 *       查到"不存在"）。故建默认夹应写 {@code INSERT ... ON DUPLICATE KEY UPDATE}
 *       或捕获 {@code DuplicateKeyException} 后重查，**不要**写成"先 SELECT 后 INSERT"。
 *       ⚠️ 本任务（T2）**不建默认夹** —— 懒建的落点是"首次收藏"（T4）；
 *       T2 只消费 {@code is_default}（判"默认夹不可删"）。</li>
 *   <li>列表查询（{@code WHERE user_id = ?}）**用 {@code uk_user_default} 的前导列即可**，
 *       表上刻意没有单独的 {@code idx_user}（冗余索引）。</li>
 *   <li>{@code is_private}（0 公开 / 1 私密）与 {@code description} 在建表时就带了
 *       （R-05：后补要 V3 + 存量回填）；私密的**可观察落点**不在这里，而在他人视角端点。</li>
 * </ul>
 *
 * <h2>T2 交付的五条 SQL（各端点用哪条）</h2>
 * <table>
 *   <caption>方法 ↔ 端点</caption>
 *   <tr><th>方法</th><th>服务于</th></tr>
 *   <tr><td>{@link #insertFolder}</td><td>{@code POST /favorite/folder/add}</td></tr>
 *   <tr><td>{@link #findByUserIdWithItemCount}</td><td>{@code GET /favorite/folder/list}</td></tr>
 *   <tr><td>{@link #findById}</td><td>改名 / 删除前的归属与类别校验</td></tr>
 *   <tr><td>{@link #updateName}</td><td>{@code POST /favorite/folder/update}</td></tr>
 *   <tr><td>{@link #deleteById}</td><td>{@code POST /favorite/folder/remove}（条目由 {@code FavoriteItemDao} 同事务删）</td></tr>
 * </table>
 *
 * <p>⚠️ 删除自建夹要**一并删条目**（R-02）；删夹与删条目必须同事务
 * （见 {@code architecture/tech/事务边界.md}）——事务边界在 {@code FavoriteService.deleteFolder}。
 */
@Mapper
public interface FavoriteFolderDao {

    /**
     * 新建收藏夹（**只能是自建夹**：{@code is_default} 走表默认 0）。
     *
     * <p>⚠️ 默认夹**不在这里建**：R-01 拍板懒建、落点是"首次收藏"（T4），
     * 那里才需要 {@code ON DUPLICATE KEY UPDATE} / 撞键重查那一套（唯一键 {@code uk_user_default}）。
     * 本方法建的夹 {@code default_uniq = NULL}，不受唯一键约束 ⇒ 同一用户可建任意多个、也可重名。
     *
     * @param name 已由 service 校验（非空白、≤ 100 字符 —— 与列 {@code varchar(100)} 对齐）
     * @return 受影响行数（正常恒为 1）
     */
    int insertFolder(@Param("userId") long userId, @Param("name") String name);

    /**
     * 按 id 取回一个夹（归属 + 类别），供改名 / 删除前校验。
     *
     * <p>返回 {@link FavoriteFolder} 而不是布尔：调用方既要判"在不在"，
     * 又要判"是不是我的"（403）与"是不是默认夹"（409），一次取回省掉重复查询。
     *
     * @return 无该行 ⇒ {@code null}
     */
    FavoriteFolder findById(@Param("id") long id);

    /**
     * 我的收藏夹列表（含每夹条目数），**含私密夹**（分期篇 §3.3）。
     *
     * <h2>为什么用 LEFT JOIN + GROUP BY 而不是"先查夹再逐夹 COUNT"</h2>
     * 后者是 N+1（自建夹一期不设上限）。这里一条 SQL 取回全部，
     * 且 {@code COUNT(i.id)} 挂在 {@code LEFT JOIN} 上：
     * <ul>
     *   <li>空夹得 {@code 0} 而不是**整行消失**（{@code INNER JOIN} 会让空夹从列表里掉出去，
     *       用户会以为夹没了）；</li>
     *   <li>失效内容的记录照样计数（R-07）—— 这条 SQL **根本不访问 content 表**，
     *       自然不存在"要不要排除已删内容"的分支。</li>
     * </ul>
     *
     * <p><b>顺序是契约</b>：默认夹置顶，其余按 {@code id} 升序（即创建顺序）。
     * {@code id} 与 {@code create_time} 同序（自增 + 同一条 INSERT 落库），故等价于
     * "先建的在前"。前端/他人视角（T3）复用同一顺序。
     *
     * @param userId 当前登录用户（只返回他自己的夹 —— 私密夹的可观察面在这里"看不见"，
     *               它由 T3 的公开端点负责过滤）
     */
    List<FavoriteFolderVO> findByUserIdWithItemCount(@Param("userId") long userId);

    /**
     * 改名。
     *
     * <p>⚠️ **不带 {@code user_id} 条件**：归属校验在 service 里**先做**（{@code findById} 判 404/403），
     * 再按 id 写。理由：把校验塞进 {@code WHERE user_id = ?} 会让"不是我的夹"与"改了但值没变"
     * 都表现为 0 行，**两种情况分不开**（MySQL 的 UPDATE 对"值未变化"同样返回 0）。
     *
     * <p>默认夹**可以改名**（B 站如此；需求篇只规定"默认夹删不掉"）。
     *
     * @param name 已由 service 校验
     * @return 受影响行数（同值改名时为 0，属正常）
     */
    int updateName(@Param("id") long id, @Param("name") String name);

    /**
     * 删除夹行（硬删）。
     *
     * <p>⚠️ <b>条目由 {@code FavoriteItemDao.deleteByFolderId} 在**同一事务**里先删</b>
     * （R-02：删夹一并删条目）。两个 DAO 两次写必须同进同退，
     * 否则会留下"夹没了、条目还在"的孤儿（夹内列表按 {@code folder_id} 查，用户永远看不到它们，
     * 但收藏数 {@code COUNT(DISTINCT user_id)} 会**把它们算进去** —— 静默脏数据）。
     *
     * @return 受影响行数（正常恒为 1）
     */
    int deleteById(@Param("id") long id);
}
