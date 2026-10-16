package io.github.yjhhhaaa06.videoweb.config;

import io.github.yjhhhaaa06.videoweb.common.config.RateLimitProperties;
import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code video.rate-limit.*} 配置的**绑定契约**测试（限流专项 T23）。
 *
 * <h2>为什么必须有这一条</h2>
 * 限流的口径全在配置里（本仓无真实流量 ⇒ 数值是"拍的"，靠配置可替换）。
 * 而 {@code @ConfigurationProperties} 的**原生类型绑定对键名错误不报错、只给默认值**
 * （同族坑见 {@link JwtPropertiesBindingTests} 的 Duration 裸数字、
 * {@link FavoriteLimitPropertiesBindingTests} 的原生 int）。
 * 更重要的是：本表有一个**实测到的静默陷阱** —— Spring 绑 {@code Map} 时会**删掉键里
 * 除小写字母数字与 {@code -} 之外的字符**，于是 YAML 里若忘了用方括号括键，
 * {@code POST /favorite/add} 会**静默变成** {@code POSTfavoriteadd}（无任何报错，
 * 只是那条权重永远命不中、全落 {@code default-base}）。
 * 故这里把"键形态"与"表非空"一起钉住。
 *
 * <p>⚠️ 判断口径（防迎合实现）：下面每条断言都能**先于实现**写出 ——
 * 期望值出自限流的口径（"匿名桶必须比登录桶宽""权重按是否触达 DB 写排序""被拒不能免费探路"），
 * **不是**抄 {@code application.yaml} 里的数字。因此**改数值不必改本用例**，只有**改口径**才要改。
 * 唯一被钉死的数值是 {@code miss-multiplier == 1.0}（它的含义是"信号②默认关闭"，属口径而非调参）。
 *
 * <p>非法值"是否真的让**上下文起不来**"由
 * {@link RateLimitPropertiesStartupFailureTests} 单独证明（这里用真上下文，只证明绑上了什么）。
 */
class RateLimitPropertiesBindingTests extends AbstractIntegrationTest {

    @Autowired
    private RateLimitProperties limits;

    @Test
    @DisplayName("三段嵌套都绑上了 —— 一个 record 里的嵌套 record 不是静默 null")
    void 三段嵌套都绑上() {
        // ★ 这条同时是"嵌套 record 可绑"的**反向证据**：若嵌套被静默忽略，这里必红。
        assertThat(limits.server()).as("video.rate-limit.server").isNotNull();
        assertThat(limits.user()).as("video.rate-limit.user").isNotNull();
        assertThat(limits.cost()).as("video.rate-limit.cost").isNotNull();
        assertThat(limits.user().authed()).as("video.rate-limit.user.authed").isNotNull();
        assertThat(limits.user().anon()).as("video.rate-limit.user.anon").isNotNull();
    }

    @Test
    @DisplayName("桶参数按设计锚点成立：为正、refill ≤ capacity、匿名桶比登录桶宽（IP 共享 ⇒ 更宽）")
    void 桶参数按设计锚点成立() {
        RateLimitProperties.Server server = limits.server();
        assertThat(server.capacity()).as("服务器桶容量（瞬时在途上限）").isPositive();
        assertThat(server.taskCost()).as("任务自付费单价（锚点：一次重建 ≈ 十次普通读）").isPositive();
        assertThat(server.refillPerSec())
                .as("服务器桶补充速率必须为正，且不得超过容量（否则桶 1 秒内补满，速率这层限制名存实亡）")
                .isPositive()
                .isLessThanOrEqualTo(server.capacity());

        RateLimitProperties.Bucket authed = limits.user().authed();
        RateLimitProperties.Bucket anon = limits.user().anon();
        assertThat(authed.capacity()).as("登录桶容量").isPositive();
        assertThat(authed.refillPerSec()).as("登录桶补充速率").isPositive().isLessThanOrEqualTo(authed.capacity());
        assertThat(anon.capacity()).as("匿名桶容量").isPositive();
        assertThat(anon.refillPerSec()).as("匿名桶补充速率").isPositive().isLessThanOrEqualTo(anon.capacity());

        // ★ 口径（不是调参）：匿名 key 是**共享的 IP**（NAT / 内网下一片人共用一个出口）
        //   ⇒ 匿名桶必须比登录桶宽，否则会误伤整片人。反过来调（匿名更窄）必须让本用例红。
        assertThat(anon.capacity())
                .as("匿名桶必须宽于登录桶（IP 共享）")
                .isGreaterThanOrEqualTo(authed.capacity());
        assertThat(anon.refillPerSec())
                .as("匿名桶补充速率必须不低于登录桶")
                .isGreaterThanOrEqualTo(authed.refillPerSec());
    }

    @Test
    @DisplayName("权重表键保持「方法 空格 路径」原形 —— 漏写方括号会被静默改写（POST /x → POSTx）")
    void 权重表键保持方法空格路径原形() {
        Map<String, Integer> base = limits.cost().base();

        assertThat(base)
                .as("权重表为空 ⇒ 多半是整段键名写错 / 前缀写错（空表本身合法，但不该是当前状态）")
                .isNotEmpty();
        assertThat(base)
                .as("设计稿点名的示例路由必须在表里（键名逐字一致）")
                .containsKey("POST /favorite/add");
        assertThat(base.keySet())
                .as("每个 key 都必须是「<方法> <以 / 开头的路径模式>」形态；"
                        + "缺了空格 = 方括号漏写、键被 Spring 静默改写（实测 POST /favorite/add → POSTfavoriteadd）")
                .allSatisfy(key -> assertThat(key).contains(" /"));
        assertThat(base.values())
                .as("每条权重必须为正：0 等于对该端点不限流")
                .allSatisfy(weight -> assertThat(weight).isPositive());
    }

    @Test
    @DisplayName("权重按「是否触达 DB 写 + 是否重活」排序 —— 登录/上传 > 写路径 > 兜底")
    void 权重按重活程度排序() {
        Map<String, Integer> base = limits.cost().base();
        int writePath = base.get("POST /favorite/add");

        assertThat(base.get("POST /api/upload/video"))
                .as("上传是重活（落盘 + 转码排队），必须重于普通写路径")
                .isGreaterThan(writePath);
        assertThat(base.get("POST /user/login"))
                .as("登录要算密码哈希，必须重于普通写路径")
                .isGreaterThan(writePath);
        assertThat(writePath)
                .as("写路径必须重于未登记路由的兜底权重")
                .isGreaterThan(limits.cost().defaultBase());
        assertThat(limits.cost().defaultBase())
                .as("兜底权重必须为正数：★ 不是 0 —— 「漏配 = 不限」正是要防的")
                .isPositive();
    }

    @Test
    @DisplayName("被拒折扣必须落在开区间 (0,1) —— 0 = 被拒免费探路，1 = 用户全额买单")
    void 被拒折扣落在开区间() {
        assertThat(limits.cost().rejectDiscount())
                .as("两端都是设计明确不取的边界：0 让刷子零成本试探、1 让用户为服务器过载全额买单")
                .isGreaterThan(0.0)
                .isLessThan(1.0);
    }

    @Test
    @DisplayName("信号②（缓存命中系数）默认关闭：1.0 表示不放大成本")
    void 缓存命中系数默认关闭() {
        assertThat(limits.cost().missMultiplier())
                .as("1.0 = 关闭（沿用本仓「0/1 = 关闭」惯例）；要启用必须显式给 >1 并同步改本用例")
                .isEqualTo(1.0);
    }
}
