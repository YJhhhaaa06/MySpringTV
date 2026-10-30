package io.github.yjhhhaaa06.videoweb.content.service;

import io.github.yjhhhaaa06.videoweb.content.cache.ContentCountBatchAggregate;
import io.github.yjhhhaaa06.videoweb.content.model.ContentCountField;
import io.github.yjhhhaaa06.videoweb.content.model.ContentCountRow;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内容**列表**的公共计数回填适配器（GAP-01，2026-10-26）。
 *
 * <h2>为什么单独立一个类（而不是塞进 {@link ContentStatusFiller}）</h2>
 * 互动态是"我的状态"（随登录态与面变化），公共计数是"请求无关的数字"——
 * 两者失败口径、缓存归属、是否看登录态都不同。T43 曾把计数填充从
 * {@code ContentStatusFiller} 删掉；GAP-01 加回来时单独立类，避免又一次
 * "把它误放进匿名短路"的同类缺陷。
 *
 * <h2>★ 按面按需</h2>
 * 维度集合由**调用点的编译期常量**给出（见 {@link ContentCountField}），本类只透传：
 * <ul>
 *   <li>网格卡片（首页 / 搜索 / 相关推荐 / 空间投稿）⇒ {@code {PLAY}}；</li>
 *   <li>动态卡片（feed）⇒ {@code {LIKE, COMMENT, PLAY}}。</li>
 * </ul>
 * 未请求的维度**保持 {@code null}**（= "本面不查"），不写成 0 —— 0 是真实值，
 * 两者必须可区分（与互动态三态同一口径）。
 *
 * <p>★ 公共计数**不看登录态**：匿名请求同样填充（与 {@code interaction} 的匿名短路正交）。
 */
@Component
public class ContentCountFiller {

    private final ContentCountBatchAggregate aggregate;

    public ContentCountFiller(ContentCountBatchAggregate aggregate) {
        this.aggregate = aggregate;
    }

    /**
     * 批量回填一页内容的指定计数维度。
     *
     * @param list   该页 VO（为空 ⇒ 不发任何连接）
     * @param fields 要填的维度（编译期常量；空集 ⇒ 不填）
     */
    public void fillCountsBatch(List<ContentVO> list, Set<ContentCountField> fields) {
        if (list == null || list.isEmpty() || fields == null || fields.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>(list.size());
        for (ContentVO vo : list) {
            ids.add(vo.getId());
        }
        Map<Long, ContentCountRow> rows = aggregate.readCounts(ids, fields);
        for (ContentVO vo : list) {
            ContentCountRow row = rows.get(vo.getId());
            if (fields.contains(ContentCountField.LIKE)) {
                vo.setLikeCount(row == null ? 0L : row.likeCount());
            }
            if (fields.contains(ContentCountField.COMMENT)) {
                vo.setCommentCount(row == null ? 0L : row.commentCount());
            }
            if (fields.contains(ContentCountField.PLAY)) {
                vo.setPlayCount(row == null ? 0L : row.playCount());
            }
        }
    }
}
