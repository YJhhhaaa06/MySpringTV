package io.github.yjhhhaaa06.videoweb.user.service;

import java.util.Collection;
import java.util.Map;

/**
 * 头像 URL 的**跨域读契约**（`R-37`）。
 *
 * <p>content / comment / feed 只依赖本接口，不依赖缓存实现（TTL / 淘汰 / 负缓存 / 写纪元
 * 都是 {@link AvatarUrlResolver} 的内部细节）。这样规则 2 的**语义**也守住：
 * 别的域拿到的是"一个能解析头像的 Service 契约"，不是"user 域的缓存"。
 *
 * <p>实现可以换（本地 Map → Redis + 失效广播 → 版本列），调用方一行不用改。
 */
public interface AvatarReadModel {

    /**
     * 批量解析生效头像 URL。
     *
     * @param ids 任意 id 集合（null / 非正数被忽略）
     * @return 对每个合法 id 都有条目（查无此人按 NULL 回落默认）；非法 id 不出现在结果里
     */
    Map<Long, String> resolveBatch(Collection<Long> ids);

    /** 单条解析；对合法用户恒返回非空 URL。 */
    String resolve(long userId);
}
