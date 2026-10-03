package io.github.yjhhhaaa06.videoweb.upload.service;

import io.github.yjhhhaaa06.videoweb.common.config.UploadProperties;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.upload.model.UploadResult;
import io.github.yjhhhaaa06.videoweb.upload.model.UploadType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件落盘服务（承接 TV {@code com.itheima.upload.service.FileUploadService}，101 行）。
 *
 * <p>纯 {@code MultipartFile → 磁盘} 搬运：**无事务、无 DB**。旧实现的全部依赖是
 * {@code AppConfig.getUploadPath()}（本实现换成 {@link UploadProperties}）与日志。
 *
 * <h2>★ 保真度表态：文件落盘与 DB 提交**无法原子**（有意保持旧口径）</h2>
 * 本服务只负责把文件写进磁盘，"写文件 + 写 DB 行"跨了两个系统，没有事务能覆盖。
 * TV 的处置是：**失败时由调用方（Controller）用 {@link #deleteFileQuietly} 补偿**。
 * 本实现**保持同一口径**，不在新基建上"顺手改进"（《事务边界决策表》§四·S7 配套 3）。
 * 后果（已知并接受）：进程在"文件已写、DB 未提交"之间崩溃时，会留下孤儿文件；
 * 这类残留的回收工具属 media 审计面（S8 的 {@code MediaAuditService}）。
 *
 * <h2>相对 TV 的三处等价改写（非行为改动）</h2>
 * <ol>
 *   <li>{@code jakarta.servlet.http.Part} → {@code MultipartFile}。旧 {@code part.write(绝对路径)}
 *       → {@link MultipartFile#transferTo(File)}；两者对"绝对路径目标"语义一致。</li>
 *   <li>{@code java.util.logging.Logger} → SLF4J（全项目统一，SOP 坑 7 的日志底座）。</li>
 *   <li>URL 前缀 {@code "/upload/"} 与目录分隔符的拼接方式逐字保留
 *       （URL 用 {@code /}，磁盘用 {@code File} 拼接；{@code absolutePath} 统一反斜杠转正斜杠）。</li>
 * </ol>
 */
@Slf4j
@Service
public class FileUploadService {

    /** 上传 URL 形状：{@code /upload/{video|image|cover}/{文件名}}（与 {@link UploadType#getDir()} 三选一对应）。 */
    private static final Pattern UPLOAD_URL_PATTERN =
            Pattern.compile("^/upload/(video|image|cover)/([A-Za-z0-9._-]+)$");

    private final UploadProperties props;

    public FileUploadService(UploadProperties props) {
        this.props = props;
    }

    /**
     * 保存一个上传文件，返回其 URL 与磁盘绝对路径。
     *
     * <p>顺序逐字保留：① 校验（contentType 非空 + 扩展名合法）→ ② 生成
     * {@code UUID + 小写扩展名} 文件名 → ③ 建目录（{@code mkdirs}）→ ④ 落盘。
     * 校验失败抛 {@link ParamException}{@code ("文件类型不支持")} → 400（**在落盘之前**，
     * 故失败路径不留文件）。
     */
    public UploadResult saveFile(MultipartFile file, UploadType type) throws IOException {
        // 1. 校验
        validate(file, type);

        // 2. 生成文件名
        String fileName = UUID.randomUUID() + getSuffix(file);

        // 3. 目录（basePath + type 子目录）
        File dir = new File(props.root(), type.getDir());
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("上传目录创建失败: " + dir.getAbsolutePath());
        }

        // 4. 写入
        File target = new File(dir, fileName);
        file.transferTo(target);

        return new UploadResult(
                "/upload/" + type.getDir() + "/" + fileName,
                target.getAbsolutePath().replace('\\', '/'));
    }

    /**
     * 尽力而为地删除一个**绝对路径**文件：不存在 / 路径为空 / 删除失败一律静默（只记 WARNING）。
     * 用于失败补偿。
     */
    public void deleteFileQuietly(String absolutePath) {
        if (absolutePath == null) {
            return;
        }
        try {
            File file = new File(absolutePath);
            if (file.exists() && !file.delete()) {
                log.warn("文件删除失败（返回 false）, path={}", absolutePath);
            }
        } catch (Exception e) {
            log.warn("文件删除失败, path={}", absolutePath, e);
        }
    }

    /**
     * 按上传 URL（{@code /upload/{video|image|cover}/{文件名}}）删除物理文件，尽力而为。
     * URL 非法或文件不存在时静默忽略（作者换源 / 删图清理旧文件用）。
     *
     * <h2>★ 这是《遗留台账》A7 的补回实现</h2>
     * S5 交付 {@code deleteMedia} / {@code deleteContent} 时因 upload 域未迁移而裁掉了这段清理，
     * 并注明"补回位置 = upload 批次接入 {@code FileUploadService} 后，在 Controller 的提交后段"。
     * 本切片兑现：{@code ContentController.mediaDelete} / {@code delete} 提交后调用本方法。
     *
     * <p>⚠️ 安全性：{@link #resolveAbsolutePath} 里 {@code fileName.contains("..")} 的守卫逐字保留，
     * 防止 DB 里被写入 {@code ../} 形式的 url 时逃逸出媒体根。
     */
    public void deleteFileByUrl(String url) {
        if (url == null) {
            return;
        }
        String path = resolveAbsolutePath(url);
        if (path == null) {
            return;
        }
        deleteFileQuietly(path);
    }

    /**
     * 上传 URL（{@code /upload/{video|image|cover}/{文件名}}）→ **磁盘绝对路径**；
     * URL 非法（形状不匹配 / 含 {@code ..} 逃逸）时返回 {@code null}。
     *
     * <h2>★ 为什么是 {@code public}（S8 起）</h2>
     * 媒体审计（{@code admin.service.MediaAuditService}）的扫描与恢复都要做同一件事：
     * 把 DB 里的 url 反解成磁盘路径。TV 在两处**各写了一份同款正则**
     * （{@code FileUploadService} + {@code MediaAuditService.MEDIA_URL_PATTERN}）。
     * 而 {@code /upload/{dir}/{file}} 的形状是**上传域的所有物**——{@code saveFile} 造它、
     * 本方法解它、{@code WebMvcConfig} 挂它——两份正则必然漂移，且 {@code ".."} 逃逸守卫
     * 只该有一处。故 S8 把它提为 public 供跨域复用（《决策留痕表》B-19）。
     *
     * <p>它是**纯函数**（只读配置、不触盘），复用无副作用。
     *
     * @param url 应用内相对 URL（如 {@code /upload/video/x.mp4}）
     * @return 磁盘绝对路径；非法 URL ⇒ {@code null}（调用方据此判"无法定位文件"）
     */
    public String resolveAbsolutePath(String url) {
        if (url == null) {
            return null;
        }
        Matcher matcher = UPLOAD_URL_PATTERN.matcher(url);
        if (!matcher.matches()) {
            return null;
        }
        String dir = matcher.group(1);
        String fileName = matcher.group(2);
        if (fileName.contains("..")) {
            return null;
        }
        return props.root() + File.separator + dir + File.separator + fileName;
    }

    /** 校验：contentType 非空 **且** 扩展名合法，否则 {@code "文件类型不支持"}（TV 逐字）。 */
    private void validate(MultipartFile file, UploadType type) {
        String contentType = file.getContentType();
        String fileName = file.getOriginalFilename();
        if (contentType == null || !type.isSuffixValid(fileName)) {
            throw new ParamException("文件类型不支持");
        }
    }

    /** 取**最后一个点起**的小写扩展名（调用前必已通过 {@link #validate}，故必含点）。 */
    private String getSuffix(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        int dotIndex = fileName.lastIndexOf('.');
        return fileName.substring(dotIndex).toLowerCase();
    }
}