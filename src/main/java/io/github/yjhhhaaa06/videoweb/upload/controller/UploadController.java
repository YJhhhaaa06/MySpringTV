package io.github.yjhhhaaa06.videoweb.upload.controller;

import io.github.yjhhhaaa06.videoweb.common.exception.BusinessException;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequiresLogin;
import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import io.github.yjhhhaaa06.videoweb.upload.model.UploadResult;
import io.github.yjhhhaaa06.videoweb.upload.model.UploadType;
import io.github.yjhhhaaa06.videoweb.upload.service.FileUploadService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 上传接口（S7）：发布视频 / 发布图文 / 作者换源。迁移自 TV
 * {@code com.itheima.upload.controller.UploadController}（469 行）。
 *
 * <h2>TV → 新实现</h2>
 * <table>
 *   <caption>对照片段</caption>
 *   <tr><th>TV</th><th>新实现</th></tr>
 *   <tr><td>{@code @WebServlet("/api/upload/*") + switch(pathInfo)}（三个 action）</td>
 *       <td>{@code @RestController + @RequestMapping("/api/upload")} + 三个 {@code @PostMapping}</td></tr>
 *   <tr><td>{@code @MultipartConfig(50MB / 100MB)}</td>
 *       <td>{@code spring.servlet.multipart.max-file-size / max-request-size}（同一组值，见 application.yaml）</td></tr>
 *   <tr><td>{@code req.getPart("video")} / {@code req.getParameter(...)}</td>
 *       <td>{@code @RequestPart MultipartFile} / {@code @RequestParam}</td></tr>
 *   <tr><td>{@code CommandConverter.uploadVideoToCommand(...)} 手工校验</td>
 *       <td>本类私有校验方法（**文案逐字保留**）；双层 DTO/Command 已删（SOP §一步骤 6）</td></tr>
 *   <tr><td>{@code AuthFilter.PROTECTED_PREFIXES} 含 {@code /api/upload}</td>
 *       <td>**类级** {@link RequiresLogin}（TV 是**前缀**保护 ⇒ 类级最贴合语义）</td></tr>
 * </table>
 *
 * <h2>★ 鉴权：为什么是**类级**（与 {@code /content} 那个控制器刻意相反）</h2>
 * TV 的 {@code AuthFilter.PROTECTED_PREFIXES} 含 {@code "/api/upload"}（**前缀**项），
 * 即该前缀下**所有**端点都需登录。故类级 {@code @RequiresLogin} 才是与旧语义一一对应的声明；
 * 将来往 {@code /api/upload/**} 加端点会被自动保护——与 TV 口径一致。
 * （对照 {@code ContentController}：那边的 {@code /content/**} 无前缀保护，故用方法级。）
 *
 * <h2>★ 文件落盘与 DB 提交无法原子：失败补偿（有意保持旧口径）</h2>
 * 文件先落盘、再写 DB，两个系统没有共同事务。TV 的处置是：任一环节失败，
 * 把**本次已落盘**的文件删掉再抛（{@code deleteFileQuietly}），避免孤儿文件。
 * 新实现**保持同一口径**（《事务边界决策表》§四·S7 配套 3），由本类的
 * {@code catch} 块完成补偿。已知残留：进程在"文件已写、DB 未提交"之间崩溃时仍会留孤儿
 * （TV 同样如此；回收工具属媒体审计面 S8）。
 */
@RestController
@RequestMapping("/api/upload")
@RequiresLogin
public class UploadController {

    private final FileUploadService fileUploadService;
    private final ContentService contentService;

    public UploadController(FileUploadService fileUploadService, ContentService contentService) {
        this.fileUploadService = fileUploadService;
        this.contentService = contentService;
    }

    /**
     * 发布视频（{@code POST /api/upload/video}）：表单 {@code title/description/categoryId} +
     * 两个文件 part {@code video} / {@code cover}。成功返回 {@code {"contentId": N}}。
     *
     * <p>校验（在**落盘之前**，与 TV 一致——失败不留文件）：
     * 标题非空且 ≤50；简介空则置 {@code "-"}、否则 ≤1000；{@code categoryId} 必须是单个数字字符。
     * 文件类型不支持 → 400 {@code "文件类型不支持"}（{@link FileUploadService#saveFile}）。
     */
    @PostMapping("/video")
    public ApiResponse<Map<String, Object>> uploadVideo(@CurrentUserId long userId,
                                                       @RequestParam(required = false) String title,
                                                       @RequestParam(required = false) String description,
                                                       @RequestParam(required = false) String categoryId,
                                                       @RequestPart("video") MultipartFile video,
                                                       @RequestPart("cover") MultipartFile cover) {
        String t = requireTitle(title);
        String d = normalizeDescription(description, 1000, "简介不得超过1000字");
        int category = parseCategoryId(categoryId);

        UploadResult videoResult = null;
        UploadResult coverResult = null;
        try {
            videoResult = fileUploadService.saveFile(video, UploadType.VIDEO);
            coverResult = fileUploadService.saveFile(cover, UploadType.COVER);
            long contentId = contentService.addVideo(userId, t, d, category,
                    videoResult.url(), coverResult.url());
            return ApiResponse.success(Map.of("contentId", contentId));
        } catch (BusinessException e) {
            deleteQuietly(videoResult);
            deleteQuietly(coverResult);
            throw e;
        } catch (Exception e) {
            deleteQuietly(videoResult);
            deleteQuietly(coverResult);
            throw new UploadFailureException(e);
        }
    }

    /**
     * 发布图文（{@code POST /api/upload/post}）：表单 {@code title/description/categoryId} +
     * 可选 {@code cover} + 0~n 张 {@code image}。成功返回 {@code {"contentId": N}}。
     *
     * <p>校验：标题非空且 ≤50；简介空则置 {@code "-"}、否则 ≤10000（**注意与视频的 1000 不同**，
     * TV 原样）；{@code categoryId} 同视频。多图按 part 出现顺序写 {@code sort} 1..n。
     */
    @PostMapping("/post")
    public ApiResponse<Map<String, Object>> uploadPost(@CurrentUserId long userId,
                                                      @RequestParam(required = false) String title,
                                                      @RequestParam(required = false) String description,
                                                      @RequestParam(required = false) String categoryId,
                                                      @RequestPart(value = "cover", required = false) MultipartFile cover,
                                                      @RequestPart(value = "image", required = false) List<MultipartFile> images) {
        String t = requireTitle(title);
        String d = normalizeDescription(description, 10000, "内容不得超过10000字");
        int category = parseCategoryId(categoryId);

        List<String> savedPaths = new ArrayList<>();
        try {
            String coverUrl = null;
            if (cover != null && cover.getSize() > 0) {
                UploadResult coverResult = fileUploadService.saveFile(cover, UploadType.COVER);
                coverUrl = coverResult.url();
                savedPaths.add(coverResult.absolutePath());
            }

            List<String> imageUrls = new ArrayList<>();
            if (images != null) {
                for (MultipartFile image : images) {
                    if (image.getSize() > 0) {
                        UploadResult imageResult = fileUploadService.saveFile(image, UploadType.IMAGE);
                        imageUrls.add(imageResult.url());
                        savedPaths.add(imageResult.absolutePath());
                    }
                }
            }

            long contentId = contentService.addPost(userId, t, d, category, coverUrl, imageUrls);
            return ApiResponse.success(Map.of("contentId", contentId));
        } catch (BusinessException e) {
            savedPaths.forEach(fileUploadService::deleteFileQuietly);
            throw e;
        } catch (Exception e) {
            savedPaths.forEach(fileUploadService::deleteFileQuietly);
            throw new UploadFailureException(e);
        }
    }

    /**
     * 作者换源（{@code POST /api/upload/replace?contentId=&type=&sort=} + 文件 part {@code file}）。
     *
     * <p>{@code type ∈ 1(视频)/2(图片)/3(封面)}（{@link UploadType#fromMediaType}），
     * {@code type}/{@code sort} 定位 {@code content_media} 记录；所有权由 Service 校验。
     * 成功返回 {@code {"url": 新URL}}，且**旧文件被删除**（尽力而为，DB 已提交）。
     */
    @PostMapping("/replace")
    public ApiResponse<Map<String, Object>> replace(@CurrentUserId long userId,
                                                   @RequestParam long contentId,
                                                   @RequestParam int type,
                                                   @RequestParam int sort,
                                                   @RequestPart(value = "file", required = false) MultipartFile file) {
        UploadType uploadType = UploadType.fromMediaType(type);
        if (uploadType == null) {
            throw new ParamException("type 不合法，应为 1/2/3");
        }
        // 缺文件 / 空文件 → TV 文案逐字保留（旧实现显式判 null 与 size<=0，同为 400）
        if (file == null || file.getSize() <= 0) {
            throw new ParamException("请选择要替换的文件");
        }

        UploadResult result = null;
        try {
            result = fileUploadService.saveFile(file, uploadType);
            String oldUrl = contentService.replaceMedia(contentId, userId, type, sort, result.url());
            // 旧文件清理：DB 已提交、尽力而为（失败只记 WARNING，不影响响应）
            fileUploadService.deleteFileByUrl(oldUrl);
            return ApiResponse.success(Map.of("url", result.url()));
        } catch (BusinessException e) {
            deleteQuietly(result);
            throw e;
        } catch (Exception e) {
            deleteQuietly(result);
            throw new UploadFailureException(e);
        }
    }

    // ========================================================================
    // 表单校验（文案逐字保留自 TV CommandConverter.uploadVideoToCommand / uploadPostToCommand）
    // ========================================================================

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new ParamException("标题不能为空");
        }
        if (title.length() > 50) {
            throw new ParamException("标题不得超过50字");
        }
        return title;
    }

    /** 简介为空 ⇒ {@code "-"}（TV 原样占位）；超长 ⇒ 对应文案。 */
    private static String normalizeDescription(String description, int maxLength, String message) {
        if (description == null || description.isBlank()) {
            return "-";
        }
        if (description.length() > maxLength) {
            throw new ParamException(message);
        }
        return description;
    }

    /**
     * {@code categoryId} 校验：TV 只接受**单个数字字符**（0~9）的分区号。
     *
     * <p>⚠️ 一处**有意差异**（《决策留痕表》D-11）：TV 直接 {@code categoryId.length()}，
     * 缺参时 NPE 未捕获 → **500**；本实现显式判空 → **400**「暂时没有这个分区」。
     * 文案与非法值路径逐字一致。
     */
    private static int parseCategoryId(String categoryId) {
        if (categoryId == null || categoryId.length() > 1 || !categoryId.matches("^[0-9]+$")) {
            throw new ParamException("暂时没有这个分区");
        }
        return Integer.parseInt(categoryId);
    }

    private void deleteQuietly(UploadResult result) {
        if (result != null) {
            fileUploadService.deleteFileQuietly(result.absolutePath());
        }
    }

    /**
     * 非业务异常（如文件 IO 失败）的包装：交给全局出口 → 500。
     *
     * <p>TV 侧对应 {@code throw new ServletException(e)}（同样落 500）。
     * 用 {@link RuntimeException} 以便穿过 Controller 的签名（Spring MVC 不需要 checked 声明）。
     */
    static class UploadFailureException extends RuntimeException {
        UploadFailureException(Throwable cause) {
            super(cause);
        }
    }
}