package io.github.yjhhhaaa06.videoweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 应用入口。
 *
 * <p>{@code @ConfigurationPropertiesScan} 扫描 {@code common.config} 下的配置记录
 * （如 {@link io.github.yjhhhaaa06.videoweb.common.config.JwtProperties}），
 * 替代 TV 的 {@code AppConfig} 静态读取 + 多级覆盖链（决策⑨）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MySpringTvApplication {

    public static void main(String[] args) {
        SpringApplication.run(MySpringTvApplication.class, args);
    }

}
