package io.github.yjhhhaaa06.videoweb.content.controller;

import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import io.github.yjhhhaaa06.videoweb.upload.service.FileUploadService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内容管理接口（作者本人操作，S5）。
 *
 * <p>迁移自 TV {@code com.itheima.content.controller.ContentController}——对照片段：
 * <table>
 *   <caption>TV → 新实现</caption>
 *   <tr><th>TV</th><th>新实现</th></tr>
 *   <tr><td>{@code @WebServlet("/content/*")} + {@code switch(pathInfo)}（四个 action）</td>
 *       <td>{@code @RestController} + 四个 {@code @PostMapping}（action 名逐字保留）</td></tr>
 *   <tr><td>{@code AuthFilter.PROTECTED_EXACT} 里四条**精确路径**</td>
 *       <td>**方法级** {@code @RequiresLogin}（不是类级——TV 这里就是精确路径语义，见下）</td></tr>
 *   <tr><td>{@code parseContentId(req, resp)} 手工空判 + parseLong + 写错误响应</td>
 *       <td>{@code @RequestParam long contentId}（缺参 / 非数字由全局出口 → 400）</td></tr>
 *   <tr><td>{@code parseEnabled(value)}（{@code 1/true/0/false}，其它 null → 写错误）</td>
 *       <td>同逻辑，但改为**抛 {@code ParamException}** 交给统一出口（文案逐字保留）</td></tr>
 * </table>
 *
 * <h2>鉴权：为什么是**方法级**而不是类级（与 S3/S4 的做法刻意不同）</h2>
 * TV 的 {@code AuthFilter} 有两份清单：{@code PROTECTED_PREFIXES}（前缀，如 {@code /follow}、
 * {@code /like}）与 {@code PROTECTED_EXACT}（精确，如 {@code /content/update}）。
 * <b>{@code /content/**} 下**只有这四个端点**在 {@code PROTECTED_EXACT} 里</b>——
 * 也就是说 `content` 域**没有前缀级保护**。
 *
 * <p>S3（{@code /like}）、S4（{@code /follow}）用类级注解是因为它们在**前缀**清单里
 * （整个前缀都要保护，类级声明最贴合语义、且新增端点不会漏）。
 * 本域恰好相反：TV 的保护是**逐端点**的 ⇒ 方法级注解才是与旧语义一一对应的声明。
 * 若这里图省事用类级，将来往 {@code /content/**} 加一个公开端点就会**被静默保护**（契约漂移）。
 *
 * <h2>本切片交付的端点</h2>
 * <ul>
 *   <li>{@code POST /content/commentEnabled} —— 作者开关评论区（CM-3 显式划入 S5）；</li>
 *   <li>{@code POST /content/update} —— 作者编辑标题/简介；</li>
 *   <li>{@code POST /content/mediaDelete} —— 作者删除单条图片；</li>
 *   <li>{@code POST /content/delete} —— 作者删除整个作品（软删 + 级联）。</li>
 * </ul>
 *
 * <h2>一处刻意的契约差异：错误文案</h2>
 * TV 的 {@code parseContentId} 对"缺失"写 {@code "contentId不能为空"}、对"非数字"写
 * {@code "contentId格式错误"}（同为 400）。新实现交给 Spring 的参数绑定 ⇒ 走全局出口的
 * **统一文案**（{@code "参数错误"}）。**HTTP 状态码与 body 的 code 不变（400）**，
 * 只有 {@code msg} 变化——与 S1（coupon）删掉 1062 判断后的同类差异，已记入决策表与提交信息。
 */
@RestController
@RequestMapping("/content")
public class ContentController {

    private final ContentService contentService;
    private final FileUploadService fileUploadService;

    public ContentController(ContentService contentService, FileUploadService fileUploadService) {
        this.contentService = contentService;
        this.fileUploadService = fileUploadService;
    }

    /**
     * 作者开关自己作品的评论区（{@code POST /content/commentEnabled?contentId=X&enabled=0|1}）。
     *
     * <p>{@code enabled} 接受 {@code 1/true/0/false}（大小写不敏感、两端空白忽略）。
     * 缺失 → 400 {@code "enabled不能为空"}；其它值 → 400 {@code "enabled格式错误，应为 0/1 或 true/false"}。
     *
     * <p>非作者 → 403；内容不存在/已软删 → 404；未登录 → 401。
     */
    @PostMapping("/commentEnabled")
    @RequiresLogin
    public ApiResponse<String> commentEnabled(@CurrentUserId long userId,
                                              @RequestParam long contentId,
                                              @RequestParam(required = false) String enabled) {
        if (enabled == null || enabled.isEmpty()) {
            throw new ParamException("enabled不能为空");
        }
        Boolean value = parseEnabled(enabled);
        if (value == null) {
            throw new ParamException("enabled格式错误，应为 0/1 或 true/false");
        }
        contentService.setCommentEnabled(contentId, userId, value);
        return ApiResponse.success("操作成功");
    }

    /**
     * 作者编辑标题/简介（{@code POST /content/update?contentId=X&title=&description=}）。
     *
     * <p>标题空 → 400 {@code "标题不能为空"}；标题 >50 → 400；简介 >5000 → 400；
     * 非作者 → 403；内容不存在 → 404。
     */
    @PostMapping("/update")
    @RequiresLogin
    public ApiResponse<String> update(@CurrentUserId long userId,
                                      @RequestParam long contentId,
                                      @RequestParam(required = false) String title,
                                      @RequestParam(required = false) String description) {
        contentService.updateContentInfo(contentId, userId, title, description);
        return ApiResponse.success("操作成功");
    }

    /** TV {@code parseEnabled} 逐字：{@code 1/true} → TRUE，{@code 0/false} → FALSE，其它 → null。 */
    private static Boolean parseEnabled(String value) {
        return switch (value.trim().toLowerCase()) {
            case "1", "true" -> Boolean.TRUE;
            case "0", "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
     * 作者删除单条媒体（{@code POST /content/mediaDelete?contentId=&type=&sort=}）。
     *
     * <p>{@code type} 缺失/非法 → 400（TV 文案 {@code "type不能为空"} / {@code "type格式错误，应为 1/2/3"}）；
     * {@code sort} 缺失/非法 → 400；{@code type != 2} → 400 {@code "仅支持删除图片"}（在 Service 里）；
     * 非作者 → 403；媒体不存在 → 404 {@code "媒体资源不存在"}。
     *
     * <p>★ <b>S7 补回（A7 兑现）</b>：Service 把被删媒体的 url 返回出来，此处**提交后**调
     * {@code deleteFileByUrl} 清理物理文件（尽力而为：非法 URL / 文件不存在静默忽略）。
     * S5 交付时因 upload 域未迁移而裁剪，补回位置即此（见《决策表》G-7 与《遗留台账》A7）。
     */
    @PostMapping("/mediaDelete")
    @RequiresLogin
    public ApiResponse<String> mediaDelete(@CurrentUserId long userId,
                                           @RequestParam long contentId,
                                           @RequestParam int type,
                                           @RequestParam int sort) {
        String oldUrl = contentService.deleteMedia(contentId, userId, type, sort);
        fileUploadService.deleteFileByUrl(oldUrl);
        return ApiResponse.success("删除成功");
    }

    /**
     * 作者删除整个作品（{@code POST /content/delete?contentId=}，软删除、不可恢复）。
     *
     * <p>级联：内容软删 + 全部评论软删 + 点赞记录物理删 + 媒体记录物理删（**同一事务**）。
     * 非作者 → 403；内容不存在/已删 → 404。
     *
     * <p>★ <b>S7 补回（A7 兑现）</b>：同 {@link #mediaDelete}——Service 返回**全部**媒体
     * url 列表，此处提交后逐个清物理文件。**磁盘文件随作品一并消失**从此成立
     * （此前为"DB 行删了、磁盘文件残留"的有意裁剪）。
     */
    @PostMapping("/delete")
    @RequiresLogin
    public ApiResponse<String> delete(@CurrentUserId long userId, @RequestParam long contentId) {
        List<String> mediaUrls = contentService.deleteContent(contentId, userId);
        for (String url : mediaUrls) {
            fileUploadService.deleteFileByUrl(url);
        }
        return ApiResponse.success("删除成功");
    }
}
