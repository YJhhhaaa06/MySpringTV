package io.github.yjhhhaaa06.videoweb.admin;

import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * S8 的**声明式机制生效证明**（SOP §三 DoD #7）。
 *
 * <h2>为什么单列一个类</h2>
 * DoD #7 的判据是自问「**这个断言的结果还可能由谁产生？**」。下面两条都刻意排除了替代来源
 * （打桩让事务在**已有成功写之后**失败），与 {@code AdminContentTests} / {@code MediaAuditTests} 的
 * "纯端到端断言可观察行为"纪律不同类，故分开。
 *
 * <table>
 *   <caption>机制 → 证明</caption>
 *   <tr><th>机制</th><th>证明方式</th></tr>
 *   <tr><td>{@code @Transactional}（{@code scanAll}）</td>
 *       <td>内容级聚合回写（扫描的**最后一步**）打桩抛错 ⇒ 断言**全部媒体行**已写入的校验列被回滚</td></tr>
 *   <tr><td>{@code @Transactional}（{@code restoreMedia}）</td>
 *       <td>同上，打桩点换成内容级聚合回写 ⇒ 断言**媒体行**的 {@code file_exists=1} 被回滚；
 *           同时断言文件**已写**——把"文件系统不参与事务"这个已知口径也钉住</td></tr>
 * </table>
 *
 * <h2>★ 为什么打桩点选在"最后一步"，而不是第一条写操作</h2>
 * 若让**第一条**写操作失败，那么"什么都没写"在有事务与没事务两个世界里都成立，
 * 断言**没有鉴别力**。必须让事务在**已有成功写之后**失败：
 * <ul>
 *   <li>有事务 ⇒ 回滚（断言看到的是扫描**之前**的旧值）；</li>
 *   <li>无事务（如 {@code @Transactional} 因自调用失效，SOP 坑 12）⇒ 前面的写已提交在库里
 *       （断言看到新值）⇒ 用例失败。</li>
 * </ul>
 *
 * <h2>为什么两个打桩点都是 {@code ContentDao.updateFileExists}</h2>
 * 它是两个方法里**唯一**在"媒体行写完之后"才执行的写操作：
 * {@code scanAll} 先循环写全部媒体行、再循环写内容级聚合；{@code restoreMedia} 同理。
 * 打在它上面，一次失败就同时覆盖"多条媒体写"与"最后一条内容写"的回滚。
 *
 * <p>⚠️ 不能用 {@code doCallRealMethod().doThrow(...)} 让"第 1 条真实执行、第 2 条抛错"——
 * MyBatis mapper 是 **JDK 动态代理**，其方法在 Mockito 眼里是**抽象方法**，
 * {@code callRealMethod()} 会直接报 "Cannot call abstract real method"（实测）。
 */
class AdminTransactionTests extends AbstractAdminIntegrationTest {

    private static final String ADMIN_PHONE = "13900005001";
    private static final String ADMIN = "admin-tx";
    private static final String AUTHOR_PHONE = "13900005002";
    private static final String AUTHOR = "admin-tx-author";

    /** 造"上一轮扫描的印记"用的时间戳（只作标记，断言只看 {@code file_exists}）。 */
    private static final String PREVIOUS_SCAN = "2000-01-01 00:00:00";

    @MockitoSpyBean
    private ContentDao contentDao;

    @BeforeEach
    void resetSpies() {
        Mockito.reset(contentDao);
    }

