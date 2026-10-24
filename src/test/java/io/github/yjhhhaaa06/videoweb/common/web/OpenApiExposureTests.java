package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "生产不暴露文档端点"这条安全前提的护栏（`T60-3`，覆盖率来自独立 review）。
 *
 * <p>为什么需要它：{@code OpenApiSpecExportTests} 为了生成 spec **主动打开**了
 * {@code springdoc.api-docs.enabled}（{@code @TestPropertySource}），因此那条线上的测试
 * 完全看不到"生产默认值"。而 `application.yaml` 里那句 {@code enabled: false} 一旦被谁删掉，
 * **不会有任何测试变红** —— 这正是本仓最忌讳的"静默失效"。
 *
 * <p>本类**不覆盖该属性**，所以跑的就是生产默认值（与其余集成测试共享同一个上下文，无额外启动成本）。
 *
 * <p>⚠️ 若 {@code enabled=true} 会怎样：{@code /v3/api-docs} 是 springdoc 自己的
 * {@code @RestController}，本仓的默认拒绝会把它判成"需登录" ⇒ **匿名仍是 401**，
 * 但**任何登录用户**都能读到完整 API 面。所以这不是"匿名泄露"，而是"暴露面没人管"。
 */
@DisplayName("T60 生产不暴露 /v3/api-docs")
class OpenApiExposureTests extends AbstractIntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("★ application.yaml 关了文档端点，且容器里没有那个控制器 bean")
    void 文档端点不暴露() {
        assertThat(environment.getProperty("springdoc.api-docs.enabled"))
                .as("`application.yaml` 的 springdoc.api-docs.enabled 必须是 false —— "
                        + "打开就等于给每个登录用户一个完整 API 面（口径见 接口契约.md §2.10）")
                .isEqualTo("false");

        assertThat(context.getBeanNamesForType(OpenApiWebMvcResource.class))
                .as("关掉 enabled 之后不该再有文档端点控制器；它出现了说明配置没生效")
                .isEmpty();
    }
}
