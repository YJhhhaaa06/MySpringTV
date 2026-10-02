package io.github.yjhhhaaa06.videoweb.content.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内容行类型（S7）。
 *
 * <p><b>本类目前只服务 upload 的发布写路径</b>：作为 {@code ContentDao.addContent} 的入参
 * + {@code useGeneratedKeys} 回填主键（角色安排与 {@code user/model/entity/User}、
 * {@code comment/model/entity/Comment} 一致）。
 *
 * <p>字段 = TV {@code insert into content (user_id, title,type, description,category_id)}
 * 的五个插入列 + 主键。其余列（{@code like_count}/{@code comment_count}/
 * {@code comment_enabled}/{@code file_exists}/{@code create_time}…）全部走表默认值，
 * **不在本类里出现**（TV 原样，`doAddContent` 也只传这五个值）。
 *
 * <p>读路径不消费本类——读走 {@code ContentCacheDTO}（缓存 DTO，含 JOIN 出的作者名等）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Content {

    /** 主键，列名 {@code id}。插入时由 {@code useGeneratedKeys} 回填。 */
    private Long id;

    private Long userId;

    /** 内容类型：1 视频 / 2 图文（对应 TV {@code content.model.command.ContentType}）。 */
    private Integer type;

    private String title;

    private String description;

    /** 分区 id（TV 口径：只接受单个数字字符 0~9，见 UploadController 的表单校验）。 */
    private Integer categoryId;
}