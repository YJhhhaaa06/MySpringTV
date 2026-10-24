package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI spec 的导出与对齐护栏（`T60`）。口径见 {@code architecture/tech/api/接口契约.md} §2.10。
 *
 * <h2>spec 的定位：产物，不是源</h2>
 * 代码是**唯一事实源**，本类只是把 springdoc 扫出来的结果落盘成 {@code api/openapi.yaml}。
 * 因此它的更新方式和 {@code endpoint-inventory.tsv} 一样是**显式写入 + review diff**：
 * <pre>
 * ./mvnw.cmd test -Dtest=OpenApiSpecExportTests -Dapi.openapi.write=true
 * </pre>
 * ⚠️ 跑生成命令"把红变绿"属迎合（见仓库根 {@code AGENTS.md}「测试纪律」）：红时先问
 * "是我改了端点没重生成，还是实现真的偏离了契约"。
 *
 * <h2>为什么生产不暴露 {@code /v3/api-docs}</h2>
 * 本仓的默认拒绝 = "命中的处理器必须标 {@code @Public}"（{@code RequestMappingLookup}），
 * 而文档端点是 springdoc 自己的 {@code @RestController} —— 加不上注解。给它开路径白名单
 * 会破坏"授权规则唯一事实源"这条红线，所以取另一条路：{@code springdoc.api-docs.enabled=false}
 * （生产不注册端点），spec 只在测试里由 {@link OpenApiWebMvcResource} **直接生成**。
 * 于是匿名面为零，登记簿仍是 67 条，安全契约与权重表都不用动。
 *
 * <h2>两条护栏为什么有判别力</h2>
 * <ol>
 *   <li><b>端点集对齐</b>：左边是 springdoc 扫 {@code RequestMappingHandlerMapping} 的结果，
 *       右边是本仓 {@code endpoint-inventory.tsv}（`T54` 用**自研扫描器**产出的快照）——
 *       两个独立来源比对，不是自己比自己。加/删/改端点而漏了一边 ⇒ 红。</li>
 *   <li><b>无假参数</b>：{@code @CurrentUserId} 不是请求参数（token 解析后注入）。
 *       不全局忽略的话 spec 里会多一个 {@code userId}，前端照着传 ⇒ 契约说谎。</li>
 * </ol>
 */
@DisplayName("T60 OpenAPI spec：导出 + 端点集对齐 + 无假参数")
@TestPropertySource(properties = {
        // 生产默认关（application.yaml），本测试单独打开：不打开就没有端点控制器 bean 可调。
        // 只影响"端点是否注册"，不影响 spec 内容。
        "springdoc.api-docs.enabled=true"
})
class OpenApiSpecExportTests extends AbstractIntegrationTest {

    /** 入库的契约快照（与登记簿同目录；`.gitignore` 已用 `!/src/test/resources/api/` 放行）。 */
    private static final Path SPEC = Path.of("src", "test", "resources", "api", "openapi.yaml");

    /** `T54` 的端点登记簿：spec 的对齐基线。 */
    private static final Path INVENTORY = Path.of("src", "test", "resources", "api", "endpoint-inventory.tsv");

    /** 生成模式：`-Dapi.openapi.write=true`（见类注释）。 */
    private static final boolean WRITE_MODE = Boolean.getBoolean("api.openapi.write");

    @Autowired
    private OpenApiWebMvcResource specResource;

    // ==================== 用例 1：导出 / 快照一致性 ====================

