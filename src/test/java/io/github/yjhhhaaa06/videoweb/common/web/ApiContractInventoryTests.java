package io.github.yjhhhaaa06.videoweb.common.web;

import io.github.yjhhhaaa06.videoweb.common.security.RequestMappingLookup;
import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对外端点登记簿（`T54`）：把「真实 handler 扫描」与「提交在仓库里的登记簿」逐条比对。
 *
 * <p>口径（登记簿的定位 / 更新纪律 / 三条护栏）见 {@code 接口契约.md} §2.9；
 * 端点定性与路径重构归 `T58`，载荷形状归 `T59`。
 *
 * <h2>它解决什么问题</h2>
 * 接口契约专项（`T58` / `T59`）要改 81 个端点的方法、路径、出参形状。没有这份清单：
 * ① 改完无法回答"还剩几个没改"（本仓纪律 = 状态结论必须可复算）；
 * ② 前端重制时无法确认"后端到底暴露了哪些端点"。
 *
 * <h2>为什么"登记簿"必须手改（而不是每次自动生成）</h2>
 * 自动生成 = 永远自洽 ⇒ 加一个端点不会有任何阻力，"新增端点必须同批进清单"这条护栏就不存在。
 * 现在的形态是**双录入**：真实 handler 是唯一事实源，登记簿是**契约快照**；
 * 两者不一致就红，逼作者在同一个 commit 里完成"加端点 + 登记"。
 *
 * <h2>唯一合法的更新方式</h2>
 * <pre>
 * ./mvnw.cmd -o test -Dtest=ApiContractInventoryTests -Dapi.contract.write=true
 * </pre>
 * 生成后**必须 review diff**（新增/删除/改签名各是哪一条），再与代码同批提交。
 * ⚠️ 生成内容只含 method / path / 鉴权档位 / 返回类型 —— 它们是**契约面**；
 * 请求参数与字段形状不在本清单里（那由 `T59` 的出参约束与 `T60` 的 OpenAPI 承担）。
 *
 * <h2>红的时候先问什么</h2>
 * "是我改了端点没登记，还是登记簿被误改？" —— 前者补登记，后者回退。
 * **不许**直接跑生成命令把红变绿：那正是本用例要防的"迎合"。
 */
class ApiContractInventoryTests extends AbstractIntegrationTest {

    /** 业务控制器所在包；只登记这个包里的 handler（Spring 自己的 {@code /error} 等不进清单）。 */
    private static final String BASE_PACKAGE = "io.github.yjhhhaaa06.videoweb";

    /**
     * 登记簿路径（相对仓库根；surefire 的工作目录 = 项目根）。
     *
     * <p>放 `test/resources` 而不是 `.docs/` 的原因：它是**测试夹具**（要能被 `Files.readString` 直接读、
     * 且随代码分支走）；文档侧的可读版本由 `接口契约.md` 承担。
     */
    private static final Path INVENTORY = Path.of("src", "test", "resources", "api", "endpoint-inventory.tsv");

    /** 生成模式：`-Dapi.contract.write=true`（见类注释）。 */
    private static final boolean WRITE_MODE = Boolean.getBoolean("api.contract.write");

    private static final String HEADER = """
            # MySpringTV 对外端点登记簿（T54 起；唯一事实源 = 真实 handler，本文件是契约快照）
            #
            # 校验：ApiContractInventoryTests（真实 handler 扫描 ↔ 本文件逐条一致；不一致即红）
            # 更新：./mvnw.cmd -o test -Dtest=ApiContractInventoryTests -Dapi.contract.write=true
            #       ⚠️ 生成后必须 review diff 再与代码同批提交；跑生成命令"把红变绿"属迎合。
            #
            # 列：METHOD <TAB> PATH <TAB> AUTH <TAB> RETURN
            # AUTH = PUBLIC（匿名可达） | LOGIN（需有效身份） | ADMIN（需管理员角色，蕴含登录）
            # 排序：PATH 升序，其次 METHOD 升序（保证 diff 可读）
            """;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /** ★ 鉴权档位取自**生产同一份判定**（不在此重写一遍规则，避免两套口径漂移）。 */
    @Autowired
    private RequestMappingLookup mappingLookup;

    // ==================== 用例 1：登记簿 ↔ 真实 handler ====================

