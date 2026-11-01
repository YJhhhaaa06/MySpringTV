package io.github.yjhhhaaa06.videoweb.content;

import io.github.yjhhhaaa06.videoweb.content.cache.RecommendOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 推荐序的**纯函数**测试（不起 Spring、不碰 Redis）。
 *
 * <h2>为什么这些断言值得单列</h2>
 * 翻页"不重不漏"的全部正确性都压在两条性质上：<b>同 seed 同 id ⇒ 同 rank</b>（纯）与
 * <b>同池内 rank 两两不同</b>（双射）。它们一旦破了，HTTP 层的用例会以"偶发重复"的形式红，
 * 极难定位；在这里断言，定位是一行。
 *
 * <p>★ 第一条用例带**硬编码基准值**：它把"跨 JVM / 跨版本一致"这句承诺变成会失败的测试。
 * 换排序算法时它会红——那是**预期**，正确动作是递增 {@code RecommendOrder.VERSION}
 * 并同步改这个基准值（旧游标降级为第一页），而不是把期望值改成新算出来的数。
 */
class RecommendOrderTests {

    @Test
    @DisplayName("排序键是纯函数，且有跨版本稳定的基准值")
    void 排序键是纯函数() {
        assertThat(RecommendOrder.rank(739201L, 1042L))
                .as("基准值：换算法必须同时递增游标版本 v1")
                .isEqualTo(-1688543909761158575L);
        assertThat(RecommendOrder.rank(739201L, 1042L))
                .as("同 (seed, id) 反复调用必须相同")
                .isEqualTo(RecommendOrder.rank(739201L, 1042L));
    }

    @Test
    @DisplayName("十万个连续 id 的 rank 两两不同（双射 ⇒ 无并列）")
    void 排序键无碰撞() {
        long seed = 739201L;
        Set<Long> ranks = new HashSet<>(200_000);
        for (long id = 1; id <= 100_000; id++) {
            boolean fresh = ranks.add(RecommendOrder.rank(seed, id));
            assertThat(fresh).as("rank 碰撞：id=%s", id).isTrue();
        }
        assertThat(ranks).hasSize(100_000);
    }

    @Test
    @DisplayName("换 seed 就是换排列（元素集合不变，次序变）")
    void 换seed换排列() {
        List<Long> bySeed1 = ranked(1L);
        List<Long> bySeed2 = ranked(2L);

        assertThat(bySeed1).as("同一个池子，元素集合必须一致").containsExactlyInAnyOrderElementsOf(bySeed2);
        assertThat(bySeed1).as("不同 seed 应给出不同次序").isNotEqualTo(bySeed2);
    }

    @Test
    @DisplayName("游标编解码往返；非法游标一律返回 null（静默归一到第一页）")
    void 游标编解码() {
        String encoded = RecommendOrder.encode(739201L, 1042L);
        assertThat(encoded).isEqualTo("v1.739201.1042");
        assertThat(RecommendOrder.decode(encoded)).isEqualTo(new RecommendOrder.Cursor(739201L, 1042L));

        for (String illegal : List.of("", " ", "v1", "v1.739201", "v1.739201.1042.9",
                "v2.739201.1042", "v1.a.1042", "v1.739201.b", "v1.0.1042", "v1.739201.0",
                "v1.-1.1042", "v1.739201.-1042")) {
            assertThat(RecommendOrder.decode(illegal)).as("非法游标：%s", illegal).isNull();
        }
        assertThat(RecommendOrder.decode(null)).isNull();
    }

    /** 池子固定为 1..100，按 rank 降序排出的 id 序列。 */
    private static List<Long> ranked(long seed) {
        List<Long> ids = new ArrayList<>();
        for (long id = 1; id <= 100; id++) {
            ids.add(id);
        }
        ids.sort((a, b) -> Long.compare(RecommendOrder.rank(seed, b), RecommendOrder.rank(seed, a)));
        return ids;
    }
}
