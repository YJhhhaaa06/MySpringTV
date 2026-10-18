package io.github.yjhhhaaa06.videoweb.config;

import io.github.yjhhhaaa06.videoweb.common.config.JwtProperties;
import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWT 配置绑定的防回归测试。
 *
 * <h2>为什么必须有这个测试</h2>
 * {@code video.jwt.expire-hours} 的绑定目标是 {@link Duration}。
 * 如果 YAML 里写成**裸数字**（{@code expire-hours: 2}），Spring 会把它当作
 * **"2 个毫秒"**（{@code DurationStyle} 在无后缀时的默认单位是 **MILLIS**），
 * 即 {@code PT0.002S}，而不是预期的 2 小时。
 *
 * <p>⚠️ **2026-10-18 更正**：本注释与 {@code application.yaml} 曾写"解析成 {@code PT2S}——2 秒"，
 * 实测（{@code ApplicationConversionService.getSharedInstance().convert("2", Duration.class)}
 * → {@code PT0.002S}）证明真实单位是**毫秒**。结论不变（都是"刚登录就 401"），只是差三个数量级；
 * 已把这条"传说"钉成下面的 {@code 裸数字按毫秒解析}，免得下次又靠记忆。
 *
 * <p>这个错误**不会报任何配置错误**：应用正常启动，登录也正常返回 200，
 * 但签发的 token 在毫秒级后就过期。现象是"刚登录就 401"，
 * 排查时极易怀疑 JWT 签名/过滤器逻辑，而真凶在配置单位的静默误解析。
 *
 * <p>切片 0 实际踩到了这个坑（token 的 iat 与 exp 完全相同）。本测试把
 * "有效期必须是人级别的时间量"钉成断言，防止再次复发。
 */
class JwtPropertiesBindingTests extends AbstractIntegrationTest {

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    @DisplayName("expire-hours 必须绑定为小时级时长，不得退化为毫秒/秒级（裸数字会变成 PT0.002S）")
    void 有效期为小时级而非秒级() {
        Duration expire = jwtProperties.expireHours();

        assertThat(expire)
                .as("配置写裸数字会被解析为 PT0.002S（2 毫秒）——必须带 h 后缀")
                .isGreaterThanOrEqualTo(Duration.ofMinutes(30));
        assertThat(expire)
                .as("TV 原值为 2 小时，允许覆盖但不应偏离量级")
                .isLessThanOrEqualTo(Duration.ofDays(1));
    }

    @Test
    @DisplayName("★裸数字的真实单位是毫秒（PT0.002S）：把'裸数字会被当成几秒'的传说钉成可复算事实")
    void 裸数字按毫秒解析() {
        MockEnvironment env = new MockEnvironment().withProperty("probe.duration", "2");

        Duration bound = Binder.get(env).bind("probe.duration", Duration.class).get();

        // 判别力：这条断言的是**框架行为**，不是本仓实现 —— 它存在的意义是给
        // application.yaml / 安全与鉴权.md 里那句警告提供一条可复算的依据
        // （不是"PT2S（2 秒）"，而是 PT0.002S（2 毫秒））。
        assertThat(bound)
                .as("无后缀 ⇒ 默认单位 MILLIS（对齐 java.util.concurrent.TimeUnit 的语义）")
                .isEqualTo(Duration.ofMillis(2));
    }

    @Test
    @DisplayName("secret 不得为空")
    void secret已配置() {
        assertThat(jwtProperties.secret()).isNotBlank();
    }
}
