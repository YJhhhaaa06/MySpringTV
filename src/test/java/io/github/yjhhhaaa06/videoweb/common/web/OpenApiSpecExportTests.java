package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
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
 * OpenAPI spec 的导出与形状护栏（`T60`）。口径见 {@code architecture/tech/api/接口契约-护栏与Spec.md} §二。
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
 * <h2>七条护栏各自钉住什么</h2>
 * <ol>
 *   <li><b>快照一致</b>：入库的 spec 就是当前代码生成的（没人手改、也没人忘了重生成）。</li>
 *   <li><b>端点集对齐</b>：左边 springdoc 扫 {@code RequestMappingHandlerMapping}，
 *       右边 {@code endpoint-inventory.tsv}（`T54` 用**自研扫描器**产出）—— 两个独立来源。</li>
 *   <li><b>无假参数</b>：{@code @CurrentUserId} 不是请求参数（token 解析后注入）。</li>
 *   <li><b>响应类别</b>：401 与**登记簿的鉴权档位**对应；{@code ResponseEntity<Void>} 必须 204 且不留 200。</li>
 *   <li><b>错误信封</b>：失败体只有 {@code code/msg/errors}，九个错误响应组件指向同一个 {@code ApiError}；
 *       码字典 = 41 项（形状守恒，见下方"判别力边界"）。</li>
 *   <li><b>multipart + 必填</b>：四个上传端点是 {@code multipart/form-data}，且必填字段没被写成可选。</li>
 *   <li><b>503 与 servers</b>：503 只挂 {@code report} 路径（{@code flush} 必须没有）；{@code servers} 不钉 localhost。</li>
 * </ol>
 *
 * <h2>★ 判别力边界（诚实声明，别把它当"独立 oracle"）</h2>
 * <ul>
 *   <li><b>鉴权档位不是独立来源</b>：{@code endpoint-inventory.tsv} 的 AUTH 列**也是**
 *       {@code RequestMappingLookup} 算出来的（`ApiContractInventoryTests#authOf`）⇒ 它与 spec 的 401
 *       共享同一个判定的**两个时点**。它能抓"customizer 忘了挂 401"，抓不到"判定本身跑偏"
 *       （那种情况先由登记簿测试红，重生成后两边会一起错）。</li>
 *   <li><b>码字典断言是形状守恒</b>：{@code OpenApiConfig#errorCodeSchema} 与 {@code ErrorCode.values()}
 *       同源 ⇒ 这条只有在"组件缺失 / 枚举被手改"时才会红。它证明的是"字典在且形状对"，
 *       **不是**"spec 里的码就是运行期会出现的码"（后者归 {@code ErrorCodeContractTests#无死码}）。</li>
 * </ul>
 */
@DisplayName("T60 OpenAPI spec：导出 + 端点集对齐 + 无假参数 + 响应类别")
@TestPropertySource(properties = {
        // 生产默认关（application.yaml），本测试单独打开：不打开就没有端点控制器 bean 可调。
        // 只影响"端点是否注册"，不影响 spec 内容。
        "springdoc.api-docs.enabled=true"
})
class OpenApiSpecExportTests extends AbstractIntegrationTest {

    /** 入库的契约快照（与登记簿同目录；`.gitignore` 已用 `!/src/test/resources/api/` 放行）。 */
    private static final Path SPEC = Path.of("src", "test", "resources", "api", "openapi.yaml");

    /** `T54` 的端点登记簿：spec 的对齐基线（method / path / 鉴权档位 / 返回类型）。 */
    private static final Path INVENTORY = Path.of("src", "test", "resources", "api", "endpoint-inventory.tsv");

    /** 生成模式：`-Dapi.openapi.write=true`（见类注释）。 */
    private static final boolean WRITE_MODE = Boolean.getBoolean("api.openapi.write");

    /**
     * 声明了 {@code @RequestPart} 的端点（`T59` 登记为 multipart 例外，§2.5）—— **四条都在**。
     * 显式列出的理由与 `T59-3` 的计数护栏相同：让"改名后脱保"这件事**变红**，而不是静默失配。
     */
    private static final List<String> MULTIPART_ENDPOINTS = List.of(
            "POST /api/v1/contents/posts",
            "POST /api/v1/contents/videos",
            "PUT /api/v1/contents/{id}/media",
            "PUT /api/v1/admin/media/{id}");

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
        assertThat(normalize(readFile(SPEC)))
                .as("入库 spec 与当前代码生成的 spec 不一致 —— 改了端点却没重生成？"
                        + "（生成方式见类注释；确认 diff 合理后再提交）")
                .isEqualTo(generated);
    }

    // ==================== 用例 2：端点集与登记簿对齐 ====================

    @Test
    @DisplayName("★ spec 的 path+method 集合 == T54 登记簿（差异即红）")
    void 端点集与登记簿一致() {
        Set<String> fromSpec = operationKeys(parse(generate()));
        Set<String> fromInventory = new TreeSet<>();
        readInventory().forEach(r -> fromInventory.add(r.method() + " " + r.path()));

        Set<String> onlyInSpec = new TreeSet<>(fromSpec);
        onlyInSpec.removeAll(fromInventory);
        Set<String> onlyInInventory = new TreeSet<>(fromInventory);
        onlyInInventory.removeAll(fromSpec);

        assertThat(onlyInSpec)
                .as("spec 里有、登记簿里没有 —— springdoc 扫到了自研扫描器漏掉的端点"
                        + "（或 `springdoc.packages-to-scan` 与 BASE_PACKAGE 不一致）")
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

    // ==================== 用例 4：响应类别 ↔ 鉴权档位 / 204 ====================

    @Test
    @DisplayName("★ 响应类别与登记簿一致：PUBLIC 无 401、ADMIN 有 403、ResponseEntity<Void> 必须 204")
    void 响应类别与登记簿一致() {
        OpenAPI spec = parse(generate());
        List<String> problems = new ArrayList<>();

        for (Row row : readInventory()) {
            Operation op = operation(spec, row);
            if (op == null) {
                problems.add(row.method() + " " + row.path() + " —— spec 里找不到该操作");
                continue;
            }
            Set<String> codes = op.getResponses().keySet();

            // ① 鉴权档位 ⇒ 401/403。oracle = T54 登记簿（同一判定的**快照**，见类注释的"判别力边界"）
            switch (row.auth()) {
                case "PUBLIC" -> {
                    if (codes.contains("401")) {
                        problems.add(row.method() + " " + row.path() + " —— 匿名端点却声明了 401");
                    }
                    if (codes.contains("403")) {
                        problems.add(row.method() + " " + row.path() + " —— 匿名端点却声明了 403");
                    }
                }
                case "LOGIN" -> {
                    if (!codes.contains("401")) {
                        problems.add(row.method() + " " + row.path() + " —— 需登录却漏了 401");
                    }
                    // 403 **不禁止**：所有权校验（`ForbiddenException`）散在各域 Service 里，
                    // 无法从"要不要登录"推导 ⇒ spec 故意放宽到"需身份就可能 403"（《护栏与Spec》§二 类别级可能域）。
                }
                case "ADMIN" -> {
                    if (!codes.contains("401") || !codes.contains("403")) {
                        problems.add(row.method() + " " + row.path() + " —— admin 端点必须同时声明 401 与 403");
                    }
                }
                default -> problems.add(row.method() + " " + row.path() + " —— 未知鉴权档位 " + row.auth());
            }

            // ② 返回类型 ⇒ 204 / 200（T59-1 定稿：DELETE 与部分 PUT = 204 无体）
            if ("ResponseEntity<Void>".equals(row.returns())) {
                if (!codes.contains("204")) {
                    problems.add(row.method() + " " + row.path() + " —— 返回 ResponseEntity<Void> 却没声明 204");
                }
                if (codes.contains("200")) {
                    problems.add(row.method() + " " + row.path() + " —— 204 端点不该同时声明 200");
                }
            } else if (!codes.contains("200")) {
                problems.add(row.method() + " " + row.path() + " —— 有响应体的端点漏了 200");
            }
        }

        assertThat(problems)
                .as("spec 的响应类别与运行期契约不一致（读法见《护栏与Spec》§二：类别是可能域，但不许自相矛盾）")
                .isEmpty();
    }

    // ==================== 用例 5：错误信封 / 码字典 / multipart / servers ====================

    @Test
    @DisplayName("★ 错误信封与码字典：错误响应都指 ApiError，ErrorCode 枚举 = 41 项")
    void 错误信封与码字典() {
        OpenAPI spec = parse(generate());
        Components components = spec.getComponents();
        assertThat(components).as("spec 缺 components").isNotNull();

        Schema<?> dictionary = components.getSchemas().get("ErrorCode");
        assertThat(dictionary)
                .as("缺 `ErrorCode` 码字典 schema")
                .isNotNull();
        List<String> enumValues = dictionary.getEnum() == null
                ? List.of()
                : dictionary.getEnum().stream().map(String::valueOf).toList();
        assertThat(enumValues)
                .as("码字典必须与 ErrorCode 枚举同源（不是手抄一份）")
                .hasSize(ErrorCode.values().length)
                .containsAll(java.util.Arrays.stream(ErrorCode.values()).map(ErrorCode::getCode).toList());

        Schema<?> apiError = components.getSchemas().get("ApiError");
        assertThat(apiError).as("缺 `ApiError` 失败体 schema").isNotNull();
        assertThat(apiError.getProperties())
                .as("失败体只能是 code/msg/errors —— 出现 data 就等于说失败也带业务数据")
                .containsOnlyKeys("code", "msg", "errors");

        // 每个 components.responses 的错误响应都必须指向同一个 ApiError（改一处形状即一处）
        assertThat(components.getResponses())
                .as("错误响应组件应覆盖 400/401/403/404/409/415/429/500/503 九类")
                .hasSize(9);
        components.getResponses().forEach((name, response) ->
                assertThat(errorSchemaRef(response))
                        .as("错误响应 `%s` 没有指向 #/components/schemas/ApiError", name)
                        .isEqualTo("#/components/schemas/ApiError"));
    }

    @Test
    @DisplayName("★ multipart 端点声明 multipart/form-data；servers 不是 localhost")
    void 上传端点与服务器地址() {
        OpenAPI spec = parse(generate());

        for (String key : MULTIPART_ENDPOINTS) {
            Operation op = operation(spec, key);
            assertThat(op).as("%s 在 spec 里不存在（改名了？同步更新 MULTIPART_ENDPOINTS）", key).isNotNull();
            assertThat(op.getRequestBody().getContent())
                    .as("%s 必须声明 multipart/form-data —— 声明成 JSON，前端就会按 JSON 传文件", key)
                    .containsOnlyKeys("multipart/form-data");
        }

        // 必填的 part：代码用 `@RequestPart(required = false)` + 手写判空，spec 不覆盖就会谎报"可选"
        assertThat(requiredBodyParts(spec, "POST /api/v1/contents/videos"))
                .as("videos 的 video / cover 在代码里是必填（`@RequestPart` 未标 required=false）")
                .contains("video", "cover");
        assertThat(requiredBodyParts(spec, "PUT /api/v1/contents/{id}/media"))
                .as("换源缺文件 ⇒ 400「请选择要替换的文件」，所以 file 是必填")
                .contains("file");
        assertThat(requiredBodyParts(spec, "PUT /api/v1/admin/media/{id}"))
                .as("管理员换源同样必填 file")
                .contains("file");

        assertThat(spec.getServers())
                .as("离线交付的 spec 不能钉 `http://localhost`（照它拼路径的调用方会打到本机）")
                .isNotEmpty()
                .noneMatch(s -> s.getUrl() != null && s.getUrl().contains("localhost"));
    }

    @Test
    @DisplayName("★ 503 只挂在 report；上传的 title/categoryId 是必填（不能写成可选）")
    void 重试端点与必填字段() {
        OpenAPI spec = parse(generate());

        assertThat(operation(spec, "PUT /api/v1/contents/{id}/progress").getResponses())
                .as("`HistoryService#report` 会抛 ServiceBusyException ⇒ 503 + Retry-After 必须声明")
                .containsKey("503");
        assertThat(operation(spec, "POST /api/v1/history/flush").getResponses())
                .as("flush 吞掉缓冲不可用并返回 200（数据留缓冲等兜底），声明 503 等于谎报")
                .doesNotContainKey("503");

        for (String key : List.of("POST /api/v1/contents/videos", "POST /api/v1/contents/posts")) {
            List<String> required = operation(spec, key).getParameters().stream()
                    .filter(p -> Boolean.TRUE.equals(p.getRequired()))
                    .map(Parameter::getName)
                    .toList();
            assertThat(required)
                    .as("%s 的 title / categoryId 由手写校验拒绝（缺一即 400），spec 不能写成可选", key)
                    .contains("title", "categoryId");
        }
    }

    /**
     * 403 / 404 的**独立期望**：不从规则反推，而是从"哪些 Service 真会抛"读出来的清单。
     *
     * <p>为什么必须手写：`OpenApiConfig` 里 403 的判据是"只要需身份就声明"（宽口径，防漏），
     * 404 的判据是"有入参就声明" —— 这两条规则本身**推不出**"哪个端点真的会 403/404"。
     * 若只断言规则，等于用实现验证实现。这里钉住的是**代码事实**：
     * <ul>
     *   <li>403 ← `CommentService#401`、`ContentService#306/356`、`FavoriteService#1198` 的
     *       {@code ForbiddenException}（所有权校验）；</li>
     *   <li>404 ← `CommentService#137/145`（contentId / parentId 在 body）、
     *       `FavoriteService#518/1195`（contentId / folderId 在 body 或 query）。</li>
     * </ul>
     */
    @Test
    @DisplayName("★ 403 不只来自 admin、404 不只来自 path 变量：所有权与资源寻址端点必须声明")
    void 所有权与资源寻址类别() {
        OpenAPI spec = parse(generate());

        // 403：各域 Service 的所有权校验（`ForbiddenException`）
        for (String key : List.of(
                "DELETE /api/v1/comments/{id}",
                "PUT /api/v1/contents/{id}/comment-enabled",
                "PUT /api/v1/contents/{id}",
                "DELETE /api/v1/contents/{id}",
                "PUT /api/v1/contents/{id}/media",
                "DELETE /api/v1/contents/{id}/media",
                "PUT /api/v1/favorites/{id}",
                "PUT /api/v1/favorite-folders/{id}",
                "DELETE /api/v1/favorite-folders/{id}")) {
            assertThat(operation(spec, key).getResponses())
                    .as("%s 会因「不是自己的」抛 ForbiddenException ⇒ 403 必须声明", key)
                    .containsKey("403");
        }

        // 404：目标资源 id 在 body / query 里（路径上没有 `{var}`）
        for (String key : List.of(
                "POST /api/v1/comments",
                "POST /api/v1/favorites",
                "GET /api/v1/favorites",
                "DELETE /api/v1/favorites")) {
            assertThat(operation(spec, key).getResponses())
                    .as("%s 的目标资源 id 在 body/query 里，会回 404 ⇒ 不能因为'路径没变量'就不声明", key)
                    .containsKey("404");
        }
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

    private static Operation operation(OpenAPI spec, Row row) {
        return operation(spec, row.method() + " " + row.path());
    }

    /** `"GET /api/v1/x"` → 对应的 {@link Operation}；不存在返回 null。 */
    private static Operation operation(OpenAPI spec, String methodAndPath) {
        int split = methodAndPath.indexOf(' ');
        PathItem.HttpMethod method = PathItem.HttpMethod.valueOf(methodAndPath.substring(0, split));
        PathItem item = spec.getPaths().get(methodAndPath.substring(split + 1));
        return item == null ? null : item.readOperationsMap().get(method);
    }

    /** 错误响应体里指向的 schema `$ref`（用于确认"所有错误响应同形"）。 */
    private static String errorSchemaRef(ApiResponse response) {
        if (response.getContent() == null) {
            return null;
        }
        io.swagger.v3.oas.models.media.MediaType json = response.getContent().get("application/json");
        if (json == null || json.getSchema() == null) {
            return null;
        }
        return json.getSchema().get$ref();
    }

    /** multipart 请求体 schema 上的 `required` 列表。 */
    private static List<String> requiredBodyParts(OpenAPI spec, String key) {
        Schema<?> schema = operation(spec, key).getRequestBody()
                .getContent().values().iterator().next().getSchema();
        return schema.getRequired() == null ? List.of() : schema.getRequired();
    }

    // ==================== 登记簿读写 ====================

    /** 登记簿的一行（`T54` 的四个列）。 */
    private record Row(String method, String path, String auth, String returns) {
    }

    private static List<Row> readInventory() {
        assertThat(Files.exists(INVENTORY))
                .as("登记簿不存在：%s", INVENTORY.toAbsolutePath())
                .isTrue();
        List<Row> out = new ArrayList<>();
        for (String raw : readLines(INVENTORY)) {
            if (raw.isBlank() || raw.startsWith("#")) {
                continue;
            }
            String[] cols = raw.split("\t", -1);
            out.add(new Row(cols[0], cols[1], cols[2], cols[3]));
        }
        return out;
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> readLines(Path path) {
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
