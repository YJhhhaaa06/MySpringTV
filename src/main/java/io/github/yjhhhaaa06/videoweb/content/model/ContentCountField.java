package io.github.yjhhhaaa06.videoweb.content.model;

/**
 * 列表面**按需填充**的公共计数维度（GAP-01，2026-10-26）。
 *
 * <p>列表不再"一个维度都不查"，也不再"五个维度全查"：不同展示面只查自己卡片真正显示的那几维
 * （见 {@code ContentVO} 类注释的矩阵）。本枚举只列**当前有展示位**的三维：
 * <ul>
 *   <li>{@link #LIKE} —— 动态卡片操作栏（feed / 个人动态）；</li>
 *   <li>{@link #COMMENT} —— 动态卡片操作栏；</li>
 *   <li>{@link #PLAY} —— 所有卡片的播放量角标（图文语义为「浏览」，口径见 GAP-05）。</li>
 * </ul>
 * ★ 刻意**不含** {@code FAVORITE} / {@code COIN}：列表面目前没有展示位，
 * "加了但永远为 null"的键等于让契约说谎。将来有展示位时再加，同时补对应的缓存键与回填通道。
 *
 * <p>⚠️ 维度集合由**调用点的编译期常量**给出（与 {@code InteractionDimension} 同款纪律），
 * 不写成"按请求参数开关"——那会让契约随 query 漂移。
 */
public enum ContentCountField {
    LIKE,
    COMMENT,
    PLAY
}
