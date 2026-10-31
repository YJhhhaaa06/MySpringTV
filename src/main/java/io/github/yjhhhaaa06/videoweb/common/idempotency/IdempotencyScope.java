package io.github.yjhhhaaa06.videoweb.common.idempotency;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 写接口幂等的**端点注册表**（口径的唯一事实源 =
 * {@code architecture/tech/idempotency/写接口幂等.md} §八）。
 *
 * <h2>键形态：{@code "<HTTP 方法> <路由模式>"}</h2>
 * 与限流权重表同形，路径取
 * {@link org.springframework.web.servlet.HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE} 命中的
 * <b>路由模式</b>（如 {@code POST /api/v1/contents/{id}/coins}），**不是原始 URI** ——
 * 用原始 URI 查表时带 {@code {id}} 的端点会静默落"未登记"（限流那张表踩过这个坑）。
 *
 * <h2>★ 为什么表在代码里而不在 yaml</h2>
 * 限流的权重是**调参**（运行时改有意义）；幂等类别是**契约**（改了必须走 review + 编译 + diff，
 * 改一条登记不留任何信号就是错的介质）。
 *
 * <h2>★ 为什么必须一次填满全部非 GET 端点</h2>
 * 完整性由 {@code IdempotencyScopeTableTests} 双向钉住（handler 有、表里无 ⇒ {@code missing} 红；
 * 表里有、handler 无 ⇒ {@code stale} 红）。只登记"要生效的几条"会让登记表退化成
 * "谁记得谁写"——那些没登记的行既没有 <i>不幂等</i> 的结论，也没有 <i>幂等</i> 的结论。
 *
 * <p>★ {@link Scope#NATURAL} <b>没有第三档</b>：判据是
 * "<b>重试不改变 DB 终态，且响应可判</b>"。运行期只查 {@link Scope#KEYED}
 * ——{@code NATURAL} 条目**只服务完整性测试**（"我们确实看过这个端点"这行登记本身就是 review 的锚点）。
 *
 * <h2>备注纪律</h2>
 * 逐条写理由等于没写（二十多条，读的人会自动跳过）⇒ <b>只标灰色地带</b>
 * （"判据大体成立但有一条边是糊的"），其余条目留空。
 */
public final class IdempotencyScope {

    /** 端点的幂等类别。 */
    public enum Scope {
        /** 效果层非幂等、或响应层不可判 ⇒ 由过滤器做"占位 + 响应快照"。 */
        KEYED,
        /** 重试不改变 DB 终态且响应可判 ⇒ 不接线（只做登记）。 */
        NATURAL
    }

    /**
     * 一条登记。
     *
     * @param scope             类别
     * @param slug              写入 Redis key 的短标识（{@code idem:{slug}:{uid}:{key}}），仅 {@code KEYED} 有
     * @param pendingTtlSeconds 占位（{@code PENDING}）的 TTL。<b>跟着注册表走、不进配置</b>：
     *                          它是"该端点业务耗时"的属性（{@link #startEntry} 的注释）
     */
    public record Entry(Scope scope, String slug, int pendingTtlSeconds) {

        public Entry {
            if (scope == null) {
                throw new IllegalArgumentException("幂等登记缺少 scope");
            }
            if (scope == Scope.KEYED) {
                if (slug == null || !slug.matches("[a-z][a-z0-9]{0,15}")) {
                    // slug 会进 Redis key（`idem:{slug}:...`）⇒ 禁止 ':' 与 '/'，否则 key 边界不可解析
                    throw new IllegalArgumentException(
                            "KEYED 条目的 slug 必须是 [a-z][a-z0-9]{0,15}（会进 Redis key）：[" + slug + "]");
                }
                if (pendingTtlSeconds <= 0) {
                    throw new IllegalArgumentException(
                            "KEYED 条目的 pendingTtlSeconds 必须为正：[" + slug + "=" + pendingTtlSeconds + "]");
                }
            } else if (slug != null || pendingTtlSeconds != 0) {
                throw new IllegalArgumentException(
                        "NATURAL 条目不写 slug / TTL（它们只对 KEYED 有意义）：slug=[" + slug
                                + "] pendingTtlSeconds=" + pendingTtlSeconds);
            }
        }
    }

    /**
     * 全部非 GET 端点（当前 42 条：POST 15 / PUT 16 / DELETE 11）。
     *
     * <p>⚠️ 增删端点必须同步改这里，否则 {@code IdempotencyScopeTableTests} 红。
     * {@code Map.of} 只到 10 对 ⇒ 用 {@link Map#ofEntries}。
     */
    private static final Map<String, Entry> TABLE = Map.ofEntries(
            // ========================================================================
            // KEYED：效果层非幂等、客户端又必须知道"成没成 / 成的是哪一条"（写接口幂等.md §三）
            // ========================================================================
            entry("POST /api/v1/comments", keyed("comment", 30)),
            // JSON 清单创建：同一意图重试只建一条内容；载荷指纹（JSON body 级）保证换内容 ⇒ 422。
            entry("POST /api/v1/contents", keyed("content", 120)),
            entry("POST /api/v1/contents/videos", keyed("video", 120)),
            entry("POST /api/v1/contents/posts", keyed("post", 120)),
            entry("POST /api/v1/contents/{id}/coins", keyed("coin", 30)),

            // ========================================================================
            // NATURAL：重试不改变 DB 终态，且响应可判（备注只标灰色地带）
            // ========================================================================
            entry("POST /api/v1/admin/media/scan", natural()),
            // 灰色地带：重试会再落一份**未被引用**的文件（孤儿），DB 终态完全不变；
            // 客户端应把重试视为"换一个 URL"，而不是"同一次上传的幂等重放"。
            entry("POST /api/v1/uploads/{type}", natural()),
            entry("POST /api/v1/contents/{id}/play", natural()),
            entry("POST /api/v1/coupons/{id}/claims", natural()),   // 重复领券由 CouponService 回查订单返原券码（结构性修复）
            // 灰色地带：重名合法（业务规则），重试会**多建一个夹**；写接口幂等.md §三 明确不在覆盖范围。
            entry("POST /api/v1/favorite-folders", natural()),
            entry("POST /api/v1/favorites", natural()),             // 重复收藏撞 uk_folder_content ⇒ 幂等终态
            entry("POST /api/v1/history/flush", natural()),
            entry("POST /api/v1/sessions", natural()),
            // 灰色地带：重试撞 uk_phone ⇒ 409，效果层幂等但响应层只说"手机号已被使用"（前端不得自动重试）。
            entry("POST /api/v1/users", natural()),
            entry("POST /api/v1/users/me/check-ins", natural()),

            entry("PUT /api/v1/admin/contents/{id}/hidden", natural()),
            entry("PUT /api/v1/admin/media/{id}", natural()),
            entry("PUT /api/v1/comments/{id}/like", natural()),
            entry("PUT /api/v1/contents/{id}", natural()),          // 灰色地带：重试会刷新 update_time（内容不变）
            entry("PUT /api/v1/contents/{id}/comment-enabled", natural()),
            entry("PUT /api/v1/contents/{id}/like", natural()),
            // 灰色地带：重试会再落一份媒体文件并清理旧份（DB 终态仍是单份）。
            entry("PUT /api/v1/contents/{id}/media", natural()),
            // 灰色地带：LWW —— 旧请求晚到会把进度回退（上报缓冲按到达顺序覆盖）。
            entry("PUT /api/v1/contents/{id}/progress", natural()),
            entry("PUT /api/v1/favorite-folders/{id}", natural()),
            entry("PUT /api/v1/favorites/{id}", natural()),
            entry("PUT /api/v1/follows/{id}", natural()),
            // 灰色地带：重试时"原密码"已不再是原密码 ⇒ 401；效果层幂等、响应层不可判（前端不得自动重试）。
            entry("PUT /api/v1/users/me/password", natural()),
            entry("PUT /api/v1/users/me/username", natural()),
            // 记录开关（R-25）：单列整体替换，同值重写结果不变 ⇒ 天然幂等（重试安全）。
            entry("PUT /api/v1/users/me/history-recording", natural()),
            entry("PUT /api/v1/watch-later/{id}", natural()),
            entry("PUT /api/v1/watch-later/{id}/folder", natural()),

            entry("DELETE /api/v1/admin/comments/{id}", natural()),
            entry("DELETE /api/v1/admin/contents/{id}/hidden", natural()),
            entry("DELETE /api/v1/comments/{id}", natural()),
            entry("DELETE /api/v1/comments/{id}/like", natural()),
            entry("DELETE /api/v1/contents/{id}", natural()),
            entry("DELETE /api/v1/contents/{id}/like", natural()),
            entry("DELETE /api/v1/contents/{id}/media", natural()),
            entry("DELETE /api/v1/favorite-folders/{id}", natural()),
            entry("DELETE /api/v1/favorites", natural()),
            entry("DELETE /api/v1/follows/{id}", natural()),
            entry("DELETE /api/v1/watch-later", natural())
    );

    static {
        // 同 slug 的两个端点会共用 key 空间（key = idem:{slug}:{uid}:{key}）⇒ 静默串味。
        // 这是编程错误，类初始化时响亮失败。
        Set<String> slugs = new HashSet<>();
        TABLE.forEach((route, entry) -> {
            if (entry.scope() == Scope.KEYED && !slugs.add(entry.slug())) {
                throw new IllegalStateException("幂等注册表里 slug 重复（会共用 Redis key 空间）："
                        + entry.slug() + " @" + route);
            }
        });
    }

    private IdempotencyScope() {
    }

    /**
     * 按「方法 空格 路由模式」查一条登记。
     *
     * @return 未登记 / 非 {@code KEYED} 时返回 {@code null}（调用方据此放行，不查第二次）
     */
    public static Entry find(String method, String pattern) {
        if (method == null || pattern == null) {
            return null;
        }
        return TABLE.get(method + " " + pattern);
    }

    /** 全表只读视图（完整性测试用；运行期只走 {@link #find}）。 */
    public static Map<String, Entry> table() {
        return Collections.unmodifiableMap(TABLE);
    }

    private static Map.Entry<String, Entry> entry(String route, Entry entry) {
        return Map.entry(route, entry);
    }

    private static Entry keyed(String slug, int pendingTtlSeconds) {
        return new Entry(Scope.KEYED, slug, pendingTtlSeconds);
    }

    private static Entry natural() {
        return new Entry(Scope.NATURAL, null, 0);
    }
}
