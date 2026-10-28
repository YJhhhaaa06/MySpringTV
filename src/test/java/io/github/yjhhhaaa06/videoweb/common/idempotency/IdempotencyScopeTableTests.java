package io.github.yjhhhaaa06.videoweb.common.idempotency;

import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等端点注册表的**完整性契约**（写接口幂等专项 `T66`；口径的唯一事实源 =
 * {@code architecture/tech/idempotency/写接口幂等-测试与契约.md} §一）。
 *
 * <p>四条断言，<b>全部扫真实 handler</b>（{@code RequestMappingHandlerMapping#getHandlerMethods}），
 * 不读配置文件——注册表是代码里的契约，"表与代码对不上"必须立刻变红：
 *
 * <table>
 *   <caption>四条断言</caption>
 *   <tr><th>#</th><th>断言</th><th>治什么</th></tr>
 *   <tr><td>1</td><td>{@code seen >= N}</td><td>introspection 失效导致"全绿但什么都没查"（假绿防线）</td></tr>
 *   <tr><td>2</td><td>{@code missing} 为空（handler 有、表里无）</td><td>新增端点漏登记 ⇒ <b>静默不幂等</b></td></tr>
 *   <tr><td>3</td><td>{@code methodUnlimited} 为空（方法级 {@code @RequestMapping} 未限定方法）</td>
 *       <td>扫描盲区；逼调用方改用 {@code @GetMapping} / {@code @PostMapping}</td></tr>
 *   <tr><td>4</td><td>{@code stale} 为空（表里有、handler 无）</td><td>删端点忘删登记 / 改路由后旧键残留（死键永不命中）</td></tr>
 * </table>
 *
 * <p>★ 第 4 条是本专项新增的**反向断言**（既有限流权重表只有前三条）：有了它，"批量改路由"
 * 之后表与代码<b>双向</b>对不上都会变红。
 *
 * <p>跳过规则与限流权重表一致：{@code GET / HEAD / OPTIONS / TRACE} 不登记；
 * {@code pattern} 以 {@code /error} 开头的不登记（Spring 默认错误分发，不是业务端点）。
 *
 * <h2>★ 反向验证（证明这四条有判别力）</h2>
 * 从 {@code IdempotencyScope} 删一条（如 {@code PUT /api/v1/watch-later/{id}}）⇒ 用例
 * {@link #非GET端点必须全部登记()} 必红；向表里加一条不存在的路由 ⇒ 用例
 * {@link #注册表里不能有死键()} 必红。
 */
class IdempotencyScopeTableTests extends AbstractHttpIntegrationTest {

    /**
     * 非 GET 端点的**实际总数**（当前 40：POST 14 / PUT 15 / DELETE 11）。
     * 新增端点后只增不减——它同时是"扫描真的看到了东西"的 sanity 下界。
     */
    private static final int EXPECTED_NON_GET_AT_LEAST = 40;

    /** ★ 必须指名标准 MVC 的那个：同为该类型的还有 actuator 的 {@code controllerEndpointHandlerMapping}。 */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("扫描面 sanity：handler 映射里确实看得到全部非 GET 端点（introspection 失效 ⇒ 后三条会假绿）")
    void 扫描面必须看到全部非GET端点() {
        Scan scan = scan();

        assertThat(scan.seen())
                .as("扫描面 sanity：应看到全部非 GET 端点（本仓当前 %d 条）。"
                        + "若这里变小，说明 handler introspection 失效或有人把方法级映射改成了不限方法 —— "
                        + "后三条会变成'全绿但什么都没查'", EXPECTED_NON_GET_AT_LEAST)
                .isGreaterThanOrEqualTo(EXPECTED_NON_GET_AT_LEAST);
    }

    @Test
    @DisplayName("missing：handler 有、注册表无 ⇒ 空（新增端点漏登记 = 静默不幂等）")
    void 非GET端点必须全部登记() {
        Set<String> missing = new TreeSet<>(scan().nonGetRoutes());
        missing.removeAll(IdempotencyScope.table().keySet());

        assertThat(missing)
                .as("这些端点 handler 有、IdempotencyScope 里没有登记：%s。"
                        + "漏登记 ⇒ 该端点**静默不幂等**（既不报错也不生效）。"
                        + "请在注册表里显式判成 KEYED 或 NATURAL —— 后者也有意义："
                        + "'我们看过它并判断它天然幂等'这行登记本身就是 review 的锚点", missing)
                .isEmpty();
    }

    @Test
    @DisplayName("methodUnlimited：方法级 @RequestMapping 未限定 HTTP 方法 ⇒ 空（否则是扫描盲区）")
    void 方法级映射必须限定HTTP方法() {
        Set<String> methodUnlimited = scan().methodUnlimited();

        assertThat(methodUnlimited)
                .as("这些映射用了方法级 @RequestMapping 且**未限定 HTTP 方法**：%s。"
                        + "本检查无法判定它该不该登记（同一个 handler 可能既处理 GET 又处理 POST）⇒ "
                        + "这是注册表完整性的一条盲区。请改用 @GetMapping / @PostMapping / @PutMapping / "
                        + "@DeleteMapping", methodUnlimited)
                .isEmpty();
    }

    @Test
    @DisplayName("stale：注册表有、handler 无 ⇒ 空（删端点忘删登记 / 改路由后旧键残留）")
    void 注册表里不能有死键() {
        Set<String> stale = new TreeSet<>(IdempotencyScope.table().keySet());
        stale.removeAll(scan().nonGetRoutes());

        assertThat(stale)
                .as("这些键在 IdempotencyScope 里、但没有任何 handler 命中：%s。"
                        + "死键永远不会被查到（KEYED 的等于保护不存在、NATURAL 的等于这行登记在骗人）⇒ "
                        + "删端点或改路由时必须同步删登记", stale)
                .isEmpty();
    }

    // ========================================================================
    // 扫描（口径同 RateLimitWeightTableTests#完整性_全部非GET端点都已登记）
    // ========================================================================

    private Scan scan() {
        Set<String> nonGetRoutes = new TreeSet<>();
        Set<String> methodUnlimited = new TreeSet<>();
        int seen = 0;
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (pattern.startsWith("/error")) {
                    continue;   // Spring 默认错误分发（BasicErrorController），不是业务端点
                }
                if (methods.isEmpty()) {
                    methodUnlimited.add(pattern);
                    continue;
                }
                for (RequestMethod method : methods) {
                    if (!needsRegistration(method)) {
                        continue;   // GET / HEAD / OPTIONS / TRACE：天然幂等，注册表不覆盖
                    }
                    seen++;
                    nonGetRoutes.add(method.name() + " " + pattern);
                }
            }
        }
        return new Scan(nonGetRoutes, methodUnlimited, seen);
    }

    /** 需要登记的"写方法"（本仓当前只有 POST / PUT / DELETE，规则面向未来）。 */
    private static boolean needsRegistration(RequestMethod method) {
        return switch (method) {
            case GET, HEAD, OPTIONS, TRACE -> false;
            case POST, PUT, PATCH, DELETE -> true;
        };
    }

    /**
     * @param nonGetRoutes    扫描到的「方法 空格 路由模式」集合
     * @param methodUnlimited 方法级未限定 HTTP 方法的路由（扫描盲区）
     * @param seen            扫描到的非 GET 映射条数（sanity 用；同一路由多条方法时逐条计）
     */
    private record Scan(Set<String> nonGetRoutes, Set<String> methodUnlimited, int seen) {
    }
}
