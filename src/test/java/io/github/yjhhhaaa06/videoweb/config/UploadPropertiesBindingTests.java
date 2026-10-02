package io.github.yjhhhaaa06.videoweb.config;

import io.github.yjhhhaaa06.videoweb.common.config.UploadProperties;
import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S7 新增配置 {@code video.upload.root} 的**绑定契约**测试。
 *
 * <h2>为什么必须有"指向项目内"这一条</h2>
 * 该键的**默认值指向真实媒体根**（老项目的 stone，见 application.yaml——dev 口径）。
 * 若测试覆盖（{@code AbstractIntegrationTest} 的 {@code @DynamicPropertySource}）失效，
 * 上传测试会**直接往真实媒体根写文件、并按测试节奏删文件**——是"静默写坏真实数据"级别的风险。
 * 故这里把覆盖结果钉死：测试媒体根必须等于项目内 {@code target/test-media} 的绝对路径。
 *
 * <h2>为什么还要钉"空白值 fail-fast"</h2>
 * 口径沿袭 TV {@code AppShutDownListener} 的"upload.path 未配置或为空，拒绝启动"。
 * 本实现把它从启动期检查改为**绑定期**校验（{@link UploadProperties} 的紧凑构造器）。
 */
class UploadPropertiesBindingTests extends AbstractIntegrationTest {

    @Autowired
    private UploadProperties uploadProperties;

    @Test
    @DisplayName("★ 测试媒体根必须被覆盖到项目内 target/test-media（防污染真实媒体根）")
    void 测试媒体根指向项目内() {
        assertThat(uploadProperties.root())
                .as("AbstractIntegrationTest 必须无条件覆盖 video.upload.root——"
                        + "否则上传测试会写入/删除真实媒体根里的文件")
                .isEqualTo(AbstractIntegrationTest.testMediaRoot());
        assertThat(uploadProperties.root()).contains("test-media");
    }

    @Test
    @DisplayName("空白媒体根拒绝启动（沿袭 TV 'upload.path 未配置或为空，拒绝启动' 的口径）")
    void 空白媒体根拒绝启动() {
        assertThatThrownBy(() -> new UploadProperties(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UploadProperties("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}