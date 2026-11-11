package io.github.yjhhhaaa06.videoweb.content.model.vo;

import java.util.List;

/**
 * 首页推荐的**一页**（游标信封）。对外 JSON：{@code data = {list, nextCursor}}。
 *
 * <h2>为什么是独立信封，而不是复用 {@code CursorResult}</h2>
 * {@code CursorResult} 的游标是 {@code Long}（评论回复用 {@code comment_id} 当游标），
 * 而推荐流的游标是**字符串** {@code v1.<seed>.<lastId>}（要携带"用哪个随机排列"）。
 * 把泛型游标塞进那个 record 会改动它的既有形状，进而波及评论回复的契约；
 * 独立一个两字段 record 更小、更清楚。
 *
 * <h2>三个刻意决定</h2>
 * <ol>
 *   <li><b>{@code list} 而不是 {@code items}</b>：与 {@code PageResult} / {@code CursorResult} 同名，
 *       前端不需要第三套读法。</li>
 *   <li><b>不返回 {@code total}</b>：随机流的"总数"没有业务含义（它不是稳定集合的大小），
 *       给了就是假精确。</li>
 *   <li><b>{@code nextCursor == null} ⇔ 没有更多</b>。服务端以"多取一条"判定（见
 *       {@code ContentCache#getRecommendPage}），**不是**靠"本页不满"——
 *       一页可能因已删内容而短，但短页在本实现里就意味着到底。</li>
 * </ol>
 *
 * <p>游标对客户端**不透明**：只回传，不解析、不拼装。伪造它最多得到"另一个随机序的某个位置"，
 * 不越权、不泄露。格式与解码规则见 {@code content/cache/RecommendOrder}。
 *
 * @param list       当前页内容（可能为空：无内容 / 池读失败降级 / 已翻到底）
 * @param nextCursor 下一页游标；{@code null} = 没有更多
 */
public record RecommendPageVO(List<ContentVO> list, String nextCursor) {
}
