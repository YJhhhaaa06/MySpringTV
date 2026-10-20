package io.github.yjhhhaaa06.videoweb.coin.event;

import io.github.yjhhhaaa06.videoweb.coin.service.CoinService;
import io.github.yjhhhaaa06.videoweb.content.event.ContentCacheChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 内容被删/下架 ⇒ **失效该内容的投币计数缓存**（承接 {@code ContentRemovedLikeListener} 的形态）。
 *
 * <h2>为什么是"coin 订阅 content 的事件"，而不是"content 直调 CoinService"</h2>
 * {@code content:coinCount:{contentId}} 属于**投币域的缓存**，但它的失效触发条件是内容域的
 * 一次业务事实（软删 / 下架）。谁负责？
 *
 * <p>S5/S6 曾把同类逻辑写成内容域直接调 {@code LikeService.deleteContentLike}——于是内容域
 * 知道了"点赞还有计数缓存"，跨域耦合**从缓存实现漏进了 service**（S6-B2c 拆掉的就是这种形态）。
 * 正确形态是：**谁拥有缓存，谁订阅事件并失效自己的 key**；内容域不必知道谁有缓存。
 *
 * <p>★ {@code coin.event} 消费 {@code content.event} 是 ArchUnit **规则 3 允许的唯一落点**
 * （跨域事件只能在**自己的 event 包**里消费）：把这段 import 写进
 * {@code CoinService}/{@code CoinController} 会让 {@code ArchitectureTests} 变红。
 *
 * <h2>为什么只失效计数、不删 {@code content_coin} 行</h2>
 * 投币不可取消 + 不变量 {@code content.coin_count == SUM(amount)} 要求行留存 ——
 * 与 like 的 {@code deleteByContentId} **有意不同**（完整理由见 {@code CoinCache} 类注释）。
 *
 * <h2>时序</h2>
 * 事件在内容删除事务**内**发布，本方法**只在提交后**被调用；事务回滚 ⇒ 完全不调用
 * （内容没删成，投币缓存自然不该动）。与内容 / 评论 / 点赞三个监听器是同一次提交后的
 * **并列**回调，互不依赖，顺序无所谓。
 */
@Slf4j
@Component
public class ContentRemovedCoinListener {

    private final CoinService coinService;

    public ContentRemovedCoinListener(CoinService coinService) {
        this.coinService = coinService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentCacheChanged(ContentCacheChangedEvent event) {
        if (event.op() != ContentCacheChangedEvent.Op.REMOVE) {
            return;
        }
        log.debug("内容删除事务已提交，失效投币计数缓存: contentId={}", event.contentId());
        coinService.deleteContentCoinCount(event.contentId());
    }
}