    @Test
    @DisplayName("★机制证明：扫描最后一步失败 ⇒ 事务回滚，此前已写的全部媒体校验列复原")
    void 扫描事务回滚() {
        String adminToken = registerAdmin(ADMIN_PHONE, ADMIN);
        registerNormal(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);

        long contentId = insertContent(authorId, CONTENT_TYPE_VIDEO, "事务_扫描", "d", 1);
        String url1 = "/upload/video/tx_scan_1_" + contentId + ".mp4";
        String url2 = "/upload/video/tx_scan_2_" + contentId + ".mp4";
        deleteMediaFileQuietly(url1);
        deleteMediaFileQuietly(url2);
        long media1 = insertMedia(contentId, url1, MEDIA_TYPE_VIDEO, 1);
        long media2 = insertMedia(contentId, url2, MEDIA_TYPE_IMAGE, 1);

        // 两行都标记为"存在(1)"（这是上一轮扫描的印记）。本次扫描**会**把它们改成 0（文件不在）。
        // 若事务没生效，这两条 0 会被提交 ⇒ 断言失败。
        setMediaVerifyState(media1, 1, PREVIOUS_SCAN);
        setMediaVerifyState(media2, 1, PREVIOUS_SCAN);
        jdbcTemplate.update("UPDATE content SET file_exists = 1, last_verify_time = ? WHERE id = ?",
                PREVIOUS_SCAN, contentId);

        // 内容级聚合回写抛错（发生在全部媒体回写**之后**）
        doThrow(new IllegalStateException("boom: 模拟内容级聚合回写失败"))
                .when(contentDao).updateFileExists(anyLong(), anyBoolean(), any());

        ResponseEntity<String> resp = post("/api/admin/media/scan", null, adminToken);
        assertThat(resp.getStatusCode().value()).as("非业务异常 → 500").isEqualTo(500);

        assertThat(mediaFileExistsColumn(media1))
                .as("@Transactional 必须真的生效：最后一步失败要回滚第一条媒体行已写入的校验列")
                .isEqualTo(1);
        assertThat(mediaFileExistsColumn(media2)).isEqualTo(1);
        assertThat(contentFileExistsColumn(contentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("★机制证明：恢复中途失败 ⇒ 媒体行回滚（而磁盘文件已写——「文件系统不参与事务」的口径）")
    void 恢复事务回滚_文件已写但DB未落库() {
        String adminToken = registerAdmin(ADMIN_PHONE, ADMIN);
        registerNormal(AUTHOR_PHONE, AUTHOR);
        long authorId = userIdOf(AUTHOR_PHONE);

        long contentId = insertContent(authorId, CONTENT_TYPE_VIDEO, "事务_恢复", "d", 1);
        String url = "/upload/video/tx_restore_" + contentId + ".mp4";
        long mediaId = insertMedia(contentId, url, MEDIA_TYPE_VIDEO, 1);
        deleteMediaFileQuietly(url);
        assertThat(mediaFileExistsColumn(mediaId)).as("前置：媒体行应为未校验(0)").isZero();

        // 内容级聚合的回写（restoreMedia 的**最后一步**）抛错 ⇒ 整个事务回滚。
        // 注意它发生在"媒体行回写**已经执行**"之后——这正是鉴别力的来源。
        doThrow(new IllegalStateException("boom: 模拟内容级聚合回写失败"))
                .when(contentDao).updateFileExists(anyLong(), anyBoolean(), any());

        MultiValueMap<String, Object> form = multipartForm();
        addFilePart(form, "file", "uploaded.mp4", "restored".getBytes(StandardCharsets.UTF_8), "video/mp4");

        try {
            ResponseEntity<String> resp = postMultipart(
                    "/api/admin/media/restore?mediaId=" + mediaId, form, adminToken);
            assertThat(resp.getStatusCode().value()).isEqualTo(500);

            // ① 媒体行的回写被回滚（有事务才有这个结果）
            assertThat(mediaFileExistsColumn(mediaId))
                    .as("@Transactional 必须真的生效：内容级回写失败要回滚媒体行的 file_exists=1")
                    .isZero();

            // ② ★ 已知口径：文件**已写**——文件系统与 DB 没有共同事务（TV 原样，见决策表 §四·S8）
            assertThat(diskPathOf(url))
                    .as("写文件在事务内、与 DB 提交无法原子：DB 回滚后磁盘会残留（既有口径，非缺陷）")
                    .exists();
        } finally {
            deleteMediaFileQuietly(url);
        }
    }
}
