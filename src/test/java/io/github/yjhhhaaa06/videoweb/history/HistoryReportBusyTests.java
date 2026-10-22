package io.github.yjhhhaaa06.videoweb.history;

import io.github.yjhhhaaa06.videoweb.common.cache.RedisCircuitBreaker;
import io.github.yjhhhaaa06.videoweb.content.AbstractContentIntegrationTest;
import io.github.yjhhhaaa06.videoweb.content.service.ContentService;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * `T51-2` 的**故障姿态**：Redis 熔断打开时，`/history/report` 返回 503 + `Retry-After`，
 * **且一次内容校验都不做**。
 *
 * <p>口径唯一事实源 = {@code history-上报缓冲.md} §5。
 *
 * <h2>★ 为什么单独一个类</h2>
 * 本类用 {@code @MockitoSpyBean} 把**整个上下文**里的 {@link RedisCircuitBreaker} 换成 spy。
 * 放在 `HistoryWriteTests` 里会连带影响同类其他用例（spy 是上下文级的，不是方法级的）。
 *
 * <h2>★ 为什么必须验证"没调 loadContentVOs"</h2>
 * "熔断先于校验"是**性能契约**（故障期不要给 DB 加压），只看 503 是测不出来的 ——
 * 把顺序写反（先校验再判熔断）仍然会返回 503，但它每次都在 Redis 挂掉时替 DB 加了一次压。
 * {@code verify(never())} 是唯一能看见这条顺序的地方。
 */
@TestPropertySource(properties = {
        "video.history.buffer.poll-interval=1h",
        "video.history.buffer.buffer-ttl=3h"
})
class HistoryReportBusyTests extends AbstractContentIntegrationTest {

    private static final String AUTHOR_PHONE = "13900000011";
    private static final String VIEWER_PHONE = "13900000012";
    private static final String VIEWER = "history-busy-viewer";

    @MockitoSpyBean
    private RedisCircuitBreaker breaker;

    @MockitoSpyBean
    private ContentService contentService;

    @Autowired
    private JdbcTemplate jdbc;

    private String viewerToken;
    private long authorId;

    @BeforeEach
    void setUpUsers() {
        registerAndGetToken(AUTHOR_PHONE, "history-busy-author");
        authorId = userIdOf(AUTHOR_PHONE);
        viewerToken = registerAndGetToken(VIEWER_PHONE, VIEWER);
        jdbc.update("DELETE FROM watch_history");
    }

    @Test
    @DisplayName("熔断打开 ⇒ 503 + Retry-After，且**不查内容**、不写库、不写缓冲")
    void 熔断打开时上报快速失败() {
        long contentId = insertVideoContent(authorId, "熔断视频");

        // 熔断器判"不可用"（tryAcquire=false 即 OPEN，真实实现里此时**根本不访问 Redis**）
        doReturn(false).when(breaker).tryAcquire();
        reset(contentService);

        ResponseEntity<String> resp = post(
                "/history/report?contentId=" + contentId + "&progress=10", null, viewerToken);

        assertThat(resp.getStatusCode().value())
                .as("★ 不降级直写库：故障瞬间把全部心跳压进 DB 正是本方案要避免的")
                .isEqualTo(503);
        assertThat(Envelope.code(resp)).isEqualTo("SYSTEM_UNAVAILABLE");
        assertThat(resp.getHeaders().getFirst("Retry-After"))
                .as("★ 必须告诉客户端多久再来，否则它只能自己猜（恢复后可能几分钟才回来）")
                .isNotNull();
        assertThat(Long.parseLong(resp.getHeaders().getFirst("Retry-After"))).isPositive();

        verify(contentService, never()).loadContentVOs(any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM watch_history", Long.class)).isZero();
    }
}
