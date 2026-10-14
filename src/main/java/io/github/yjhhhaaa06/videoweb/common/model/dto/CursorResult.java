package io.github.yjhhhaaa06.videoweb.common.model.dto;

import java.util.List;

/**
 * 游标（keyset）分页信封（跨域共享）。
 *
 * <p>与 {@link PageResult} 并列的**第二种分页信封**。它存在的理由：offset 分页在
 * "按单调列升序取一页"的场景下，DB 侧必须 `LIMIT offset, n`（读到 offset+n 行为止）⇒
 * **负载 ∝ 页码**。keyset 分页改为"上一页末条的键值"作游标，DB 只取本页
 * （{@code WHERE key > cursor ORDER BY key LIMIT n}）⇒ **负载 ∝ 本页**。
 *
 * <h2>为什么是"另起一个 record"而不是给 {@link PageResult} 加字段</h2>
 * {@link PageResult} 的键集（{@code {list,total,page,pageSize,totalPages}}）是**全站冻结契约**：
 * 旧 pytest 对键集合做了逐字断言、对"缺省 == 显式"做了逐字节一致断言，且它被
 * follow / search / profile / feed / comment-show 共用 ⇒ 给它加一个游标字段会**一次破坏所有分页端点**。
 * 故游标信封独立成 record，只有显式采用游标的端点使用它。
 *
 * <h2>字段语义</h2>
 * <ul>
 *   <li>{@code total} —— 条目总数，**语义与 {@link PageResult#total()} 同源**（同一业务口径，
 *       不是"本页条数"）；游标翻页**不改变**它。</li>
 *   <li>{@code nextCursor} —— **下一页的游标**（本页末条的键值）；{@code null} = **已到底，无下一页**。
 *       服务端以"多取一行"（{@code LIMIT pageSize+1}）判定：取到 {@code pageSize+1} 行才说明还有，
 *       否则本页已到楼尾 ⇒ 前端**一次请求即可知到底**，无需再发一次空请求。</li>
 * </ul>
 *
 * <p>⚠️ 游标本身**不做服务端校验**：非法 / 越界的游标由"取不到行 ⇒ 空 list + {@code nextCursor=null}"
 * 自然兜底（与 offset 分页"越界页空 list"同构）。
 *
 * @param list       当前页的条目
 * @param total      条目总数（与 {@link PageResult} 同一业务口径）
 * @param pageSize   本页**生效**的信封大小（已归一，1..域级上限）
 * @param nextCursor 下一页游标；{@code null} 表示已到底
 */
public record CursorResult<T>(List<T> list, int total, int pageSize, Long nextCursor) {
}
