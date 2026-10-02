package io.github.yjhhhaaa06.videoweb.content.dao;

import io.github.yjhhhaaa06.videoweb.content.model.entity.ContentMedia;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 内容媒体数据访问（承接 TV {@code com.itheima.content.dao.ContentMediaDao}）。
 *
 * <h2>只搬本切片端点在用的 2 条（决策表 §二·G 盘点 B）</h2>
 * {@link #findMediaByContentId}（内容详情的媒体聚合）与
 * {@link #findMediaByContentIds}（批量装载的媒体聚合）。
 *
 * <p><b>不搬</b>（各有归属）：
 * <ul>
 *   <li>{@code addMedia} / {@code updateMediaUrl} / {@code updateFileExists}</li>
 *   <li>{@code deleteMediaById} / {@code findAllMedia} / {@code findMediaById}</li>
 *   <li>{@code findMediaByContentTypeSort} / {@code deleteMediaByContentIdAndTypeSort} /
 *       {@code compactImageSort} / {@code deleteByContentId} —— 随 content 写路径（S5 后半，
 *       本仓按提交切分：媒体写路径在 content 删除/媒体写路径那一段一并交付）</li>
 * </ul>
 *
 * <h2>一处与原实现的形态差异（等价，非行为改动）</h2>
 * TV 的 {@code findMedia} 直接在 DAO 内把行按 {@code type} 分组返回
 * {@code Map<Integer, List<ContentMedia>>}。新实现返回**扁平行列表**（SQL 的
 * {@code order by type,sort} 保证同 type 内有序），分组由 {@code ContentAggregate.groupMediaByType}
 * 完成——这样 DAO 只做"取行"，分组逻辑与批量路径**共用同一份实现**（TV 的批量路径就是在
 * Java 侧分组的，两处口径因此统一）。
 */
@Mapper
public interface ContentMediaDao {

    /**
     * 某内容的全部媒体行，按 {@code type, sort} 升序。
     *
     * <p>TV: {@code select id,content_id,url,type,sort from content_media where content_id=? order by type,sort}
     */
    List<ContentMedia> findMediaByContentId(@Param("contentId") long contentId);

    /**
     * 按 contentId 集合批量取媒体行（TV 第五期 T5：启动/批量装载消 N+1）。
     *
     * <p>TV: {@code ... where content_id IN (?…) order by content_id,type,sort}
     * ——{@code StringBuilder} 拼 IN 改 {@code <foreach>}（机械改动）。
     */
    List<ContentMedia> findMediaByContentIds(@Param("ids") Collection<Long> ids);
}
