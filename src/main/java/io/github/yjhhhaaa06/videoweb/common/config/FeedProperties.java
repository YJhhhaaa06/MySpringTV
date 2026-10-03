package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * feed 批次参数（S9）。
 *
 * <h2>键名与默认值逐条承接 TV {@code app.properties} 的 {@code feed.*}</h2>
 * TV 的取值层是被放弃的 {@code config.AppConfig}（静态读取 + 60 个扁平键 + 多级覆盖链）。
 * 本记录把 feed 域**真正被消费**的那些键收进来（值照搬 TV，含默认值），作废的键不搬。
 *
 * <table>
 *   <caption>TV 键 → 本记录字段</caption>
 *   <tr><th>TV 键</th><th>字段</th><th>默认</th><th>用途</th></tr>
 *   <tr><td>{@code feed.inbox.ttlMinutes}</td><td>{@link #inboxTtl}</td><td>60m</td>
 *       <td>收件箱读缓存（ZSet）TTL，命中滑动续期</td></tr>
 *   <tr><td>{@code feed.inbox.windowPerAuthor}</td><td>{@link #inboxWindowPerAuthor}</td><td>20</td>
 *       <td>重建/补推时<b>每关注作者取最近 K 条</b></td></tr>
 *   <tr><td>{@code feed.inbox.windowMax}</td><td>{@link #inboxWindowMax}</td><td>200</td>
 *       <td>重建窗口裁剪上界 C（表侧每用户行数上限）</td></tr>
 *   <tr><td>{@code feed.readWindowMax}</td><td>{@link #readWindowMax}</td><td>300</td>
 *       <td>读侧归并窗口裁剪上界 M（收件箱腿 ∪ 大V发件箱腿）</td></tr>
 *   <tr><td>{@code feed.outbox.windowSize}</td><td>{@link #outboxWindowSize}</td><td>20</td>
 *       <td>大V发件箱腿每作者取最近 N 条</td></tr>
 *   <tr><td>{@code feed.outbox.ttlMinutes}</td><td>{@link #outboxTtl}</td><td>60m</td>
 *       <td>大V发件箱读缓存 TTL</td></tr>
 *   <tr><td>{@code feed.fanout.batch}</td><td>{@link #fanoutBatch}</td><td>200</td>
 *       <td>写扩散粉丝游标每批批量</td></tr>
 *   <tr><td>{@code feed.rebuild.authorBatch}</td><td>{@link #rebuildAuthorBatch}</td><td>50</td>
 *       <td>重建时"逐作者最近 K"的 SQL 批尺寸</td></tr>
 *   <tr><td>{@code feed.bigv.queryBatch}</td><td>{@link #bigvQueryBatch}</td><td>200</td>
 *       <td>大V批量判定的 IN 列表分块尺寸（<b>执行参数</b>，非判定输入）</td></tr>
 *   <tr><td>{@code feed.rebuild.debounceMillis}</td><td>{@link #rebuildDebounce}</td><td>1s</td>
 *       <td>重建请求去抖窗口（per-user 尾沿合并）</td></tr>
 *   <tr><td>{@code feed.dlq.ttlMillis}</td><td>{@link #dlqTtl}</td><td>7d</td>
 *       <td>死信队列消息保留时长（证据窗口有界）</td></tr>
 * </table>
 *
 * <h2>不搬的键（无 owner / 已由框架替代）</h2>
 * <ul>
 *   <li>{@code feed.bigv.configFile} / {@code feed.bigv.refreshMillis} —— 外置文件热更，本批裁剪（B 类，见《决策留痕表》C-8）；</li>
 *   <li>{@code feed.delivery.queueCapacity} / {@code feed.delivery.drainTimeoutMillis} —— 属被裁剪的
 *       "异步投递线程池"（B8）；</li>
 *   <li>{@code feed.compensate.bufferCapacity} —— 属被裁剪的"内存补偿缓冲"（B9）；</li>
 *   <li>{@code feed.consume.retry.maxRetries} / {@code feed.consume.retry.backoffMillis} ——
 *       <b>改由 Spring AMQP 承接</b>（{@code spring.rabbitmq.listener.simple.retry.*}），不再进本记录，
 *       避免"同一事实两处配置"（两处改了以哪处为准会变成新的坑）。</li>
 * </ul>
 *
 * <h2>校验口径：NaN / 非正数 = 启动即拒</h2>
 * 沿袭 TV {@code validatePositiveBatch} 的"宁可启动即拒"取向（{@link BigVProperties} 同款），
 * 与本项目"配置绑定期校验"的统一口径一致。{@code Duration} 属性**必须带单位后缀**
 * （裸数字会解析成秒级，见 {@code JwtProperties} 的坑记录）。
 *
 * @param inboxTtl            收件箱读缓存 TTL（&gt; 0）
 * @param inboxWindowPerAuthor 每关注作者最近 K 条（&gt; 0）
 * @param inboxWindowMax      重建窗口裁剪上界 C（&gt; 0）
 * @param readWindowMax       读侧归并窗口裁剪上界 M（&gt; 0）
 * @param outboxWindowSize    大V发件箱每作者最近 N 条（&gt; 0）
 * @param outboxTtl           大V发件箱读缓存 TTL（&gt; 0）
 * @param fanoutBatch         写扩散粉丝游标批尺寸（&gt; 0）
 * @param rebuildAuthorBatch  重建"逐作者"SQL 批尺寸（&gt; 0）
 * @param bigvQueryBatch      大V判定 IN 分块尺寸（&gt; 0）
 * @param rebuildDebounce     重建请求去抖窗口（&ge; 0；<b>0 = 显式关闭去抖</b>）
 * @param dlqTtl              死信队列消息 TTL（&gt; 0）
 */
@ConfigurationProperties(prefix = "video.feed")
public record FeedProperties(
        Duration inboxTtl,
        int inboxWindowPerAuthor,
        int inboxWindowMax,
        int readWindowMax,
        int outboxWindowSize,
        Duration outboxTtl,
        int fanoutBatch,
        int rebuildAuthorBatch,
        int bigvQueryBatch,
        Duration rebuildDebounce,
        Duration dlqTtl) {

    public FeedProperties {
        requirePositive(inboxWindowPerAuthor, "video.feed.inbox-window-per-author");
        requirePositive(inboxWindowMax, "video.feed.inbox-window-max");
        requirePositive(readWindowMax, "video.feed.read-window-max");
        requirePositive(outboxWindowSize, "video.feed.outbox-window-size");
        requirePositive(fanoutBatch, "video.feed.fanout-batch");
        requirePositive(rebuildAuthorBatch, "video.feed.rebuild-author-batch");
        requirePositive(bigvQueryBatch, "video.feed.bigv-query-batch");
        requirePositiveDuration(inboxTtl, "video.feed.inbox-ttl");
        requirePositiveDuration(outboxTtl, "video.feed.outbox-ttl");
        requirePositiveDuration(dlqTtl, "video.feed.dlq-ttl");
        // 去抖窗口：0 有显式语义（关闭去抖）⇒ 只拒负数
        if (rebuildDebounce == null || rebuildDebounce.isNegative()) {
            throw new IllegalArgumentException("video.feed.rebuild-debounce 不得为负数: " + rebuildDebounce);
        }
    }

    private static void requirePositive(int value, String key) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " 必须为正数: " + value);
        }
    }

    private static void requirePositiveDuration(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(key + " 必须为正的时长（且带单位后缀，如 60m）: " + value);
        }
    }
}
