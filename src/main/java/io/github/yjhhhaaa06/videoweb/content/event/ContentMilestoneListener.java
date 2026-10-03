package io.github.yjhhhaaa06.videoweb.content.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 内容里程碑的**提交后**落日志者（第三批 T2 / 账 B12）。
 *
 * <h2>★ {@code AFTER_COMMIT} 是这条日志正确性的全部</h2>
 * 事件在事务内发布，监听器只在**提交成功后**被调用；回滚 ⇒ 完全不调用。
 * 于是"失败不发里程碑"不是靠"把 log 放在方法最后一行"的纪律，而是框架层面保证的。
 * 配套的机制测试见 {@code observability/MilestoneLogTests}：它让事务在发布事件后回滚
 * （借 {@code @MockitoSpyBean} 让事务内后续调用抛异常），断言**一条里程碑都没产生**——
 * 若有人把日志改回事务内直写，该用例立刻变红。
 *
 * <h2>为什么不用 {@code @Async}</h2>
 * 异步会丢 MDC（reqId 断链），且让测试需要等待。TV 的里程碑也是提交线程同步写。保持同步。
 *
 * <h2>文案是契约</h2>
 * 三条文案与 TV {@code test_milestone_log.py} 的指纹逐字一致
 * （{@code 添加视频成功, contentId=} / {@code 添加动态成功, contentId=} / {@code 删除内容成功, contentId=}），
 * 因为老 pytest 就是按这几个前缀定位行的——换文案等于换契约。
 */
@Slf4j
@Component
public class ContentMilestoneListener {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentMilestone(ContentMilestoneEvent event) {
        switch (event.action()) {
            case VIDEO_ADDED -> log.info("添加视频成功, contentId={}", event.contentId());
            case POST_ADDED -> log.info("添加动态成功, contentId={}", event.contentId());
            case DELETED -> log.info("删除内容成功, contentId={}", event.contentId());
        }
    }
}
