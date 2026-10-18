package io.github.yjhhhaaa06.videoweb.config;

import io.github.yjhhhaaa06.videoweb.common.config.FavoriteCacheProperties;
import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T11 新增配置 {@code video.cache.favorite-ttl} 的**绑定契约**测试
 * （同 {@link FollowPropertiesBindingTests} / {@link JwtPropertiesBindingTests} 的坑）。
 *
 * <h2>为什么必须有这一条</h2>
 * 绑定目标是 {@link Duration}：YAML 里写裸数字（{@code favorite-ttl: 15}）会被解析成
 * <b>{@code PT0.015S}</b>（15 毫秒）而不是 15 分钟 —— **不报任何配置错误**，
 * 只表现为"收藏缓存命中率莫名很低"（缓存几乎立刻失效）。
 * 同款坑在本项目已出现三次（{@code jwt.expire-hours}、{@code cache.like-ttl}、{@code cache.follow-ttl}），
 * 故对新键再钉一次。
 *
 * <p>另：{@link FavoriteCacheProperties} 的构造器在**绑定期**校验"必须为正"，非法值会让上下文
 * 启动失败 —— 这里只断言合法默认值能被正确绑定。
 */
class FavoriteCachePropertiesBindingTests extends AbstractIntegrationTest {

    @Autowired
    private FavoriteCacheProperties favoriteCacheProperties;

    @Test
    @DisplayName("favorite-ttl 必须绑定为**分钟级**时长，不得退化为毫秒级（裸数字会解析成 PT0.015S）")
    void 收藏缓存TTL为分钟级而非秒级() {
        Duration ttl = favoriteCacheProperties.favoriteTtl();

        assertThat(ttl)
                .as("配置写裸数字会被解析为 PT0.015S（15 毫秒）——必须带 m 后缀")
                .isGreaterThanOrEqualTo(Duration.ofMinutes(5));
        assertThat(ttl)
                .as("刻意与 like 同档（15m）：两域是同一类缓存形态，且不应对齐 follow/content 的 30m")
                .isLessThanOrEqualTo(Duration.ofHours(2));
    }
}
