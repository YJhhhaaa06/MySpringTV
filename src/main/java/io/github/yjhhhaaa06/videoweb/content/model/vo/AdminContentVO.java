package io.github.yjhhhaaa06.videoweb.content.model.vo;

import lombok.Data;

/**
 * 管理端内容清单条目（承接 TV {@code com.itheima.admin.model.vo.AdminContentVO}）。
 *
 * <p>字段与 JSON 键集**逐字保留**：{@code {id, title, type, authorName, hidden}}——
 * 旧 pytest {@code test_hide_content.py::test_admin_list_200_shape} 对每个条目的这五个键有断言。
 *
 * <h2>★ 与 TV 的一处位置差异（有意，见《决策留痕表》C-6）</h2>
 * TV 把它放在 {@code admin/model/vo/}，但其**生产方**是 {@code ContentDao.findContentForAdmin}
 * （内容域）。照 TV 放会形成 {@code content → admin.model} 与 {@code admin.controller →
 * content.service} 的 **content⇄admin 环**，与 S6 的模块边界纪律冲突。
 * 故模型**跟着它的生产方走**：落在 {@code content/model/vo}。可观察行为不变。
 *
 * <h2>{@code hidden} 的口径</h2>
 * {@code true} = 该内容处于**管理员下架**态（{@code content.is_deleted == 2}）。
 * TV 在 Java 侧算 {@code rs.getInt("is_deleted") == 2}；本实现由 MyBatis 把 {@code is_deleted}
 * 直接映射到 {@code boolean}——查询带 {@code WHERE c.is_deleted IN (0, 2)}，取值只有 0/2，
 * 而 JDBC 的 {@code getBoolean} 对数值列取 {@code != 0} ⇒ 语义**逐字等价**。
 */
@Data
public class AdminContentVO {

    /** {@code content.id}。 */
    private long id;

    private String title;

    /** 1 视频 / 2 图文。 */
    private int type;

    /** 作者用户名（JOIN users 得到）。 */
    private String authorName;

    /** 是否处于管理员下架态（{@code is_deleted == 2}）。 */
    private boolean hidden;
}
