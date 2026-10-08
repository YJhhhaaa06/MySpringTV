package io.github.yjhhhaaa06.videoweb.favorite.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 收藏夹行（表 {@code favorite_folder}，V2 建）——**只映射用得着的列**。
 *
 * <h2>为什么字段不全（同 {@code content.model.entity.Content} 的口径）</h2>
 * 本类在 T2 的角色是「**按 id 取回一个夹的归属与类别**」，供改名 / 删除做校验：
 * <ul>
 *   <li>{@code id} / {@code userId} —— 归属校验（不是我的 ⇒ 403）；</li>
 *   <li>{@code isDefault} —— ★ 删除前判"默认夹"（R-02 红线：默认夹不可删）；</li>
 *   <li>{@code name} —— 随行读出（日志 / 断言可读性），本期不改名逻辑不消费旧值；</li>
 *   <li>{@code isPrivate} —— 一期建表就带的列（R-05），本行映射随带读出。</li>
 * </ul>
 * <b>不映射</b> {@code description}（需求篇标「待定」）/ {@code create_time} / {@code update_time}
 * —— 一期没有消费方，按"不提前搬无主字段"的纪律留空（补时改本类 + resultMap 两处）。
 *
 * <p>⚠️ <b>{@code default_uniq} 是 VIRTUAL 生成列（唯一键的判别列），刻意不映射</b>：
 * 它不承载业务语义，只是 {@code uk_user_default} 的挂载点（理由见
 * {@code CURRENT_NEEDS.md} R-09）。
 *
 * <p>⚠️ <b>两个布尔用包装类型 {@code Boolean} 而不是 {@code boolean}</b>：列是 {@code NOT NULL}，
 * 但属性名 {@code isDefault} 配 {@code boolean} 会让 Lombok 生成 {@code isDefault()} + {@code setDefault()}，
 * 于是 MyBatis 推导出的**属性名**（setter 侧）会变成 {@code default}，与 resultMap 里写的
 * {@code isDefault} 对不上（静默映射成 null）。用包装类型后 getter/setter 是
 * {@code getIsDefault}/{@code setIsDefault}，属性名稳定为 {@code isDefault}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteFolder {

    private Long id;

    private Long userId;

    private String name;

    /** 私密：0 公开 / 1 私密（一期建列，落点在他人视角端点 —— T3）。 */
    private Boolean isPrivate;

    /** 默认夹：0 自建 / 1 默认。★ **默认夹不可删除**，故它是删除路径的判据。 */
    private Boolean isDefault;

    /** 是否默认夹（{@code null} 视为否 —— 列是 NOT NULL，此守卫只为防映射缺失时的 NPE）。 */
    public boolean defaultFolder() {
        return Boolean.TRUE.equals(isDefault);
    }
}
