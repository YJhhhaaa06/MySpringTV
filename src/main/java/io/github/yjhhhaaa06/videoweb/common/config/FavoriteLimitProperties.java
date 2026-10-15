package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 收藏域容量上限（收藏三期 <b>T16</b> 收藏夹 + <b>T17</b> 稍后再看）。
 *
 * <p>与 {@link FavoriteCacheProperties} / {@link LikeCacheProperties} 同款：标准配置绑定 +
 * 环境变量可覆盖，放在 {@code common.config}（本项目的 {@code @ConfigurationProperties} 记录
 * 都收在这里，与域代码分离）。
 *
 * <p>★ <b>上限刻意不进 schema</b>（T16 红线④）：它是业务常量、不是结构 ——
 * 改数不动代码，也不需要新迁移（**V4 留给 T17 的 {@code watch_later} 表本身**）。
 * 稍后再看的上限 {@link #watchLaterMaxItems} 同族（T17 决策 D5：**扩展本类**而非新建类）。
 *
 * <h2>数字的来源（别照抄 B 站）</h2>
 * B 站实测为 默认夹 5 万 / 单夹 1000 / 自建夹 99，且**官方未公示**
 * （favorite-需求.md §六 早已写明"别直接照抄"）⇒ 用户 2026-10-14 拍板取更小的**演示值**
 * <b>5000 / 500 / 20</b>。⚠️ 该数字**一上线即对外契约**（同"默认公开"那条的先例）
 * ⇒ 要改就改这里 + {@code application.yaml}，**别在代码里另写一个 500**。
 *
 * <h2>★ 计数口径（{@code favorite.md} §八 是唯一事实源）</h2>
 * <ul>
 *   <li>{@link #defaultFolderMaxItems} / {@link #customFolderMaxItems} 数的是
 *       <b>{@code favorite_item} 行</b>（<b>含已失效条目</b>）—— 与
 *       {@code FavoriteFolderVO#itemCount}、{@code /favorite/list} 的 {@code total}
 *       <b>同一口径</b>；别一个口径数"内容"、一个口径数"行"（那会得到两个都"对"
 *       却互相矛盾的数）；</li>
 *   <li>{@link #customFolderMaxCount} 只数<b>自建夹</b>（{@code is_default = 0}）：
 *       默认夹**不计入** —— 它是 R-01 懒建出来的、用户删不掉，计入就会出现
 *       "夹数满 ⇒ 默认夹建不出来"这类**不自愈的死局**（会重演 I-08 的前端互锁）；</li>
 *   <li>超限 ⇒ <b>400</b>（与"夹名空 / 格式非法"同族）；判定排在<b>夹归属 403/404 之后</b>
 *       —— 容量是"资源状态"、不是"参数形态"，理由见 {@code FavoriteService#addItem}
 *       的"校验顺序"节（那节同时说明为什么它**不能**套用"参数 400 先于归属"的既有惯例）。</li>
 * </ul>
 *
 * <h2>★ 它是<b>软上限</b>（用户 2026-10-14 拍板）</h2>
 * 判定是 {@code COUNT → INSERT} 两步、**非原子** ⇒ 真并发下会**瞬时越界**：
 * 越界量 ≤ 并发通过检查的请求数（19 个夹 + 8 并发 ⇒ 最多 27），且**越界后自限**
 * （此后所有新增 / 新建一律 400，不会再涨）。
 * <b>读路径不因"越界"做任何隐藏或限制</b> —— 越界的夹照常列、照常打开。
 *
 * <p>⚠️ <b>为什么不上锁</b>（"用户行锁 + COUNT"看着便宜，实测不是）：
 * <ol>
 *   <li><b>RR 快照陷阱（会静默失效）</b>：快照建立在事务内**第一条普通读**上，
 *       锁若晚于它（{@code addItem} 的第一条普通读是内容存在性校验），后面的 COUNT
 *       读的还是旧快照 ⇒ <b>锁在、但不生效，且无任何报错</b>；后人重构把它挪位即
 *       <b>静默重开</b>窗口；</li>
 *   <li><b>本域没有可锁的常驻行</b>：{@code favorite_folder} 在"0 个夹"时没有行可锁，
 *       在 {@code uk_user_default} 上锁不存在的行 = gap lock（正是本仓在
 *       {@code FavoriteItemDao} 注释里**明文排除 {@code FOR UPDATE}** 的那个死锁来源），
 *       唯一干净的对象 {@code users} 是<b>别域的表</b>；</li>
 *   <li>要覆盖**全部**写点才有意义，且 {@code createFolder} 现在<b>没有事务</b>
 *       （上锁必须连带给它加 {@code @Transactional}）。
 * </ol>
 * ⇒ 完整论证 + "何时该改硬上限"（走计数列 + 原子条件 {@code UPDATE}，即 coupon 域那种形态）
 * 见 {@code FURTHER_ISSUES.md} <b>F-23</b>。
 *
 * @param defaultFolderMaxItems 默认夹内容上限（5000；每用户至多一个默认夹）
 * @param customFolderMaxItems  自建夹内容上限（500）
 * @param customFolderMaxCount  自建夹**个数**上限（20；默认夹不计入）
 * @param watchLaterMaxItems    **稍后再看**上限（100；T17，B 站 实测值）
 */
@ConfigurationProperties(prefix = "video.favorite")
public record FavoriteLimitProperties(int defaultFolderMaxItems, int customFolderMaxItems, int customFolderMaxCount,
                                      int watchLaterMaxItems) {

    /**
     * 绑定期校验"必须为正"（同 {@link UploadProperties#root} 的空值 fail-fast 纪律）。
     *
     * <p>★ 四个字段都是 {@code int}**原生类型** ⇒ 键名 / 前缀写错时**不报错、只绑成 0**
     * —— 而 0 会把每一次新增 / 新建都判成 400。故这里**故意让它响亮失败**（上下文起不来），
     * 而不是留一个"应用看着在跑、其实全 400"的静默态。
     */
    public FavoriteLimitProperties {
        requirePositive(defaultFolderMaxItems, "video.favorite.default-folder-max-items");
        requirePositive(customFolderMaxItems, "video.favorite.custom-folder-max-items");
        requirePositive(customFolderMaxCount, "video.favorite.custom-folder-max-count");
        requirePositive(watchLaterMaxItems, "video.favorite.watch-later-max-items");
    }

    private static void requirePositive(int value, String key) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " 必须为正（当前值 " + value + "）");
        }
    }
}
