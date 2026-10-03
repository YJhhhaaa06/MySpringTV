package io.github.yjhhhaaa06.videoweb.common.log;

import io.github.yjhhhaaa06.videoweb.common.config.LogProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三条"行构造"纯函数的契约（第三批 T2）：访问行 / 审计行 / 请求标识。
 *
 * <h2>为什么这些是纯函数、且必须直测</h2>
 * 行字段顺序与命名就是**契约**——老 pytest 正是靠
 * {@code method=… path=… userId=… code=…} 这样的字段序列定位与抽取的。
 * 把它们抽成 static 纯函数，测试就不必启动容器；同时"改字段顺序"这件事会在
 * 本类立刻变红（而不是等到某天有人 grep 不到才发现）。
 *
 * <p>带 {@code @Tag("observability")}：见 {@link LogMaskerTests} 类注释。
 */
@Tag("observability")
class LogLineBuildersTests {

    @Test
    @DisplayName("访问行：固定字段顺序 method → path → status → code → userId → cost → slow")
    void 访问行字段顺序() {
        String line = AccessLogFilter.buildLine("GET", "/start", 200, 200, null, 3L, 1000L);

        assertThat(line).isEqualTo("method=GET path=/start status=200 code=200 userId=- cost=3ms slow=0");
    }

    @Test
    @DisplayName("访问行：未登录记 userId=-，静态资源业务码 0，达到阈值打 slow=1")
    void 访问行边界() {
        String staticLine = AccessLogFilter.buildLine("GET", "/upload/a.png", 200, 0, null, 1L, 1000L);
        assertThat(staticLine).contains("code=0 ").contains("userId=-").contains("slow=0");

        String slowLine = AccessLogFilter.buildLine("POST", "/user/register", 200, 200, 42L, 1000L, 1000L);
        assertThat(slowLine).contains("userId=42").contains("slow=1");
    }

    @Test
    @DisplayName("审计行：action → operatorId → target → result=success")
    void 审计行字段顺序() {
        assertThat(AuditLog.buildLine("admin.content.hide", 13L, "contentId:42"))
                .isEqualTo("action=admin.content.hide operatorId=13 target=contentId:42 result=success");
    }

    @Test
    @DisplayName("审计行：operatorId 缺失记 -（与访问行的 userId=- 同口径）")
    void 审计行操作者缺失() {
        assertThat(AuditLog.buildLine("user.changePassword", null, "userId:7"))
                .isEqualTo("action=user.changePassword operatorId=- target=userId:7 result=success");
    }

    @Test
    @DisplayName("请求标识：16 位十六进制，且同毫秒内不重复")
    void 请求标识形态与唯一性() {
        String first = RequestIdFilter.newRequestId();
        String second = RequestIdFilter.newRequestId();

        assertThat(first).matches("[0-9a-f]{16}");
        assertThat(second).matches("[0-9a-f]{16}");
        assertThat(second).as("同一个进程内自增序号必须让它不同").isNotEqualTo(first);
    }

    @Test
    @DisplayName("慢阈值配成非法值（<=0）时回落 1000，不让观测面抛异常")
    void 慢阈值非法值回落() {
        assertThat(new LogProperties(0).slowRequestMs()).isEqualTo(LogProperties.DEFAULT_SLOW_REQUEST_MS);
        assertThat(new LogProperties(-1).slowRequestMs()).isEqualTo(LogProperties.DEFAULT_SLOW_REQUEST_MS);
        assertThat(new LogProperties(50).slowRequestMs()).isEqualTo(50);
    }
}
