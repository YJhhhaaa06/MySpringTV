package io.github.yjhhhaaa06.videoweb.history.service;

import io.github.yjhhhaaa06.videoweb.common.config.HistoryBufferProperties;
import io.github.yjhhhaaa06.videoweb.history.cache.HistoryBufferRedisOps;
import io.github.yjhhhaaa06.videoweb.history.cache.HistoryReportBuffer;
import io.github.yjhhhaaa06.videoweb.history.model.dto.HistoryProgressRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 播放记录缓冲的**落库编排**（`T51-2`）：`/flush`（退出信号）与轮询兜底**共用同一段逻辑**。
 *
 * <p>口径唯一事实源 = {@code history-上报缓冲.md} §3–§5。
 *
 * <h2>一次落库的四步（§4）</h2>
 * <ol>
 *   <li>{@code snapshot} 读全量（**原始 field → 原始值**，不解析、不筛选）；</li>
 *   <li>解析：{@code contentId} 与 {@code "progress:ts"} 任一不合法 ⇒ **记 WARN 并丢弃该条**，
 *       但它**仍要进确认清单**（不删就永远删不掉 ⇒ {@code HLEN>0} ⇒ 脏成员摘不掉）；</li>
 *   <li>整批写入（一个事务）；</li>
 *   <li><b>条件删</b>：只删"值仍是落库时那个"的 field（期间有新上报 ⇒ 不动，下一轮写新值）。</li>
 * </ol>
 *
 * <h2>★ 失败分流：按"是不是数据问题"分，不按"失败了几次"分</h2>
 * <ul>
 *   <li><b>数据问题</b>（{@link DataIntegrityViolationException} / {@link BadSqlGrammarException}）：
 *       整批会被回滚 ⇒ 降级**逐条重试**，把坏行摘出来丢弃（ERROR）并确认掉，其余照常写；</li>
 *   <li><b>基础设施问题</b>（连接、超时、死锁、未知）：**一条都不确认** ⇒ 缓冲原样留着，下一轮重试。
 *       ⚠️ 这类绝不能"重试 N 次就丢"——DB 抖 5 分钟就把 5 分钟的进度全扔，比丢单条坏数据严重得多。</li>
 * </ul>
 *
 * <h2>★ 为什么空快照也要跑一次确认</h2>
 * 见 {@link HistoryBufferRedisOps#removeIfUnchanged}：清理"hash 已过期、脏成员还在"的僵尸。
 *
 * <h2>剪枝（成员级 TTL）</h2>
 * 轮询每轮先 {@code ZREMRANGEBYSCORE -inf (now - bufferTtl)} —— 见
 * {@link HistoryBufferRedisOps#removeDirtyBefore}（阈值与 hash 过期时刻严格对齐）。
 */
@Slf4j
@Service
public class HistoryFlushService {

    private final HistoryReportBuffer buffer;
    private final HistoryBufferRedisOps bufferOps;
    private final HistoryProgressWriter writer;
    private final HistoryBufferProperties props;

    public HistoryFlushService(HistoryReportBuffer buffer,
                               HistoryBufferRedisOps bufferOps,
                               HistoryProgressWriter writer,
                               HistoryBufferProperties props) {
        this.buffer = buffer;
        this.bufferOps = bufferOps;
        this.writer = writer;
        this.props = props;
    }

    /** 一条缓冲解析后的形态；{@code contentId} / {@code entry} 任一为 null ⇒ 不可用（丢弃但确认）。 */
    private record Parsed(String field, String raw, Long contentId, HistoryBufferRedisOps.Entry entry) {

        boolean usable() {
            return contentId != null && entry != null;
        }

        HistoryProgressRow toRow() {
            return new HistoryProgressRow(contentId, entry.progress(), new Timestamp(entry.receiveTs()));
        }
    }

    /**
     * 把一个用户的缓冲**同步**落库（`/flush` 的语义：退出信号，延迟为 0）。
     *
     * @return 实际写入/覆盖的行数（0 = 没有可用数据，或暂时失败已留缓冲）
     */
    public int flushUser(long userId) {
        Map<String, String> snapshot = buffer.snapshot(userId);
        if (snapshot.isEmpty()) {
            buffer.acknowledge(userId, snapshot);   // 清僵尸脏成员（否则会占满每轮兜底名额）
            return 0;
        }

        List<Parsed> parsed = new ArrayList<>(snapshot.size());
        Map<String, String> discardable = new LinkedHashMap<>();
        List<HistoryProgressRow> rows = new ArrayList<>(snapshot.size());
        for (Map.Entry<String, String> e : snapshot.entrySet()) {
            Parsed p = new Parsed(e.getKey(), e.getValue(),
                    parseContentId(e.getKey()), HistoryBufferRedisOps.Entry.ofRaw(e.getValue()).orElse(null));
            parsed.add(p);
            if (p.usable()) {
                rows.add(p.toRow());
            } else {
                log.warn("播放记录缓冲：丢弃无法解析的条目 userId={}, field={}, value={}",
                        userId, e.getKey(), e.getValue());
                discardable.put(p.field(), p.raw());   // 丢弃，但**必须**让它被删掉
            }
        }

        if (rows.isEmpty()) {
            buffer.acknowledge(userId, discardable);
            return 0;
        }

        try {
            writer.upsertBatch(userId, rows);
            // 整批成功 ⇒ 整份快照都可确认（期间无法解析的条目也在里面，它们同样必须被删）
            buffer.acknowledge(userId, snapshot);
            return rows.size();
        } catch (DataAccessException e) {
            if (!isPoison(e)) {
                log.warn("播放记录落库暂时失败，缓冲原样保留等下一轮: userId={}, rows={}",
                        userId, rows.size(), e);
                return 0;
            }
            log.warn("播放记录整批落库失败（疑似脏数据），降级逐条重试: userId={}, rows={}, cause={}",
                    userId, rows.size(), e.toString());
            return retryOneByOne(userId, parsed);
        }
    }

    /**
     * 轮询兜底：先剪枝（成员级 TTL），再处理"够闲"的用户。
     *
     * <p>★ 只影响**崩溃 / 断网**的会话 —— 正常退出的用户已经走 {@code /flush} 落库了。
     * 单用户失败**不中断**其余用户（照 `FeedDeliveryBuffer` 的"坏状态下不空转、但也不放弃整轮"）。
     *
     * @return 本轮写入/覆盖的总行数（诊断与测试断言用）
     */
    public int pollOnce() {
        long pruned = bufferOps.removeDirtyBefore(System.currentTimeMillis() - props.bufferTtl().toMillis());
        if (pruned > 0) {
            log.info("播放记录缓冲剪枝：摘掉 {} 个已过期的脏成员（hash 已随 TTL 消失）", pruned);
        }
        int written = 0;
        List<Long> userIds = buffer.idleUserIds();
        for (long userId : userIds) {
            try {
                written += flushUser(userId);
            } catch (RuntimeException e) {
                log.warn("播放记录兜底落库失败（等下一轮）: userId={}", userId, e);
            }
        }
        if (written > 0) {
            log.info("播放记录兜底落库完成: 本轮 {} 行, 候选 {} 人", written, userIds.size());
        }
        return written;
    }

    // ==================== 内部 ====================

    /**
     * 整批失败后的逐条重试：把坏行摘出去，好行照常写。
     *
     * <p>⚠️ 每条走**独立事务**（{@link HistoryProgressWriter#upsertOne}）：整批回滚不能把
     * 已成功的单条也带走 —— 这正是"毒批"（一条坏数据毒死 99 条好数据）的解法。
     */
    private int retryOneByOne(long userId, List<Parsed> parsed) {
        Map<String, String> acknowledged = new LinkedHashMap<>();
        int written = 0;
        for (Parsed p : parsed) {
            if (!p.usable()) {
                acknowledged.put(p.field(), p.raw());
                continue;
            }
            try {
                writer.upsertOne(userId, p.toRow());
                written++;
                acknowledged.put(p.field(), p.raw());
            } catch (DataAccessException e) {
                if (isPoison(e)) {
                    log.error("播放记录：丢弃脏数据 userId={}, contentId={}, progress={}（其余行已单独写入）",
                            userId, p.contentId(), p.entry().progress(), e);
                    acknowledged.put(p.field(), p.raw());
                } else {
                    // 基础设施仍未恢复 ⇒ 不确认，留缓冲；本轮到此为止（避免坏状态下空转）
                    log.warn("播放记录逐条重试遇基础设施故障，本轮中止（缓冲保留）: userId={}", userId, e);
                    break;
                }
            }
        }
        if (!acknowledged.isEmpty()) {
            buffer.acknowledge(userId, acknowledged);
        }
        return written;
    }

    /**
     * 是否"数据问题"（重放必然同样失败）—— 只有这类才允许丢弃。
     *
     * <p>★ 刻意**不**按失败次数判断：连接失败 / 超时 / 死锁重放就会成功，按次数丢会把
     * "DB 抖动期间的进度"整段扔掉（比丢一条坏数据严重得多）。
     */
    private static boolean isPoison(DataAccessException e) {
        return e instanceof DataIntegrityViolationException || e instanceof BadSqlGrammarException;
    }

    private static Long parseContentId(String field) {
        try {
            return Long.parseLong(field);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
