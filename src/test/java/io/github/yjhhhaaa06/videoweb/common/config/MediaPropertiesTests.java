package io.github.yjhhhaaa06.videoweb.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MediaProperties#stripBaseUrl} 的单元测试。
 *
 * <p>写路径（删图、清单提交）比较 URL / 落库前必须脱掉对外前缀；这条规则若只靠
 * "测试环境 base-url 恰好为空"覆盖，前缀一配就静默失效。这里把四种形态直接钉住：
 * 带前缀、已相对、前缀为空、前缀不匹配（原样返回）。
 */
class MediaPropertiesTests {

    @Test
    @DisplayName("★剥前缀：带前缀 → 相对；已相对 / 前缀空 / 不匹配 → 原样")
    void 剥前缀四种形态() {
        MediaProperties withPrefix = new MediaProperties("https://cdn.example.com");

        assertThat(withPrefix.stripBaseUrl("https://cdn.example.com/upload/image/a.jpg"))
                .isEqualTo("/upload/image/a.jpg");
        assertThat(withPrefix.stripBaseUrl("/upload/image/a.jpg"))
                .as("客户端直接回传相对 URL 时不得误伤").isEqualTo("/upload/image/a.jpg");
        assertThat(withPrefix.stripBaseUrl("https://other.example.com/upload/image/a.jpg"))
                .as("不匹配的前缀原样返回（后续类型/文件校验会拒绝它）")
                .isEqualTo("https://other.example.com/upload/image/a.jpg");
        assertThat(withPrefix.stripBaseUrl(null)).isNull();

        MediaProperties noPrefix = new MediaProperties("");
        assertThat(noPrefix.stripBaseUrl("/upload/image/a.jpg")).isEqualTo("/upload/image/a.jpg");
    }
}
