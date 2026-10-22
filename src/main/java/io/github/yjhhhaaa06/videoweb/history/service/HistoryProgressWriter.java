package io.github.yjhhhaaa06.videoweb.history.service;

import io.github.yjhhhaaa06.videoweb.history.dao.WatchHistoryDao;
import io.github.yjhhhaaa06.videoweb.history.model.dto.HistoryProgressRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 播放记录批量落库的**事务载体**（`T51-2`）。
 *
 * <p>★ 为什么单独一个 bean，而不是把 {@code @Transactional} 标在 {@code HistoryFlushService} 的方法上：
 * 编排层需要"整批失败后**逐条重试**"，那两次调用必须是**两个独立事务**
 * （整批回滚不能把逐条成功的也带走）。同类内部自调用会绕过 Spring 代理、`@Transactional` 直接失效 ——
 * 这是本仓既有的教训（事务切分用独立 bean）。
 *
 * <p>★ 单条落库复用批量语句（{@code List.of(row)}）：语义只有一处，不做两份 SQL。
 */
@Service
public class HistoryProgressWriter {

    private final WatchHistoryDao watchHistoryDao;

    public HistoryProgressWriter(WatchHistoryDao watchHistoryDao) {
        this.watchHistoryDao = watchHistoryDao;
    }

    /** 整批写入（一个事务：全成或全败）。 */
    @Transactional
    public void upsertBatch(long userId, List<HistoryProgressRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        watchHistoryDao.upsertProgressBatch(userId, rows);
    }

    /** 单条写入（独立事务；用于整批失败后的逐条重试）。 */
    @Transactional
    public void upsertOne(long userId, HistoryProgressRow row) {
        watchHistoryDao.upsertProgressBatch(userId, List.of(row));
    }
}
