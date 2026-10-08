package io.github.yjhhhaaa06.videoweb.favorite.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 我的收藏夹列表项（{@code GET /favorite/folder/list} 的条目）。
 *
 * <h2>一个形状两个角色</h2>
 * 本类同时是 **Mapper 行类型**（{@code favoriteFolderVoMap} 的 {@code item_count} 由
 * {@code LEFT JOIN favorite_item} 聚合而来）与 **API 载荷形状**——与
 * {@code coupon.model.vo.CouponVO} 同款处置，不让"一个形状"多出一层 row→VO 拷贝。
 *
 * <h2>字段口径</h2>
 * <ul>
 *   <li>{@code id} / {@code name} —— 夹的身份，供前端打开夹 / 改名 / 删除（改名要 id，不按名字定位）；</li>
 *   <li>{@code isDefault} —— ★ 前端据此**不给默认夹渲染"删除"**；后端不依赖前端，
 *       删默认夹在 {@code FavoriteService.deleteFolder} 里另有一道 409（契约测试守着）；</li>
 *   <li>{@code isPrivate} —— 列表是"我的夹"，**含私密**（分期篇 §3.3 原话）；
 *       本 VO 只**读**它；写入口（私密开关）在 T3 已落地（{@code POST /favorite/folder/update}，
 *       部分更新，见 {@code FavoriteService.updateFolder}）；</li>
 *   <li>{@code itemCount} —— 每夹**条目数**。</li>
 * </ul>
 *
 * <h2>★ {@code itemCount} 的口径：数的是<b>收藏记录</b>，不是"还能打开的内容"</h2>
 * 失效内容（{@code is_deleted != 0}）的记录**照样算**（R-07 用户 B 站实测：占位条目计入视频数）。
 * 实现上 {@code COUNT(i.id)} 挂在 {@code LEFT JOIN} 上，**根本没有感知内容有效性**——
 * 也就是说"失效也计入"不是特意写出来的分支，而是**数对了对象**的自然结果。
 * 反向：若哪天有人"顺手"改成 {@code JOIN content}，失效条目会从计数里消失 ⇒ 与 R-07 冲突，
 * 且夹内存了 3 条却显示 2 条（用户可见的错）。故这里点明，改动前先回看 R-07。
 *
 * <h2>⚠️ 为什么两个布尔用包装类型 {@code Boolean}</h2>
 * 同 {@code FavoriteFolder} 实体：属性名 {@code isDefault} 配 {@code boolean} 会让
 * getter 变 {@code isDefault()}、setter 变 {@code setDefault()}，MyBatis 侧的属性名随之漂成
 * {@code default}（resultMap 里写的 {@code isDefault} 就落空、静默为 null）。
 * 用包装类型后属性名稳定，且 Jackson 序列化出的 JSON 键逐字是
 * {@code isDefault} / {@code isPrivate}（getter 名 {@code getIsDefault} ⇒ 属性 {@code isDefault}），
 * 不需要 {@code @JsonProperty} 兜底。
 *
 * <p>JSON 形状（{@code /favorite/folder/list} 的 {@code data}）：
 * <pre>
 * [{"id":1,"name":"默认收藏夹","isDefault":true,"isPrivate":false,"itemCount":3}]
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteFolderVO {

    private Long id;

    private String name;

    private Boolean isDefault;

    private Boolean isPrivate;

    /** 夹内**收藏记录数**（含已失效内容的记录，见类注释）。 */
    private Integer itemCount;
}
