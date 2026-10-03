package io.github.yjhhhaaa06.videoweb.feed.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * 收件箱 / 读侧窗口的**归并口径单一来源**（承接 TV {@code FeedRebuildService.mergeDedupSortTrim}，
 * 由包内可见提升为独立工具类）。
 *
 * <h2>为什么单列一个类</h2>
 * TV 里这段是 {@code FeedRebuildService} 的包内静态方法：重建侧裁 C、读侧裁 M 各调一次，
 * 注释明写"归并这一口径全仓只有一份实现（只有**上限参数**按层不同）"。
 * 本实现把它提到独立工具类，**理由不变**——只是让"口径唯一"在包结构上更显眼
 * （{@code FeedRebuildService} 与 {@code FeedReadService} 都依赖它，而不是靠同包可见性）。
 *
 * <h2>语义</h2>
 * {@code 去重 → contentId 降序 → 截断到 windowMax}。
 * 排序口径 = <b>contentId</b>（{@code content.id} 自增单调 ⇒ id 越大越新；与 {@code feed_inbox}
 * 不存时间字段、"排序 / 归并 / 裁剪全按 contentId"的收件箱层口径同源）。
 *
 * <p>内存 {@code O(输入规模)}、时间 {@code O(N log N)}——规模增长后可换有界最小堆，本期取简单实现
 * （去重用无序 {@link HashSet}：顺序由随后的排序决定，无需保插入序）。TV 原样。
 */
public final class FeedWindows {

    private FeedWindows() {
    }

    /**
     * 归并 → 去重 → contentId 降序 → 裁剪到上限。
     *
     * @param raw       原始 id 流（可含重复；顺序无关）
     * @param windowMax 上限（重建侧传 C = {@code inboxWindowMax}；读侧传 M = {@code readWindowMax}）
     * @return 去重降序并截断后的不可变列表（输入为空 ⇒ 空列表）
     */
    public static List<Long> mergeDedupSortTrim(List<Long> raw, int windowMax) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = new ArrayList<>(new HashSet<>(raw));
        distinct.sort(Collections.reverseOrder());
        if (distinct.size() <= windowMax) {
            return distinct;
        }
        return new ArrayList<>(distinct.subList(0, windowMax));
    }
}