    @Test
    @DisplayName("★ 登记簿与真实 handler 逐条一致：新增/删除/改签名端点的同批登记是本用例的护栏")
    void 登记簿与真实handler逐条一致() {
        List<Endpoint> scanned = scan();

        if (WRITE_MODE) {
            write(scanned);
            return;   // 生成模式只负责落盘；比对留给下一次正常运行（见类注释）
        }

        List<Endpoint> recorded = read();

        Set<String> scannedLines = scanned.stream().map(Endpoint::line).collect(Collectors.toCollection(TreeSet::new));
        Set<String> recordedLines = recorded.stream().map(Endpoint::line).collect(Collectors.toCollection(TreeSet::new));

        Set<String> missing = new TreeSet<>(scannedLines);
        missing.removeAll(recordedLines);
        Set<String> stale = new TreeSet<>(recordedLines);
        stale.removeAll(scannedLines);

        assertThat(missing)
                .as("这些端点真实存在但登记簿里没有 —— 加了端点却没同批登记（或改签名没更新登记）")
                .isEmpty();
        assertThat(stale)
                .as("登记簿里有、真实 handler 里没有 —— 端点被删/改名/改方法后没更新登记")
                .isEmpty();
    }

    // ==================== 用例 2：扫描面 sanity（防"全绿但什么都没查"）====================

    @Test
    @DisplayName("扫描面 sanity：确实扫到了业务端点，且方法集合限定、路径不重复、无 OPTIONS 兜底混入")
    void 扫描面自检() {
        List<Endpoint> scanned = scan();

        assertThat(scanned)
                .as("扫描面必须非空（introspection 失效时本类会'全绿'，这是假绿防线）")
                .isNotEmpty();

        assertThat(scanned.stream().map(Endpoint::method).distinct().collect(Collectors.toSet()))
                .as("方法级映射必须显式限定 HTTP 方法（@GetMapping/@PostMapping…）；"
                        + "@RequestMapping 不限定方法会让本清单与权重表都无法判定")
                .doesNotContain("ANY");

        assertThat(scanned.stream().map(e -> e.method() + " " + e.path()).collect(Collectors.toSet()))
                .as("同一 (METHOD, PATH) 只许出现一次")
                .hasSize(scanned.size());

        assertThat(scanned.stream().map(Endpoint::auth).collect(Collectors.toSet()))
                .as("鉴权档位只能取三值")
                .isSubsetOf(Set.of("PUBLIC", "LOGIN", "ADMIN"));
    }

    // ==================== 用例 3：出参形状（§2.5 的机器判据）====================

    @Test
    @DisplayName("★ 出参形状统一：非 DELETE 返回 ApiResponse<T>；DELETE 返回 ResponseEntity<Void>（204 无体）")
    void 出参形状统一() {
        List<String> offenders = scan().stream()
                .filter(e -> {
                    if ("DELETE".equals(e.method())) {
                        // ★ T59：删除/取消 = 204 无 body，故 DELETE 必须走 ResponseEntity<Void>
                        return !"ResponseEntity<Void>".equals(e.returns());
                    }
                    return !e.returns().startsWith("ApiResponse<");
                })
                .map(Endpoint::line)
                .toList();

        assertThat(offenders)
                .as("非 DELETE 必须走统一信封 ApiResponse<T>；DELETE 必须 204 无体（ResponseEntity<Void>）"
                        + "——否则前端无法统一解包 / 无法区分空响应")
                .isEmpty();
    }

    /** 全站**标量计数端点**（`T59-3` 定稿；新增计数端点必须同批登记到这里）。 */
    private static final Set<String> COUNT_ENDPOINTS = Set.of(
            "GET /api/v1/contents/{id}/like-count",
            "GET /api/v1/comments/{id}/like-count",
            "GET /api/v1/contents/{id}/favorite-count");

    /**
     * ★ 计数端点（`*-count`）出参统一为 {@code ApiResponse<Long>}（§2.7）。
     *
     * <p>这里刻意**显式列端点集合**而不是只按 `endsWith("-count")` 过滤：后者在端点改名 /
     * 新增 {@code /xx-total} 时会**静默脱保**（新端点根本进不了过滤）。集合比对让
     * "改名 / 新增 / 删除"都变红，逼同一 commit 更新。
     */
    @Test
    @DisplayName("★ 计数端点集合固定且出参统一 ApiResponse<Long>（§2.7：计数不分 int/long 两套）")
    void 计数端点出参统一为long() {
        List<Endpoint> scanned = scan();

        Set<String> actual = scanned.stream()
                .filter(e -> e.path().endsWith("-count"))
                .map(e -> e.method() + " " + e.path())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(actual)
                .as("*-count 端点集合变了 —— 新增/改名/删除必须同批更新本清单与登记簿")
                .containsExactlyInAnyOrderElementsOf(COUNT_ENDPOINTS);

        List<String> offenders = scanned.stream()
                .filter(e -> COUNT_ENDPOINTS.contains(e.method() + " " + e.path()))
                .filter(e -> !"ApiResponse<Long>".equals(e.returns()))
                .map(Endpoint::line)
                .toList();

        assertThat(offenders)
                .as("计数端点的 Java 出参必须是 Long（OpenAPI 里因此统一为 int64；"
                        + "int 与 long 两套会让前端/生成器看到两种整数宽度）")
                .isEmpty();
    }

    // ==================== 扫描 ====================

