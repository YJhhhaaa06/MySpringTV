package io.github.yjhhhaaa06.videoweb.common.cache;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 缓存值的 JSON 编解码 —— 承接 TV {@code cache.JacksonCodec}。
 *
 * <h2>为什么不用 TV 的 Jackson 2 写法</h2>
 * 本项目是 Boot 4 / **Jackson 3**，包名已迁到 {@code tools.jackson.*}（坑 1：注解仍在
 * {@code com.fasterxml.jackson.annotation}，但 {@code ObjectMapper} 在
 * {@code tools.jackson.databind}）。{@link ObjectMapper} 注入 Boot 自动配置的那个实例
 * （而不是 {@code new ObjectMapper()}）——这样与 MVC 的 JSON 序列化口径一致，
 * 且 JavaTime 支持随 jackson-databind 3 内置（{@code tools.jackson.databind.ext.javatime}），
 * 无需额外注册模块。
 *
 * <h2>★ 编解码失败 = 缓存不可用（有意）</h2>
 * TV 的 {@code JacksonCodec} 把编解码异常包成 {@code CacheException}；本实现包成
 * {@link CacheUnavailableException}。语义是刻意的：**脏 JSON / 无法序列化**意味着"这份缓存不可信"，
 * 上层应把它当作缓存 miss / 失效处理（回源 DB 或删 key），而**不是**当成业务错误上抛。
 * 于是"Redis 里存了一份坏值"永远不可能让接口 500 —— 这正是"缓存只是加速器"的落实。
 */
@Component
public class JsonCodec {

    private final ObjectMapper mapper;

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            throw new CacheUnavailableException("缓存值序列化失败: " + e.getMessage(), e);
        }
    }

    public <T> T fromJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (RuntimeException e) {
            throw new CacheUnavailableException("缓存值反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 泛型容器版本（如 {@code TypeReference<List<CommentCacheDTO>>}）。
     *
     * <p>为什么要它：{@code Class} 版本拿不到泛型参数，楼中楼的 children 列表
     * （{@code List<CommentCacheDTO>}）只能靠 {@code TypeReference} 才能正确反序列化。
     */
    public <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (RuntimeException e) {
            throw new CacheUnavailableException("缓存值反序列化失败: " + e.getMessage(), e);
        }
    }
}
