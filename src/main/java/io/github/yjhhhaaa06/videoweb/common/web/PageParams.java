package io.github.yjhhhaaa06.videoweb.common.web;

/**
 * 分页参数归一（承接 TV {@code BaseServletUtil.normalizePage / normalizePageSize}）。
 *
 * <h2>为什么抽（S5，rule of three）</h2>
 * S4 时 {@code FollowController} 内联了一份私有实现；S5 又要给 {@code /search}、{@code /profile}、
 * {@code /comment/*} 各来一份 ⇒ 第三个使用方出现，抽到这里。
 * FollowController 同时改为委托（口径零变化，其测试是回归网）。
 *
 * <h2>★ 为什么收 {@code String} 而不是 {@code Integer}</h2>
 * TV 对**非数字**的 {@code page}/{@code pageSize} 是**静默归一**（回落缺省），而 {@code userId}
 * 之类的参数对非数字**抛 400**——这个不对称是有意的（旧 pytest 对 {@code pageSize=abc} 断言 200
 * 且回落到域级缺省，属冻结契约）。若收 {@code Integer}，非数字会被 Spring 抛成
 * {@code MethodArgumentTypeMismatchException} → 400，把"静默归一"变成"报错"，属**契约改动**。
 *
 * <h2>三域口径差异（T19 拍板，须逐域传常量）</h2>
 * 各域自持「上限 / 信封」两个常量，**不再有公共 cap**：
 * <ul>
 *   <li>follow / search / profile / feed：上限 <b>100</b>、信封 <b>100</b>；</li>
 *   <li>comment：上限 <b>500</b>、信封 <b>200</b>（评论的"一屏主楼"比内容列表大得多）。</li>
 * </ul>
 */
public final class PageParams {

    private PageParams() {
    }

    /** 页码归一：缺省 / 非数字 / ≤0 → 1。 */
    public static int normalizePage(String raw) {
        Integer parsed = parseIntOrNull(raw);
        return (parsed == null || parsed <= 0) ? 1 : parsed;
    }

    /**
     * 信封大小归一：缺省 / 非数字 / ≤0 → {@code min(defaultSize, maxSize)}；
     * 传了 → {@code min(raw, maxSize)}。
     *
     * <p><b>返回值恒 ≤ maxSize</b>——把"信封不会超过上限"这一不变量收进方法内；
     * 且 {@code raw ≤ maxSize} 时**原样回显**（{@code pageSize=51} 必须回显 51，不是 100）
     * ——旧 pytest 明确断言该行为，因为"小信封跨页验证能力"必须保留。
     */
    public static int normalizePageSize(String raw, int maxSize, int defaultSize) {
        int fallback = Math.min(defaultSize, maxSize);
        Integer parsed = parseIntOrNull(raw);
        return (parsed == null || parsed <= 0) ? fallback : Math.min(parsed, maxSize);
    }

    /** {@code null} / 空白 / 非数字 → {@code null}（由调用方决定回落值）。 */
    public static Integer parseIntOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * ★ T14：keyset 游标归一（复用上面的"**静默归一**"口径，不引入 400）。
     *
     * <p>与 {@link #normalizePage(String)} 同构：缺省 / 非数字 / ≤0 → **0**（= 第 1 页，
     * 覆盖"从楼头开始"）。⚠️ 游标值是**主键列的值**（如 {@code comment_id}），属 BIGINT ⇒
     * 按 {@code Long} 解析（{@code Integer} 会在超大 id 上误判成"非数字"）。
     *
     * <p>⚠️ 这里**只做归一、不做有效性校验**：越界 / 不属于本楼的游标由调用方
     * "取不到行 ⇒ 空页 + {@code nextCursor=null}" 自然兜底。
     */
    public static long normalizeCursor(String raw) {
        Long parsed = parseLongOrNull(raw);
        return (parsed == null || parsed <= 0) ? 0L : parsed;
    }

    /** {@code null} / 空白 / 非数字 → {@code null}；用于 BIGINT 级游标。 */
    public static Long parseLongOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
