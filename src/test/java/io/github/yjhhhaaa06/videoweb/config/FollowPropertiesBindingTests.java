package io.github.yjhhhaaa06.videoweb.config;

import io.github.yjhhhaaa06.videoweb.common.config.BigVProperties;
import io.github.yjhhhaaa06.videoweb.common.config.FollowCacheProperties;
import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4 新增两处配置的**绑定契约**测试（决策表 F-6 / F-7 的配置面）。
 *
 * <h2>为什么必须有 follow-ttl 这一条</h2>
 * 与 {@link JwtPropertiesBindingTests} 同一个坑，而且**更隐蔽**：{@code video.cache.follow-ttl} 的
 * 绑定目标也是 {@link Duration}。YAML 里写裸数字（{@code follow-ttl: 30}）会被解析成
 * <b>{@code PT30S}</b>（30 秒）而不是 30 分钟——**不报任何配置错误**，
 * 只表现为"关注列表缓存命中率莫名很低"（缓存几乎立刻失效）。
 *
 * <p>同款坑在本项目已出现两次（`jwt.expire-hours`、`cache.like-ttl`），故这里对新键再钉一次。
 *
 * <h2>为什么也要钉 bigv 的默认值</h2>
 * {@code video.bigv.threshold} 默认 10000 是**行为参数**：改小会让普通用户被误判成"自动大V"
 * （进而影响 feed 的写扩散策略）。它是从 TV {@code app.properties} 抄来的数值，
 * 抄错一位不会报错、只会在 feed 上表现为"人人都是大V"。故断言原值。
 *
 * <p>{@link BigVProperties} 的区间校验（ratio ∈ (0,1]、threshold &gt; 0）在**绑定期**生效，
 * 非法值会让上下文启动失败——这里只断言合法默认值能被正确绑定。
 */
class FollowPropertiesBindingTests extends AbstractIntegrationTest {

    @Autowired
    private FollowCacheProperties followCacheProperties;

    @Autowired
    private BigVProperties bigVProperties;

    @Test
    @DisplayName("follow-ttl 必须绑定为**分钟级**时长，不得退化为秒级（裸数字会解析成 PT30S）")
    void 关注缓存TTL为分钟级而非秒级() {
        Duration ttl = followCacheProperties.followTtl();

        assertThat(ttl)
                .as("配置写裸数字会被解析为 PT30S（30 秒）——必须带 m 后缀")
                .isGreaterThanOrEqualTo(Duration.ofMinutes(5));
        assertThat(ttl)
                .as("TV 原值为 30 分钟，允许覆盖但不应偏离量级")
                .isLessThanOrEqualTo(Duration.ofHours(2));
    }

    @Test
    @DisplayName("bigv 阈值与滞回系数绑定为 TV 原值（10000 / 0.8），且 ratio 在合法区间")
    void 大V参数绑定为TV默认值() {
        assertThat(bigVProperties.threshold())
                .as("TV app.properties 的 feed.bigv.threshold")
                .isEqualTo(10000);
        assertThat(bigVProperties.downgradeRatio())
                .as("TV app.properties 的 feed.bigv.downgradeRatio")
                .isEqualTo(0.8);
        assertThat(bigVProperties.downgradeRatio())
                .as("滞回系数必须在 (0,1]（>1 会让降级线高于升级线，滞回反向失效）")
                .isGreaterThan(0.0)
                .isLessThanOrEqualTo(1.0);
    }
}
