package io.github.yjhhhaaa06.videoweb.common.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CacheMissSignal} 的行为契约（限流专项 T26 的信号②运行期载体；
 * 结算口径见 {@code architecture/tech/限流.md} §四）。
 *
 * <h2>本类证明什么 / 反向验证指向</h2>
 * <ul>
 *   <li>{@link #mark_consume_取走并清零()}：{@code consume} 是"取走"不是"查看"（幂等标记 + 一次性读出）；</li>
 *   <li>{@link #clear_只清不读()}：闸门入口的清理动作不改变"本请求"的任何结论；</li>
 *   <li>★ {@link #线程封闭_别的线程的标记不可见()}：{@code ThreadLocal} 封闭性——**限流红线**
 *       "回传机制不得泄漏到别的线程"（MQ 消费线程 / 去抖线程不带）的机制面证明。</li>
 * </ul>
 * 接线面（各缓存类在回源点调 {@code mark()}）的证明在
 * {@code ratelimit.CacheMissSignalWiringTests}；把 {@code mark()} 改成空实现 ⇒ 那边必红。
 */
class CacheMissSignalTests {

    @AfterEach
    void 清理本线程标记() {
        // 线程复用防串味：本类是纯单测，但 JUnit 的执行线程也可能被别的用例复用
        CacheMissSignal.clear();
    }

    @Test
    @DisplayName("mark → consume：取走一次为 true、再取为 false（consume 即清零；重复 mark 幂等）")
    void mark_consume_取走并清零() {
        assertThat(CacheMissSignal.consume()).as("未标记时 consume 必须是 false").isFalse();

        CacheMissSignal.mark();
        CacheMissSignal.mark();                 // 幂等：同一请求内多次回源也只算"发生过"
        assertThat(CacheMissSignal.consume())
                .as("标记后 consume 必须是 true（限流器据此补扣差额）").isTrue();
        assertThat(CacheMissSignal.consume())
                .as("consume 已清零 ⇒ 紧接着的第二次取必为 false（否则同一笔账会被扣两次）")
                .isFalse();
    }

    @Test
    @DisplayName("clear：只清不读——闸门入口的清理不得把上一请求的标记透出来")
    void clear_只清不读() {
        CacheMissSignal.mark();
        CacheMissSignal.clear();
        assertThat(CacheMissSignal.consume()).as("clear 后 consume 必须为 false").isFalse();

        // 对照：clear 之后重新 mark 仍可正常取出（clear 没有破坏标记能力）
        CacheMissSignal.mark();
        assertThat(CacheMissSignal.consume()).isTrue();
    }

    @Test
    @DisplayName("★ 线程封闭：别的线程的标记不可见（'不得泄漏到别的线程'的机制面证明）")
    void 线程封闭_别的线程的标记不可见() throws Exception {
        CacheMissSignal.mark();                 // 本线程（模拟请求线程）已标记

        AtomicBoolean otherSeesMainMark = new AtomicBoolean(true);
        AtomicBoolean otherSeesOwnMark = new AtomicBoolean(false);
        Thread other = new Thread(() -> {       // 模拟 MQ 消费 / 去抖线程
            otherSeesMainMark.set(CacheMissSignal.consume());
            CacheMissSignal.mark();
            otherSeesOwnMark.set(CacheMissSignal.consume());
        });
        other.start();
        other.join();

        assertThat(otherSeesMainMark.get()).as("别的线程看不到本线程的标记").isFalse();
        assertThat(otherSeesOwnMark.get()).as("对照：别的线程自己的标记可见").isTrue();
        assertThat(CacheMissSignal.consume())
                .as("对照：别的线程的操作不影响本线程，本线程的标记仍在").isTrue();
    }
}
