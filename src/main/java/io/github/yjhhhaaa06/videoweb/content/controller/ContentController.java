package io.github.yjhhhaaa06.videoweb.content.controller;

import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.content.model.dto.CommentEnabledRequest;
import io.github.yjhhhaaa06.videoweb.content.model.dto.UpdateContentRequest;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import io.github.yjhhhaaa06.videoweb.upload.service.FileUploadService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * <p>★ `T57` 起这类错误不再是"只有一句话"：出口统一给 {@code errors:[{field,code,message}]}
 * （{@code field=contentId}，文案如 {@code 缺少必填参数} / {@code 参数类型不正确}），
 * {@code msg} 变成该条的摘要。差异仍然存在（TV 的原话没有保留），但**机器已经能定位到参数**了。
 */
@RestController
public class ContentController {

    private final ContentService contentService;
    private final FileUploadService fileUploadService;

    public ContentController(ContentService contentService, FileUploadService fileUploadService) {
        this.contentService = contentService;
        this.fileUploadService = fileUploadService;
    }

    /**
     * 作者开关自己作品的评论区（{@code PUT /api/v1/contents/{id}/comment-enabled}）。
     *
     * <p>`T59` 载荷面：入参改为 JSON body {@code {"enabled": true|false}}（布尔真类型，
     * 依据《接口契约.md》§2.5）；旧的 {@code 1/true/0/false} 字符串形态退役。
     * 缺 {@code enabled} → 400「enabled不能为空」；非布尔 → 400「参数取值不合法」。
     *
     * <p>非作者 → 403；内容不存在/已软删 → 404；未登录 → 401。
     */
    @PutMapping("/api/v1/contents/{id}/comment-enabled")
    @RequiresLogin
    public ApiResponse<Void> commentEnabled(@CurrentUserId long userId,
                                            @PathVariable("id") long contentId,
                                            @RequestBody @Valid CommentEnabledRequest request) {
        contentService.setCommentEnabled(contentId, userId, request.enabled());
        return ApiResponse.success();
    }

    /**
     * 作者编辑作品（{@code PUT /api/v1/contents/{id}}）：元数据 + 可选的整份媒体清单。
     *
     * <h2>★ {@code media} 的出现即整份覆盖</h2>
     * <ul>
     *   <li>{@code media} 缺省 / {@code null} ⇒ 只改文案，媒体行原样保留（兼容旧调用方）；</li>
     *   <li>{@code media} 出现 ⇒ 按清单重写媒体：缺席的旧图片/封面删除、新增的插入、
     *       图片 {@code sort} 按数组顺序 1..n。清单外的旧文件在**事务提交后**由本方法删除
     *       （{@code deleteFileByUrl} 尽力而为，失败只记 WARNING）。</li>
     * </ul>
     * 编辑中的媒体对观众不可见的实现前提是：新资源先走 upload-only 端点，只有本次提交
     * 才会被绑定；提交前它们不参与任何内容装配。
     *
     * <h2>校验与 GAP-31</h2>
     * 标题空/超 50、分区越界 → 400；简介按内容类型取上限（视频 1000 / 图文 10000）；
     * 非作者 → 403；内容不存在 → 404；未登录 → 401。
     */
    @PutMapping("/api/v1/contents/{id}")
    @RequiresLogin
    public ApiResponse<Void> update(@CurrentUserId long userId,
                                    @PathVariable("id") long contentId,
                                    @RequestBody UpdateContentRequest request) {
        List<String> removedUrls = contentService.updateContent(contentId, userId,
                request.title(), request.description(), request.categoryId(), request.media());
        for (String url : removedUrls) {
            fileUploadService.deleteFileByUrl(url);
        }
        return ApiResponse.success();
    }

    /**
     * 作者删除单条媒体（{@code DELETE /api/v1/contents/{id}/media?type=2&url=…}）。
     *
     * <p>{@code type} 缺失/非法 → 400（TV 文案 {@code "type不能为空"} / {@code "type格式错误，应为 1/2/3"}）；
     * {@code url} 缺失 → 400（与 {@code type} 缺参同一条出口，见 `T57` 校验失败体）；
     * {@code type != 2} → 400 {@code "仅支持删除图片"}（在 Service 里）；
     * 非作者 → 403；媒体不存在 → 404 {@code "媒体资源不存在"}。
     *
     * <p>★ <b>`T63`：选择参数 {@code sort} → {@code url}</b>。{@code sort} 是**可变位置**
     * （删完一张就被 {@code compactImageSort} 前移），重试"删第 N 张"会删掉**原来的第 N+1 张**
     * 且仍回 204。{@code url} 是上传后不再复用的稳定标识（文件名 UUID，
     * 换源会换新 url）⇒ 重试只可能 404，符合 `接口契约.md` §2.4①"已删视为不存在"。
     * 依据：《接口契约.md》§2.4① 与 §2.5（DELETE 的选择参数走 query）。
     *
     * <p>⚠️ {@code url} **刻意不加 {@code @NotBlank}**：缺参与 {@code type} 缺参必须走同一条
     * 400 出口，加约束会新增一条校验文案（= 新契约面）。
     *
     * <p>★ <b>S7 补回（A7 兑现）</b>：Service 把被删媒体的 url（**DB 行原值**）返回出来，此处**提交后**调
     * {@code deleteFileByUrl} 清理物理文件（尽力而为：非法 URL / 文件不存在静默忽略）。
     * 带 {@code video.media.base-url} 前缀的入参在 Service 里已被剥离，物理删除不会拿到前缀值。
     */
    @DeleteMapping("/api/v1/contents/{id}/media")
    @RequiresLogin
    public ResponseEntity<Void> mediaDelete(@CurrentUserId long userId,
                                            @PathVariable("id") long contentId,
                                            @RequestParam int type,
                                            @RequestParam String url) {
        String oldUrl = contentService.deleteMedia(contentId, userId, type, url);
        fileUploadService.deleteFileByUrl(oldUrl);
        return ResponseEntity.noContent().build();
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
    @DeleteMapping("/api/v1/contents/{id}")
    @RequiresLogin
    public ResponseEntity<Void> delete(@CurrentUserId long userId, @PathVariable("id") long contentId) {
        List<String> mediaUrls = contentService.deleteContent(contentId, userId);
        for (String url : mediaUrls) {
            fileUploadService.deleteFileByUrl(url);
        }
        return ResponseEntity.noContent().build();
    }
}
