package io.github.yjhhhaaa06.videoweb.observability;

import io.github.yjhhhaaa06.videoweb.admin.AbstractAdminIntegrationTest;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.support.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * 审计留痕端到端（第三批 T2 / 账 B12）——翻译老项目 {@code test_audit_log.py}（6 条）的**意图**。
 *
 * <h2>覆盖的 6 个 HTTP 可触达点（与 TV 一一对应）</h2>
 * 管理端 4：{@code admin.content.hide} / {@code admin.content.unhide} /
 * {@code admin.comment.delete} / {@code admin.media.restore}；
 * 用户侧 2：{@code user.changePassword} / {@code user.changeUserName}。
 * TV 还有第 7 点 {@code user.changePhone}——它**没有 HTTP 入口**（TV 侧也只被单测调用），
 * 故不在本类覆盖范围（口径与 TV 一致，不是遗漏）。
 *
 * <h2>三条断言的由来</h2>
 * <ul>
 *   <li><b>恰好一条 + 字段完整</b>：TV 的 {@code audited()} 统一断言
 *       "成功路径各产生恰好一条，操作者非空 / 操作 / 对象可读 / 结果正确"；</li>
 *   <li><b>与 system / access 输出端零重叠**（文件级）</b>：见本类
 *       {@link #审计行只在audit输出端()}；文件级证明在 {@code LogOutputSplitTests}；</li>
 *   <li><b>无敏感值</b>：新密码 / 手机号明文不得进 audit.log（老用例验收④）。</li>
 * </ul>
 *
 * <h2>★ 审计写在哪一层（本实现与 TV 的一处有意差异）</h2>
 * TV 把用户侧审计写在 service 层（方法体末尾）；本项目写在 **controller**、服务调用**返回之后**。
 * 理由是同一个：service 方法标着 {@code @Transactional}，在方法体内写审计会在**提交之前**
 * 留下"改过"的记录，提交失败就变成假留痕（审计是合规物，假留痕比没有更坏）。
 * 控制器在事务之外 ⇒ 调用返回即已提交 ⇒ 审计行是"事实之后"。
 */
@Tag("observability")
class AuditLogTests extends AbstractAdminIntegrationTest {

    private static final Duration AWAIT = Duration.ofSeconds(3);

    /** 用于「服务事务回滚 ⇒ 审计不得留痕」的打桩点（口径同 {@code AdminTransactionTests}）。 */
    @MockitoSpyBean
    private ContentDao contentDao;

    @BeforeEach
    void resetSpies() {
        reset(contentDao);
    }

    @Test
    @DisplayName("管理端：下架与恢复各恰好一条审计，字段完整")
    void 管理端内容下架与恢复各一条审计() {
        String adminToken = registerAdmin("13800009401", "obs-audit-admin-1");
        long adminId = userIdOf("13800009401");
        long authorId = registerAuthor("13800009402", "obs-audit-author-1");
        long contentId = insertVideoContent(authorId, "obs-audit-content-1");

        try (LogCapture audit = LogCapture.audit()) {
            audit.clear();
            assertThat(post("/api/admin/content/hide?contentId=" + contentId, null, adminToken)
                    .getStatusCode().value()).isEqualTo(200);
            String hideLine = audit.awaitLine(l -> l.contains("action=admin.content.hide"), "下架审计行", AWAIT);
            assertThat(hideLine)
                    .contains("level=INFO")
                    .contains("logger=audit")
                    .contains("req=")
                    .contains("operatorId=" + adminId)
                    .contains("target=contentId:" + contentId)
                    .contains("result=success");
            assertThat(audit.countLinesContaining("action=admin.content.hide")).isEqualTo(1);

            audit.clear();
            assertThat(post("/api/admin/content/unhide?contentId=" + contentId, null, adminToken)
                    .getStatusCode().value()).isEqualTo(200);
            String unhideLine = audit.awaitLine(l -> l.contains("action=admin.content.unhide"), "恢复审计行", AWAIT);
            assertThat(unhideLine)
                    .contains("operatorId=" + adminId)
                    .contains("target=contentId:" + contentId);
            assertThat(audit.countLinesContaining("action=admin.content.unhide")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("管理端：删评论恰好一条审计，对象是评论 id")
    void 管理端删评论一条审计() {
        String adminToken = registerAdmin("13800009403", "obs-audit-admin-2");
        long adminId = userIdOf("13800009403");
        long authorId = registerAuthor("13800009404", "obs-audit-author-2");
        long contentId = insertVideoContent(authorId, "obs-audit-content-2");
        long commentId = insertMainComment(contentId, authorId, "审计用例的评论");
        // ⚠️ 夹具必须让 content.comment_count 与评论行数自洽（否则删评论时计数从 0 再 -1，
        // 撞上 int unsigned 下溢 ⇒ Data truncation ⇒ 500 而不是走完删除）。
        // 教训与 CommentCounterTests 里那句注释同一条（《测试约定·夹具冗余计数必须自洽》）。
        jdbcTemplate.update("UPDATE content SET comment_count = 1 WHERE id = ?", contentId);

        try (LogCapture audit = LogCapture.audit()) {
            audit.clear();
            assertThat(post("/api/admin/comment/delete?commentId=" + commentId, null, adminToken)
                    .getStatusCode().value()).isEqualTo(200);
            String line = audit.awaitLine(l -> l.contains("action=admin.comment.delete"), "删评论审计行", AWAIT);
            assertThat(line)
                    .contains("operatorId=" + adminId)
                    .contains("target=commentId:" + commentId)
                    .contains("result=success");
            assertThat(audit.countLinesContaining("action=admin.comment.delete")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("管理端：恢复媒体恰好一条审计，对象是媒体 id")
    void 管理端恢复媒体一条审计() {
        String adminToken = registerAdmin("13800009405", "obs-audit-admin-3");
        long adminId = userIdOf("13800009405");
        long authorId = registerAuthor("13800009406", "obs-audit-author-3");
        long contentId = insertVideoContent(authorId, "obs-audit-content-3");
        String url = "/upload/video/obs_audit_restore_" + contentId + ".mp4";
        long mediaId = insertMedia(contentId, url, MEDIA_TYPE_VIDEO, 1);

        MultiValueMap<String, Object> form = multipartForm();
        addFilePart(form, "file", "uploaded.mp4",
                "restored-bytes".getBytes(StandardCharsets.UTF_8), "video/mp4");

        try (LogCapture audit = LogCapture.audit()) {
            audit.clear();
            assertThat(postMultipart("/api/admin/media/restore?mediaId=" + mediaId, form, adminToken)
                    .getStatusCode().value()).isEqualTo(200);
            String line = audit.awaitLine(l -> l.contains("action=admin.media.restore"), "恢复媒体审计行", AWAIT);
            assertThat(line)
                    .contains("operatorId=" + adminId)
                    .contains("target=mediaId:" + mediaId)
                    .contains("result=success");
            assertThat(audit.countLinesContaining("action=admin.media.restore")).isEqualTo(1);
        } finally {
            deleteMediaFileQuietly(url);
        }
    }

    @Test
    @DisplayName("★ 用户侧：改密码与改名各一条审计，且新密码/手机号不落盘")
    void 用户侧改密码与改名各一条审计() {
        String phone = "13800009407";
        String password = PASSWORD;
        String token = registerNormal(phone, "obs-audit-user");
        long userId = userIdOf(phone);
        String newPassword = "zz998877";

        try (LogCapture audit = LogCapture.audit()) {
            audit.clear();
            assertThat(post("/user/changePassword",
                    Map.of("phone", phone, "oldPassword", password, "newPassword", newPassword), token)
                    .getStatusCode().value()).isEqualTo(200);
            String passwordLine = audit.awaitLine(
                    l -> l.contains("action=user.changePassword"), "改密码审计行", AWAIT);
            assertThat(passwordLine)
                    .contains("operatorId=" + userId)
                    .contains("target=userId:" + userId)
                    .contains("result=success");
            assertThat(audit.countLinesContaining("action=user.changePassword")).isEqualTo(1);

            audit.clear();
            assertThat(post("/user/changeUserName?userName=obs_audit_renamed", null, token)
                    .getStatusCode().value()).isEqualTo(200);
            String renameLine = audit.awaitLine(
                    l -> l.contains("action=user.changeUserName"), "改名审计行", AWAIT);
            assertThat(renameLine)
                    .contains("operatorId=" + userId)
                    .contains("target=userId:" + userId);
            assertThat(audit.countLinesContaining("action=user.changeUserName")).isEqualTo(1);

            // 敏感值：新密码 / 手机号明文一律不落审计
            assertThat(audit.lines())
                    .noneMatch(l -> l.contains(newPassword))
                    .noneMatch(l -> l.contains(phone));
        }
    }

    @Test
    @DisplayName("★ 审计行只在 audit 输出端（与 system / access 零重叠）")
    void 审计行只在audit输出端() {
        String adminToken = registerAdmin("13800009408", "obs-audit-admin-4");
        long authorId = registerAuthor("13800009409", "obs-audit-author-4");
        long contentId = insertVideoContent(authorId, "obs-audit-content-4");

        try (LogCapture audit = LogCapture.audit();
             LogCapture system = LogCapture.root()) {
            audit.clear();
            system.clear();

            post("/api/admin/content/hide?contentId=" + contentId, null, adminToken);
            audit.awaitLine(l -> l.contains("action=admin.content.hide"), "下架审计行", AWAIT);

            // 两个 sink 的自证：下架本身**只**产生审计行（应用日志里它一条 INFO/WARN 都没有），
            // 故不能拿它证明 root sink 在工作——用一条探针日志直接证明。
            LoggerFactory.getLogger("observability.probe").info("probe for audit split");
            assertThat(system.awaitLine(l -> l.contains("probe for audit split"), "探针行", AWAIT))
                    .as("root sink 必须真的在工作，否则下面的零命中是空过")
                    .isNotNull();
            assertThat(system.linesContaining("msg=action="))
                    .as("审计行不得泄进 system 输出端（additivity=false 是唯一机制）")
                    .isEmpty();
            assertThat(structuredLines(LogCapture.accessFile()).stream()
                    .filter(l -> l.contains("msg=action=")).toList())
                    .as("审计行不得泄进 access.log")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("★ 文件级：审计行落 audit.log，且不进 system.log")
    void 审计行落audit文件() {
        String adminToken = registerAdmin("13800009410", "obs-audit-admin-5");
        long authorId = registerAuthor("13800009411", "obs-audit-author-5");
        long contentId = insertVideoContent(authorId, "obs-audit-content-5");

        try (LogCapture audit = LogCapture.audit()) {
            audit.clear();
            assertThat(post("/api/admin/content/hide?contentId=" + contentId, null, adminToken)
                    .getStatusCode().value()).isEqualTo(200);
            audit.awaitLine(l -> l.contains("action=admin.content.hide"), "下架审计行", AWAIT);

            List<String> auditFile = structuredLines(LogCapture.auditFile());
            assertThat(auditFile).as("audit.log 必须有内容").isNotEmpty();
            assertThat(auditFile)
                    .as("audit.log 只应有审计行")
                    .allMatch(line -> line.contains(" logger=audit "));
            assertThat(auditFile.stream()
                    .filter(line -> line.contains("target=contentId:" + contentId)).toList())
                    .as("本次下架的审计行应真的落进了文件")
                    .isNotEmpty();

            List<String> systemFile = structuredLines(LogCapture.systemFile());
            assertThat(systemFile.stream()
                    .filter(line -> line.contains("msg=action=admin.content.hide")).toList())
                    .as("审计行进 system.log 就等于分流未生效（additivity=false 是唯一机制）")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("★机制证明：服务事务回滚 ⇒ 审计行一条都不产生（审计只在提交后写）")
    void 服务回滚则不产生审计行() {
        String adminToken = registerAdmin("13800009412", "obs-audit-admin-6");
        long authorId = registerAuthor("13800009413", "obs-audit-author-6");
        long contentId = insertContent(authorId, CONTENT_TYPE_VIDEO, "obs-audit-rollback", "d", 1);
        String url = "/upload/video/obs_audit_rollback_" + contentId + ".mp4";
        long mediaId = insertMedia(contentId, url, MEDIA_TYPE_VIDEO, 1);
        deleteMediaFileQuietly(url);
        assertThat(mediaFileExistsColumn(mediaId)).as("前置：媒体行应为未校验(0)").isZero();

        // 打桩点是 restoreMedia 的**最后一步**（内容级聚合回写），它发生在"媒体行回写**已经执行**"之后
        // ——鉴别力就在这（口径同 AdminTransactionTests：让事务在已有成功写之后失败）。
        // 若有人把 AuditLog.success 挪进 service 方法体（提交前留痕），这里会立刻变红。
        doThrow(new IllegalStateException("boom: 模拟内容级聚合回写失败"))
                .when(contentDao).updateFileExists(anyLong(), anyBoolean(), any());

        MultiValueMap<String, Object> form = multipartForm();
        addFilePart(form, "file", "uploaded.mp4", "restored".getBytes(StandardCharsets.UTF_8), "video/mp4");

        try (LogCapture audit = LogCapture.audit()) {
            audit.clear();
            assertThat(postMultipart("/api/admin/media/restore?mediaId=" + mediaId, form, adminToken)
                    .getStatusCode().value()).isEqualTo(500);

            // 自证"事务真的回滚了"，否则下面的零命中可能是空过
            assertThat(mediaFileExistsColumn(mediaId))
                    .as("前置写必须被回滚（有事务才有这个结果）")
                    .isZero();
            assertThat(audit.countLinesContaining("action=admin.media.restore"))
                    .as("服务失败 ⇒ 控制器到不了审计那一行 ⇒ 不得有审计留痕")
                    .isZero();
        } finally {
            deleteMediaFileQuietly(url);
        }
    }

    private long registerAuthor(String phone, String username) {
        registerNormal(phone, username);
        return userIdOf(phone);
    }

    /** 文件里的结构化记录行（以 {@code ts=} 开头；异常堆栈续行不算记录）。 */
    private static List<String> structuredLines(java.nio.file.Path file) {
        try {
            return java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> line.startsWith("ts="))
                    .toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