    @Test
    @DisplayName("spec 与入库快照逐字一致（-Dapi.openapi.write=true 时写盘）")
    void 与入库快照一致() {
        String generated = generate();

        if (WRITE_MODE) {
            try {
                Files.createDirectories(SPEC.getParent());
                // ⚠️ 显式 UTF-8 + LF：仓库 `* text=auto eol=lf`，写 CRLF 会让 diff 整文件变红。
                Files.writeString(SPEC, generated, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            System.out.printf("[openapi] 已生成 %d 字符 → %s%n", generated.length(), SPEC);
            return;
        }

        assertThat(SPEC)
                .as("契约快照不存在：%s（首次生成见类注释）", SPEC.toAbsolutePath())
                .exists();
        // 读侧同样归一化换行：Windows 检出可能把 LF 换成 CRLF，不归一化会假红。
        assertThat(normalize(readSpec()))
                .as("入库 spec 与当前代码生成的 spec 不一致 —— 改了端点却没重生成？"
                        + "（生成方式见类注释；确认 diff 合理后再提交）")
                .isEqualTo(generated);
    }

    // ==================== 用例 2：端点集与登记簿对齐 ====================

    @Test
    @DisplayName("★ spec 的 path+method 集合 == T54 登记簿（差异即红）")
    void 端点集与登记簿一致() {
        Set<String> fromSpec = operationKeys(parse(generate()));
        Set<String> fromInventory = inventoryKeys();

        Set<String> onlyInSpec = new TreeSet<>(fromSpec);
        onlyInSpec.removeAll(fromInventory);
        Set<String> onlyInInventory = new TreeSet<>(fromInventory);
        onlyInInventory.removeAll(fromSpec);

        assertThat(onlyInSpec)
                .as("spec 里有、登记簿里没有 —— springdoc 扫到了自研扫描器漏掉的端点"
                        + "（或扫描面配置 `springdoc.packages-to-scan` 与 BASE_PACKAGE 不一致）")
                .isEmpty();
        assertThat(onlyInInventory)
                .as("登记簿里有、spec 里没有 —— springdoc 没能表达该端点（多为映射写法超出它识别的范围）")
                .isEmpty();
        assertThat(fromSpec)
                .as("端点总数应与 T54 的 67 条一致（T58/T59 后恒定）")
                .hasSize(67);
    }

    // ==================== 用例 3：假参数 ====================

    @Test
    @DisplayName("★ spec 里不出现 @CurrentUserId 的假参数（userId 不是请求参数）")
    void 无CurrentUserId假参数() {
        OpenAPI spec = parse(generate());
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, PathItem> path : spec.getPaths().entrySet()) {
            for (Map.Entry<PathItem.HttpMethod, Operation> op : path.getValue().readOperationsMap().entrySet()) {
                List<Parameter> params = op.getValue().getParameters();
                if (params == null) {
                    continue;
                }
                for (Parameter p : params) {
                    if ("userId".equals(p.getName())) {
                        offenders.add(op.getKey() + " " + path.getKey());
                    }
                }
            }
        }
        assertThat(offenders)
                .as("这些端点的 spec 里出现了 userId 参数 —— 全局忽略 `@CurrentUserId` 失效，"
                        + "前端会照它传值（实际由 token 注入）")
                .isEmpty();
    }

    // ==================== 生成 ====================

    /**
     * 直接调 springdoc 的资源 bean 生成 YAML，**不走 HTTP**。
     *
     * <p>为什么不打端点：生产的默认拒绝会把 `/v3/api-docs` 判成需登录（第三方控制器加不了
     * {@code @Public}），而测试里绕鉴权去请求它，等于在验证一条生产上不存在的路。
     */
    private String generate() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v3/api-docs");
        request.setRequestURI("/v3/api-docs");
        try {
            // ⚠️ 第二个参数是 docs 路径（不是 group）：springdoc 用它从 requestURL 末尾**减掉**
            //    来反推 server url。传 null 会 NPE（`apiDocsUrl.length()`）—— 实测踩到。
            return normalize(new String(specResource.openapiYaml(request, "/v3/api-docs", Locale.ENGLISH),
                    StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("生成 OpenAPI spec 失败（先看 springdoc 与 Boot 的版本线）", e);
        }
    }

    private static OpenAPI parse(String yaml) {
        try {
            return Yaml.mapper().readValue(yaml, OpenAPI.class);
        } catch (Exception e) {
            throw new IllegalStateException("spec 不是合法 OpenAPI YAML", e);
        }
    }

    private static Set<String> operationKeys(OpenAPI spec) {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, PathItem> path : spec.getPaths().entrySet()) {
            for (PathItem.HttpMethod m : path.getValue().readOperationsMap().keySet()) {
                out.add(m.name() + " " + path.getKey());
            }
        }
        return out;
    }

    /** 登记簿的 (METHOD, PATH) 集合。只取前两列，鉴权/返回类型不在本类断言面。 */
    private static Set<String> inventoryKeys() {
        assertThat(Files.exists(INVENTORY))
                .as("登记簿不存在：%s", INVENTORY.toAbsolutePath())
                .isTrue();
        Set<String> out = new TreeSet<>();
        for (String raw : readSpecLines(INVENTORY)) {
            if (raw.isBlank() || raw.startsWith("#")) {
                continue;
            }
            String[] cols = raw.split("\t", -1);
            out.add(cols[0] + " " + cols[1]);
        }
        return out;
    }

    private static String readSpec() {
        return readFile(SPEC);
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> readSpecLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }
}
