package io.github.yjhhhaaa06.videoweb.config;

import io.github.yjhhhaaa06.videoweb.common.config.FavoriteLimitProperties;
import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T16 新增配置 {@code video.favorite.*} 的**绑定契约**测试。
 *
 * <h2>为什么必须有这一条</h2>
 * 三个上限是**对外契约**（同"默认公开"那条：一上线就改不动），而它们的载体是 YAML 键
 * —— 键名 / 前缀写错时 {@code @ConfigurationProperties} 的**原生类型绑定不会报错，只会给 0**
 * （本仓已有同族坑：{@code Duration} 裸数字被静默解析成秒级，见
 * {@link JwtPropertiesBindingTests} / {@link FavoriteCachePropertiesBindingTests}）。
 * 故这里把三个数字**逐个钉死**：改数必须连本用例一起改（那正是"改数不动代码"的代价面）。
 *
 * <p>⚠️ 与 Duration 那个坑的差别：{@link FavoriteLimitProperties} 的构造器**校验"必须为正"**
 * ⇒ 绑成 0 的那一版连上下文都起不来（整个测试套件一起红，不会静默放行）。本类第二条用例
 * 把这条校验**直接**钉住，免得后人"顺手"把校验删掉、把 fail-fast 退化成静默 0。
 */
class FavoriteLimitPropertiesBindingTests extends AbstractIntegrationTest {

    @Autowired
    private FavoriteLimitProperties limits;

    @Test
    @DisplayName("三个上限必须绑定为 5000 / 500 / 20 —— 不是 0、也不是互换（数字即对外契约）")
    void 三个上限绑定正确() {
        // 期望值出自用户 2026-10-14 拍板 + 需求篇 §六 / 三期设计 §三（**不是**抄实现）：
        // B 站实测是 5 万 / 1000 / 99 且未公示 ⇒ 本仓刻意取更小的演示值。
        assertThat(limits.defaultFolderMaxItems()).as("默认夹内容上限").isEqualTo(5000);
        assertThat(limits.customFolderMaxItems()).as("自建夹内容上限").isEqualTo(500);
        assertThat(limits.customFolderMaxCount()).as("自建夹个数上限（默认夹不计入）").isEqualTo(20);
    }

    @Test
    @DisplayName("★绑定期校验：0 / 负数必须让构造失败（否则静默绑成 0 会把每次新增都判 400）")
    void 非正数必须被拒() {
        assertThatThrownBy(() -> new FavoriteLimitProperties(0, 500, 20))
                .as("default-folder-max-items = 0 必须响亮失败")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default-folder-max-items");
        assertThatThrownBy(() -> new FavoriteLimitProperties(5000, -1, 20))
                .as("自建夹上限为负同样必须失败")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("custom-folder-max-items");
        assertThatThrownBy(() -> new FavoriteLimitProperties(5000, 500, 0))
                .as("夹数上限 = 0 必须失败")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("custom-folder-max-count");
    }
}
