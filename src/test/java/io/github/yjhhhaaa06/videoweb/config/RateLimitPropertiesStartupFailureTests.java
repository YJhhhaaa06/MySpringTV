package io.github.yjhhhaaa06.videoweb.config;

import io.github.yjhhhaaa06.videoweb.common.config.RateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code video.rate-limit.*} 非法值必须让**上下文起不来**（限流专项 T23 验收①）。
 *
 * <h2>为什么不能只断"构造器抛异常"</h2>
 * 直接 {@code new RateLimitProperties(0, …)} 只能证明"构造器会拒绝"，
 * 而这条纪律真正要保的性质是"**应用起不来**"——两者之间隔着一步：
 * 记录要真被 {@code @ConfigurationPropertiesScan} / {@code @EnableConfigurationProperties}
 * 当配置位绑定（而不是被某个 {@code @Bean} 手工 new 出来、或干脆没被扫到）。
 * 本类走**真实的绑定路径**（{@code ApplicationContextRunner} + {@code @ConfigurationProperties}），
 * 断言的是"上下文启动失败"，而不是"某个方法抛了异常"。
 *
 * <p>本类**不需要**外部依赖（不起容器、不连 DB/Redis）⇒ 故意不继承
 * {@code AbstractIntegrationTest}，这样它可以在没有 Docker 的环境里单独跑。
 *
 * <h2>★ 夹具带对照</h2>
 * 每条"非法 ⇒ 起不来"都必须配一条"**同一份夹具、同样的键，值合法 ⇒ 起得来**"的对照
 * （{@link #合法配置必须能启动()}）—— 否则这些失败可能来自夹具自身写错（键名拼错、
 * 前缀打错），而不是来自校验：那样就是**假绿**。
 *
 * <p>⚠️ 断言的是**失败原因**（根因链里出现出错的键名），不是"反正失败了"：
 * 只断 {@code hasFailed()} 的话，任何启动错误（缺 Bean、语法错）都会让用例通过。
 */
class RateLimitPropertiesStartupFailureTests {

    /** 绑定目标：只注册这一个配置位，最小上下文。 */
    @Configuration
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class Config {}

    /**
     * 一份**合法**的完整配置（唯一来源，避免每个用例各抄一遍而抄漏）。
     * 期望值 = 首版数值（唯一出处 = {@code application.yaml} 的 {@code video.rate-limit} 段注释）；
     * 本类只关心"合法/非法"，不关心量级（那是
     * {@code RateLimitPropertiesBindingTests} 的事）。
     */
    private static List<String> validConfig() {
        return new ArrayList<>(List.of(
                "video.rate-limit.server.capacity=200",
                "video.rate-limit.server.refill-per-sec=50",
                "video.rate-limit.server.task-cost=10",
                "video.rate-limit.user.authed.capacity=20",
                "video.rate-limit.user.authed.refill-per-sec=2",
                "video.rate-limit.user.anon.capacity=60",
                "video.rate-limit.user.anon.refill-per-sec=6",
                "video.rate-limit.cost.default-base=1",
                "video.rate-limit.cost.miss-multiplier=1.0",
                "video.rate-limit.cost.reject-discount=0.3",
                "video.rate-limit.cost.base.[POST /favorite/add]=3"));
    }

    /** 合法配置 + 一条覆盖 / 追加。 */
    private static List<String> validConfigPlus(String... extra) {
        List<String> props = validConfig();
        props.addAll(List.of(extra));
        return props;
    }

    /** 合法配置但把第 {@code index} 条替换成 {@code replacement}。 */
    private static List<String> validConfigWith(int index, String replacement) {
        List<String> props = validConfig();
        props.set(index, replacement);
        return props;
    }

    private static void assertStartupFails(List<String> props, String expectedReasonFragment) {
        new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withPropertyValues(props.toArray(String[]::new))
                .run(context -> {
                    assertThat(context)
                            .as("非法配置必须让上下文起不来（静默绑默认值是最危险的失败模式）")
                            .hasFailed();
                    assertThat(causeChain(context))
                            .as("失败原因必须指向真正出错的那个键 —— 只断「失败了」会让任何启动错误都通过")
                            .contains(expectedReasonFragment);
                });
    }

    /** 异常链上每一环的 toString（含消息），用于断言"为什么失败"。 */
    private static String causeChain(AssertableApplicationContext context) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
            chain.append(t).append('\n');
        }
        return chain.toString();
    }

    @Test
    @DisplayName("★ 对照：同一份夹具、同样的键，值合法 ⇒ 必须起得来（否则上面那些「起不来」是假绿）")
    void 合法配置必须能启动() {
        new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withPropertyValues(validConfig().toArray(String[]::new))
                .run(context -> {
                    assertThat(context).as("合法配置不该让上下文起不来").hasNotFailed();
                    assertThat(context.getBean(RateLimitProperties.class).cost().base())
                            .as("★ 方括号写法必须真的绑出「POST /favorite/add」，而不是被静默改写成 POSTfavoriteadd")
                            .containsKey("POST /favorite/add");
                });
    }

    @Test
    @DisplayName("capacity = 0 ⇒ 起不来（原生 int 键名写错只会绑成 0，必须响亮失败）")
    void 容量为零必须被拒() {
        assertStartupFails(validConfigWith(0, "video.rate-limit.server.capacity=0"),
                "video.rate-limit.server.capacity");
    }

    @Test
    @DisplayName("任务单价 = 0 ⇒ 起不来")
    void 任务单价为零必须被拒() {
        assertStartupFails(validConfigWith(2, "video.rate-limit.server.task-cost=0"),
                "video.rate-limit.server.task-cost");
    }

    @Test
    @DisplayName("兜底权重 = 0 ⇒ 起不来（0 会把「未登记路由」变成不限流）")
    void 兜底权重为零必须被拒() {
        assertStartupFails(validConfigWith(7, "video.rate-limit.cost.default-base=0"),
                "video.rate-limit.cost.default-base");
    }

    @Test
    @DisplayName("miss-multiplier < 1 ⇒ 起不来（小于 1 等于「miss 更便宜」，与信号②语义相反）")
    void 命中系数小于一必须被拒() {
        assertStartupFails(validConfigWith(8, "video.rate-limit.cost.miss-multiplier=0.5"),
                "miss-multiplier");
    }

    @Test
    @DisplayName("reject-discount 越界（1.5 / 负数）⇒ 起不来")
    void 被拒折扣越界必须被拒() {
        assertStartupFails(validConfigWith(9, "video.rate-limit.cost.reject-discount=1.5"),
                "reject-discount");
        assertStartupFails(validConfigWith(9, "video.rate-limit.cost.reject-discount=-0.1"),
                "reject-discount");
    }

    @Test
    @DisplayName("★ 权重表键漏写方括号 ⇒ 起不来（静默改写 POST /x → POSTx 必须变成响亮失败）")
    void 权重表键漏方括号必须被拒() {
        assertStartupFails(validConfigWith(10, "video.rate-limit.cost.base.POST /favorite/add=3"),
                "video.rate-limit.cost.base");
    }

    @Test
    @DisplayName("权重表某条权重 = 0 ⇒ 起不来（0 等于对该端点不限流）")
    void 权重为零必须被拒() {
        assertStartupFails(validConfigWith(10, "video.rate-limit.cost.base.[POST /favorite/add]=0"),
                "video.rate-limit.cost.base");
    }

    @Test
    @DisplayName("整段缺失（连 server 都没有）⇒ 起不来")
    void 整段缺失必须被拒() {
        assertStartupFails(List.of(), "video.rate-limit.server");
    }

    @Test
    @DisplayName("嵌套段缺失（user.authed 缺一个键）⇒ 起不来，且指向缺失的那一段")
    void 嵌套段缺失必须被拒() {
        List<String> props = validConfig();
        props.removeIf(p -> p.startsWith("video.rate-limit.user.authed."));
        assertStartupFails(props, "video.rate-limit.user.authed");
    }

    @Test
    @DisplayName("类型不匹配（capacity 给非数字）⇒ 起不来")
    void 类型不匹配必须被拒() {
        assertStartupFails(validConfigPlus("video.rate-limit.server.capacity=abc"),
                "video.rate-limit.server.capacity");
    }
}
