package io.github.yjhhhaaa06.videoweb.content.model;

/**
 * 搜索结果的排序口径（GAP-10，2026-10-27）：{@code GET /api/v1/contents?order=}。
 *
 * <h2>只有两个值，缺省 = {@link #LATEST}</h2>
 * <ul>
 *   <li>{@link #LATEST} —— {@code create_time DESC, id DESC}；**与加参数前的行为逐字一致**，
 *       故"不传"与"显式传 LATEST"必须等价。</li>
 *   <li>{@link #PLAY} —— {@code play_count DESC, id DESC}；播放量来自 {@code content.play_count}
 *       （V6 列，唯一写路径是 {@code HistoryService#play}）。</li>
 * </ul>
 *
 * <h2>刻意没有"综合"（相关性）</h2>
 * 真·综合排序要 {@code MATCH(...) AGAINST(...)} 的分数，而搜索的**单字符分支**走
 * {@code title LIKE '%x%'}（ngram 对单字命中不可靠，TV 实测结论）**没有分数** ⇒ 同一个需求
 * 换个词长就会换排序规则。与其给一个只在部分关键词下成立的"综合"，不如只留两个确定的口径
 * ——前端也就不会把默认排序标成"综合排序"（那是谎）。
 *
 * <h2>⚠️ 名义上是枚举，实际只有 {@link #PLAY} 有分支</h2>
 * {@code ContentMapper.xml} 的 {@code searchOrderBy} 片段用
 * {@code <otherwise>} 兜 {@link #LATEST}。新增值时必须**同时**改 mapper 与
 * {@code ContentSearchFilterTests}，否则新值会静默落进 {@code otherwise}（= 最新）。
 * DAO 收的是本枚举的 {@link #name()}（白名单字符串），不是用户输入。
 */
public enum SearchOrder {

    /** 最新发布：{@code create_time DESC, id DESC}（缺省，与加参数前一致）。 */
    LATEST,

    /** 最多播放：{@code play_count DESC, id DESC}。⚠️ 图文的 {@code play_count} 结构性恒 0。 */
    PLAY
}
