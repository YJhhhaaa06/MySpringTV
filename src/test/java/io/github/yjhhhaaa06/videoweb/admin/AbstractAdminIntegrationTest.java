package io.github.yjhhhaaa06.videoweb.admin;

import io.github.yjhhhaaa06.videoweb.common.config.UploadProperties;
import io.github.yjhhhaaa06.videoweb.content.AbstractContentIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * S8 admin 切片的共用夹具。
 *
 * <h2>★ 为什么要继承 content 的夹具</h2>
 * admin 的全部用例都以"库里已有一条内容（及其媒体行）"为**前置条件**——
 * 而造内容/媒体的夹具（{@code insertContent} / {@code insertVideoContent} / {@code insertMedia} /
 * 两套 oracle / 清 Redis / 两套 Content-Type 的请求方法）在
 * {@link AbstractContentIntegrationTest} 里已有一套，且是 S5 起 content/comment **共用**的那套。
 * 与其复制，不如继承——与 {@code comment/CommentReadTests} 同一做法（同一切片的夹具跨域共用）。
 *
 * <h2>★ 管理员身份怎么造（不引入任何"测试后门"）</h2>
 * 老 pytest 用 {@code tools/admin.py --promote}（subprocess 改库）。本仓更直接：注册一个普通用户后
 * {@code UPDATE users SET role = 1}。这不是取巧——{@code role} 是**数据**，
 * 而"注册端不能自封管理员"这条规则由端点本身保证（注册不接收 role）。
 * 提升后**无需重新登录**：token 里没有 role，{@code JwtAuthFilter.isAdminPath} 是**请求期查库**。
 */
public abstract class AbstractAdminIntegrationTest extends AbstractContentIntegrationTest {

    /** 内容类型 / 媒体类型常量由父类提供（{@code CONTENT_TYPE_VIDEO} 等）。 */

    /** 测试媒体根（被 {@code AbstractIntegrationTest} 覆盖为 {@code target/test-media}）。 */
    @Autowired
    protected UploadProperties uploadProperties;

    // ==================== 身份 ====================

    /** 注册一个**普通**用户并返回 token。 */
    protected String registerNormal(String phone, String username) {
        return registerAndGetToken(phone, username, PASSWORD);
    }

    /**
     * 注册用户 → 提升为管理员（{@code role = 1}）→ 返回其 token。
     *
     * <p>token 无需重签：{@code isAdmin} 在请求期查库（{@code UserDao.findRoleById}）。
     */
    protected String registerAdmin(String phone, String username) {
        String token = registerAndGetToken(phone, username, PASSWORD);
        jdbcTemplate.update("UPDATE users SET role = 1 WHERE id = ?", userIdOf(phone));
        return token;
    }

    // ==================== 媒体行 oracle ====================

    /** 按 {@code (contentId, type, sort)} 取媒体行 id。 */
    protected long mediaIdOf(long contentId, int type, int sort) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM content_media WHERE content_id = ? AND type = ? AND sort = ?",
                Long.class, contentId, type, sort);
        if (id == null) {
            throw new AssertionError("媒体行应存在: contentId=" + contentId + ", type=" + type + ", sort=" + sort);
        }
        return id;
    }

    /** {@code content_media.file_exists}（独立 oracle，不问接口）。 */
    protected int mediaFileExistsColumn(long mediaId) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT file_exists FROM content_media WHERE id = ?", Integer.class, mediaId);
        return v == null ? -1 : v;
    }

    /** {@code content_media.last_verify_time} 的字符串形态（{@code null} = 未校验过）。 */
    protected String mediaLastVerifyTime(long mediaId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_verify_time FROM content_media WHERE id = ?", String.class, mediaId);
    }

    /** {@code content.file_exists} 聚合列。 */
    protected int contentFileExistsColumn(long contentId) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT file_exists FROM content WHERE id = ?", Integer.class, contentId);
        return v == null ? -1 : v;
    }

    /** 直改媒体的校验列（造"上轮扫描的印记"供回滚断言用）。 */
    protected void setMediaVerifyState(long mediaId, int fileExists, String lastVerifyTime) {
        jdbcTemplate.update("UPDATE content_media SET file_exists = ?, last_verify_time = ? WHERE id = ?",
                fileExists, lastVerifyTime, mediaId);
    }

    // ==================== 磁盘（第三套 oracle：文件系统） ====================

    /** {@code /upload/{dir}/{file}} → 测试媒体根下的绝对路径。 */
    protected Path diskPathOf(String url) {
        if (url == null || !url.startsWith("/upload/")) {
            throw new AssertionError("媒体 url 形状必须是 /upload/{dir}/{file}，实际: " + url);
        }
        Path path = Path.of(uploadProperties.root());
        for (String segment : url.substring("/upload/".length()).split("/")) {
            path = path.resolve(segment);
        }
        return path;
    }

    /** 往测试媒体根写入一个文件（造 EXISTS/MISSING 的对照）。 */
    protected void writeMediaFile(String url, String content) {
        Path path = diskPathOf(url);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 删除测试媒体根下的一个文件（若存在）。 */
    protected void deleteMediaFileQuietly(String url) {
        try {
            Files.deleteIfExists(diskPathOf(url));
        } catch (IOException ignored) {
            // 清理尽力而为
        }
    }

    // ==================== multipart（/media/restore 的请求形态） ====================

    /**
     * multipart POST。
     *
     * <p>手工拼 {@code MultiValueMap<String, Object>} 而**不用** {@code MultipartBodyBuilder}——
     * 后者引用 {@code org.reactivestreams.Publisher}，本项目无 WebFlux/Reactive 依赖
     * （与 {@code UploadFlowTests} 同一理由，见那里的类注释）。
     */
    protected ResponseEntity<String> postMultipart(String path, MultiValueMap<String, Object> form, String token) {
        return send(spec -> {
            RestClient.RequestBodySpec request = spec.uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA);
            if (token != null) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return request.body(form);
        });
    }

    /** 空表单（{@code addText}/{@code addFilePart} 的载体）。 */
    protected static MultiValueMap<String, Object> multipartForm() {
        return new LinkedMultiValueMap<>();
    }

    /** 文本 part。 */
    protected static void addText(MultiValueMap<String, Object> form, String name, String value) {
        form.add(name, value);
    }

    /** 文件 part：文件名由本方法给定（{@code ByteArrayResource} 的默认名不可用）。 */
    protected static void addFilePart(MultiValueMap<String, Object> form, String name,
                                      String fileName, byte[] bytes, String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(contentType));
        Resource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        form.add(name, new HttpEntity<>(resource, headers));
    }
}
