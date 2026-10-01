package io.github.yjhhhaaa06.videoweb.config;

import io.github.yjhhhaaa06.videoweb.common.config.JwtProperties;
import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWT 配置绑定的防回归测试。
 *
 * <h2>为什么必须有这个测试</h2>
 * {@code video.jwt.expire-hours} 的绑定目标是 {@link Duration}。
 * 如果 YAML 里写成**裸数字**（{@code expire-hours: 2}），Spring 会把它当作
 * "2 个单位、无单位"，解析成 {@code PT2S}——也就是 **2 秒**，而不是预期的 2 小时。
 *
 * <p>这个错误**不会报任何配置错误**：应用正常启动，登录也正常返回 200，
 * 但签发的 token 在 2 秒后即过期。现象是"刚登录就 401"，
 * 排查时极易怀疑 JWT 签名/过滤器逻辑，而真凶在配置单位的静默误解析。
 *
 * <p>切片 0 实际踩到了这个坑（token 的 iat 与 exp 完全相同）。本测试把
 * "有效期必须是人级别的时间量"钉成断言，防止再次复发。
 */
class JwtPropertiesBindingTests extends AbstractIntegrationTest {

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    @DisplayName("expire-hours 必须绑定为小时级时长，不得退化为秒级（裸数字会解析成 PT2S）")
    void 有效期为小时级而非秒级() {
        Duration expire = jwtProperties.expireHours();

        assertThat(expire)
                .as("配置写裸数字会被解析为 PT2S（2 秒）——必须带 h 后缀")
                .isGreaterThanOrEqualTo(Duration.ofMinutes(30));
        assertThat(expire)
                .as("TV 原值为 2 小时，允许覆盖但不应偏离量级")
                .isLessThanOrEqualTo(Duration.ofDays(1));
    }

    @Test
    @DisplayName("secret 不得为空")
    void secret已配置() {
        assertThat(jwtProperties.secret()).isNotBlank();
    }
}
