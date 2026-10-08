package io.github.yjhhhaaa06.videoweb.favorite.dao;

import org.apache.ibatis.annotations.Mapper;

/**
 * 收藏夹数据访问（表 {@code favorite_folder}，V2 建）。
 *
 * <h2>为什么是空接口（T1 只落骨架）</h2>
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
 *       或捕获 {@code DuplicateKeyException} 后重查，**不要**写成"先 SELECT 后 INSERT"。</li>
 *   <li>列表查询（{@code WHERE user_id = ?}）**用 {@code uk_user_default} 的前导列即可**，
 *       表上刻意没有单独的 {@code idx_user}（冗余索引）。</li>
 *   <li>{@code is_private}（0 公开 / 1 私密）与 {@code description} 在建表时就带了
 *       （R-05：后补要 V3 + 存量回填）；私密的**可观察落点**不在这里，而在他人视角端点。</li>
 * </ul>
 *
 * <p>⚠️ 删除自建夹要**一并删条目**（R-02）；删夹与删条目必须同事务
 * （见 {@code architecture/tech/事务边界.md}）。
 */
@Mapper
public interface FavoriteFolderDao {
}
