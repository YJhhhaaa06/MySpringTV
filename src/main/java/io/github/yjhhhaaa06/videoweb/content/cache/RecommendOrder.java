package io.github.yjhhhaaa06.videoweb.content.cache;

/**
 * 推荐序：把"一次会话的随机排列"定义成 <b>(seed, contentId) 的纯函数</b>，外加游标的编解码。
 *
 * <h2>为什么排序键必须是纯函数</h2>
 * 翻页要求"第 2 页接着第 1 页"。若每次请求各自 {@code shuffle}，两页就是两个独立排列
 * （重叠期望 {@code k²/N}）；而只要排序键是 {@code (seed, id)} 的纯函数，同一 seed 就重建同一个排列，
 * 序与"这次请求恰好取到哪几条"无关。这也让游标可以只带 {@code (seed, lastId)} 两个数。
 *
 * <h2>为什么是 splitmix64 finalizer</h2>
 * <ol>
 *   <li><b>纯算术</b>：只用 {@code long} 运算 ⇒ 跨 JVM / 跨进程 / 跨版本一致。
 *       {@code Object.hashCode} / {@code Random} / {@code ThreadLocalRandom} 的实现细节
 *       **不构成契约**，不能当排序键（换 JDK 就可能换序，游标会静默错位）。</li>
 *   <li><b>双射</b>：三步都可逆（异或右移是三角可逆变换、乘数是奇数）⇒ {@code mix64} 在 64 位上
 *       一一对应 ⇒ {@code id ↦ rank} 单射 ⇒ **同一池内不会并列**，不需要 id 兜底排序。</li>
 * </ol>
 * ⚠️ 换算法 = 换排列：必须同时把 {@link #VERSION} 递增，让旧游标降级为第一页。
 *
 * <h2>游标形态：{@code v1.<seed>.<lastId>}</h2>
 * 三段十进制，**不签名**。它由客户端原样回传，可被伪造 —— 伪造的后果只是"另一个随机序的某个位置"，
 * 不越权、不泄露、成本与普通请求相同。解析失败（少段 / 非数字 / 版本不认识 / 非正数）
 * 一律返回 {@code null}，由调用方当作"没传游标"处理（**静默归一**，不返 400）。
 *
 * <p>★ 游标锚在 <b>id</b> 上而不是下标上：{@link #rank} 只依赖 id 本身，所以上页末条
 * **即使已被删除**，游标照样精确 —— 服务端不需要那条内容存在。
 */
public final class RecommendOrder {

    /** 游标格式版本。换排序算法时必须递增（旧游标随之降级为第一页）。 */
    private static final String VERSION = "v1";

    private static final char SEPARATOR = '.';

    private RecommendOrder() {
    }

    /**
     * 排序键：{@code mix64(seed ^ mix64(contentId))}，**降序**即推荐序。
     *
     * <p>对固定的 seed，{@code id ↦ rank} 是单射（见类注释）——同池内两条内容不会同 rank。
     */
    public static long rank(long seed, long contentId) {
        return mix64(seed ^ mix64(contentId));
    }

    /** splitmix64 finalizer（三步皆 64 位双射）。 */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** 游标的两段语义：用哪个排列（{@code seed}）、从哪个 id 之后继续（{@code lastId}）。 */
    public record Cursor(long seed, long lastId) {
    }

    /** 编码：{@code v1.<seed>.<lastId>}。只接受正数（id 与 seed 都从 1 起）。 */
    public static String encode(long seed, long lastId) {
        return VERSION + SEPARATOR + seed + SEPARATOR + lastId;
    }

    /**
     * 解码，**不抛异常**。
     *
     * @return {@code null} = 没传 / 不合法（调用方按"第一页"处理）
     */
    public static Cursor decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            return null;
        }
        long seed;
        long lastId;
        try {
            seed = Long.parseLong(parts[1]);
            lastId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (seed <= 0 || lastId <= 0) {
            return null;
        }
        return new Cursor(seed, lastId);
    }
}