    private List<Endpoint> scan() {
        List<Endpoint> out = new ArrayList<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getName().startsWith(BASE_PACKAGE)
                    || !AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), Controller.class)) {
                continue;   // Spring 自己的 /error 等：不是业务端点
            }

            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            String returns = typeName(handler.getMethod().getGenericReturnType());
            for (String pattern : info.getPatternValues()) {
                if (methods.isEmpty()) {
                    // 无法判定 HTTP 方法 ⇒ 用哨兵值让用例 2 变红（而不是静默跳过一条扫描盲区）。
                    // 鉴权与 HTTP 方法无关（判据是处理器注解），故用 GET 探测不影响结论。
                    out.add(new Endpoint("ANY", pattern, authOf(RequestMethod.GET, pattern), returns));
                    continue;
                }
                for (RequestMethod method : methods) {
                    out.add(new Endpoint(method.name(), pattern, authOf(method, pattern), returns));
                }
            }
        }
        out.sort(Endpoint.ORDER);
        return out;
    }

    /**
     * 鉴权档位：走**生产同一个** {@link RequestMappingLookup}，不在此重写三条规则。
     *
     * <p>本仓全部映射都是字面路径（无 `{id}` 等路径变量，见 `@RequestParam` 的既有形态）⇒
     * 用 {@code pattern} 作请求路径是精确的。若将来引入路径变量，这里必须补样本值，
     * 否则 lookup 取不到 handler、会退化成 {@code PUBLIC} —— 届时登记簿 diff 会暴露它
     * （档位从 LOGIN 变 PUBLIC，review 时看得见）。
     */
    private String authOf(RequestMethod method, String pattern) {
        // ★ `T58` 起路径含 {id} 变量：必须填一个样本值才匹配得到 handler，
        //   否则 lookup 返回 NONE、整张表被误记成 PUBLIC（假绿的典型形态）。
        var authz = mappingLookup.resolve(
                new MockHttpServletRequest(method.name(), pattern.replaceAll("\\{[^/]+}", "1")));
        if (authz.adminRequired()) {
            return "ADMIN";
        }
        return authz.loginRequired() ? "LOGIN" : "PUBLIC";
    }

    // ==================== 登记簿读写 ====================

    private List<Endpoint> read() {
        assertThat(Files.exists(INVENTORY))
                .as("登记簿不存在：%s（首次生成见类注释）", INVENTORY.toAbsolutePath())
                .isTrue();
        List<Endpoint> out = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(INVENTORY, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.isBlank() || raw.startsWith("#")) {
                continue;
            }
            String[] cols = raw.split("\t", -1);
            assertThat(cols)
                    .as("登记簿第 %d 行不是 4 列（METHOD/PATH/AUTH/RETURN）：%s", i + 1, raw)
                    .hasSize(4);
            out.add(new Endpoint(cols[0], cols[1], cols[2], cols[3]));
        }
        return out;
    }

    private void write(List<Endpoint> scanned) {
        StringBuilder sb = new StringBuilder(HEADER);
        scanned.stream().map(Endpoint::line).forEach(l -> sb.append(l).append('\n'));
        try {
            Files.createDirectories(INVENTORY.getParent());
            // ⚠️ 显式 UTF-8 + `\n`：仓库 `* text=auto eol=lf`，CRLF 会污染 diff。
            Files.writeString(INVENTORY, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.printf("[api-contract] 已生成 %d 条端点 → %s%n", scanned.size(), INVENTORY);
    }

    // ==================== 类型名归一 ====================

    /**
     * 泛型返回类型 → 人类可读的简单名（`ApiResponse<PageResult<ContentVO>>`）。
     *
     * <p>用简单名的理由：清单是给人和前端看的契约快照，包名改动不该让 81 行全红。
     */
    private static String typeName(Type type) {
        if (type instanceof Class<?> c) {
            return c.isArray() ? typeName(c.getComponentType()) + "[]" : c.getSimpleName();
        }
        if (type instanceof ParameterizedType p) {
            String args = Arrays.stream(p.getActualTypeArguments())
                    .map(ApiContractInventoryTests::typeName)
                    .collect(Collectors.joining(", "));
            return typeName(p.getRawType()) + "<" + args + ">";
        }
        if (type instanceof WildcardType w) {
            Type[] upper = w.getUpperBounds();
            if (upper.length == 0 || upper[0] == Object.class) {
                return "?";
            }
            return "? extends " + typeName(upper[0]);
        }
        return type.getTypeName();
    }

    /** 登记簿的一行。 */
    private record Endpoint(String method, String path, String auth, String returns) {

        static final Comparator<Endpoint> ORDER =
                Comparator.comparing(Endpoint::path).thenComparing(Endpoint::method);

        String line() {
            return method + "\t" + path + "\t" + auth + "\t" + returns;
        }
    }
}
